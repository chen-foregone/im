'use strict';

(function () {
  var PRIVATE_MSG_SIM_ACTIVE_KEY = 'im-simulator-private-msg-simulate-active';
  var GROUP_MSG_SIM_ACTIVE_KEY = 'im-simulator-group-msg-simulate-active';
  var POLL_MS = 100;

  var MAX_BATCH = 10000;

  var MAX_PRIVATE_SENDERS = 1000;
  var MAX_PRIVATE_RECEIVERS = 5000;
  var MAX_TOTAL_SENDS = 10000000;

  var MAX_GROUP_SENDERS = 1000;
  var MAX_GROUP_GROUPS = 10;

  var STATUS_ZH = {
    IDLE: '空闲',
    RUNNING: '运行中',
    SETTLING: '收尾中',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已停止',
  };

  var PRIVATE_SAMPLE_HEADER = { round: '轮次', sendId: '发送方 ID', recvId: '接收方 ID', reason: '原因' };
  var GROUP_SAMPLE_HEADER = { round: '轮次', sendId: '发送方 ID', groupId: '群 ID', reason: '原因' };

  var state = {
    step: 1,
    batchReady: false,
    simulateMode: null,
    batchLoading: false,
    batchStopping: false,
    privatePollTimer: null,
    privatePollSession: 0,
    privateLoading: false,
    privateStopping: false,
    groupPollTimer: null,
    groupPollSession: 0,
    groupLoading: false,
    groupStopping: false,
  };

  function $(id) {
    return document.getElementById(id);
  }

  function bindClick(id, handler) {
    bindEvent(id, 'click', handler);
  }

  function bindSubmit(id, handler) {
    bindEvent(id, 'submit', handler);
  }

  function bindEvent(id, type, handler) {
    var el = $(id);
    if (el) el.addEventListener(type, handler);
  }

  function setDisabled(id, on) {
    var el = $(id);
    if (el) el.disabled = on;
  }

  function show(el, on) {
    if (!el) return;
    el.classList.toggle('is-hidden', !on);
  }

  function text(el, s) {
    if (el) el.textContent = s == null ? '' : String(s);
  }

  function syncGroupOnlineFromScale() {
    var sel = $('group-scale');
    var opt = sel && sel.options[sel.selectedIndex];
    var n = opt && opt.getAttribute('data-members');
    if (n) $('g-online-members').value = n;
  }

  function parseInt10(v) {
    var n = parseInt(String(v), 10);
    return Number.isFinite(n) ? n : NaN;
  }

  function parseOptionalMaxSendQps(id) {
    var el = $(id);
    if (!el) return null;
    var raw = (el.value || '').trim();
    if (!raw) return null;
    var n = parseInt10(raw);
    if (!Number.isInteger(n) || n < 1) return NaN;
    return n;
  }

  function parseMaxSendQpsInput() {
    return parseOptionalMaxSendQps('private-max-send-qps');
  }

  function parseGroupMaxSendQpsInput() {
    return parseOptionalMaxSendQps('group-max-send-qps');
  }

  function totalPrivateSends() {
    var a = parseInt10($('sender-count').value);
    var b = parseInt10($('receiver-count').value);
    var r = parseInt10($('send-rounds').value);
    if (!Number.isFinite(a) || !Number.isFinite(b) || !Number.isFinite(r)) return 0;
    var p = a * b;
    if (!Number.isFinite(p) || p > Number.MAX_SAFE_INTEGER / Math.max(1, r)) return -1;
    return p * r;
  }

  function privateCountsValid() {
    var a = parseInt10($('sender-count').value);
    var b = parseInt10($('receiver-count').value);
    var r = parseInt10($('send-rounds').value);
    if (!Number.isInteger(a) || !Number.isInteger(b) || !Number.isInteger(r) || a < 1 || b < 1 || r < 1)
      return false;
    if (a > MAX_PRIVATE_SENDERS || b > MAX_PRIVATE_RECEIVERS) return false;
    if (Number.isNaN(parseMaxSendQpsInput())) return false;
    var t = totalPrivateSends();
    if (t < 0 || t < 1 || t > MAX_TOTAL_SENDS) return false;
    return Number.isSafeInteger(t);
  }

  function updatePrivateTotalLine() {
    var t = totalPrivateSends();
    var line = $('private-total-line');
    var span = $('private-total-val');
    if (!line || !span) return;
    if (t < 0) {
      text(span, '数值过大');
      line.classList.add('invalid');
    } else {
      text(span, String(t));
      line.classList.toggle('invalid', t > MAX_TOTAL_SENDS || t < 1);
    }
  }

  function totalGroupHttp() {
    var a = parseInt10($('g-sender-count').value);
    var b = parseInt10($('g-receiver-groups').value);
    var r = parseInt10($('g-send-rounds').value);
    if (!Number.isFinite(a) || !Number.isFinite(b) || !Number.isFinite(r)) return 0;
    var p = a * b;
    if (!Number.isFinite(p) || p > Number.MAX_SAFE_INTEGER / Math.max(1, r)) return -1;
    return p * r;
  }

  function totalGroupWs() {
    var http = totalGroupHttp();
    var online = parseInt10($('g-online-members').value);
    if (!Number.isFinite(http) || http < 0) return -1;
    if (!Number.isFinite(online) || online < 1) return -1;
    return http * (online - 1);
  }

  function groupCountsValid() {
    var a = parseInt10($('g-sender-count').value);
    var b = parseInt10($('g-receiver-groups').value);
    var r = parseInt10($('g-send-rounds').value);
    if (!Number.isInteger(a) || !Number.isInteger(b) || !Number.isInteger(r) || a < 1 || b < 1 || r < 1)
      return false;
    if (a > MAX_GROUP_SENDERS || b > MAX_GROUP_GROUPS) return false;
    if (Number.isNaN(parseGroupMaxSendQpsInput())) return false;
    var t = totalGroupHttp();
    if (t < 0 || t < 1 || t > MAX_TOTAL_SENDS) return false;
    return Number.isSafeInteger(t);
  }

  function updateGroupTotalLines() {
    var http = totalGroupHttp();
    var ws = totalGroupWs();
    var hLine = $('group-http-total-line');
    var wLine = $('group-ws-total-line');
    var hVal = $('group-http-total-val');
    var wVal = $('group-ws-total-val');
    if (!hLine || !wLine || !hVal || !wVal) return;
    if (http < 0) {
      text(hVal, '数值过大');
      hLine.classList.add('invalid');
    } else {
      text(hVal, String(http));
      hLine.classList.toggle('invalid', http > MAX_TOTAL_SENDS || http < 1);
    }
    if (ws < 0) {
      text(wVal, '数值过大');
      wLine.classList.add('invalid');
    } else {
      text(wVal, String(ws));
      wLine.classList.toggle('invalid', http > MAX_TOTAL_SENDS || http < 1);
    }
  }

  function jobStatusModifier(s) {
    if (!s) return 'unknown';
    var x = String(s).toLowerCase();
    if (
      x === 'idle' ||
      x === 'running' ||
      x === 'settling' ||
      x === 'completed' ||
      x === 'failed' ||
      x === 'cancelled'
    )
      return x;
    return 'unknown';
  }

  function renderStepsNav() {
    var step = state.step;
    var mode = state.simulateMode;

    var btn1 = $('step-nav-1-btn');
    var st1 = $('step-nav-1-static');
    var btn2 = $('step-nav-2-btn');
    var st2 = $('step-nav-2-static');
    var st3 = $('step-nav-3');
    var bar1 = $('steps-bar-1');
    var bar2 = $('steps-bar-2');
    var label3 = $('step3-nav-label');

    if (mode === 'group') text(label3, '模拟群聊');
    else if (mode === 'private') text(label3, '模拟私聊');
    else text(label3, '执行任务');

    if (btn1) {
      show(btn1, step > 1);
      btn1.classList.toggle('steps__seg--done', step > 1);
    }
    if (st1) {
      show(st1, step === 1);
      if (step === 1) {
        st1.classList.add('steps__seg--active');
        st1.classList.remove('steps__seg--muted');
      } else {
        st1.classList.remove('steps__seg--active');
      }
    }

    if (btn2) {
      show(btn2, step > 2);
      btn2.classList.toggle('steps__seg--done', step > 2);
    }
    if (st2) {
      show(st2, step <= 2);
      st2.classList.toggle('steps__seg--active', step === 2);
      st2.classList.toggle('steps__seg--muted', step === 1);
      st2.classList.remove('steps__seg--done');
      var step2Clickable = state.batchReady && step === 1;
      st2.classList.toggle('steps__seg--clickable', step2Clickable);
      st2.setAttribute('role', step2Clickable ? 'button' : 'presentation');
      st2.setAttribute('tabindex', step2Clickable ? '0' : '-1');
      st2.title = step2Clickable ? '进入选择场景' : '';
    }

    if (st3) {
      st3.classList.toggle('steps__seg--active', step === 3);
      st3.classList.toggle('steps__seg--muted', step < 3);
    }

    if (bar1) bar1.classList.toggle('steps__bar--done', step > 1);
    if (bar2) bar2.classList.toggle('steps__bar--done', step === 3);
  }

  function renderWizardPanels() {
    show($('wizard-step-1'), state.step === 1);
    show($('wizard-step-2'), state.step === 2);
    show($('wizard-step-3'), state.step === 3);
    show($('panel-private'), state.step === 3 && state.simulateMode === 'private');
    show($('panel-group'), state.step === 3 && state.simulateMode === 'group');
    setDisabled('btn-go-step2', !state.batchReady);
    renderStepsNav();
  }

  function goStep2() {
    if (!state.batchReady) {
      show($('batch-error'), true);
      text($('batch-error'), '请先完成批量登录（至少 1 个用户完全在线）');
      return;
    }
    state.step = 2;
    state.simulateMode = null;
    renderWizardPanels();
  }

  function goStep1() {
    state.step = 1;
    state.simulateMode = null;
    renderWizardPanels();
  }

  function goStep3Private() {
    state.step = 3;
    state.simulateMode = 'private';
    renderWizardPanels();
  }

  function goStep3Group() {
    state.step = 3;
    state.simulateMode = 'group';
    renderWizardPanels();
  }

  function goStep2FromStep3() {
    state.step = 2;
    state.simulateMode = null;
    renderWizardPanels();
  }

  function batchLimitValid() {
    var n = parseInt10($('user-count').value);
    return Number.isInteger(n) && n >= 1 && n <= MAX_BATCH;
  }

  function renderBatchResult(r) {
    var wrap = $('batch-result');
    var stats = $('batch-stats');
    show($('batch-error'), false);
    if (!r) {
      show(wrap, false);
      return;
    }
    show(wrap, true);
    stats.innerHTML =
      '<div><dt>请求用户数</dt><dd>' +
      r.requested +
      '</dd></div>' +
      '<div><dt>HTTP 登录成功</dt><dd>' +
      r.httpLoginSucceeded +
      '</dd></div>' +
      '<div><dt>完全在线（含 WS）</dt><dd>' +
      r.succeeded +
      '</dd></div>' +
      '<div><dt>未完全在线</dt><dd>' +
      r.failed +
      '</dd></div>' +
      '<div><dt>耗时</dt><dd>' +
      r.elapsedMs +
      ' ms</dd></div>' +
      '<div><dt>状态</dt><dd>' +
      escapeHtml(r.message || '') +
      '</dd></div>';
    var samples = r.failureSamples && r.failureSamples.length;
    var sw = $('batch-samples');
    var tbody = $('batch-samples-body');
    if (samples) {
      show(sw, true);
      tbody.innerHTML = r.failureSamples
        .map(function (s) {
          return (
            '<tr><td>' +
            escapeHtml(s.userName || '') +
            '</td><td class="mono">' +
            escapeHtml(s.reason || '') +
            '</td></tr>'
          );
        })
        .join('');
    } else {
      show(sw, false);
    }
  }

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function renderFailureTable(headId, bodyId, wrapId, samples, headerMap) {
    var wrap = $(wrapId);
    var thead = $(headId);
    var tbody = $(bodyId);
    if (!samples || !samples.length) {
      show(wrap, false);
      return;
    }
    var keys = Object.keys(samples[0]);
    thead.innerHTML = '<tr>' + keys.map(function (k) {
      return '<th>' + escapeHtml(headerMap[k] || k) + '</th>';
    }).join('') + '</tr>';
    tbody.innerHTML = samples
      .map(function (row) {
        return (
          '<tr>' +
          keys
            .map(function (k) {
              return '<td class="mono">' + escapeHtml(row[k] == null ? '' : String(row[k])) + '</td>';
            })
            .join('') +
          '</tr>'
        );
      })
      .join('');
    show(wrap, true);
  }

  function renderPrivateJob(job) {
    var wrap = $('private-result');
    var stats = $('private-stats');
    if (!job) {
      show(wrap, false);
      return;
    }
    show(wrap, true);
    var n = job.wsPrivateMessagesReceived;
    var ms = job.recvMs;
    var recvQps =
      n != null && ms != null && ms > 0 ? ((n * 1000) / ms).toFixed(2) : '—';
    var st = job.status || '';
    var stZh = STATUS_ZH[st] || st || '—';
    var mod = jobStatusModifier(st);
    stats.innerHTML =
      '<div><dt>发送成功数量</dt><dd>' +
      job.succeeded +
      '</dd></div>' +
      '<div><dt>发送失败数量</dt><dd>' +
      job.failed +
      '</dd></div>' +
      '<div><dt>发送总耗时</dt><dd>' +
      job.sendMs +
      ' 毫秒</dd></div>' +
      '<div><dt>接收总耗时</dt><dd>' +
      job.recvMs +
      ' 毫秒</dd></div>' +
      '<div><dt>http请求量</dt><dd>' +
      job.totalRequested +
      '</dd></div>' +
      (job.maxSendQps > 0
        ? '<div><dt>发送速率限制</dt><dd>' + job.maxSendQps + ' 条/秒</dd></div>'
        : '') +
      '<div><dt>ws接收量</dt><dd>' +
      (job.wsPrivateMessagesReceived == null ? '—' : job.wsPrivateMessagesReceived) +
      '</dd></div>' +
      '<div><dt>QPS</dt><dd>' +
      recvQps +
      ' 条/秒</dd></div>' +
      '<div><dt>任务状态</dt><dd class="job-status job-status--' +
      mod +
      '">' +
      escapeHtml(stZh) +
      '</dd></div>' +
      (job.message
        ? '<div><dt>说明</dt><dd>' + escapeHtml(job.message) + '</dd></div>'
        : '');
    renderFailureTable('private-samples-head', 'private-samples-body', 'private-samples-wrap', job.failureSamples, PRIVATE_SAMPLE_HEADER);
  }

  function renderGroupJob(job) {
    var wrap = $('group-result');
    var stats = $('group-stats');
    if (!job) {
      show(wrap, false);
      return;
    }
    show(wrap, true);
    var online = parseInt10($('g-online-members').value) || 0;
    var n = job.wsGroupMessagesReceived;
    var ms = job.recvMs;
    var recvQpsWs = n != null && ms != null && ms > 0 ? (n * 1000) / ms : 0;
    var recvQpsHttp =
      online > 1 && recvQpsWs > 0 ? (recvQpsWs / (online - 1)).toFixed(2) : '—';
    var recvQpsWsStr = n != null && ms != null && ms > 0 ? recvQpsWs.toFixed(2) : '—';
    var st = job.status || '';
    var stZh = STATUS_ZH[st] || st || '—';
    var mod = jobStatusModifier(st);
    stats.innerHTML =
      '<div><dt>发送成功数量</dt><dd>' +
      job.succeeded +
      '</dd></div>' +
      '<div><dt>发送失败数量</dt><dd>' +
      job.failed +
      '</dd></div>' +
      '<div><dt>发送总耗时</dt><dd>' +
      job.sendMs +
      ' 毫秒</dd></div>' +
      '<div><dt>接收总耗时</dt><dd>' +
      job.recvMs +
      ' 毫秒</dd></div>' +
      '<div><dt>总 HTTP 请求量</dt><dd>' +
      job.totalRequested +
      '</dd></div>' +
      (job.maxSendQps > 0
        ? '<div><dt>发送速率限制</dt><dd>' + job.maxSendQps + ' 条/秒</dd></div>'
        : '') +
      '<div><dt>QPS（按HTTP计）</dt><dd>' +
      recvQpsHttp +
      ' 条/秒</dd></div>' +
      '<div><dt>WS实际接收量</dt><dd>' +
      (job.wsGroupMessagesReceived == null ? '—' : job.wsGroupMessagesReceived) +
      '</dd></div>' +
      '<div><dt>QPS（按WS计）</dt><dd>' +
      recvQpsWsStr +
      ' 条/秒</dd></div>' +
      '<div><dt>任务状态</dt><dd class="job-status job-status--' +
      mod +
      '">' +
      escapeHtml(stZh) +
      '</dd></div>' +
      (job.message
        ? '<div><dt>说明</dt><dd>' + escapeHtml(job.message) + '</dd></div>'
        : '');
    renderFailureTable('group-samples-head', 'group-samples-body', 'group-samples-wrap', job.failureSamples, GROUP_SAMPLE_HEADER);
  }

  function clearPrivatePoll() {
    state.privatePollSession++;
    if (state.privatePollTimer) {
      clearInterval(state.privatePollTimer);
      state.privatePollTimer = null;
    }
  }

  function clearGroupPoll() {
    state.groupPollSession++;
    if (state.groupPollTimer) {
      clearInterval(state.groupPollTimer);
      state.groupPollTimer = null;
    }
  }

  function markPrivateActive() {
    try {
      sessionStorage.setItem(PRIVATE_MSG_SIM_ACTIVE_KEY, '1');
    } catch (e) {}
  }

  function clearPrivateActive() {
    try {
      sessionStorage.removeItem(PRIVATE_MSG_SIM_ACTIVE_KEY);
    } catch (e) {}
  }

  function privateActiveInSession() {
    try {
      return sessionStorage.getItem(PRIVATE_MSG_SIM_ACTIVE_KEY) === '1';
    } catch (e) {
      return false;
    }
  }

  function markGroupActive() {
    try {
      sessionStorage.setItem(GROUP_MSG_SIM_ACTIVE_KEY, '1');
    } catch (e) {}
  }

  function clearGroupActive() {
    try {
      sessionStorage.removeItem(GROUP_MSG_SIM_ACTIVE_KEY);
    } catch (e) {}
  }

  function groupActiveInSession() {
    try {
      return sessionStorage.getItem(GROUP_MSG_SIM_ACTIVE_KEY) === '1';
    } catch (e) {
      return false;
    }
  }

  function stopPrivateKeepalive() {
    if (privateActiveInSession()) {
      fetch('/simulator/private-msg-simulate/stop', { method: 'POST', keepalive: true });
    }
  }

  function stopGroupKeepalive() {
    if (groupActiveInSession()) {
      fetch('/simulator/group-msg-simulate/stop', { method: 'POST', keepalive: true });
    }
  }

  function buildPendingPrivateJob() {
    var t = totalPrivateSends();
    return {
      status: 'RUNNING',
      senderCount: parseInt10($('sender-count').value),
      receiverCount: parseInt10($('receiver-count').value),
      sendRounds: parseInt10($('send-rounds').value),
      maxSendQps: parseMaxSendQpsInput() || 0,
      totalRequested: t >= 0 && t <= MAX_TOTAL_SENDS ? t : 0,
      expectWsReceiveTotal: null,
      wsPrivateMessagesReceived: null,
      succeeded: 0,
      failed: 0,
      sendMs: 0,
      recvMs: 0,
      message: '',
      failureSamples: [],
    };
  }

  function buildPendingGroupJob() {
    var t = totalGroupHttp();
    var scale = $('group-scale').value;
    return {
      status: 'RUNNING',
      groupScale: scale,
      senderCount: parseInt10($('g-sender-count').value),
      receiverGroupCount: parseInt10($('g-receiver-groups').value),
      sendRounds: parseInt10($('g-send-rounds').value),
      maxSendQps: parseGroupMaxSendQpsInput() || 0,
      totalRequested: t >= 0 && t <= MAX_TOTAL_SENDS ? t : 0,
      wsGroupMessagesReceived: null,
      succeeded: 0,
      failed: 0,
      sendMs: 0,
      recvMs: 0,
      message: '',
      failureSamples: [],
    };
  }

  function setPrivateFormDisabled(on) {
    ['sender-count', 'receiver-count', 'send-rounds', 'private-max-send-qps'].forEach(function (id) {
      $(id).disabled = on;
    });
  }

  function setGroupFormDisabled(on) {
    ['group-scale', 'g-sender-count', 'g-receiver-groups', 'g-send-rounds', 'g-online-members', 'group-max-send-qps'].forEach(function (id) {
      $(id).disabled = on;
    });
  }

  function updatePrivateButtons() {
    var submit = $('private-submit');
    var stopBtn = $('private-stop-sim');
    if (!submit || !stopBtn) return;
    submit.textContent = state.privateLoading ? '执行中…' : '开始模拟私聊';
    submit.disabled = state.privateLoading || !privateCountsValid();
    stopBtn.disabled = !state.privateLoading || state.privateStopping;
    stopBtn.textContent = state.privateStopping ? '停止中…' : '停止模拟';
  }

  function updateGroupButtons() {
    var submit = $('group-submit');
    var stopBtn = $('group-stop-sim');
    if (!submit || !stopBtn) return;
    submit.textContent = state.groupLoading ? '执行中…' : '开始模拟群聊';
    submit.disabled = state.groupLoading || !groupCountsValid();
    stopBtn.disabled = !state.groupLoading || state.groupStopping;
    stopBtn.textContent = state.groupStopping ? '停止中…' : '停止模拟';
  }

  function bindWizardEvents() {
    bindClick('btn-back-2-to-1', goStep1);
    bindClick('btn-back-3-to-2', goStep2FromStep3);
    bindClick('step-nav-1-btn', goStep1);
    bindClick('step-nav-2-btn', goStep2FromStep3);

    document.addEventListener('click', function (e) {
      if (e.target.closest('#pick-private')) {
        goStep3Private();
        return;
      }
      if (e.target.closest('#pick-group')) {
        goStep3Group();
        return;
      }
      if (e.target.closest('#btn-go-step2')) {
        goStep2();
        return;
      }
      if (e.target.closest('#step-nav-2-static') && state.step === 1 && state.batchReady) {
        goStep2();
      }
    });

    document.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter' && e.key !== ' ') return;
      if (!e.target.closest('#step-nav-2-static') || state.step !== 1 || !state.batchReady) return;
      e.preventDefault();
      goStep2();
    });
  }

  function bindFormEvents() {
    var batchForm = $('batch-form');
    if (batchForm) {
      batchForm.addEventListener('submit', function (e) {
    e.preventDefault();
    show($('batch-error'), false);
    renderBatchResult(null);
    state.batchReady = false;
    renderWizardPanels();
    if (!batchLimitValid()) {
      show($('batch-error'), true);
      text($('batch-error'), '模拟用户数须为 1～' + MAX_BATCH + ' 的整数');
      return;
    }
    state.batchLoading = true;
    $('user-count').disabled = true;
    $('terminal').disabled = true;
    $('batch-submit').textContent = '执行中…';
    $('batch-submit').disabled = true;
    $('batch-stop').disabled = true;
    var q = new URLSearchParams();
    q.set('limit', $('user-count').value);
    q.set('terminal', $('terminal').value);
    fetch('/simulator/batch-login?' + q.toString(), { method: 'POST' })
      .then(function (res) {
        if (!res.ok) return res.text().then(function (t) { throw new Error(t || 'HTTP ' + res.status); });
        return res.json();
      })
      .then(function (r) {
        renderBatchResult(r);
        state.batchReady = r.succeeded > 0;
        renderWizardPanels();
      })
      .catch(function (err) {
        show($('batch-error'), true);
        text($('batch-error'), err.message || String(err));
        state.batchReady = false;
        renderWizardPanels();
      })
      .finally(function () {
        state.batchLoading = false;
        $('user-count').disabled = false;
        $('terminal').disabled = false;
        $('batch-submit').textContent = '开始模拟登录';
        $('batch-stop').disabled = false;
        $('batch-submit').disabled = !batchLimitValid();
      });
    });
    }

    var batchStop = $('batch-stop');
    if (batchStop) batchStop.addEventListener('click', function () {
    show($('batch-error'), false);
    state.batchStopping = true;
    $('user-count').disabled = true;
    $('terminal').disabled = true;
    $('batch-stop').textContent = '断开中…';
    $('batch-stop').disabled = true;
    $('batch-submit').disabled = true;
    fetch('/simulator/stop-sessions', { method: 'POST' })
      .then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
      })
      .then(function () {
        renderBatchResult(null);
        state.batchReady = false;
        renderWizardPanels();
      })
      .catch(function (err) {
        show($('batch-error'), true);
        text($('batch-error'), err.message || String(err));
      })
      .finally(function () {
        state.batchStopping = false;
        $('user-count').disabled = false;
        $('terminal').disabled = false;
        $('batch-stop').textContent = '断开全部模拟连接';
        $('batch-stop').disabled = false;
        $('batch-submit').disabled = !batchLimitValid();
      });
    });

    var privateForm = $('private-form');
    if (privateForm) privateForm.addEventListener('submit', function (e) {
    e.preventDefault();
    show($('private-error'), false);
    clearPrivatePoll();
    if (!privateCountsValid()) {
      show($('private-error'), true);
      text(
        $('private-error'),
        '请输入合法整数：a≤' +
          MAX_PRIVATE_SENDERS +
          '，b≤' +
          MAX_PRIVATE_RECEIVERS +
          '，轮次≥1，发送速率限制留空或≥1，且总发送量 a×b×c≤' +
          MAX_TOTAL_SENDS,
      );
      return;
    }
    state.privateLoading = true;
    setPrivateFormDisabled(true);
    updatePrivateButtons();
    renderPrivateJob(buildPendingPrivateJob());
    var body = {
      senderCount: parseInt10($('sender-count').value),
      receiverCount: parseInt10($('receiver-count').value),
      sendRounds: parseInt10($('send-rounds').value),
    };
    var maxQps = parseMaxSendQpsInput();
    if (maxQps != null) body.maxSendQps = maxQps;
    fetch('/simulator/private-msg-simulate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
      .then(function (res) {
        if (!res.ok) return res.text().then(function (t) { throw new Error(t || 'HTTP ' + res.status); });
        return res.json();
      })
      .then(function () {
        markPrivateActive();
        var session = state.privatePollSession;
        var pollInFlight = false;
        function tick() {
          if (session !== state.privatePollSession) return;
          if (pollInFlight) return;
          pollInFlight = true;
          fetch('/simulator/private-msg-simulate/status')
            .then(function (res) {
              if (!res.ok) return res.text().then(function (t) { throw new Error(t || 'HTTP ' + res.status); });
              return res.json();
            })
            .then(function (j) {
              if (session !== state.privatePollSession) return;
              renderPrivateJob(j);
              if (j.status === 'COMPLETED' || j.status === 'FAILED' || j.status === 'CANCELLED') {
                clearPrivatePoll();
                state.privateLoading = false;
                clearPrivateActive();
                setPrivateFormDisabled(false);
                updatePrivateButtons();
              }
            })
            .catch(function (err) {
              if (session !== state.privatePollSession) return;
              clearPrivatePoll();
              state.privateLoading = false;
              clearPrivateActive();
              setPrivateFormDisabled(false);
              updatePrivateButtons();
              show($('private-error'), true);
              text($('private-error'), err.message || String(err));
              renderPrivateJob(null);
            })
            .finally(function () {
              pollInFlight = false;
            });
        }
        tick();
        state.privatePollTimer = setInterval(tick, POLL_MS);
      })
      .catch(function (err) {
        show($('private-error'), true);
        text($('private-error'), err.message || String(err));
        state.privateLoading = false;
        clearPrivateActive();
        setPrivateFormDisabled(false);
        updatePrivateButtons();
        renderPrivateJob(null);
      });
    });

    var privateStop = $('private-stop-sim');
    if (privateStop) privateStop.addEventListener('click', function () {
    show($('private-error'), false);
    state.privateStopping = true;
    updatePrivateButtons();
    fetch('/simulator/private-msg-simulate/stop', { method: 'POST' })
      .then(function (res) {
        if (!res.ok) return res.text().then(function (t) { throw new Error(t || 'HTTP ' + res.status); });
      })
      .catch(function (err) {
        show($('private-error'), true);
        text($('private-error'), err.message || String(err));
      })
      .finally(function () {
        state.privateStopping = false;
        updatePrivateButtons();
      });
    });

    var groupForm = $('group-form');
    if (groupForm) groupForm.addEventListener('submit', function (e) {
    e.preventDefault();
    show($('group-error'), false);
    clearGroupPoll();
    if (!groupCountsValid()) {
      show($('group-error'), true);
      text(
        $('group-error'),
        '参数不合法：发送人数 1～' +
          MAX_GROUP_SENDERS +
          '，接收群数 1～' +
          MAX_GROUP_GROUPS +
          '，轮次≥1，发送速率限制留空或≥1，且总 HTTP 量 ≤' +
          MAX_TOTAL_SENDS,
      );
      return;
    }
    state.groupLoading = true;
    setGroupFormDisabled(true);
    updateGroupButtons();
    renderGroupJob(buildPendingGroupJob());
    var groupBody = {
      groupScale: $('group-scale').value,
      senderCount: parseInt10($('g-sender-count').value),
      receiverGroupCount: parseInt10($('g-receiver-groups').value),
      sendRounds: parseInt10($('g-send-rounds').value),
      onlineMemberCount: parseInt10($('g-online-members').value),
    };
    var groupMaxQps = parseGroupMaxSendQpsInput();
    if (groupMaxQps != null) groupBody.maxSendQps = groupMaxQps;
    fetch('/simulator/group-msg-simulate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(groupBody),
    })
      .then(function (res) {
        if (!res.ok) return res.text().then(function (t) { throw new Error(t || 'HTTP ' + res.status); });
        return res.json();
      })
      .then(function () {
        markGroupActive();
        var session = state.groupPollSession;
        var pollInFlight = false;
        function tick() {
          if (session !== state.groupPollSession) return;
          if (pollInFlight) return;
          pollInFlight = true;
          fetch('/simulator/group-msg-simulate/status')
            .then(function (res) {
              if (!res.ok) return res.text().then(function (t) { throw new Error(t || 'HTTP ' + res.status); });
              return res.json();
            })
            .then(function (j) {
              if (session !== state.groupPollSession) return;
              renderGroupJob(j);
              if (j.status === 'COMPLETED' || j.status === 'FAILED' || j.status === 'CANCELLED') {
                clearGroupPoll();
                state.groupLoading = false;
                clearGroupActive();
                setGroupFormDisabled(false);
                updateGroupButtons();
              }
            })
            .catch(function (err) {
              if (session !== state.groupPollSession) return;
              clearGroupPoll();
              state.groupLoading = false;
              clearGroupActive();
              setGroupFormDisabled(false);
              updateGroupButtons();
              show($('group-error'), true);
              text($('group-error'), err.message || String(err));
              renderGroupJob(null);
            })
            .finally(function () {
              pollInFlight = false;
            });
        }
        tick();
        state.groupPollTimer = setInterval(tick, POLL_MS);
      })
      .catch(function (err) {
        show($('group-error'), true);
        text($('group-error'), err.message || String(err));
        state.groupLoading = false;
        clearGroupActive();
        setGroupFormDisabled(false);
        updateGroupButtons();
        renderGroupJob(null);
      });
    });

    var groupStop = $('group-stop-sim');
    if (groupStop) groupStop.addEventListener('click', function () {
    show($('group-error'), false);
    state.groupStopping = true;
    updateGroupButtons();
    fetch('/simulator/group-msg-simulate/stop', { method: 'POST' })
      .then(function (res) {
        if (!res.ok) return res.text().then(function (t) { throw new Error(t || 'HTTP ' + res.status); });
      })
      .catch(function (err) {
        show($('group-error'), true);
        text($('group-error'), err.message || String(err));
      })
      .finally(function () {
        state.groupStopping = false;
        updateGroupButtons();
      });
    });

    ['sender-count', 'receiver-count', 'send-rounds', 'private-max-send-qps'].forEach(function (id) {
      var el = $(id);
      if (!el) return;
      el.addEventListener('input', function () {
        updatePrivateTotalLine();
        updatePrivateButtons();
      });
    });

    ['g-sender-count', 'g-receiver-groups', 'g-send-rounds', 'g-online-members', 'group-max-send-qps'].forEach(function (id) {
      var el = $(id);
      if (!el) return;
      el.addEventListener('input', function () {
        updateGroupTotalLines();
        updateGroupButtons();
      });
    });

    var groupScale = $('group-scale');
    if (groupScale) {
      groupScale.addEventListener('change', function () {
        syncGroupOnlineFromScale();
        updateGroupTotalLines();
        updateGroupButtons();
      });
    }

    var userCount = $('user-count');
    if (userCount) {
      userCount.addEventListener('input', function () {
        setDisabled('batch-submit', state.batchLoading || !batchLimitValid());
      });
    }
  }

  function boot() {
    bindWizardEvents();
    bindFormEvents();
    window.addEventListener('pagehide', stopPrivateKeepalive);
    window.addEventListener('pagehide', stopGroupKeepalive);

    window.addEventListener('beforeunload', function () {
      stopPrivateKeepalive();
      stopGroupKeepalive();
    });

    if (privateActiveInSession()) {
      fetch('/simulator/private-msg-simulate/stop', { method: 'POST' })
        .then(function () { clearPrivateActive(); })
        .catch(function () { clearPrivateActive(); });
    }
    if (groupActiveInSession()) {
      fetch('/simulator/group-msg-simulate/stop', { method: 'POST' })
        .then(function () { clearGroupActive(); })
        .catch(function () { clearGroupActive(); });
    }
    syncGroupOnlineFromScale();
    updatePrivateTotalLine();
    updateGroupTotalLines();
    updatePrivateButtons();
    updateGroupButtons();
    setDisabled('batch-submit', !batchLimitValid());
    renderWizardPanels();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
