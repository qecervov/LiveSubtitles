"""Protocol/security tests use a deterministic engine; no model downloads."""
import os
from pathlib import Path
import struct
import sys
import tempfile
import threading
import unittest

TEST_DATA = tempfile.TemporaryDirectory(prefix='livesubtitles-test-')
os.environ['LIVESUBTITLES_DATA'] = TEST_DATA.name
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect
import app as server

ORIGIN = {'origin': 'chrome-extension://' + 'a' * 32}
VOICE = struct.pack('<3200h', *([4000, -4000] * 1600))
SILENCE = bytes(6400)


class FakeEngine:
    status = 'ready'
    error = None
    ready = True

    def __init__(self):
        self.calls = 0
        self.block = False
        self.started = threading.Event()
        self.release = threading.Event()

    def load(self):
        pass

    def ensure_translation(self, source, target):
        pass

    def process(self, pcm, source, target):
        self.calls += 1
        number = self.calls
        self.started.set()
        if self.block and number == 1:
            self.release.wait(5)
        return {'type': 'subtitle', 'text': f'Перевод {number}', 'original': 'hello', 'language': 'en'}


def authenticate(ws):
    ws.send_json({'type': 'auth', 'token': server.TOKEN, 'source': 'auto', 'target': 'ru'})
    assert ws.receive_json()['type'] == 'accepted'
    while ws.receive_json()['type'] != 'ready':
        pass


def phrase(ws):
    for frame in [VOICE, VOICE, VOICE, SILENCE, SILENCE, SILENCE]:
        ws.send_bytes(frame)


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.engine = FakeEngine()
        server.engine = self.engine
        server.client_busy = False

    def tearDown(self):
        self.engine.release.set()

    def test_setup_and_health_do_not_cache_or_leak_code(self):
        with TestClient(server.app) as client:
            self.assertNotIn(server.TOKEN, client.get('/health').text)
            page = client.get('/setup')
            self.assertIn(server.TOKEN, page.text)
            self.assertEqual(page.headers['cache-control'], 'no-store')
            self.assertEqual(page.headers['x-frame-options'], 'DENY')
            self.assertEqual(client.get('/health', headers={'host': 'evil.example'}).status_code, 400)

    def test_web_page_origin_cannot_connect(self):
        with TestClient(server.app) as client:
            with self.assertRaises(WebSocketDisconnect) as error:
                with client.websocket_connect('/listen', headers={'origin': 'https://example.com'}):
                    pass
            self.assertEqual(error.exception.code, 1008)

    def test_wrong_connection_code_is_rejected(self):
        with TestClient(server.app) as client:
            with client.websocket_connect('/listen', headers=ORIGIN) as ws:
                ws.send_json({'type': 'auth', 'token': 'wrong'})
                self.assertEqual(ws.receive_json()['type'], 'error')
                with self.assertRaises(WebSocketDisconnect):
                    ws.receive_json()
            self.assertFalse(server.client_busy)

    def test_only_one_tab_can_use_models(self):
        with TestClient(server.app) as client:
            with client.websocket_connect('/listen', headers=ORIGIN) as first:
                authenticate(first)
                with client.websocket_connect('/listen', headers=ORIGIN) as second:
                    second.send_json({'type': 'auth', 'token': server.TOKEN, 'source': 'auto'})
                    self.assertIn('другая вкладка', second.receive_json()['message'])
                self.assertTrue(server.client_busy)
            self.assertFalse(server.client_busy)

    def test_pcm_phrase_becomes_translated_event(self):
        with TestClient(server.app) as client:
            with client.websocket_connect('/listen', headers=ORIGIN) as ws:
                authenticate(ws)
                phrase(ws)
                event = ws.receive_json()
                self.assertEqual(event['type'], 'subtitle')
                self.assertEqual(event['text'], 'Перевод 1')
                self.assertEqual(event['epoch'], 0)

    def test_invalid_audio_size_fails(self):
        with TestClient(server.app) as client:
            with client.websocket_connect('/listen', headers=ORIGIN) as ws:
                authenticate(ws)
                ws.send_bytes(b'bad')
                self.assertEqual(ws.receive_json()['type'], 'error')

    def test_seek_discards_inflight_translation(self):
        self.engine.block = True
        with TestClient(server.app) as client:
            with client.websocket_connect('/listen', headers=ORIGIN) as ws:
                authenticate(ws)
                phrase(ws)
                self.assertTrue(self.engine.started.wait(3))
                ws.send_json({'type': 'reset', 'epoch': 1})
                ws.send_json({'type': 'ping'})
                self.assertEqual(ws.receive_json()['type'], 'pong')
                phrase(ws)
                self.engine.release.set()
                event = ws.receive_json()
                self.assertEqual(event['epoch'], 1)
                self.assertEqual(event['text'], 'Перевод 2')

    def test_backlog_is_bounded_and_reports_dropped_audio(self):
        self.engine.block = True
        with TestClient(server.app) as client:
            with client.websocket_connect('/listen', headers=ORIGIN) as ws:
                authenticate(ws)
                phrase(ws)
                self.assertTrue(self.engine.started.wait(3))
                for _ in range(3):
                    phrase(ws)
                event = ws.receive_json()
                self.assertEqual(event['type'], 'status')
                self.assertIn('Пропущен', event['message'])
                self.engine.release.set()
                self.assertEqual(ws.receive_json()['type'], 'subtitle')


if __name__ == '__main__':
    unittest.main()
