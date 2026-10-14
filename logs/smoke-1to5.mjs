
const BASE = 'http://127.0.0.1:8888';
const delay = (ms) => new Promise(r => setTimeout(r, ms));
async function login(name) {
  const r = await fetch(BASE + '/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ terminal: 0, userName: name, password: '123456' }) });
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
  const senderTok = await login('TT0001');
  const recvTok = await login('TT0002');
  const recv = openWs(recvTok, 888001);
  await recv.ready;
  console.log('  接收方 WS 已登录（产生活跃连接）');
  await delay(1500);

  for (const [gid, label, expect] of [[1111, '万人群(读扩散)', 6], [1121, '五百人群(写扩散)', 4]]) {
    const before = recv.frames.length;
    const r = await fetch(BASE + '/message/group/send', { method: 'POST',
      headers: { 'Content-Type': 'application/json', accessToken: senderTok },
      body: JSON.stringify({ localId: 'smoke-' + gid + '-' + Date.now(), groupId: gid, content: 'smoke', type: 0 }) });
    const j = await r.json();
    if (j.code !== 200) { console.log('  ' + label + '  groupId=' + gid + ' 发送失败: ' + JSON.stringify(j).slice(0,120)); continue; }
    await delay(4000);
    const got = recv.frames.slice(before).map(f => f.cmd);
    const ok = got.includes(expect);
    console.log('  ' + label.padEnd(18) + ' groupId=' + String(gid).padEnd(6) + ' 收到 cmd=' + JSON.stringify(got) + '  期望含 ' + expect + '  ' + (ok ? '✅' : '❌'));
  }
  recv.ws.close();
  process.exit(0);
})().catch(e => { console.error('  探针失败: ' + e.message); process.exit(1); });
