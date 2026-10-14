
const BASE = 'http://127.0.0.1:8888';
const delay = (ms) => new Promise(r => setTimeout(r, ms));
async function login(name) {
  const r = await fetch(BASE + '/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ terminal: 0, userName: name, password: '123456' }) });
  const j = await r.json();
  if (j.code !== 200) throw new Error(name + ' 登录失败');
  return j.data.accessToken;
}
async function history(tok, groupId, minSeqNo, maxSeqNo) {
  const r = await fetch(BASE + '/message/group/history', { method: 'POST',
    headers: { 'Content-Type': 'application/json', accessToken: tok },
    body: JSON.stringify({ groupId, minSeqNo, maxSeqNo }) });
  const j = await r.json();
  if (j.code !== 200) throw new Error('history 失败: ' + JSON.stringify(j));
  return j.data || [];
}
(async () => {
  const groupId = 1111;
  const senderTok = await login('TT0001');
  const recvTok = await login('TT0002');

  // 模拟客户端本地游标：先问一次当前最大 seqNo
  const before = await history(recvTok, groupId, 0, 0);
  let cursor = 0;
  for (const m of before) cursor = Math.max(cursor, m.seqNo || 0);
  console.log('  客户端本地游标(模拟) maxSeqNo = ' + cursor);

  const frames = [];
  const ws = new WebSocket('ws://127.0.0.1:8878/im');
  await new Promise((res, rej) => {
    ws.onopen = () => ws.send(JSON.stringify({ cmd: 0, data: { accessToken: recvTok, devId: 777002 } }));
    ws.onmessage = (e) => { const i = JSON.parse(e.data); if (i.cmd === 0) return res(); frames.push(i); };
    setTimeout(() => rej(new Error('WS 登录超时')), 15000);
  });

  const marker = 'pull-check-' + Date.now();
  await fetch(BASE + '/message/group/send', { method: 'POST',
    headers: { 'Content-Type': 'application/json', accessToken: senderTok },
    body: JSON.stringify({ localId: marker, groupId, content: marker, type: 0 }) });
  await delay(5000);

  const sig = frames.find(f => f.cmd === 6);
  if (!sig) { console.error('  ❌ 没收到 cmd=6 信号'); process.exit(1); }
  console.log('  收到信号: ' + JSON.stringify(sig.data));

  // 客户端按信号游标拉取
  const pulled = await history(recvTok, groupId, cursor + 1, sig.data.maxMessageId);
  console.log('  按游标拉取 [' + (cursor + 1) + ', ' + sig.data.maxMessageId + '] -> ' + pulled.length + ' 条');
  const hit = pulled.find(m => m.localId === marker);
  if (!hit) { console.error('  ❌ 拉取结果里没有刚发的消息'); process.exit(1); }
  console.log('  ✅ 拉到目标消息: content=' + JSON.stringify(hit.content) + ' seqNo=' + hit.seqNo + ' id=' + hit.id);
  if (sig.data.maxMessageId !== hit.id) { console.error('  ❌ 信号 maxMessageId 与实际消息 id 不一致'); process.exit(1); }
  console.log('  ✅ 信号 maxMessageId 与实际消息 id 一致 (' + hit.id + ')');

  // 幂等：再拉一次应为空（客户端跳过重复信号）
  const again = await history(recvTok, groupId, hit.seqNo + 1, sig.data.maxMessageId);
  console.log('  重复拉取（游标已推进）-> ' + again.length + ' 条' + (again.length === 0 ? '  ✅ 幂等' : '  ❌ 非空'));

  ws.close(); process.exit(0);
})().catch(e => { console.error('  探针失败: ' + e.message); process.exit(1); });
