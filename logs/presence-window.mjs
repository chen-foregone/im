
const BASE = 'http://127.0.0.1:8888';
const delay = (ms) => new Promise(r => setTimeout(r, ms));
async function login(name) {
  const r = await fetch(BASE + '/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ terminal: 0, userName: name, password: '123456' }) });
  const j = await r.json();
  if (j.code !== 200) throw new Error(name + ' 登录失败');
  return { token: j.data.accessToken, id: j.data.id };
}
(async () => {
  const u = await login('TT0002');
  const ws = new WebSocket('ws://127.0.0.1:8878/im');
  await new Promise((res, rej) => {
    ws.onopen = () => ws.send(JSON.stringify({ cmd: 0, data: { accessToken: u.token, devId: 888002 } }));
    ws.onmessage = (e) => { if (JSON.parse(e.data).cmd === 0) return res(); };
    setTimeout(() => rej(new Error('WS 登录超时')), 15000);
  });
  console.log('CONNECTED userId=' + u.id + ' ws_open_at=' + Date.now());
  // 保持连接，留给外部采样窗口
  await delay(22000);
  console.log('CLOSING');
  ws.close();
  await delay(1500);
  console.log('CLOSED');
  process.exit(0);
})().catch(e => { console.error('探针失败: ' + e.message); process.exit(1); });
