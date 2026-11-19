const STATE_KEY = 'captureState';
let starting = false;
let offscreenCreation;

async function state() {
  return (await chrome.storage.session.get(STATE_KEY))[STATE_KEY] || {status: 'idle'};
}

async function setState(value) {
  await chrome.storage.session.set({[STATE_KEY]: value});
  await chrome.action.setBadgeText({text: value.status === 'running' ? 'CC' : ''});
  await chrome.action.setBadgeBackgroundColor({color: '#64d5b3'});
}

async function ensureOffscreen() {
  const contexts = await chrome.runtime.getContexts({contextTypes: ['OFFSCREEN_DOCUMENT']});
  if (contexts.length) return;
  offscreenCreation ||= chrome.offscreen.createDocument({
    url: 'offscreen.html', reasons: ['USER_MEDIA'],
    justification: 'Capture tab audio, preserve original playback and send PCM to the local translator.'
  }).finally(() => { offscreenCreation = null; });
  await offscreenCreation;
}

async function sendOverlay(tabId, data) {
  if (typeof tabId !== 'number') return;
  await chrome.tabs.sendMessage(tabId, {target: 'overlay', ...data}).catch(() => {});
}

async function stop() {
  const previous = await state();
  await chrome.runtime.sendMessage({target: 'offscreen', type: 'STOP'}).catch(() => {});
  await sendOverlay(previous.tabId, {type: 'HIDE'});
  await setState({status: 'idle'});
}

async function start(tabId, settings) {
  if (starting) throw new Error('Перевод уже запускается.');
  starting = true;
  try {
    if (!settings.token?.trim()) throw new Error('Вставьте код подключения со страницы локального сервера.');
    await stop();
    const tab = await chrome.tabs.get(tabId);
    if (!/^https?:\/\//.test(tab.url || '')) throw new Error('Откройте обычную веб-страницу с видео.');
    await chrome.scripting.executeScript({target: {tabId}, files: ['overlay.js']});
    await ensureOffscreen();
    const sessionId = crypto.randomUUID();
    // Do not set consumerTabId: the offscreen document consumes this stream ID.
    const streamId = await chrome.tabCapture.getMediaStreamId({targetTabId: tabId});
    await setState({status: 'starting', tabId, sessionId});
    const {token, ...appearance} = settings;
    await sendOverlay(tabId, {type: 'STATUS', text: 'Подключение к локальному переводчику…', settings: appearance});
    const response = await chrome.runtime.sendMessage({
      target: 'offscreen', type: 'START', streamId, settings, sessionId
    });
    if (!response?.ok) throw new Error(response?.error || 'Не удалось захватить звук вкладки.');
    return await state();
  } catch (error) {
    await stop();
    await setState({status: 'error', error: error.message});
    throw error;
  } finally { starting = false; }
}

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (message.target !== 'background') return;
  // Web pages/content scripts cannot initiate capture or read the token.
  const trustedPage = sender.url?.startsWith(chrome.runtime.getURL(''));
  if (sender.tab && !trustedPage && !['RESET', 'REPOSITION'].includes(message.type)) return;
  (async () => {
    if (message.type === 'STATE') return {ok: true, state: await state()};
    if (message.type === 'START') return {ok: true, state: await start(message.tabId, message.settings)};
    if (message.type === 'STOP') { await stop(); return {ok: true}; }
    const current = await state();
    if (message.type === 'RESET' && sender.tab?.id === current.tabId) {
      await chrome.runtime.sendMessage({target: 'offscreen', type: 'RESET'});
      await sendOverlay(current.tabId, {type: 'CLEAR'});
    }
    if (message.type === 'EVENT' && message.sessionId === current.sessionId) {
      const event = message.event;
      if (event.type === 'ready') {
        await setState({...current, status: 'running'});
        await sendOverlay(current.tabId, {type: 'STATUS', text: 'Слушаю звук вкладки…'});
      } else if (event.type === 'subtitle') {
        await sendOverlay(current.tabId, {...event, type: 'SUBTITLE'});
      } else if (event.type === 'status') {
        await sendOverlay(current.tabId, {type: 'STATUS', text: event.message});
      } else if (event.type === 'error' || event.type === 'ended') {
        await stop();
        await setState({status: 'error', error: event.message || 'Захват звука завершён.'});
      }
    }
    return {ok: true};
  })().then(respond, error => respond({ok: false, error: error.message}));
  return true;
});

chrome.tabs.onRemoved.addListener(async tabId => {
  if ((await state()).tabId === tabId) await stop();
});
chrome.tabs.onUpdated.addListener(async (tabId, change) => {
  if (change.status === 'loading' && (await state()).tabId === tabId) await stop();
});
