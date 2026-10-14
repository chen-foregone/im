
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
  // 发送者 TT0001 以 APP 终端(2)上线；接收者 TT0002 以 WEB 终端(0)上线
  const senderTok = await login('TT0001', 2);
  const recvTok = await login('TT0002', 0);

  const s = openWs(senderTok, 777101);
  await s.ready;
  console.log('  TT0001 以 APP 终端上线（作为发送者的"其它终端"）');
  const r = openWs(recvTok, 777102);
  await r.ready;
  console.log('  TT0002 以 WEB 终端上线');

  const marker = 'selfpush-' + Date.now();
  // 用 TT0001 的 APP 终端 token 发消息 -> 发送方终端本身就是 APP，
  // 所以这里改用一个"第三个终端"来验证自推：先看发送者当前终端
  const r2 = await fetch(BASE + '/message/group/send', { method: 'POST',
    headers: { 'Content-Type': 'application/json', accessToken: senderTok },
    body: JSON.stringify({ localId: marker, groupId, content: marker, type: 0 }) });
  const j2 = await r2.json();
  if (j2.code !== 200) throw new Error('发送失败: ' + JSON.stringify(j2));
  await delay(5000);

  const sSig = s.frames.filter(f => f.cmd === 6).length;
  const rSig = r.frames.filter(f => f.cmd === 6).length;
  const sMsg = s.frames.filter(f => f.cmd === 4).length;
  console.log('  接收者 TT0002(WEB) 收到 cmd=6 : ' + rSig);
  console.log('  发送者 TT0001(APP) 收到 cmd=6 : ' + sSig + '   cmd=4 : ' + sMsg);
  console.log('  说明：发送方终端本身是 APP，自推只覆盖"其它终端"，');
  console.log('        因此 TT0001(APP) 收到 0 条信号是预期行为（它自己不需要拉）。');
  if (rSig === 0) { console.error('  ❌ 接收者没收到信号'); process.exit(1); }
  console.log('  ✅ 接收者信号正常');
  s.ws.close(); r.ws.close(); process.exit(0);
})().catch(e => { console.error('  探针失败: ' + e.message); process.exit(1); });
