'use strict';
const byId = id => document.getElementById(id);
let historyOffset = 0;
let busy = false;
let currentTaskId = null;
// 页面状态：idle（还没有结果）、report（有报告）、failed（本次检测失败）。
function showPanel(which, detail) {
  byId('result').hidden = which !== 'report';
  byId('empty-state').hidden = which !== 'idle';
  byId('failure').hidden = which !== 'failed';
  if (which === 'failed') { byId('failure-code').textContent = detail.code; byId('failure-message').textContent = detail.message; }
}
const MAX_UPLOAD = 5 * 1024 * 1024;

function message(text, error = false, state = error ? 'error' : 'info') {
  const box = byId('message');
  box.textContent = text;
  box.dataset.state = state;
  box.classList.toggle('error', error);
  box.setAttribute('role', error ? 'alert' : 'status');
  // 失败信息在页面下方触发时也要看得到，不用回到页面顶部。
  if (error && typeof box.scrollIntoView === 'function') box.scrollIntoView({block: 'nearest'});
}
async function api(path, options = {}) {
  let response;
  try { response = await fetch(path, options); }
  catch { throw new Error('无法连接本机服务。请确认服务仍在运行，然后点击“刷新”重试。'); }
  let body;
  try { body = await response.json(); }
  catch { throw new Error(`${response.status}：服务返回了无法识别的内容。`); }
  if (!response.ok) throw new Error(`${body.code || response.status}：${body.message || '请求未完成'}`);
  return body;
}
function cell(row, value, label) {
  const td = document.createElement('td');
  td.textContent = value == null ? '—' : String(value);
  if (label) td.dataset.label = label;
  row.append(td);
  return td;
}
async function action(work) {
  if (busy) return;
  busy = true;
  document.querySelector('main').setAttribute('aria-busy', 'true');
  const trigger = document.activeElement;
  document.querySelectorAll('button, #dataset, #csv-file').forEach(control => { control.disabled = true; });
  try { await work(); } catch (error) {
    message(error.message, true);
    const focus = document.activeElement;
    if (trigger?.matches('button, #dataset, #csv-file') && (focus === document.body || focus === trigger))
      byId('message').focus({preventScroll: true});
  }
  finally {
    busy = false;
    document.querySelector('main').setAttribute('aria-busy', 'false');
    document.querySelectorAll('button, #dataset, #csv-file').forEach(control => { control.disabled = false; });
    byId('inspect').disabled = !byId('dataset').value;
    updatePager();
    updateUpload();
  }
}
async function loadDatasets(selected = byId('dataset').value) {
  const datasets = await api('/api/datasets?limit=100');
  byId('dataset').replaceChildren(new Option('请选择数据集', ''));
  for (const dataset of datasets) {
    const name = `${dataset.fileName} · ${new Date(dataset.createdAt).toLocaleString()} · ${dataset.id.slice(0, 8)}`;
    byId('dataset').add(new Option(name, dataset.id));
  }
  byId('dataset').value = selected;
  byId('inspect').disabled = !byId('dataset').value;
}
async function loadHistory(append = false) {
  if (!append) historyOffset = 0;
  const tasks = await api(`/api/tasks?limit=20&offset=${historyOffset}`);
  if (!append) byId('history').replaceChildren();
  if (!tasks.length && !append) {
    const row = document.createElement('tr');
    cell(row, '暂无任务。先导入文件并开始检测。').colSpan = 6;
    byId('history').append(row);
  }
  for (const task of tasks) {
    const row = document.createElement('tr');
    row.dataset.taskId = task.id;
    if (task.id === currentTaskId) { row.className = 'current-task'; row.setAttribute('aria-current', 'true'); }
    cell(row, new Date(task.createdAt).toLocaleString());
    const identity = cell(row, task.id.slice(0, 8)); identity.title = task.id;
    const status = cell(row, {SUCCEEDED: '检测完成', FAILED: '检测失败', RUNNING: '检测中'}[task.status] || task.status);
    status.className = `state ${task.status}`; status.title = task.status;
    cell(row, task.totalRecords == null ? null : `${task.totalRecords} / ${task.invalidRecords}`);
    const quality = cell(row, task.totalRecords === 0 ? '未评估' : task.qualityScore == null ? null : `${task.qualityScore}%`);
    quality.className = task.totalRecords === 0 || task.qualityScore == null ? 'quality-unassessed'
      : task.invalidRecords > 0 ? 'quality-issue' : 'quality-clear';
    const control = cell(row, '');
    const button = document.createElement('button'); button.type = 'button'; button.className = 'secondary';
    button.textContent = task.status === 'SUCCEEDED' ? '报告' : '详情';
    button.addEventListener('click', () => action(() => showTask(task.id)));
    control.append(button); byId('history').append(row);
  }
  historyOffset += tasks.length;
  byId('more').hidden = tasks.length < 20;
}
const PAGE_SIZE = 50;
const RULES = {
  CSV_STRUCTURE: ['CSV 列数不符', '该行的列数与表头不一致（应为 7 列），请检查空行或缺失、多出的单元格。'],
  REQUIRED_FIELD: ['必填字段为空', '必填字段不能为空。'],
  DUPLICATE_ORDER: ['订单编号重复', '订单编号在文件中出现多次，所有重复的行都会被标记。'],
  FIELD_TYPE: ['字段类型不符', '字段的类型或格式不符合要求（数量应为整数，单价应为十进制数）。'],
  NUMERIC_RANGE: ['数值超出范围', '数量应在 1 到 100，单价应在 0.01 到 10000.00（含边界）。'],
  DATE_VALIDITY: ['日期时间无效', "应为真实存在的本地日期时间，格式 yyyy-MM-dd'T'HH:mm:ss，不含时区。"],
  STATUS_ALLOWED: ['状态取值不合法', '状态应为 PENDING、PAID、SHIPPED 或 CANCELLED，区分大小写。'],
  TOTAL_AMOUNT_POLICY: ['订单金额超过样例策略', '数量乘以单价超过样例业务策略的 100000.00，需要人工复核。']
};
let allIssues = [], issuesEmpty = false, issuePage = 0;
function renderComposition(report, empty) {
  const box = byId('composition');
  box.hidden = empty;
  if (empty) return;
  const total = report.totalRecords;
  box.querySelector('.seg-valid').style.width = `${(report.validRecords / total) * 100}%`;
  box.querySelector('.seg-invalid').style.width = `${(report.invalidRecords / total) * 100}%`;
  byId('composition-legend').textContent = `正常 ${report.validRecords} 条 · 异常 ${report.invalidRecords} 条（共 ${total} 条记录）`;
}
// 规则分布：数量取自报告的 issuesByRule；条内的错误/警告比例取自问题清单。
function renderRuleChart(report, empty) {
  const chart = byId('rule-chart');
  chart.replaceChildren();
  const entries = Object.entries(report.issuesByRule).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
  byId('rules').textContent = empty ? '没有可供规则检查的订单记录。'
    : entries.length ? '按问题数统计，同一条记录可以触发多项问题；百分比是占全部问题的比例。' : '本次检查未发现质量问题。';
  if (!entries.length) return;
  const max = Math.max(...entries.map(entry => entry[1]));
  const bySeverity = {};
  for (const issue of report.issues) {
    const counts = bySeverity[issue.rule] || (bySeverity[issue.rule] = {ERROR: 0, WARNING: 0});
    counts[issue.severity] = (counts[issue.severity] || 0) + 1;
  }
  for (const [rule, count] of entries) {
    const row = document.createElement('button'); row.type = 'button'; row.className = 'rule-row'; row.dataset.rule = rule;
    row.setAttribute('aria-pressed', 'false');
    const name = document.createElement('span'); name.className = 'rule-name';
    name.append(document.createTextNode(RULES[rule] ? RULES[rule][0] : rule));
    const code = document.createElement('code'); code.textContent = rule; name.append(code);
    const track = document.createElement('span'); track.className = 'rule-track';
    track.setAttribute('aria-hidden', 'true');
    const counts = bySeverity[rule] || {ERROR: 0, WARNING: 0};
    for (const [severity, cls] of [['ERROR', 'rule-fill-error'], ['WARNING', 'rule-fill-warning']]) {
      if (!counts[severity]) continue;
      const fill = document.createElement('span'); fill.className = cls;
      fill.style.width = `${(counts[severity] / max) * 100}%`; track.append(fill);
    }
    const number = document.createElement('span'); number.className = 'rule-count';
    number.textContent = String(count);
    const share = document.createElement('small');
    share.textContent = `${((count / report.totalIssues) * 100).toFixed(1)}%`; number.append(share);
    row.append(name, track, number);
    row.addEventListener('click', () => {
      const select = byId('filter-rule');
      select.value = select.value === rule ? '' : rule;
      issuePage = 0; renderIssues();
      revealFilteredIssues();
    });
    chart.append(row);
  }
  const legend = document.createElement('p'); legend.className = 'rule-legend';
  legend.innerHTML = '<span><i class="rule-fill-error"></i>错误</span><span><i class="rule-fill-warning"></i>警告</span>';
  chart.append(legend);
}
// Clicking a rule bar filters a list that usually sits below the fold. Bring the filter bar (which shows
// the active rule and the match count) and the first rows into view, otherwise the click looks like a no-op.
function listNeedsReveal(top, viewportHeight) {
  return top < 0 || top > viewportHeight * 0.6;
}
function revealFilteredIssues() {
  const target = document.querySelector('.issue-filters');
  if (target && typeof target.scrollIntoView === 'function'
      && listNeedsReveal(target.getBoundingClientRect().top, window.innerHeight)) {
    target.scrollIntoView({block: 'start'});
  }
}
// The report already carries the raw CSV row; show the value that triggered the issue so the
// user does not have to reopen the file at the reported line to see what was wrong.
function issueValueText(issue) {
  const record = issue.record;
  if (!record || typeof record !== 'object' || issue.field === '*') return null;
  const names = String(issue.field).split(',').filter(name => Object.prototype.hasOwnProperty.call(record, name));
  if (!names.length) return null;
  const show = name => (String(record[name]).trim() === '' ? '（空）' : String(record[name]));
  return names.length === 1 ? show(names[0]) : names.map(name => `${name}=${show(name)}`).join('；');
}
function filteredIssues() {
  const severity = byId('filter-severity').value, rule = byId('filter-rule').value, field = byId('filter-field').value;
  return allIssues.filter(issue => (!severity || issue.severity === severity)
    && (!rule || issue.rule === rule) && (!field || issue.field === field));
}
function fillFilter(select, values, label) {
  select.replaceChildren(new Option('全部', ''));
  for (const value of values) select.add(new Option(label ? label(value) : value, value));
}
function setIssues(issues, empty) {
  allIssues = issues; issuesEmpty = empty; issuePage = 0;
  byId('filter-severity').value = '';
  fillFilter(byId('filter-rule'), [...new Set(issues.map(i => i.rule))].sort(), rule => RULES[rule] ? `${RULES[rule][0]} · ${rule}` : rule);
  fillFilter(byId('filter-field'), [...new Set(issues.map(i => i.field))].sort());
  renderIssues();
}
function renderIssues() {
  const matches = filteredIssues();
  const pages = Math.max(1, Math.ceil(matches.length / PAGE_SIZE));
  issuePage = Math.min(issuePage, pages - 1);
  const from = issuePage * PAGE_SIZE;
  byId('issues').replaceChildren();
  for (const issue of matches.slice(from, from + PAGE_SIZE)) {
    const row = document.createElement('tr');
    const known = RULES[issue.rule];
    const labels = ['记录 / 文件行', '订单编号', '字段', '规则', '严重程度', '原因'];
    const values = [`${issue.recordNumber} / ${issue.lineNumber}`, issue.orderId, issue.field,
      known ? known[0] : issue.rule, '', ''];
    values.forEach((value, index) => {
      const td = cell(row, value, labels[index]);
      if (index === 2) {
        const actual = issueValueText(issue);
        if (actual !== null) {
          const code = document.createElement('code'); code.className = 'issue-value';
          code.textContent = actual; code.title = '该字段在 CSV 中的实际内容';
          const label = document.createElement('span'); label.className = 'issue-value-label'; label.textContent = '实际值 ';
          const line = document.createElement('div'); line.className = 'issue-value-line'; line.append(label, code);
          td.append(line);
        }
      }
      if (index === 3) {
        td.classList.add('cell-rule');
        if (known) { const code = document.createElement('code'); code.textContent = issue.rule; td.append(code); }
      }
      if (index === 4) {
        const severity = document.createElement('span'); severity.className = `issue-severity ${issue.severity}`;
        severity.textContent = issue.severity === 'ERROR' ? '错误' : '警告'; severity.title = issue.severity;
        td.append(severity);
      }
      if (index === 5) {
        const reason = document.createElement('div');
        reason.append(document.createTextNode(known ? known[1] : issue.message));
        if (known) { const original = document.createElement('span'); original.className = 'reason-original'; original.textContent = issue.message; reason.append(original); }
        td.replaceChildren(reason);
      }
    });
    byId('issues').append(row);
  }
  if (!matches.length) {
    const row = document.createElement('tr');
    cell(row, issuesEmpty ? '无订单记录，暂不评估数据质量。' : allIssues.length ? '没有符合筛选条件的问题。' : '当前报告没有问题记录。').colSpan = 6;
    byId('issues').append(row);
  }
  byId('issue-count').textContent = allIssues.length
    ? `共 ${allIssues.length} 项问题${matches.length === allIssues.length ? '' : `，筛选后 ${matches.length} 项`}${matches.length ? `；当前显示第 ${from + 1}–${Math.min(from + PAGE_SIZE, matches.length)} 项` : ''}。`
    : '';
  byId('issues-page').textContent = `第 ${issuePage + 1} / ${pages} 页`;
  for (const row of byId('rule-chart').querySelectorAll('.rule-row'))
    row.setAttribute('aria-pressed', String(row.dataset.rule === byId('filter-rule').value));
  updatePager();
}
function updatePager() {
  const pages = Math.max(1, Math.ceil(filteredIssues().length / PAGE_SIZE));
  byId('issues-prev').disabled = busy || issuePage <= 0;
  byId('issues-next').disabled = busy || issuePage >= pages - 1;
  byId('filter-reset').disabled = busy;
}
for (const id of ['filter-severity', 'filter-rule', 'filter-field'])
  byId(id).addEventListener('change', () => { issuePage = 0; renderIssues(); });
byId('filter-reset').addEventListener('click', () => {
  for (const id of ['filter-severity', 'filter-rule', 'filter-field']) byId(id).value = '';
  issuePage = 0; renderIssues();
});
function turnPage(step) {
  issuePage += step; renderIssues();
  const count = byId('issue-count');
  if (typeof count.scrollIntoView === 'function' && count.getBoundingClientRect().top < 0) count.scrollIntoView({block: 'start'});
}
byId('issues-prev').addEventListener('click', () => turnPage(-1));
byId('issues-next').addEventListener('click', () => turnPage(1));
function markCurrentTask() {
  for (const row of byId('history').rows) {
    const current = row.dataset.taskId === currentTaskId;
    row.classList.toggle('current-task', current);
    if (current) row.setAttribute('aria-current', 'true'); else row.removeAttribute('aria-current');
  }
}
async function showTask(id) {
  currentTaskId = id;
  markCurrentTask();
  const task = await api(`/api/tasks/${encodeURIComponent(id)}`);
  if (task.status !== 'SUCCEEDED') {
    if (task.status === 'FAILED') showPanel('failed', {code: task.errorCode || 'FAILED', message: task.errorMessage || '检测失败。'});
    else showPanel('idle');
    message(`${task.status} · ${task.errorCode || '尚未完成'}：${task.errorMessage || '稍后刷新任务状态。'}`, task.status === 'FAILED');
    return;
  }
  const report = await api(`/api/tasks/${encodeURIComponent(id)}/report`);
  showPanel('report');
  byId('report-source').textContent = `${report.sourceName} · ${new Date(report.generatedAt).toLocaleString()}`;
  // This is a display interpretation of the existing report, not a new scoring rule.
  const empty = report.totalRecords === 0;
  const errors = report.issuesBySeverity.ERROR || 0;
  const warnings = report.issuesBySeverity.WARNING || 0;
  const tone = empty ? 'empty' : report.invalidRecords === 0 ? 'clear' : errors > 0 ? 'error' : 'warning';
  byId('quality-summary').className = `quality-summary ${tone}`;
  byId('quality-title').textContent = {
    empty: '无数据可评估', clear: '通过当前规则检查', error: '发现数据问题', warning: '存在待复核警告'
  }[tone];
  byId('quality-description').textContent = empty
    ? `文件只有表头，没有订单记录。系统约定评分为 ${report.qualityScore}%，不代表数据已通过验证。`
    : report.invalidRecords === 0 ? '本次已配置规则未发现异常，检测结果已保存。'
    : `${report.invalidRecords} 条异常记录 · ${errors} 项错误 · ${warnings} 项警告。检测完成不代表数据合格。`;
  byId('metrics').replaceChildren();
  for (const [key, label, value] of [['qualityScore', '质量分', empty ? '—' : `${report.qualityScore}%`],
    ['totalRecords', '总记录', report.totalRecords], ['validRecords', '正常记录', report.validRecords],
    ['invalidRecords', '异常记录', report.invalidRecords], ['totalIssues', '问题数量', report.totalIssues]]) {
    const block = document.createElement('div'); block.className = key === 'qualityScore' ? 'metric metric-score' : 'metric';
    block.dataset.metric = key;
    if (!empty && key === 'validRecords' && report.validRecords > 0) block.classList.add('metric-clear');
    if (!empty && ['invalidRecords', 'totalIssues', 'qualityScore'].includes(key)) {
      if (report.invalidRecords > 0) block.classList.add(`metric-${tone}`);
      else if (key === 'qualityScore') block.classList.add('metric-clear');
    }
    const number = document.createElement('strong'); number.textContent = String(value);
    const caption = document.createElement('span'); caption.textContent = label;
    block.append(number, caption); byId('metrics').append(block);
  }
  renderComposition(report, empty);
  renderRuleChart(report, empty);
  setIssues(report.issues, empty);
  byId('json-report').href = `/api/tasks/${encodeURIComponent(id)}/report`;
  byId('html-report').href = `/api/tasks/${encodeURIComponent(id)}/report.html`;
  byId('json-download').href = byId('json-report').href;
  byId('html-download').href = byId('html-report').href;
  byId('report-title').focus();
  message(empty ? '检测完成：无数据可评估。结果已保存。'
    : `检测完成：${report.totalRecords} 条记录，${report.invalidRecords} 条异常。结果已保存。`, false, 'ok');
}
byId('dataset').addEventListener('change', () => { byId('inspect').disabled = busy || !byId('dataset').value; });
function fileProblem(file) {
  if (!file) return '';
  if (!/\.csv$/i.test(file.name)) return '只支持 .csv 文件。';
  if (file.size > MAX_UPLOAD) return 'CSV 文件不能超过 5 MiB。';
  return '';
}
function formatSize(bytes) { return bytes < 1024 ? `${bytes} B` : bytes < 1048576 ? `${(bytes / 1024).toFixed(1)} KiB` : `${(bytes / 1048576).toFixed(2)} MiB`; }
function updateUpload() {
  const file = byId('csv-file').files[0], problem = fileProblem(file);
  byId('file-info').textContent = !file ? '未选择文件，也可以把 CSV 拖到这里。'
    : problem ? `${file.name}：${problem}` : `已选择 ${file.name}（${formatSize(file.size)}），点击“上传文件”保存。`;
  byId('drop-zone').classList.toggle('invalid', Boolean(problem));
  byId('upload').disabled = busy || !file || Boolean(problem);
}
byId('csv-file').addEventListener('change', updateUpload);
const zone = byId('drop-zone');
for (const name of ['dragenter', 'dragover']) zone.addEventListener(name, event => { event.preventDefault(); if (!busy) zone.classList.add('dragover'); });
for (const name of ['dragleave', 'drop']) zone.addEventListener(name, event => { event.preventDefault(); zone.classList.remove('dragover'); });
zone.addEventListener('drop', event => {
  const files = event.dataTransfer?.files;
  if (busy || !files || !files.length) return;
  if (files.length > 1) { byId('file-info').textContent = '一次只能上传一个文件。'; return; }
  const transfer = new DataTransfer(); transfer.items.add(files[0]);
  byId('csv-file').files = transfer.files; updateUpload();
});
byId('upload-form').addEventListener('submit', event => {
  event.preventDefault();
  action(async () => {
    const file = byId('csv-file').files[0];
    if (!file) throw new Error('请选择 CSV 文件。');
    const problem = fileProblem(file);
    if (problem) throw new Error(problem);
    message('正在保存上传文件…', false, 'busy');
    const form = new FormData(); form.append('file', file);
    const dataset = await api('/api/datasets', {method: 'POST', body: form});
    await loadDatasets(dataset.id); byId('csv-file').value = ''; message(`已保存 ${dataset.fileName}，现在可以开始检测。`);
  });
});
byId('sample').addEventListener('click', () => action(async () => {
  const dataset = await api('/api/datasets/import', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({path: 'orders.csv'})});
  await loadDatasets(dataset.id); message('模拟订单已导入，点击“开始检测”。');
}));
byId('inspect').addEventListener('click', () => action(async () => {
  message('正在执行规则并保存报告…', false, 'busy');
  byId('inspect').textContent = '检测中…';
  try {
    const task = await api('/api/tasks', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({datasetId: byId('dataset').value})});
    await loadHistory(); await showTask(task.id);
  } catch (error) {
    const [code, ...rest] = error.message.split('：');
    showPanel('failed', rest.length ? {code, message: rest.join('：')} : {code: '检测失败', message: error.message});
    throw error;
  } finally { byId('inspect').textContent = '开始检测'; }
}));
byId('refresh').addEventListener('click', () => action(async () => { await loadDatasets(); await loadHistory(); message('记录已刷新。'); }));
byId('more').addEventListener('click', () => action(() => loadHistory(true)));
action(async () => { await loadDatasets(); await loadHistory(); message('服务就绪。选择文件或导入模拟订单。'); });
updateUpload();
