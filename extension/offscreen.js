let active;

function report(session, event) {
  if (active !== session) return;
  chrome.runtime.sendMessage({target: 'background', type: 'EVENT', sessionId: session.id, event}).catch(() => {});
}

async function cleanup() {
  const old = active;
  active = null;
  if (!old) return;
  clearInterval(old.heartbeat);
  clearTimeout(old.timeout);
  old.socket?.close();
  old.node?.disconnect();
  old.source?.disconnect();
  old.stream?.getTracks().forEach(track => track.stop());
  await old.context?.close().catch(() => {});
}

async function start(message) {
  await cleanup();
  const session = {id: message.sessionId, epoch: 0, ready: false};
  active = session;
  try {
    session.stream = await navigator.mediaDevices.getUserMedia({
      audio: {mandatory: {chromeMediaSource: 'tab', chromeMediaSourceId: message.streamId}}, video: false
    });
    session.context = new AudioContext();
    session.source = session.context.createMediaStreamSource(session.stream);
    // Tab capture mutes normal playback. Restore only the original audio.
    session.source.connect(session.context.destination);
    await session.context.resume();
    await session.context.audioWorklet.addModule(chrome.runtime.getURL('pcm-worklet.js'));
    session.node = new AudioWorkletNode(session.context, 'pcm-capture');
    session.source.connect(session.node);
    const silent = session.context.createGain();
    silent.gain.value = 0;
    session.node.connect(silent).connect(session.context.destination);
    const socket = session.socket = new WebSocket('ws://127.0.0.1:8765/listen');
    session.timeout = setTimeout(() => {
      report(session, {type: 'error', message: 'Сервер не отвечает. Запустите START.cmd и проверьте код подключения.'});
    }, 15000);
    socket.onopen = () => {
      socket.send(JSON.stringify({type: 'auth', token: message.settings.token.trim(),
        source: message.settings.source || 'en', target: message.settings.language || 'ru'}));
    };
    socket.onmessage = ({data}) => {
      if (active !== session) return;
      let event;
      try { event = JSON.parse(data); } catch { return; }
      if (event.type === 'accepted') {
        clearTimeout(session.timeout);
        session.heartbeat = setInterval(() => {
          if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({type: 'ping'}));
        }, 15000);
      }
      if (event.type === 'ready') {
        session.ready = true;
        session.node.port.postMessage({type: 'reset'});
      }
      if (event.epoch !== undefined && event.epoch !== session.epoch) return;
      report(session, event);
    };
    socket.onerror = () => report(session, {type: 'error', message: 'Нет связи с локальным сервером. Запустите START.cmd.'});
    socket.onclose = () => report(session, {type: 'error', message: 'Локальный сервер отключился. Запустите перевод снова.'});
    session.node.port.onmessage = ({data}) => {
      if (active !== session || !session.ready || socket.readyState !== WebSocket.OPEN) return;
      if (socket.bufferedAmount > 16000 * 2 * 2) {
        report(session, {type: 'error', message: 'Соединение не успевает передавать звук. Перезапустите перевод.'});
        return;
      }
      socket.send(data);
    };
    session.stream.getAudioTracks()[0].onended = () => report(session, {type: 'ended'});
  } catch (error) {
    await cleanup();
    throw error;
  }
}

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (message.target !== 'offscreen') return;
  (async () => {
    if (message.type === 'START') await start(message);
    if (message.type === 'STOP') await cleanup();
    if (message.type === 'RESET' && active?.ready) {
      active.epoch++;
      active.node?.port.postMessage({type: 'reset'});
      if (active.socket?.readyState === WebSocket.OPEN) {
        active.socket.send(JSON.stringify({type: 'reset', epoch: active.epoch}));
      }
    }
    return {ok: true};
  })().then(respond, error => respond({ok: false, error: error.message}));
  return true;
});
