
const BASE = 'http://127.0.0.1:8888';
const delay = (ms) => new Promise(r => setTimeout(r, ms));

async function login(name) {
  const r = await fetch(BASE + '/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ terminal: 0, userName: name, password: '123456' })
  });
  const j = await r.json();
  if (j.code !== 200) throw new Error(name + ' 登录失败: ' + JSON.stringify(j));
  return j.data.accessToken;
}

(async () => {
  const groupId = Number(process.argv[2] || 1111);
  const senderTok = await login('TT0001');
  const recvTok = await login('TT0002');

  const frames = [];
  const ws = new WebSocket('ws://127.0.0.1:8878/im');
  await new Promise((res, rej) => {
    ws.onopen = () => ws.send(JSON.stringify({ cmd: 0, data: { accessToken: recvTok, devId: 777001 } }));
    ws.onmessage = (e) => {
      const info = JSON.parse(e.data);
      if (info.cmd === 0) return res();
      frames.push(info);
    };
    ws.onerror = (e) => rej(new Error('ws error'));
    setTimeout(() => rej(new Error('WS 登录超时')), 15000);
  });
  console.log('  接收方 WS 登录成功');

  // 发两条群消息
  for (let i = 1; i <= 2; i++) {
    const r = await fetch(BASE + '/message/group/send', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', accessToken: senderTok },
      body: JSON.stringify({ localId: 'e2e-probe-' + Date.now() + '-' + i, groupId, content: 'e2e-probe-' + i, type: 0 })
    });
    const j = await r.json();
    if (j.code !== 200) throw new Error('发送失败: ' + JSON.stringify(j));
  }
  console.log('  已发送 2 条群消息到 groupId=' + groupId);

  await delay(6000);
  const byCmd = {};
  for (const f of frames) byCmd[f.cmd] = (byCmd[f.cmd] || 0) + 1;
  console.log('  收到的帧: ' + JSON.stringify(byCmd));
  const sig = frames.filter(f => f.cmd === 6);
  const msg = frames.filter(f => f.cmd === 4);
  console.log('  cmd=4 (消息内容) : ' + msg.length);
  if (msg[0]) console.log('    首帧原文: ' + JSON.stringify(msg[0]).slice(0, 700));
  console.log('  cmd=6 (拉取信号) : ' + sig.length);
  if (sig[0]) console.log('    首帧原文: ' + JSON.stringify(sig[0]).slice(0, 700));
  ws.close();
  process.exit(0);
})().catch(e => { console.error('  探针失败: ' + e.message); process.exit(1); });