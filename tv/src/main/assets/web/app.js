'use strict';

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => Array.from(el.querySelectorAll(s));
// 与电视端 data/Models.kt 的 Subject、AnswerMode 对应；新增科目或作答方式时两边同步
const SUBJECTS = { MATH: '数学', ENGLISH: '英语', HANZI: '认字', PINYIN: '拼音' };
const MODES = { VOICE: '语音回答', CHOICE: '选择题' };
const VOICES = {
  baidu: [['110', '度小童（童声）'], ['111', '度小萌'], ['103', '度米朵'], ['4', '度丫丫'], ['0', '度小美']],
  volc: [
    ['zh_female_xiaoxue_uranus_bigtts', '儿童绘本 2.0'],
    ['zh_female_shaoergushi_uranus_bigtts', '少儿故事 2.0'],
    ['zh_male_tiancaitongsheng_uranus_bigtts', '天才童声 2.0'],
    ['zh_male_naiqimengwa_uranus_bigtts', '奶气萌娃 2.0'],
    ['zh_female_mengyatou_uranus_bigtts', '萌丫头 2.0'],
  ],
};

// ---------- 表情：用电视上的同一套图片显示，保证所见即所得 ----------

let EMOJI = new Set();
// 界面图标用的表情，不出现在出题用的表情选择里
const UI_EMOJI = new Set(['1f441', '1f6ab', '1f5d1', '1f3e0', '1f4fa', '1f399', '1f3a8', '1f511', '1f512', '1f513', '1f4f1']);
const EMOJI_RE = /(\p{Extended_Pictographic})\uFE0F?/gu;
const EMOJI_TEST = /\p{Extended_Pictographic}/u;
function emojiKey(ch) { return ch.codePointAt(0).toString(16); }
function emojiImg(ch, cls = 'emo') { return `<img class="${cls}" src="/emoji/${emojiKey(ch)}.webp" alt="${ch}">`; }
/** 把 HTML 字符串中的表情换成图片（电视上没有的表情保留原字符）。 */
function emo(html) { return String(html).replace(EMOJI_RE, (m, ch) => EMOJI.has(emojiKey(ch)) ? emojiImg(ch) : m); }
/** 处理静态页面里写死的表情（标签栏、按钮、标题）。 */
function emojifyStatic(root = document.body) {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const nodes = [];
  while (walker.nextNode()) if (EMOJI_TEST.test(walker.currentNode.nodeValue)) nodes.push(walker.currentNode);
  nodes.forEach(n => {
    if (n.parentElement.closest('script,style,textarea,option')) return;
    const span = document.createElement('span');
    span.innerHTML = emo(esc(n.nodeValue));
    n.replaceWith(...span.childNodes);
  });
}
async function loadEmoji() {
  try { EMOJI = new Set((await (await fetch('/emoji/list.json')).json()).emoji); } catch (_) {}
  emojifyStatic();
}

const state = { token: localStorage.getItem('token'), settings: null, status: null, packs: [], pack: null, sharedReady: false, questions: [], apps: [], filter: 'ALL' };

// ---------- 通用 ----------

async function api(path, opts = {}) {
  const res = await fetch('/api/' + path, {
    method: opts.method || 'GET',
    headers: { 'Content-Type': 'application/json', 'X-Token': state.token || '' },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && path !== 'login') { logout(); throw new Error('请重新登录'); }
  if (!res.ok) throw new Error(data.error || ('请求失败 ' + res.status));
  return data;
}

let toastTimer;
function toast(msg, bad = false) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.toggle('bad', bad);
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 2200);
}

async function run(fn, okMsg) {
  try { const r = await fn(); if (okMsg) toast(okMsg); return r; } catch (e) { toast(e.message, true); }
}

function esc(s) { return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
function fmt(sec) { sec = Math.max(0, sec | 0); return Math.floor(sec / 60) + ':' + String(sec % 60).padStart(2, '0'); }

// ---------- 登录 ----------

function showLogin() { $('#login').classList.remove('hidden'); $('#app').classList.add('hidden'); setTimeout(() => $('#pin').focus(), 300); }
function logout() { state.token = null; localStorage.removeItem('token'); showLogin(); }

$('#loginBtn').onclick = async () => {
  const pin = $('#pin').value.trim();
  try {
    const r = await api('login', { method: 'POST', body: { pin } });
    state.token = r.token;
    localStorage.setItem('token', r.token);
    $('#loginErr').textContent = '';
    start();
  } catch (e) {
    $('#loginErr').textContent = e.message;
    const card = $('.login-card');
    card.classList.remove('shake'); void card.offsetWidth; card.classList.add('shake');
  }
};
$('#pin').addEventListener('keydown', e => { if (e.key === 'Enter') $('#loginBtn').click(); });

// ---------- 页面切换 ----------

$$('.tabbar button').forEach(b => b.onclick = () => {
  $$('.tabbar button').forEach(x => x.classList.toggle('on', x === b));
  $$('.page').forEach(p => p.classList.toggle('hidden', p.dataset.page !== b.dataset.tab));
  if (b.dataset.tab === 'bank') { if (state.pack) openPack(state.pack.id); else loadPacks(); }
  if (b.dataset.tab === 'apps') loadApps();
  if (b.dataset.tab === 'voice') loadOffline();
  window.scrollTo({ top: 0, behavior: 'smooth' });
});

// ---------- 概览 ----------

async function refreshStatus() {
  const s = await api('status');
  state.status = s;
  const left = s.limitSeconds - s.usedSeconds;
  $('#remain').textContent = fmt(left);
  const ratio = Math.max(0, Math.min(1, left / Math.max(1, s.limitSeconds)));
  const bar = $('#ringBar');
  bar.style.strokeDashoffset = String(314.16 * (1 - ratio));
  bar.style.stroke = ratio < 0.2 ? 'var(--accent)' : 'var(--success)';

  const chip = $('#tvState');
  let title, cls;
  if (s.paused) { title = '已暂停守护'; cls = 'off'; }
  else if (s.locked) { title = '孩子正在答题'; cls = 'warn'; }
  else { title = '守护中'; cls = 'ok'; }
  chip.textContent = title; chip.className = 'chip ' + cls;
  $('#heroTitle').textContent = title;
  const st = state.settings;
  if (st) $('#heroSub').textContent = `每次 ${st.watchMinutes} 分钟 · 答对 ${st.questionCount} 题继续`;
  $('#fgApp').textContent = s.foregroundLabel ? '电视正在打开：' + s.foregroundLabel : '';
  $('#noAppsHint').classList.toggle('hidden', !(st && st.countMode === 'SELECTED_APPS' && st.apps.length === 0));
  $('#pauseBtn').innerHTML = emo(s.paused ? '<span>▶️</span>恢复守护' : '<span>⏸️</span>暂停守护');

  const p = s.permissions;
  const items = [['悬浮窗（答题界面）', p.overlay], ['录音（语音作答）', p.record], ['无障碍（识别 App、屏蔽主页键）', p.accessibility], ['使用情况（无障碍的备用）', p.usage]];
  $('#perms').innerHTML = items.map(([n, ok]) => `<li><span>${n}</span><span>${emo(ok ? '✅' : '⚠️')}</span></li>`).join('');
  $('#permHint').classList.toggle('hidden', p.overlay && p.record && p.accessibility);
  $('#version').textContent = '学习小助手 v' + s.version;
  $('#ttsCache').textContent = `电视上已缓存 ${s.ttsCached} 条朗读。题目和固定用语会在后台提前合成，同一句只请求一次语音服务。`;
}

async function refreshLogs() {
  const { logs } = await api('logs');
  $('#logs').innerHTML = logs.length ? logs.slice(0, 15).map(l => `
    <li><span class="log-q">${emo(esc(l.display))}</span>
      <span class="log-heard">${l.correct ? '<span class="ok-mark">✓ 答对</span>' : '<span class="bad-mark">✗ 答错</span>'}
        <span class="muted">${l.heard ? '「' + esc(l.heard) + '」' : ''}</span></span>
      <span class="log-time">${new Date(l.time).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</span></li>`).join('')
    : '<li class="muted small">还没有作答记录</li>';
}

$$('.action').forEach(b => b.onclick = () => {
  const act = b.dataset.act;
  const map = {
    lock: () => api('lock', { method: 'POST' }),
    unlock: () => api('unlock', { method: 'POST' }),
    add10: () => api('addtime', { method: 'POST', body: { minutes: 10 } }),
    add30: () => api('addtime', { method: 'POST', body: { minutes: 30 } }),
    pause: () => api('pause', { method: 'POST', body: { paused: !state.status?.paused } }),
    preview: () => api('preview', { method: 'POST' }),
  };
  const msgs = { lock: '电视已开始答题', unlock: '已解锁', add10: '已加 10 分钟', add30: '已加 30 分钟', pause: '已切换', preview: '电视上已打开试答' };
  run(map[act], msgs[act]).then(() => setTimeout(refreshStatus, 400));
});

$('#gotoApps').onclick = () => $('.tabbar button[data-tab=apps]').click();

// ---------- 版本更新 ----------
// 只在打开管理页时检查；有新版本时在概览页顶部提示，家长点「更新」后电视下载并弹出系统安装界面

let updatePoll = null;

async function checkUpdate(refresh = false) {
  const u = await api('update' + (refresh ? '?refresh=1' : ''));
  renderUpdate(u);
  return u;
}

/** CHANGELOG 的 Markdown 只用到小标题和列表，简单转成 HTML。 */
function notesHtml(md) {
  return esc(md).split('\n').map(l => l.trim()).filter(Boolean).map(l =>
    l.startsWith('#') ? `<b>${l.replace(/^#+\s*/, '')}</b>` : `<div>${l.replace(/^[-*]\s*/, '• ')}</div>`).join('');
}

function renderUpdate(u) {
  const st = u.status || {};
  const busy = st.phase === 'DOWNLOADING' || st.phase === 'VERIFYING';
  const show = u.available || busy || st.phase === 'INSTALLING';
  $('#updateCard').classList.toggle('hidden', !show);
  if (!show) return;
  if (u.latest) {
    $('#updateTitle').innerHTML = emo(`🎉 有新版本 v${esc(u.latest.version)}`);
    $('#updateNotes').innerHTML = emo(notesHtml(u.latest.notes)) +
      `<div class="muted">当前 v${esc(u.current)} · 安装包 ${(u.latest.size / 1048576).toFixed(1)} MB</div>`;
  }
  renderUpdateStatus(st);
}

function renderUpdateStatus(st) {
  const busy = st.phase === 'DOWNLOADING' || st.phase === 'VERIFYING';
  $('#updateBar').classList.toggle('hidden', !busy);
  $('#updateBar i').style.width = (st.progress || 0) + '%';
  const msg = $('#updateMsg');
  msg.textContent = st.phase === 'IDLE' ? '' : st.message + (st.phase === 'DOWNLOADING' ? ` ${st.progress}%` : '');
  msg.className = 'small ' + (st.phase === 'FAILED' ? 'bad-mark' : '');
  const btn = $('#updateBtn');
  btn.disabled = busy;
  btn.textContent = st.phase === 'FAILED' ? '再试一次' : st.phase === 'INSTALLING' ? '重新弹出安装界面' : '更新到电视';
}

function pollUpdate() {
  clearInterval(updatePoll);
  updatePoll = setInterval(async () => {
    try {
      const st = await api('update/status');
      renderUpdateStatus(st);
      if (st.phase !== 'DOWNLOADING' && st.phase !== 'VERIFYING') clearInterval(updatePoll);
    } catch (_) { clearInterval(updatePoll); }
  }, 1000);
}

$('#updateBtn').onclick = () => run(async () => {
  renderUpdateStatus(await api('update/install', { method: 'POST' }));
  pollUpdate();
});

$('#checkUpdate').onclick = () => run(async () => {
  const u = await checkUpdate(true);
  if (u.error) throw new Error(u.error);
  if (u.available) { $('.tabbar button[data-tab=home]').click(); toast('有新版本 v' + u.latest.version); }
  else toast('已经是最新版本');
});

// ---------- 设置 ----------

let saveTimer;
function saveSettings(patch, msg = '已保存') {
  Object.assign(state.settings, patch);
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => run(async () => { state.settings = await api('settings', { method: 'PUT', body: patch }); renderSettings(); }, msg), 250);
}

function renderSettings() {
  const s = state.settings;
  $$('.stepper').forEach(st => { $('b', st).textContent = s[st.dataset.key]; });
  $$('[data-key].switch').forEach(el => { el.checked = !!s[el.dataset.key]; });
  $$('select[data-key]').forEach(el => { el.value = s[el.dataset.key]; });
  $$('#subjects input').forEach(el => { el.checked = s.subjects.includes(el.value); });
  $('#mathMode').value = s.math.mode;
  $('#mathEnabled').checked = s.math.enabled;
  $('#mathAdd').checked = s.math.add;
  $('#mathSub').checked = s.math.sub;
  $('#mathMax').value = String(s.math.max);
  $('#mathAdaptive').checked = !!s.math.adaptive;
  $$('input[name=countMode]').forEach(r => { r.checked = r.value === s.countMode; });
  $('#packIndexUrl').value = s.packIndexUrl || '';
  renderVoice();
}

$$('.stepper').forEach(st => {
  const [minus, plus] = $$('button', st);
  const step = +st.dataset.step, min = +st.dataset.min, max = +st.dataset.max, key = st.dataset.key;
  const change = d => {
    const v = Math.min(max, Math.max(min, state.settings[key] + d));
    if (v === state.settings[key]) return;
    const b = $('b', st); b.textContent = v; b.classList.remove('bump'); void b.offsetWidth; b.classList.add('bump');
    saveSettings({ [key]: v });
  };
  minus.onclick = () => change(-step);
  plus.onclick = () => change(step);
});
$$('[data-key].switch').forEach(el => el.onchange = () => saveSettings({ [el.dataset.key]: el.checked }));
$$('select[data-key]').forEach(el => el.onchange = () => saveSettings({ [el.dataset.key]: el.value }));
$('#subjects').innerHTML = Object.entries(SUBJECTS).map(([k, n]) => `<label><input type="checkbox" value="${k}"><span>${n}</span></label>`).join('');
$$('#subjects input').forEach(el => el.onchange = () => {
  const subjects = $$('#subjects input').filter(x => x.checked).map(x => x.value);
  if (!subjects.length) { el.checked = true; toast('至少保留一个科目', true); return; }
  saveSettings({ subjects });
});
const saveMath = () => saveSettings({ math: { enabled: $('#mathEnabled').checked, add: $('#mathAdd').checked, sub: $('#mathSub').checked, max: +$('#mathMax').value, mode: $('#mathMode').value, adaptive: $('#mathAdaptive').checked } });
['#mathEnabled', '#mathAdd', '#mathSub', '#mathMax', '#mathMode', '#mathAdaptive'].forEach(s => $(s).onchange = saveMath);
$$('input[name=countMode]').forEach(r => r.onchange = () => saveSettings({ countMode: r.value }));

$('#savePin').onclick = () => {
  const pin = $('#newPin').value.trim();
  if (!/^\d{4,8}$/.test(pin)) { toast('密码需要 4~8 位数字', true); return; }
  run(() => api('settings', { method: 'PUT', body: { pin } }), '密码已修改').then(() => { $('#newPin').value = ''; });
};

$('#savePackIndex').onclick = () => {
  const url = $('#packIndexUrl').value.trim();
  if (url && !/^https?:\/\/\S+$/.test(url)) { toast('地址需要以 http:// 或 https:// 开头', true); return; }
  run(async () => { state.settings = await api('settings', { method: 'PUT', body: { packIndexUrl: url } }); renderSettings(); }, '已保存');
};

// ---------- 语音 ----------

function segValue(id) { return $(`#${id} button.on`)?.dataset.v; }
function setSeg(id, v) { $$(`#${id} button`).forEach(b => b.classList.toggle('on', b.dataset.v === v)); }
$$('.seg').forEach(seg => $$('button', seg).forEach(b => b.addEventListener('click', () => {
  if (seg.id === 'bankFilter') return;
  setSeg(seg.id, b.dataset.v);
  if (seg.id === 'ttsProvider') fillVoices(b.dataset.v, '');
  updateVoiceVisibility();
})));

function fillVoices(provider, current) {
  const list = VOICES[provider] || [];
  const opts = list.map(([v, n]) => `<option value="${v}">${n}</option>`);
  if (current && !list.some(([v]) => v === current)) opts.push(`<option value="${esc(current)}">${esc(current)}</option>`);
  opts.push('<option value="__custom">其他音色（手动填写）…</option>');
  $('#ttsVoice').innerHTML = opts.join('');
  $('#ttsVoice').value = current || (list[0] ? list[0][0] : '');
}
$('#ttsVoice').onchange = () => {
  if ($('#ttsVoice').value !== '__custom') return;
  const v = prompt('输入音色编号（百度为 per 数字，火山为 voice_type）');
  fillVoices(segValue('ttsProvider'), v ? v.trim() : '');
};

function updateVoiceVisibility() {
  const asr = segValue('asrProvider'), tts = $('#ttsEnabled').checked ? segValue('ttsProvider') : 'none';
  const online = asr === 'baidu' || asr === 'volc';
  $('#offlineCard').classList.toggle('hidden', asr === 'none');
  $('#fallbackRow').classList.toggle('hidden', !online);
  $('#testAsr').classList.toggle('hidden', asr === 'none');
  $('#testAsr').textContent = asr === 'offline' ? '测试离线识别' : '测试连接';
  $('#asrHint').textContent = asr === 'offline' && !state.offline?.installed ? '请先在下面把离线语音包下载到电视。' : '';
  $('#ttsBox').classList.toggle('hidden', !$('#ttsEnabled').checked);
  $('#baiduKeys').classList.toggle('hidden', asr !== 'baidu' && tts !== 'baidu');
  $('#volcKeys').classList.toggle('hidden', asr !== 'volc' && tts !== 'volc');
  $('#speedField').classList.toggle('hidden', segValue('ttsProvider') !== 'baidu');
}
$('#ttsEnabled').onchange = updateVoiceVisibility;
$('#ttsSpeed').oninput = () => { $('#speedVal').textContent = $('#ttsSpeed').value; };

function renderVoice() {
  const v = state.settings.voice;
  setSeg('asrProvider', v.asrProvider);
  $('#asrOfflineFallback').checked = v.asrOfflineFallback;
  setSeg('ttsProvider', v.ttsProvider);
  $('#ttsEnabled').checked = v.ttsEnabled;
  fillVoices(v.ttsProvider, v.ttsVoice);
  $('#ttsSpeed').value = v.ttsSpeed;
  $('#speedVal').textContent = v.ttsSpeed;
  ['baiduApiKey', 'baiduSecretKey', 'volcAppId', 'volcAccessKey', 'volcApiKey'].forEach(k => { $('#' + k).value = v[k] || ''; });
  updateVoiceVisibility();
}

function voicePatch() {
  const voice = {
    asrProvider: segValue('asrProvider'),
    asrOfflineFallback: $('#asrOfflineFallback').checked,
    ttsEnabled: $('#ttsEnabled').checked,
    ttsProvider: segValue('ttsProvider'),
    ttsVoice: $('#ttsVoice').value === '__custom' ? '' : $('#ttsVoice').value,
    ttsSpeed: +$('#ttsSpeed').value,
  };
  ['baiduApiKey', 'baiduSecretKey', 'volcAppId', 'volcAccessKey', 'volcApiKey'].forEach(k => { voice[k] = $('#' + k).value.trim(); });
  return { voice };
}
async function saveVoice() { state.settings = await api('settings', { method: 'PUT', body: voicePatch() }); renderVoice(); }
$('#saveVoice').onclick = () => run(saveVoice, '语音设置已保存');
$('#testAsr').onclick = async e => {
  e.target.disabled = true;
  await run(async () => { await saveVoice(); const r = await api('test/asr', { method: 'POST' }); toast(r.message); });
  e.target.disabled = false;
};
// ---------- 离线语音包 ----------

let offlinePoll = null;
async function loadOffline() {
  try { renderOffline(await api('voice/offline')); } catch (_) { /* 打开语音页时顺带刷新，失败不打扰 */ }
}

function renderOffline(o) {
  state.offline = o;
  const st = o.status, busy = st.phase === 'DOWNLOADING';
  $('#offlineSize').textContent = Math.round(o.size / 1048576);
  $('#offlineBar').classList.toggle('hidden', !busy);
  $('#offlineBar i').style.width = (st.progress || 0) + '%';
  const msg = $('#offlineMsg');
  msg.textContent = busy ? `${st.message} ${st.progress}%` : st.phase === 'FAILED' ? st.message : o.installed ? '已下载到电视' : '还没有下载';
  msg.className = 'small ' + (st.phase === 'FAILED' ? 'bad-mark' : o.installed ? 'ok-mark' : 'muted');
  const install = $('#offlineInstall');
  install.classList.toggle('hidden', o.installed);
  install.disabled = busy;
  install.textContent = busy ? '下载中…' : st.phase === 'FAILED' ? '再试一次' : '下载到电视';
  $('#offlineRemove').classList.toggle('hidden', !o.installed);
  updateVoiceVisibility();
  clearInterval(offlinePoll);
  if (busy) offlinePoll = setInterval(loadOffline, 1000);
}

$('#offlineInstall').onclick = () => run(async () => renderOffline(await api('voice/offline/install', { method: 'POST' })));
$('#offlineRemove').onclick = () => {
  if (!confirm('删除电视上的离线语音包？之后要用离线识别需要重新下载。')) return;
  run(async () => renderOffline(await api('voice/offline/remove', { method: 'POST' })), '已删除');
};

$('#testTts').onclick = async e => {
  e.target.disabled = true;
  await run(async () => { await saveVoice(); const r = await api('test/tts', { method: 'POST', body: { text: $('#ttsText').value } }); toast(r.message); });
  e.target.disabled = false;
};

// ---------- 应用 ----------

async function loadApps() {
  const { apps } = await run(() => api('apps')) || { apps: [] };
  state.apps = apps;
  $('#appList').innerHTML = apps.map(a => `
    <li><img data-src="/api/icon?pkg=${encodeURIComponent(a.pkg)}" alt="">
      <span class="name"><b>${esc(a.label)}</b><small>${esc(a.pkg)}</small></span>
      <input type="checkbox" class="switch" data-pkg="${esc(a.pkg)}" ${a.selected ? 'checked' : ''}></li>`).join('');
  // 图标接口也要令牌，<img> 不能带请求头，用 fetch 拉取后转成本地地址
  $$('#appList img').forEach(async img => {
    const url = img.dataset.src;
    try {
      const res = await fetch(url, { headers: { 'X-Token': state.token } });
      img.src = URL.createObjectURL(await res.blob());
    } catch (_) { img.removeAttribute('src'); }
  });
  $$('#appList .switch').forEach(sw => sw.onchange = () => {
    const apps = $$('#appList .switch').filter(x => x.checked).map(x => x.dataset.pkg);
    saveSettings({ apps }, sw.checked ? '已加入管控' : '已移出管控');
  });
}

// ---------- 题库 ----------
// 题库包：一本书、一个绘本或一个单元的题，是开关和分享的单位。打开开关的题库合在一起出题，相同的题只出一次。

const PACK_ICONS = ['📘', '📗', '📙', '📕', '📚', '🐷', '🐻', '🐰', '🦁', '🐼', '🐯', '🦊', '🐸', '🦄', '🍎', '🌈', '⭐', '🚗', '🎵', '🌻', '🚀', '🎲', '✏️', '🎯'];

async function loadPacks() {
  const r = await run(() => api('packs'));
  if (!r) return;
  state.packs = r.packs;
  state.sharedReady = r.sharedReady;
  renderPacks(r.activeCount);
}

function packStatLine(p) {
  const parts = [`${p.enabledCount} 题`];
  if (p.mastered) parts.push(`已掌握 ${p.mastered}`);
  if (p.recentAnswers) parts.push(`近 7 天答对 ${Math.round(p.recentCorrect * 100 / p.recentAnswers)}%`);
  return parts.join(' · ');
}

function renderPacks(activeCount) {
  const on = state.packs.filter(p => p.enabled);
  $('#packSummary').textContent = on.length ? `打开开关的题库会用来出题，现在共 ${activeCount} 道题（不同题库里相同的题只算一道）。点题库可以查看和修改题目。` : '';
  $('#noPackHint').classList.toggle('hidden', on.length > 0);
  $('#packList').innerHTML = state.packs.map((p, i) => `
    <li class="${p.enabled ? '' : 'off'}" style="animation-delay:${Math.min(i, 8) * 40}ms" data-id="${esc(p.id)}">
      <span class="pack-icon">${emo(esc(p.icon))}</span>
      <span class="pack-main"><b>${esc(p.name)}</b><small>${packStatLine(p)}${p.source !== 'LOCAL' ? ` · ${esc(p.sourceLabel)}` : ''}</small></span>
      <input type="checkbox" class="switch" ${p.enabled ? 'checked' : ''} aria-label="用来出题">
    </li>`).join('');
}

$('#packList').onclick = e => {
  const li = e.target.closest('li'); if (!li) return;
  const p = state.packs.find(x => x.id === li.dataset.id);
  if (e.target.classList.contains('switch')) {
    li.classList.toggle('off', !e.target.checked);
    run(() => api('pack/enable', { method: 'POST', body: { id: p.id, enabled: e.target.checked } }), e.target.checked ? `「${p.name}」会用来出题` : `「${p.name}」暂时不出题`).then(loadPacks);
    return;
  }
  openPack(p.id);
};

async function openPack(id) {
  const p = await run(() => api('pack?id=' + encodeURIComponent(id)));
  if (!p) return;
  state.pack = p;
  state.questions = p.questions;
  if (!$('#packView').classList.contains('hidden')) { renderPack(); return; }
  state.filter = 'ALL';
  $$('#bankFilter button').forEach(x => x.classList.toggle('on', x.dataset.v === 'ALL'));
  $('#bankSearch').value = '';
  $('#packsView').classList.add('hidden');
  $('#packView').classList.remove('hidden');
  renderPack();
  window.scrollTo({ top: 0 });
}

function closePack() {
  state.pack = null;
  $('#packView').classList.add('hidden');
  $('#packsView').classList.remove('hidden');
  loadPacks();
}
$('#backPacks').onclick = closePack;

function renderPack() {
  const p = state.pack;
  const stat = [`共 ${p.count} 题`];
  if (p.mastered) stat.push(`已掌握 ${p.mastered} 题`);
  if (p.recentAnswers) stat.push(`最近 7 天答了 ${p.recentAnswers} 次，答对 ${Math.round(p.recentCorrect * 100 / p.recentAnswers)}%`);
  $('#packHero').innerHTML = `
    <div class="row gap">
      <span class="pack-icon big">${emo(esc(p.icon))}</span>
      <div class="grow"><h3>${esc(p.name)}</h3><p class="muted small">${esc(p.description || p.sourceLabel + '题库')}</p></div>
    </div>
    <p class="small">${stat.join('，')}</p>
    ${p.editable ? '' : '<p class="muted small">这个题库不能直接改题，不想要的题可以点 👁️ 停用；想修改请点「更多 → 复制一份来修改」。</p>'}
    <label class="switch-row"><span>用来出题</span><input type="checkbox" class="switch" id="packEnabled" ${p.enabled ? 'checked' : ''}></label>
    <div class="row gap">
      <button class="btn grow" id="packOnly">只练这个题库</button>
      <button class="btn grow" id="packMore">更多…</button>
    </div>`;
  emojifyStatic($('#packHero'));
  $('#importBtn').classList.toggle('hidden', !p.editable);
  $('#addQ').classList.toggle('hidden', !p.editable);
  $('#packEnabled').onchange = e => run(() => api('pack/enable', { method: 'POST', body: { id: p.id, enabled: e.target.checked } }), e.target.checked ? '会用来出题' : '暂时不出题').then(() => openPack(p.id));
  $('#packOnly').onclick = () => {
    if (!confirm(`只用「${p.name}」出题，其他题库都关掉？`)) return;
    run(() => api('pack/only', { method: 'POST', body: { id: p.id } }), `现在只练「${p.name}」`).then(() => openPack(p.id));
  };
  $('#packMore').onclick = () => packMenu(p);
  renderQuestions();
}

function packMenu(p) {
  const items = [];
  if (p.editable) items.push(['rename', '✏️', '改名字和图标', '']);
  else items.push(['copy', '📝', '复制一份来修改', '复制成自己的题库，原来的会关掉，不会重复出题']);
  items.push(['export', '📤', '导出文件', '发给别的家长，或提交到分享题库']);
  if (p.deletable) items.push(['delete', '🗑️', '删除这个题库', '']);
  openMenu(p.name, items, async act => {
    if (act === 'rename') return packForm(p);
    if (act === 'export') return exportPack(p);
    if (act === 'copy') {
      const r = await run(() => api('pack/copy', { method: 'POST', body: { id: p.id } }));
      if (!r) return;
      await api('pack/enable', { method: 'POST', body: { id: p.id, enabled: false } }).catch(() => {});
      toast(`已复制为「${r.name}」，现在可以改题了`);
      openPack(r.id);
    }
    if (act === 'delete' && confirm(`删除「${p.name}」和里面的 ${p.count} 道题？`)) {
      if (await run(() => api('pack?id=' + encodeURIComponent(p.id), { method: 'DELETE' }), '已删除') !== undefined) closePack();
    }
  });
}

/** 选项菜单：每项 [动作, 图标, 标题, 说明]，点了就关闭。 */
function openMenu(title, items, onPick) {
  openSheet(title, `<div class="menu">${items.map(([act, icon, label, hint, disabled]) => `
    <button type="button" data-act="${act}" ${disabled ? 'disabled' : ''}><span class="menu-icon">${icon}</span>
      <span><b>${label}</b>${hint ? `<small>${hint}</small>` : ''}</span></button>`).join('')}</div>`, null);
  emojifyStatic($('#sheetBody'));
  $$('#sheetBody .menu button').forEach(b => b.onclick = () => { closeSheet(); onPick(b.dataset.act); });
}

$('#addPack').onclick = () => openMenu('添加题库', [
  ['shared', '📚', '从分享题库挑选', state.sharedReady ? '别人整理好的绘本、课本题库' : '分享题库即将开放', !state.sharedReady],
  ['new', '✏️', '自己建一个', '比如孩子这周在读的绘本'],
  ['file', '📂', '打开题库文件', '别人发给你的 .json 题库文件'],
], act => {
  if (act === 'new') packForm(null);
  if (act === 'file') $('#packFile').click();
  if (act === 'shared') sharedPacks();
});

function packForm(p) {
  let icon = p ? p.icon : PACK_ICONS[0];
  openSheet(p ? '修改题库' : '新建题库', `
    <label class="field"><span>名字</span><input id="pName" maxlength="30" placeholder="例：小猪佩奇第 1 册" value="${esc(p ? p.name : '')}"></label>
    <div class="field"><span>图标</span><div class="emoji-grid icons" id="pIcons">${PACK_ICONS.map(ic => `<button type="button" data-ch="${ic}" class="${ic === icon ? 'on' : ''}">${ic}</button>`).join('')}</div></div>
    <label class="field"><span>简介（可不填）</span><input id="pDesc" maxlength="200" value="${esc(p ? p.description : '')}"></label>`, async () => {
    const name = $('#pName').value.trim();
    if (!name) { toast('请填写题库名字', true); return false; }
    const r = await run(() => api('pack', { method: 'POST', body: { id: p ? p.id : '', name, icon, description: $('#pDesc').value } }));
    if (!r) return false;
    if (p) { toast('已保存'); openPack(p.id); } else { toast('建好了，点右下角 ＋ 添加题目'); openPack(r.id); }
  });
  emojifyStatic($('#pIcons'));
  $('#pIcons').onclick = e => {
    const b = e.target.closest('button'); if (!b) return;
    icon = b.dataset.ch;
    $$('#pIcons button').forEach(x => x.classList.toggle('on', x === b));
  };
  if (!p) setTimeout(() => $('#pName').focus(), 300);
}

async function exportPack(p) {
  const res = await fetch('/api/pack/export?id=' + encodeURIComponent(p.id), { headers: { 'X-Token': state.token } });
  if (!res.ok) { toast('导出失败', true); return; }
  const blob = await res.blob();
  const file = new File([blob], `${p.name}.json`, { type: 'application/json' });
  // 手机上优先用系统分享（可直接发微信），不支持时下载
  if (navigator.canShare && navigator.canShare({ files: [file] })) {
    try { await navigator.share({ files: [file], title: p.name }); return; } catch (e) { if (e.name === 'AbortError') return; }
  }
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob); a.download = file.name; a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  toast('已下载题库文件');
}

$('#packFile').onchange = async e => {
  const f = e.target.files[0]; e.target.value = '';
  if (!f) return;
  let pack;
  try { pack = JSON.parse(await f.text()); } catch (_) { toast('这个文件不是题库文件', true); return; }
  const r = await run(() => api('pack/upload', { method: 'POST', body: pack }));
  if (!r) return;
  toast(`${r.updated ? '已更新' : '已添加'}「${r.name}」，共 ${r.count} 道题` + (r.skipped ? `（${r.skipped} 道电视上用不了，已跳过）` : ''));
  loadPacks();
};

async function sharedPacks() {
  openSheet('分享题库', '<p class="muted small center">正在加载…</p>', null);
  let r;
  try { r = await api('shared'); } catch (e) { $('#sheetBody').innerHTML = `<p class="err center">${esc(e.message)}</p>`; return; }
  const label = { new: '添加', update: '更新', installed: '已添加', local: '已有' };
  $('#sheetBody').innerHTML = r.packs.length ? `<ul class="pack-list shared">${r.packs.map(p => `
    <li data-id="${esc(p.id)}">
      <span class="pack-icon">${emo(esc(p.icon))}</span>
      <span class="pack-main"><b>${esc(p.name)}</b><small>${p.count} 题${p.tags.length ? ' · ' + esc(p.tags.join(' ')) : ''}</small>
        ${p.description ? `<small>${esc(p.description)}</small>` : ''}</span>
      <button class="btn small-btn ${p.state === 'new' || p.state === 'update' ? 'primary' : ''}" ${p.state === 'new' || p.state === 'update' ? '' : 'disabled'}>${label[p.state]}</button>
    </li>`).join('')}</ul>` : '<p class="muted small center">分享题库里还没有题库</p>';
  $$('#sheetBody li button').forEach(b => b.onclick = async () => {
    b.disabled = true; b.textContent = '下载中…';
    const res = await run(() => api('shared/install', { method: 'POST', body: { id: b.closest('li').dataset.id } }));
    if (!res) { b.disabled = false; b.textContent = '重试'; return; }
    b.textContent = '已添加'; b.classList.remove('primary');
    toast(`${res.updated ? '已更新' : '已添加'}「${res.name}」，共 ${res.count} 道题`);
    loadPacks();
  });
}

$('#restoreBuiltin').onclick = () => {
  if (!confirm('把内置题库恢复成出厂内容？（删掉的会装回来，停用的题会重新启用；自己建的题库不受影响）')) return;
  run(() => api('packs/restore', { method: 'POST' }), '已恢复内置题库').then(loadPacks);
};

function renderQuestions() {
  const p = state.pack;
  const kw = $('#bankSearch').value.trim().toLowerCase();
  const list = state.questions.filter(q => (state.filter === 'ALL' || q.subject === state.filter) &&
    (!kw || [q.prompt, q.display, ...q.answers, ...q.choices].join(' ').toLowerCase().includes(kw)));
  $('#bankCount').textContent = `${list.length} 道题`;
  $('#bankList').innerHTML = list.slice(0, 300).map((q, i) => `
    <li class="${q.enabled ? '' : 'disabled'}" style="animation-delay:${Math.min(i, 12) * 25}ms" data-id="${esc(q.id)}">
      <span class="q-face ${[...q.display].length > 3 ? 'long' : ''}">${emo(esc(q.display))}</span>
      <span class="q-main"><b>${esc(q.prompt)}</b><small><span class="tag ${q.subject}">${SUBJECTS[q.subject]}</span>${q.mode === 'CHOICE' ? '<span class="tag mode">选择</span>' : ''}答案：${esc(q.answers.join(' / '))}</small></span>
      <span class="q-ops">
        <button class="icon-btn" data-op="toggle" title="启用/停用">${emo(q.enabled ? '👁️' : '🚫')}</button>
        ${p.editable ? `<button class="icon-btn" data-op="edit" title="编辑">${emo('✏️')}</button>
        <button class="icon-btn" data-op="del" title="删除">${emo('🗑️')}</button>` : ''}
      </span></li>`).join('') || `<li class="muted small">${state.questions.length ? '没有找到题目' : '还没有题目，点右下角 ＋ 添加，或者用「批量导入」一次加很多'}</li>`;
}

$('#bankList').onclick = e => {
  const btn = e.target.closest('button'); if (!btn) return;
  const id = btn.closest('li').dataset.id;
  const q = state.questions.find(x => x.id === id);
  const packId = state.pack.id;
  const reload = () => openPack(packId);
  if (btn.dataset.op === 'edit') editQuestion(q);
  if (btn.dataset.op === 'toggle') run(() => api('questions/enable', { method: 'POST', body: { packId, id, enabled: !q.enabled } })).then(reload);
  if (btn.dataset.op === 'del' && confirm(`删除「${q.display}」这道题？`)) run(() => api(`questions?pack=${encodeURIComponent(packId)}&id=${encodeURIComponent(id)}`, { method: 'DELETE' }), '已删除').then(reload);
};
$$('#bankFilter button').forEach(b => b.onclick = () => {
  state.filter = b.dataset.v;
  $$('#bankFilter button').forEach(x => x.classList.toggle('on', x === b));
  renderQuestions();
});
$('#bankSearch').oninput = renderQuestions;
$('#addQ').onclick = () => editQuestion({ id: '', subject: state.filter === 'ALL' ? 'ENGLISH' : state.filter, mode: state.filter === 'PINYIN' ? 'CHOICE' : 'VOICE', prompt: '', display: '', answers: [], choices: [], speak: '', enabled: true });

// 各科目的填写示例；选择题共用一套
const HINTS = {
  ENGLISH: { prompt: '例：香蕉的英语是什么？', display: '屏幕上的大字或表情，例：🍌', answers: '例：banana' },
  HANZI: { prompt: '例：这个字怎么读？', display: '要认的字，例：山', answers: '例：山（按读音判断，同音字也算对）' },
  MATH: { prompt: '例：算一算，等于几？', display: '例：7 + 5 = ?', answers: '例：12（说「十二」也算对）' },
  PINYIN: { prompt: '例：这个字怎么读？', display: '要认的字，例：山', answers: '例：山（看字读出来）' },
};
const CHOICE_HINT = { prompt: '例：苹果的英语是哪一个？', display: '屏幕上的大字或表情，例：🍎' };

/** [onOk] 为 null 时是菜单或浏览类面板，只显示「关闭」。 */
function openSheet(title, html, onOk) {
  $('#sheetTitle').textContent = title;
  $('#sheetBody').innerHTML = html;
  $('#sheet').classList.remove('hidden');
  $('#sheetOk').classList.toggle('hidden', !onOk);
  $('#sheetCancel').textContent = onOk ? '取消' : '关闭';
  $('#sheetOk').onclick = async () => { if (await onOk() !== false) closeSheet(); };
}
function closeSheet() { $('#sheet').classList.add('hidden'); }
$('#sheetCancel').onclick = closeSheet;
$('#sheet').onclick = e => { if (e.target.id === 'sheet') closeSheet(); };

function segHtml(id, map, current) {
  return `<div class="seg" id="${id}">${Object.entries(map).map(([k, n]) => `<button type="button" data-v="${k}" class="${k === current ? 'on' : ''}">${n}</button>`).join('')}</div>`;
}

function editQuestion(q) {
  const wrong = q.mode === 'CHOICE' ? q.choices.filter(c => c !== q.answers[0]) : [];
  while (wrong.length < 3) wrong.push('');
  const body = `
    <label class="field"><span>科目</span>${segHtml('qSubject', SUBJECTS, q.subject)}</label>
    <label class="field"><span>作答方式</span>${segHtml('qMode', MODES, q.mode)}</label>
    <label class="field"><span>题目文字</span><input id="qPrompt" value="${esc(q.prompt)}"></label>
    <label class="field"><span>大字展示</span>
      <span class="row gap"><input id="qDisplay" class="grow" value="${esc(q.display)}"><button type="button" class="btn" id="pickEmoji">选表情</button></span></label>
    <div id="emojiGrid" class="emoji-grid hidden"></div>
    <div id="voiceBox">
      <label class="field"><span>答案（多个用逗号分隔）</span><input id="qAnswers" value="${esc(q.mode === 'VOICE' ? q.answers.join(', ') : '')}"></label>
    </div>
    <div id="choiceBox">
      <label class="field"><span>正确选项</span><input id="qRight" class="right" value="${esc(q.mode === 'CHOICE' ? q.answers[0] || '' : '')}"></label>
      <label class="field"><span>错误选项（至少填一个，最多五个）</span>
        <div id="qWrong">${wrong.map(w => `<input class="wrong" value="${esc(w)}">`).join('')}</div>
        <button type="button" class="btn small-btn" id="addWrong">＋ 再加一个</button></label>
    </div>
    <label class="field"><span>朗读内容（留空则朗读题目文字）</span><input id="qSpeak" value="${esc(q.speak)}"></label>`;
  openSheet(q.id ? '编辑题目' : '新建题目', body, async () => {
    const mode = segValue('qMode');
    const payload = {
      packId: state.pack.id, id: q.id, subject: segValue('qSubject'), mode, enabled: q.enabled,
      prompt: $('#qPrompt').value, display: $('#qDisplay').value, speak: $('#qSpeak').value,
    };
    if (mode === 'CHOICE') {
      payload.answers = [$('#qRight').value.trim()].filter(Boolean);
      payload.choices = $$('#qWrong input').map(i => i.value.trim()).filter(Boolean);
    } else {
      payload.answers = $('#qAnswers').value.split(/[,，、]/).map(s => s.trim()).filter(Boolean);
      payload.choices = [];
    }
    const r = await run(() => api('questions', { method: 'POST', body: payload }), '已保存');
    if (!r) return false;
    openPack(state.pack.id);
  });
  $('#addWrong').onclick = () => {
    if ($$('#qWrong input').length >= 5) return;
    const i = document.createElement('input'); i.className = 'wrong'; $('#qWrong').appendChild(i); i.focus();
  };
  $('#pickEmoji').onclick = async () => {
    const grid = $('#emojiGrid');
    if (!grid.classList.contains('hidden')) { grid.classList.add('hidden'); return; }
    // 只列出动物、食物、交通等适合做题的表情（界面符号排除）
    grid.innerHTML = [...EMOJI].filter(k => parseInt(k, 16) >= 0x1F300 && !UI_EMOJI.has(k)).map(k =>
      `<button type="button" data-ch="${String.fromCodePoint(parseInt(k, 16))}"><img loading="lazy" src="/emoji/${k}.webp" alt=""></button>`).join('');
    grid.classList.remove('hidden');
    grid.onclick = e => { const b = e.target.closest('button'); if (!b) return; $('#qDisplay').value = b.dataset.ch; grid.classList.add('hidden'); };
  };
  const apply = () => {
    const choice = segValue('qMode') === 'CHOICE';
    const h = choice ? CHOICE_HINT : HINTS[segValue('qSubject')];
    $('#qPrompt').placeholder = h.prompt; $('#qDisplay').placeholder = h.display;
    if (!choice) $('#qAnswers').placeholder = HINTS[segValue('qSubject')].answers;
    $('#voiceBox').classList.toggle('hidden', choice);
    $('#choiceBox').classList.toggle('hidden', !choice);
    $('#qRight').placeholder = '例：apple'; $$('#qWrong input').forEach((i, n) => { i.placeholder = ['例：banana', '例：orange', '例：grape', '', ''][n]; });
  };
  ['qSubject', 'qMode'].forEach(id => $$(`#${id} button`).forEach(b => b.onclick = () => { setSeg(id, b.dataset.v); apply(); }));
  apply();
}

$('#importBtn').onclick = () => {
  const body = `
    <label class="field"><span>科目</span>${segHtml('impSubject', SUBJECTS, state.filter === 'ALL' ? 'ENGLISH' : state.filter)}</label>
    <label class="field"><span>作答方式</span>${segHtml('impMode', MODES, 'VOICE')}</label>
    <p class="muted small" id="impHint"></p>
    <textarea id="impText"></textarea>`;
  const voiceHints = {
    ENGLISH: ['每行一题：中文 英文 [表情]', '香蕉 banana 🍌\n苹果 apple 🍎\n老虎 tiger'],
    HANZI: ['直接粘贴一段汉字，每个字生成一道「看字读出来」，重复的字会跳过', '日月水火山石田禾'],
    MATH: ['每行一题：算式=答案', '3+4=7\n10-6=4\n2+2+3=7'],
    PINYIN: ['直接粘贴一段汉字，每个字生成一道「选出拼音」四选一（拼音自动生成）', '日月水火山石田禾'],
  };
  const choiceHint = ['每行一题，用竖线分隔：题目|大字展示|正确答案|错误选项1|错误选项2…', '苹果的英语是哪一个？|🍎|apple|banana|orange\n哪个数最大？|比一比|9|6|3|7'];
  openSheet('批量导入', body, async () => {
    const r = await run(() => api('questions/import', { method: 'POST', body: { packId: state.pack.id, subject: segValue('impSubject'), mode: segValue('impSubject') === 'PINYIN' ? 'VOICE' : segValue('impMode'), text: $('#impText').value } }));
    if (!r) return false;
    toast(`导入了 ${r.added} 道新题`);
    openPack(state.pack.id);
  });
  const apply = () => {
    // 拼音按字导入时本身就是选择题，这里不再区分作答方式
    const pinyin = segValue('impSubject') === 'PINYIN';
    $('#impMode').classList.toggle('hidden', pinyin);
    const [h, p] = segValue('impMode') === 'CHOICE' && !pinyin ? choiceHint : voiceHints[segValue('impSubject')];
    $('#impHint').textContent = h; $('#impText').placeholder = p;
  };
  ['impSubject', 'impMode'].forEach(id => $$(`#${id} button`).forEach(b => b.onclick = () => { setSeg(id, b.dataset.v); apply(); }));
  apply();
};

// ---------- 电视扫码动作：http://电视/#unlock 等，登录后执行 ----------

const HASH_ACTIONS = {
  unlock: { label: '解锁电视', run: () => api('unlock', { method: 'POST' }), done: '电视已解锁' },
  pause: { label: '暂停守护', run: () => api('pause', { method: 'POST', body: { paused: true } }), done: '已暂停守护' },
  resume: { label: '恢复守护', run: () => api('pause', { method: 'POST', body: { paused: false } }), done: '已恢复守护' },
};
const pendingAction = HASH_ACTIONS[location.hash.slice(1)];
if (pendingAction) { $('#loginAction').textContent = `登录后将${pendingAction.label}`; $('#loginAction').classList.remove('hidden'); }

async function runPendingAction() {
  if (!pendingAction) return;
  history.replaceState(null, '', location.pathname);
  if (!confirm(`确认${pendingAction.label}？`)) return;
  await run(pendingAction.run, pendingAction.done);
  refreshStatus().catch(() => {});
}

// ---------- 启动 ----------

async function start() {
  try {
    state.settings = await api('settings');
  } catch (e) { if (!state.token) return; toast(e.message, true); return; }
  $('#login').classList.add('hidden');
  $('#app').classList.remove('hidden');
  const status = await api('status');
  $('#themeSel').innerHTML = status.themes.map(t => `<option value="${t.id}">${t.name}</option>`).join('');
  renderSettings();
  refreshStatus().catch(() => {});
  refreshLogs().catch(() => {});
  runPendingAction();
  checkUpdate().then(u => { if (['DOWNLOADING', 'VERIFYING'].includes(u.status.phase)) pollUpdate(); }).catch(() => {});
  setInterval(() => { if (!document.hidden && state.token) refreshStatus().catch(() => {}); }, 3000);
  setInterval(() => { if (!document.hidden && state.token) refreshLogs().catch(() => {}); }, 15000);
}

loadEmoji().finally(() => { if (state.token) start(); else showLogin(); });
