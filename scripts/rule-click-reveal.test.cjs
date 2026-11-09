// Tests for bringing the filtered issue list into view after a rule-bar click.
// Run: node --test scripts/rule-click-reveal.test.cjs
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '..', 'src/main/resources/static/app.js'), 'utf8');
const start = source.indexOf('function listNeedsReveal'), end = source.indexOf('// The report already carries');
assert.ok(start >= 0 && end > start, 'reveal helpers must exist in app.js');
function load(top, viewport, withTarget = true) {
  const calls = [];
  const target = {getBoundingClientRect: () => ({top}), scrollIntoView: arg => calls.push(arg)};
  const sandbox = {window: {innerHeight: viewport}, document: {querySelector: sel => (withTarget && sel === '.issue-filters' ? target : null)}};
  vm.createContext(sandbox);
  vm.runInContext(source.slice(start, end) + ';this.reveal = revealFilteredIssues; this.need = listNeedsReveal;', sandbox);
  return {sandbox, calls};
}
test('list far below the fold is scrolled to the top of the viewport', () => {
  const {sandbox, calls} = load(1500, 800); sandbox.reveal();
  assert.deepEqual(JSON.parse(JSON.stringify(calls)), [{block: 'start'}]);
});
test('list above the viewport is also brought back', () => {
  const {sandbox, calls} = load(-300, 800); sandbox.reveal(); assert.equal(calls.length, 1);
});
test('list already in the upper part of the viewport is left alone', () => {
  const {sandbox, calls} = load(120, 800); sandbox.reveal(); assert.equal(calls.length, 0);
});
test('threshold: 60% of viewport height', () => {
  const {sandbox} = load(0, 1000);
  assert.equal(sandbox.need(600, 1000), false); assert.equal(sandbox.need(601, 1000), true);
  assert.equal(sandbox.need(0, 1000), false); assert.equal(sandbox.need(-1, 1000), true);
});
test('missing filter bar does not throw', () => {
  const {sandbox, calls} = load(1500, 800, false); assert.doesNotThrow(() => sandbox.reveal()); assert.equal(calls.length, 0);
});
test('the rule-bar click handler actually calls the reveal', () => {
  const handler = source.slice(source.indexOf("row.addEventListener('click'"), source.indexOf('chart.append(row)'));
  assert.match(handler, /renderIssues\(\);\s*revealFilteredIssues\(\);/);
});
