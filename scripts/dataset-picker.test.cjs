// Drives the real app.js event handlers with a small DOM stand-in and paginated API fixtures.
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const staticDir = path.join(__dirname, '..', 'src/main/resources/static');
const source = readFileSync(path.join(staticDir, 'app.js'), 'utf8');
const html = readFileSync(path.join(staticDir, 'index.html'), 'utf8');

class Element {
  constructor() {
    this.handlers = new Map();
    this.children = [];
    this.dataset = {};
    this.classList = {toggle() {}};
    this.value = '';
    this.files = [];
    this.hidden = false;
    this.disabled = false;
  }
  addEventListener(event, callback) { this.handlers.set(event, callback); }
  dispatch(event) { return this.handlers.get(event)?.(); }
  replaceChildren(...children) { this.children = children; this.value = ''; }
  append(child) { this.children.push(child); }
  add(option) { this.append(option); }
  get options() { return this.children; }
  get rows() { return this.children; }
  setAttribute() {}
  focus() {}
}

async function openPicker(count) {
  const datasets = Array.from({length: count}, (_, index) => ({
    id: `test-${count - index}`,
    fileName: index === count - 1 ? '较早的 <b>订单</b>.csv' : 'orders.csv',
    createdAt: '2026-01-01T00:00:00Z'
  }));
  const elements = new Map(Array.from(html.matchAll(/\bid="([^"]+)"/g), ([, id]) => [id, new Element()]));
  const get = id => elements.get(id);
  const pages = [];
  const tasks = [];
  let failureStatus;
  const respond = (body, status = 200) => ({ok: status < 400, status, json: async () => body});
  async function fetch(url, options) {
    const route = new URL(url, 'http://localhost');
    if (route.pathname === '/api/datasets') {
      const limit = Number(route.searchParams.get('limit'));
      const offset = Number(route.searchParams.get('offset'));
      pages.push(offset);
      if (offset && failureStatus) {
        const status = failureStatus;
        failureStatus = undefined;
        return respond({message: '测试故障'}, status);
      }
      return respond(datasets.slice(offset, offset + limit));
    }
    if (route.pathname.startsWith('/api/datasets/')) {
      const dataset = datasets.find(item => item.id === route.pathname.split('/').at(-1));
      return dataset ? respond(dataset) : respond({}, 404);
    }
    if (url === '/api/tasks' && options.method === 'POST') {
      tasks.push(JSON.parse(options.body));
      return respond({id: 'test-task'}, 201);
    }
    if (url === '/api/tasks/test-task') return respond({status: 'RUNNING'});
    if (route.pathname === '/api/tasks') return respond([]);
    throw new Error(`Unexpected request ${url}`);
  }
  vm.runInNewContext(source, {
    document: {
      getElementById: get,
      querySelector: () => get('main'),
      querySelectorAll: () => Array.from(elements.values()),
      createElement: () => new Element()
    },
    Option: class { constructor(text, value) { this.text = text; this.value = value; } },
    fetch
  });
  await new Promise(resolve => setImmediate(resolve));
  return {get, datasets, pages, tasks, failNextPage: () => { failureStatus = 503; }};
}

test('loads a 101st dataset, keeps selection through refresh, and sends its ID for inspection', async () => {
  const {get, datasets, pages, tasks} = await openPicker(101);
  const picker = get('dataset');
  const more = get('more-datasets');
  const oldest = datasets.at(-1);
  assert.equal(picker.options.length, 101);
  assert.equal(more?.hidden, false, 'the older-results button is available');
  assert.equal(picker.options.some(option => option.value === oldest.id), false);

  picker.value = datasets[0].id;
  await more.dispatch('click');
  assert.deepEqual(pages, [0, 100]);
  assert.equal(picker.value, datasets[0].id);
  assert.match(picker.options.at(-1).text, /<b>订单<\/b>/);
  assert.equal(more.hidden, true);

  picker.value = oldest.id;
  picker.dispatch('change');
  await get('refresh').dispatch('click');
  assert.equal(picker.value, oldest.id);
  assert.equal(get('inspect').disabled, false);
  await more.dispatch('click');
  assert.equal(picker.options.filter(option => option.value === oldest.id).length, 1);
  await get('inspect').dispatch('click');
  assert.deepEqual(tasks, [{datasetId: oldest.id}]);
});

test('an unsuccessful page leaves the selection and offset intact for retry', async () => {
  const {get, datasets, pages, failNextPage} = await openPicker(101);
  const picker = get('dataset');
  picker.value = datasets[0].id;
  failNextPage();
  await get('more-datasets').dispatch('click');
  assert.equal(picker.value, datasets[0].id);
  assert.equal(picker.options.length, 101);
  assert.match(get('message').textContent, /503/);
  assert.equal(get('more-datasets').disabled, false);
  await get('more-datasets').dispatch('click');
  assert.deepEqual(pages, [0, 100, 100]);
  assert.equal(picker.options.length, 102);
});

test('short first pages hide the control and an empty final page stops loading', async () => {
  const small = await openPicker(2);
  assert.equal(small.get('more-datasets').hidden, true);
  const full = await openPicker(100);
  await full.get('more-datasets').dispatch('click');
  assert.deepEqual(full.pages, [0, 100]);
  assert.equal(full.get('more-datasets').hidden, true);
  assert.equal(full.get('dataset').options.length, 101);
});

test('refresh clears an old selection when its lookup returns 404', async () => {
  const {get, datasets} = await openPicker(101);
  await get('more-datasets').dispatch('click');
  get('dataset').value = datasets.pop().id;
  await get('refresh').dispatch('click');
  assert.equal(get('dataset').value, '');
  assert.equal(get('inspect').disabled, true);
});
