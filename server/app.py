import asyncio
from contextlib import asynccontextmanager, suppress
from concurrent.futures import ThreadPoolExecutor
import hmac
import html
import json
import logging
import re
import secrets
from time import monotonic

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse
from starlette.middleware.trustedhost import TrustedHostMiddleware
from engine import DATA, Engine
from streaming import FRAME_BYTES, Segmenter

log = logging.getLogger('livesubtitles')
TOKEN_FILE = DATA / 'connection-code.txt'
if not TOKEN_FILE.exists():
    TOKEN_FILE.write_text(secrets.token_urlsafe(32), encoding='utf-8')
TOKEN = TOKEN_FILE.read_text(encoding='utf-8').strip()
if len(TOKEN) < 32:
    raise RuntimeError('Повреждён connection-code.txt. Удалите его и перезапустите сервер.')
engine = Engine()
inference_lock = asyncio.Lock()
inference_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix='local-model')
client_busy = False


async def run_engine(function, *args):
    # Cancelling a WebSocket task cannot stop a running native inference call.
    # One executor thread keeps subsequent sessions from using the model concurrently.
    return await asyncio.get_running_loop().run_in_executor(inference_executor, function, *args)


async def initialize():
    try:
        async with inference_lock:
            await run_engine(engine.load)
    except Exception as error:
        log.exception('Could not load models')
        engine.error = f'Не удалось загрузить модели: {error}'
        engine.status = engine.error


@asynccontextmanager
async def lifespan(app):
    task = asyncio.create_task(initialize())
    yield
    task.cancel()
    with suppress(asyncio.CancelledError):
        await task


app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
app.add_middleware(TrustedHostMiddleware, allowed_hosts=['127.0.0.1', 'localhost', 'testserver'])


@app.get('/health')
async def health():
    return {'ready': engine.ready, 'status': engine.status, 'error': engine.error}


@app.get('/setup', response_class=HTMLResponse)
async def setup():
    return HTMLResponse(f'''<!doctype html><html lang="ru"><meta charset="utf-8">
    <title>LiveSubtitles — подключение</title><style>
    body{{background:#111721;color:#f0f5ff;font:17px/1.6 system-ui;max-width:720px;margin:70px auto;padding:24px}}
    h1{{letter-spacing:-1px}}input{{box-sizing:border-box;width:100%;padding:16px;background:#202d40;color:#9ce7ce;border:1px solid #526782;border-radius:10px;font:17px monospace}}
    li{{margin:12px 0}}code{{color:#9ce7ce}}a{{color:#9ce7ce}}.status{{padding:18px;background:#202d40;border-radius:12px}}</style>
    <h1>LiveSubtitles <span style="color:#9ce7ce">CC</span></h1>
    <p class="status">{html.escape(engine.status)}</p><a href="/setup">Обновить статус</a>
    <ol><li>В Chrome откройте <code>chrome://extensions</code> и включите «Режим разработчика».</li>
    <li>Нажмите «Загрузить распакованное расширение», выберите папку <code>extension</code> из архива.</li>
    <li>Скопируйте код ниже и вставьте его в поле расширения «Код подключения».</li>
    <li>Откройте видео, включите звук, нажмите значок расширения и «Начать перевод».</li></ol>
    <input aria-label="Код подключения" readonly value="{html.escape(TOKEN, quote=True)}">
    <p>Выделите код и нажмите Ctrl+C. Код сохраняется между запусками.</p>
    <p>Оставьте окно START.cmd открытым. Звук и текст не отправляются в облако. Модели скачиваются при первом использовании языка.</p>
    </html>''', headers={'Cache-Control': 'no-store', 'X-Frame-Options': 'DENY',
                        'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'"})


SOURCES = {'auto', 'en', 'ru', 'de', 'fr', 'es', 'it', 'pt', 'ja', 'ko', 'zh', 'uk'}
TARGETS = {'ru', 'en', 'de', 'fr', 'es', 'uk'}


@app.websocket('/listen')
async def listen(ws: WebSocket):
    global client_busy
    # HTTP CORS alone does not protect WebSockets. Only extension origins may connect.
    if not re.fullmatch(r'chrome-extension://[a-p]{32}', ws.headers.get('origin', '')):
        await ws.close(code=1008)
        return
    await ws.accept()
    owns_slot = False
    worker = None
    preparation = None
    try:
        auth = await asyncio.wait_for(ws.receive_json(), timeout=5)
        if not isinstance(auth, dict) or auth.get('type') != 'auth' or not hmac.compare_digest(str(auth.get('token', '')), TOKEN):
            await ws.send_json({'type': 'error', 'message': 'Неверный код подключения. Скопируйте его со страницы локального сервера.'})
            await ws.close(code=1008)
            return
        source, target = auth.get('source', 'en'), auth.get('target', 'ru')
        if source not in SOURCES or target not in TARGETS:
            raise ValueError('Этот язык не поддерживается настройками сервера.')
        if client_busy:
            await ws.send_json({'type': 'error', 'message': 'Уже переводится другая вкладка. Сначала остановите её перевод.'})
            await ws.close(code=1013)
            return
        client_busy = owns_slot = True
        await ws.send_json({'type': 'accepted'})

        async def wait_for_models(done):
            while not done():
                if engine.error:
                    raise RuntimeError(engine.error)
                await ws.send_json({'type': 'status', 'message': engine.status})
                try:
                    incoming = await asyncio.wait_for(ws.receive(), timeout=2)
                    if incoming['type'] == 'websocket.disconnect':
                        raise WebSocketDisconnect()
                    if incoming.get('bytes') is not None:
                        raise ValueError('Сначала дождитесь подготовки моделей.')
                except asyncio.TimeoutError:
                    pass

        await wait_for_models(lambda: engine.ready)
        if source != 'auto':
            await ws.send_json({'type': 'status', 'message': 'Подготавливаю выбранную языковую пару…'})
            async def prepare_pair():
                async with inference_lock:
                    await run_engine(engine.ensure_translation, source, target)
            preparation = asyncio.create_task(prepare_pair())
            await wait_for_models(preparation.done)
            await preparation
        await ws.send_json({'type': 'ready'})
        segmenter = Segmenter()
        queue = asyncio.Queue(maxsize=2)
        epoch = 0
        send_lock = asyncio.Lock()

        async def send(event):
            async with send_lock:
                await ws.send_json(event)

        async def consume():
            while True:
                pcm, stamp, generation = await queue.get()
                if generation != epoch or monotonic() - stamp > 8:
                    continue
                try:
                    async with inference_lock:
                        if generation != epoch:
                            continue
                        result = await run_engine(engine.process, pcm, source, target)
                    if generation != epoch:
                        continue
                    if monotonic() - stamp > 10:
                        await send({'type': 'status', 'message': 'Компьютер не успевает. Попробуйте модель tiny — см. README.', 'epoch': epoch})
                    elif result:
                        await send({**result, 'epoch': generation})
                except Exception as error:
                    log.exception('Recognition/translation failed')
                    await send({'type': 'error', 'message': str(error)})
                    await ws.close(code=1011)
                    return

        worker = asyncio.create_task(consume())
        while True:
            message = await ws.receive()
            if message['type'] == 'websocket.disconnect':
                break
            if message.get('bytes') is not None:
                pcm = message['bytes']
                if len(pcm) != FRAME_BYTES:
                    raise ValueError('Неверный формат аудиокадра.')
                segment = segmenter.push(pcm)
                if segment is not None:
                    if queue.full():
                        queue.get_nowait()
                        await send({'type': 'status', 'message': 'Пропущен фрагмент, чтобы сократить задержку.', 'epoch': epoch})
                    queue.put_nowait((segment, monotonic(), epoch))
            elif message.get('text') is not None:
                event = json.loads(message['text'])
                if not isinstance(event, dict):
                    raise ValueError('Неверный формат команды.')
                if event.get('type') == 'ping':
                    await send({'type': 'pong'})
                elif event.get('type') == 'reset':
                    next_epoch = event.get('epoch')
                    if type(next_epoch) is not int or next_epoch <= epoch:
                        raise ValueError('Неверная последовательность сброса.')
                    epoch = next_epoch
                    segmenter.reset()
                    while not queue.empty():
                        queue.get_nowait()
    except WebSocketDisconnect:
        pass
    except Exception as error:
        with suppress(Exception):
            await ws.send_json({'type': 'error', 'message': str(error)})
            await ws.close(code=1011)
    finally:
        if preparation:
            preparation.cancel()
            with suppress(asyncio.CancelledError, Exception):
                await preparation
        if worker:
            worker.cancel()
            with suppress(asyncio.CancelledError, Exception):
                await worker
        if owns_slot:
            client_busy = False


if __name__ == '__main__':
    import uvicorn
    print('\nLiveSubtitles: http://127.0.0.1:8765/setup\nKeep this window open. Ctrl+C to stop.\n', flush=True)
    uvicorn.run(app, host='127.0.0.1', port=8765, ws_max_size=65536, ws_max_queue=8, access_log=False)
