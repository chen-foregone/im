
const BASE = 'http://127.0.0.1:8888';
const delay = (ms) => new Promise(r => setTimeout(r, ms));
async function login(name, terminal) {
  const r = await fetch(BASE + '/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ terminal, userName: name, password: '123456' }) });
  const j = await r.json();
  if (j.code !== 200) throw new Error(name + ' 登录失败: ' + JSON.stringify(j));
  return j.data.accessToken;
}
function openWs(token, devId) {
  const frames = [];
  const ws = new WebSocket('ws://127.0.0.1:8878/im');
  const ready = new Promise((res, rej) => {
    ws.onopen = () => ws.send(JSON.stringify({ cmd: 0, data: { accessToken: token, devId } }));
    ws.onmessage = (e) => { const i = JSON.parse(e.data); if (i.cmd === 0) return res(); frames.push(i); };
    setTimeout(() => rej(new Error('WS 登录超时')), 15000);
  });
  return { ws, frames, ready };
}
(async () => {
  const groupId = 1111;
  // 发送者 TT0001 的 APP 终端(2)：仅用于 HTTP 发送，不连 WS
  const sendTok = await login('TT0001', 2);
  // 发送者 TT0001 的 WEB 终端(0)：连 WS，属于"发送方的其它终端"
  const selfTok = await login('TT0001', 0);
  const self = openWs(selfTok, 777201);
  await self.ready;
  console.log('  TT0001 已用 APP 终端持有发送 token，WEB 终端在线');

  const marker = 'selfpush2-' + Date.now();
  const r = await fetch(BASE + '/message/group/send', { method: 'POST',
    headers: { 'Content-Type': 'application/json', accessToken: sendTok },
    body: JSON.stringify({ localId: marker, groupId, content: marker, type: 0 }) });
  const j = await r.json();
  if (j.code !== 200) throw new Error('发送失败: ' + JSON.stringify(j));
  console.log('  已以 APP 终端发送消息');
  await delay(6000);

  const sig = self.frames.filter(f => f.cmd === 6);
  console.log('  发送方 WEB 终端收到 cmd=6 : ' + sig.length + (sig[0] ? '   载荷=' + JSON.stringify(sig[0].data) : ''));
  if (!sig.length) { console.error('  ❌ 发送方的其它终端没收到自推信号 —— 另一台设备将看不到这条消息'); process.exit(1); }
  console.log('  ✅ 自推路径生效：发送方的其它终端收到了拉取信号');
  self.ws.close(); process.exit(0);
})().catch(e => { console.error('  探针失败: ' + e.message); process.exit(1); });
