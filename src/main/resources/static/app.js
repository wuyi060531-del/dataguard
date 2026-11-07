'use strict';
const byId = id => document.getElementById(id);
let historyOffset = 0;
let busy = false;

function message(text, error = false) {
  byId('message').textContent = text;
  byId('message').classList.toggle('error', error);
}
async function api(path, options = {}) {
  const response = await fetch(path, options);
  const body = await response.json();
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
  document.querySelectorAll('button').forEach(button => { button.disabled = true; });
  try { await work(); } catch (error) { message(error.message, true); }
  finally {
    busy = false;
    document.querySelectorAll('button').forEach(button => { button.disabled = false; });
    byId('inspect').disabled = !byId('dataset').value;
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
async function showTask(id) {
  const task = await api(`/api/tasks/${encodeURIComponent(id)}`);
  if (task.status !== 'SUCCEEDED') {
    byId('result').hidden = true;
    message(`${task.status} · ${task.errorCode || '尚未完成'}：${task.errorMessage || '稍后刷新任务状态。'}`, task.status === 'FAILED');
    return;
  }
  const report = await api(`/api/tasks/${encodeURIComponent(id)}/report`);
  byId('result').hidden = false;
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
  for (const [key, label, value] of [['totalRecords', '总记录', report.totalRecords], ['validRecords', '正常记录', report.validRecords],
    ['invalidRecords', '异常记录', report.invalidRecords], ['totalIssues', '问题数量', report.totalIssues],
    ['qualityScore', '质量分', empty ? '—' : `${report.qualityScore}%`]]) {
    const block = document.createElement('div'); block.className = 'metric';
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
  byId('rules').textContent = empty ? '没有可供规则检查的订单记录。'
    : Object.entries(report.issuesByRule).map(([name, count]) => `${name}：${count}`).join(' · ') || '本次检查未发现质量问题。';
  byId('issues').replaceChildren();
  for (const issue of report.issues.slice(0, 100)) {
    const row = document.createElement('tr');
    const values = [`${issue.recordNumber} / ${issue.lineNumber}`, issue.orderId, issue.field, issue.rule, issue.severity, issue.message];
    const labels = ['记录 / 文件行', '订单编号', '字段', '规则', '严重程度', '原因'];
    values.forEach((value, index) => {
      const td = cell(row, index === 4 ? '' : value, labels[index]);
      if (index === 4) {
        const severity = document.createElement('span'); severity.className = `issue-severity ${issue.severity}`;
        severity.textContent = `${issue.severity === 'ERROR' ? '错误' : '警告'} · ${issue.severity}`;
        td.append(severity);
      }
    });
    byId('issues').append(row);
  }
  byId('json-report').href = `/api/tasks/${encodeURIComponent(id)}/report`;
  byId('html-report').href = `/api/tasks/${encodeURIComponent(id)}/report.html`;
  if (!report.issues.length) {
    const row = document.createElement('tr');
    cell(row, empty ? '无订单记录，暂不评估数据质量。' : '当前报告没有问题记录。').colSpan = 6;
    byId('issues').append(row);
  }
  message(empty ? '检测完成：无数据可评估。结果已保存。'
    : `检测完成：${report.totalRecords} 条记录，${report.invalidRecords} 条异常。结果已保存。`);
}
byId('dataset').addEventListener('change', () => { byId('inspect').disabled = busy || !byId('dataset').value; });
byId('upload-form').addEventListener('submit', event => {
  event.preventDefault();
  action(async () => {
    const file = byId('csv-file').files[0];
    if (!file) throw new Error('请选择 CSV 文件。');
    if (file.size > 5 * 1024 * 1024) throw new Error('CSV 文件不能超过 5 MiB。');
    message('正在保存上传文件…');
    const form = new FormData(); form.append('file', file);
    const dataset = await api('/api/datasets', {method: 'POST', body: form});
    await loadDatasets(dataset.id); message(`已保存 ${dataset.fileName}，现在可以开始检测。`);
  });
});
byId('sample').addEventListener('click', () => action(async () => {
  const dataset = await api('/api/datasets/import', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({path: 'orders.csv'})});
  await loadDatasets(dataset.id); message('模拟订单已导入，点击“开始检测”。');
}));
byId('inspect').addEventListener('click', () => action(async () => {
  message('正在执行规则并保存报告…');
  const task = await api('/api/tasks', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({datasetId: byId('dataset').value})});
  await loadHistory(); await showTask(task.id);
}));
byId('refresh').addEventListener('click', () => action(async () => { await loadDatasets(); await loadHistory(); message('记录已刷新。'); }));
byId('more').addEventListener('click', () => action(() => loadHistory(true)));
action(async () => { await loadDatasets(); await loadHistory(); message('服务就绪。选择文件或导入模拟订单。'); });
