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
function cell(row, value) {
  const td = document.createElement('td');
  td.textContent = value == null ? '—' : String(value);
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
    const status = cell(row, task.status); status.className = `state ${task.status}`;
    cell(row, task.totalRecords == null ? null : `${task.totalRecords} / ${task.invalidRecords}`);
    cell(row, task.qualityScore == null ? null : `${task.qualityScore}%`);
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
  byId('metrics').replaceChildren();
  for (const [label, value] of [['总记录', report.totalRecords], ['正常记录', report.validRecords],
    ['异常记录', report.invalidRecords], ['问题数量', report.totalIssues], ['质量分', `${report.qualityScore}%`]]) {
    const block = document.createElement('div'); block.className = 'metric';
    const number = document.createElement('strong'); number.textContent = String(value);
    const caption = document.createElement('span'); caption.textContent = label;
    block.append(number, caption); byId('metrics').append(block);
  }
  byId('rules').textContent = Object.entries(report.issuesByRule).map(([name, count]) => `${name}：${count}`).join(' · ') || '没有发现质量问题。';
  byId('issues').replaceChildren();
  for (const issue of report.issues.slice(0, 100)) {
    const row = document.createElement('tr');
    for (const value of [`${issue.recordNumber} / ${issue.lineNumber}`, issue.orderId, issue.field,
      issue.rule, issue.severity, issue.message]) cell(row, value);
    byId('issues').append(row);
  }
  byId('json-report').href = `/api/tasks/${encodeURIComponent(id)}/report`;
  byId('html-report').href = `/api/tasks/${encodeURIComponent(id)}/report.html`;
  message(`检测完成：${report.totalRecords} 条记录，${report.invalidRecords} 条异常。结果已保存。`);
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
