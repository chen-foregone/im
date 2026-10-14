
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
// 与 im-web/src/stores/chat.ts pullGroupBySignal 相同的循环追平逻辑
async function pullBySignal(tok, groupId, cursor, signalMaxMessageId) {
  const all = [];
  let rounds = 0;
  for (let round = 0; round < 200 && cursor < signalMaxMessageId; round++) {
    rounds++;
    const batch = await history(tok, groupId, cursor + 1, signalMaxMessageId);
    if (!batch.length) break;
    all.push(...batch);
    const next = batch.reduce((mx, m) => Math.max(mx, m.seqNo || 0), cursor);
    if (next <= cursor) break;
    cursor = next;
    if (batch.length < 100) break;
  }
  return { all, rounds, cursor };
}
(async () => {
  const groupId = 1111;
  const senderTok = await login('TT0001');
  const recvTok = await login('TT0002');

  // 先发 300 条，制造一个真实的"落后"区间
  console.log('  预置 300 条消息...');
  for (let i = 0; i < 300; i++) {
    await fetch(BASE + '/message/group/send', { method: 'POST',
      headers: { 'Content-Type': 'application/json', accessToken: senderTok },
      body: JSON.stringify({ localId: 'gap-' + i, groupId, content: 'gap-' + i, type: 0 }) });
  }
  await delay(3000);

  const frames = [];
  const ws = new WebSocket('ws://127.0.0.1:8878/im');
  await new Promise((res, rej) => {
    ws.onopen = () => ws.send(JSON.stringify({ cmd: 0, data: { accessToken: recvTok, devId: 777003 } }));
    ws.onmessage = (e) => { const i = JSON.parse(e.data); if (i.cmd === 0) return res(); frames.push(i); };
    setTimeout(() => rej(new Error('WS 登录超时')), 15000);
  });

  const marker = 'tail-' + Date.now();
  await fetch(BASE + '/message/group/send', { method: 'POST',
    headers: { 'Content-Type': 'application/json', accessToken: senderTok },
    body: JSON.stringify({ localId: marker, groupId, content: marker, type: 0 }) });
  await delay(5000);

  const sig = frames.find(f => f.cmd === 6);
  if (!sig) { console.error('  ❌ 没收到 cmd=6 信号'); process.exit(1); }
  console.log('  收到信号 maxMessageId=' + sig.data.maxMessageId);

  // 模拟客户端落后 250 条（跨 3 页）
  const staleCursor = sig.data.maxMessageId - 250;
  const single = await history(recvTok, groupId, staleCursor + 1, sig.data.maxMessageId);
  console.log('  单次拉取(修复前行为): ' + single.length + ' 条, 最大 seqNo=' +
    single.reduce((mx, m) => Math.max(mx, m.seqNo || 0), 0) + ' -> ' +
    (single.some(m => m.localId === marker) ? '命中' : '❌ 未命中目标'));

  const { all, rounds } = await pullBySignal(recvTok, groupId, staleCursor, sig.data.maxMessageId);
  const hit = all.find(m => m.localId === marker);
  console.log('  循环追平(修复后行为): ' + rounds + ' 轮, 共 ' + all.length + ' 条, 最大 seqNo=' +
    all.reduce((mx, m) => Math.max(mx, m.seqNo || 0), 0));
  if (!hit) { console.error('  ❌ 循环后仍未命中目标'); process.exit(1); }
  console.log('  ✅ 命中目标消息 content=' + JSON.stringify(hit.content) + ' id=' + hit.id);
  if (all.reduce((mx, m) => Math.max(mx, m.seqNo || 0), 0) !== sig.data.maxMessageId) {
    console.error('  ❌ 未追平到信号给出的 maxMessageId'); process.exit(1);
  }
  console.log('  ✅ 已追平到信号 maxMessageId');
  ws.close(); process.exit(0);
})().catch(e => { console.error('  探针失败: ' + e.message); process.exit(1); });
