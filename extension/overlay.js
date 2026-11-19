(() => {
  if (globalThis.__liveSubtitlesInstalled) return;
  globalThis.__liveSubtitlesInstalled = true;
  const host = document.createElement('div');
  host.id = 'local-live-subtitles';
  host.style.cssText = 'all:initial!important;position:fixed!important;z-index:2147483647!important;pointer-events:none!important;';
  const root = host.attachShadow({mode: 'closed'});
  root.innerHTML = `<style>
    :host{--size:28px;--bottom:8%}*{box-sizing:border-box}
    .wrap{position:absolute;bottom:var(--bottom);left:50%;transform:translateX(-50%);width:max-content;max-width:88%;text-align:center;font-family:Arial,sans-serif}
    .line{color:#fff;background:rgba(10,14,22,.88);padding:9px 16px;border-radius:9px;box-shadow:0 3px 20px #0005;white-space:pre-wrap;overflow-wrap:anywhere;font-size:var(--size);line-height:1.35}
    .original{color:#d5d9e2;font-size:calc(var(--size)*.66);margin-top:5px;max-height:4em;overflow:hidden}
    .status{font-size:15px;color:#b9e8d7}.hidden{display:none}
  </style><div class="wrap hidden"><div class="line"></div><div class="line original hidden"></div></div>`;
  const wrap = root.querySelector('.wrap');
  const line = root.querySelector('.line');
  const original = root.querySelector('.original');
  let timeout, settings = {}, visible = false;

  function mount() {
    // A video's native fullscreen surface cannot host HTML. Request its parent instead
    // via the player fullscreen control. Container fullscreen works automatically.
    const fullscreen = document.fullscreenElement;
    const parent = fullscreen && !['VIDEO', 'IFRAME'].includes(fullscreen.tagName) ? fullscreen : document.documentElement;
    if (host.parentElement !== parent) parent.append(host);
    let rect = {left: 0, top: 0, width: innerWidth, height: innerHeight};
    if (!fullscreen) {
      const videos = [...document.querySelectorAll('video')];
      const candidates = videos.length ? videos : [...document.querySelectorAll('iframe')];
      const bounds = candidates.map(element => element.getBoundingClientRect()).filter(r =>
        r.width > 160 && r.height > 90 && r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth);
      if (bounds.length) rect = bounds.sort((a, b) => b.width * b.height - a.width * a.height)[0];
    }
    for (const key of ['left', 'top', 'width', 'height']) host.style.setProperty(key, `${rect[key]}px`, 'important');
  }
  function clear() { clearTimeout(timeout); wrap.classList.add('hidden'); }
  function show(text, source, status = false) {
    clearTimeout(timeout);
    mount();
    line.textContent = text;
    line.classList.toggle('status', status);
    original.textContent = source || '';
    original.classList.toggle('hidden', !source || !settings.original);
    wrap.classList.remove('hidden');
    timeout = setTimeout(clear, status ? 12000 : Math.min(13000, Math.max(4500, text.length * 65)));
  }
  chrome.runtime.onMessage.addListener(message => {
    if (message.target !== 'overlay') return;
    if (message.settings) {
      settings = message.settings;
      host.style.setProperty('--size', `${Math.max(18, Math.min(48, Number(settings.fontSize) || 28))}px`);
      host.style.setProperty('--bottom', `${Math.max(3, Math.min(35, Number(settings.bottom) || 8))}%`);
    }
    if (message.type === 'HIDE') { visible = false; clear(); host.remove(); }
    if (message.type === 'CLEAR') clear();
    if (message.type === 'STATUS') { visible = true; show(message.text, '', true); }
    if (message.type === 'SUBTITLE') { visible = true; show(message.text, message.original); }
  });
  document.addEventListener('fullscreenchange', () => { if (visible) mount(); });
  setInterval(() => { if (visible) mount(); }, 500);
  let lastReset = 0;
  for (const event of ['seeking', 'pause', 'ratechange']) {
    document.addEventListener(event, e => {
      if (!visible || !(e.target instanceof HTMLMediaElement)) return;
      if (Date.now() - lastReset < 150) return;
      lastReset = Date.now(); clear();
      chrome.runtime.sendMessage({target: 'background', type: 'RESET'}).catch(() => {});
    }, true);
  }
})();
