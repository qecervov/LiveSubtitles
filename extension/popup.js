const fields = ['token', 'source', 'language', 'fontSize', 'bottom', 'original'];
const defaults = {token: '', source: 'en', language: 'ru', fontSize: '28', bottom: '8', original: false};
const status = document.getElementById('state');
const startButton = document.getElementById('start');
const stopButton = document.getElementById('stop');
let selectedTabId;
function display(text, error = false) { status.textContent = text; status.classList.toggle('error', error); }
function settings() {
  return Object.fromEntries(fields.map(id => [id, id === 'original' ? document.getElementById(id).checked : document.getElementById(id).value]));
}
async function call(message) {
  const result = await chrome.runtime.sendMessage({target: 'background', ...message});
  if (!result?.ok) throw new Error(result?.error || 'Расширение не ответило.');
  return result;
}
async function refresh() {
  const {state} = await call({type: 'STATE'});
  const busy = ['starting', 'running'].includes(state.status);
  startButton.disabled = busy;
  stopButton.disabled = !busy;
  if (busy) display(state.status === 'running' ? '● Перевод включён' : 'Подключение / подготовка моделей…');
  else if (state.status === 'error') display(state.error, true);
  else display('Готово к запуску. Включите видео.');
}
async function save() { await chrome.storage.local.set({settings: settings()}); }
startButton.addEventListener('click', async () => {
  startButton.disabled = true;
  try { await save(); await call({type: 'START', tabId: selectedTabId, settings: settings()}); await refresh(); }
  catch (error) { display(error.message, true); startButton.disabled = false; }
});
stopButton.addEventListener('click', async () => {
  try { await call({type: 'STOP'}); await refresh(); } catch (error) { display(error.message, true); }
});
fields.forEach(id => document.getElementById(id).addEventListener('change', save));
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'session' && changes.captureState) refresh().catch(error => display(error.message, true));
});
(async () => {
  const manifest = chrome.runtime.getManifest();
  document.getElementById('about-version').textContent = manifest.version_name || manifest.version;
  // Keep the connection code out of content scripts.
  await chrome.storage.local.setAccessLevel({accessLevel: 'TRUSTED_CONTEXTS'});
  const saved = {...defaults, ...(await chrome.storage.local.get('settings')).settings};
  fields.forEach(id => {
    if (id === 'original') document.getElementById(id).checked = saved[id];
    else document.getElementById(id).value = saved[id];
  });
  [ {id: selectedTabId} ] = await chrome.tabs.query({active: true, currentWindow: true});
  await refresh();
})().catch(error => display(error.message, true));
