// Pure-function tests for the issue "actual value" display. Run: node --test scripts/issue-value.test.cjs
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '..', 'src/main/resources/static/app.js'), 'utf8');
const start = source.indexOf('function issueValueText'), end = source.indexOf('function filteredIssues');
assert.ok(start >= 0 && end > start, 'issueValueText must exist in app.js');
const sandbox = {}; vm.createContext(sandbox); vm.runInContext(source.slice(start, end) + ';this.f = issueValueText;', sandbox);
const text = issue => sandbox.f(issue);
const row = {order_id: 'ORD1', quantity: '101', unit_price: '1e2', status: 'paid', customer_id: '  ', note: ''};

test('single field shows the raw value', () => {
  assert.equal(text({field: 'quantity', record: row}), '101');
  assert.equal(text({field: 'unit_price', record: row}), '1e2');
  assert.equal(text({field: 'status', record: row}), 'paid');
});
test('blank and whitespace-only values are made visible', () => {
  assert.equal(text({field: 'note', record: row}), '（空）');
  assert.equal(text({field: 'customer_id', record: row}), '（空）');
});
test('composite fields list every value', () => {
  assert.equal(text({field: 'quantity,unit_price', record: row}), 'quantity=101；unit_price=1e2');
});
test('values are returned verbatim, never reformatted', () => {
  assert.equal(text({field: 'q', record: {q: ' 5'}}), ' 5');
  assert.equal(text({field: 'q', record: {q: '<b>x</b>'}}), '<b>x</b>');
});
test('structure issues, missing record and unknown fields show nothing', () => {
  assert.equal(text({field: '*', record: row}), null);
  assert.equal(text({field: 'quantity'}), null);
  assert.equal(text({field: 'quantity', record: null}), null);
  assert.equal(text({field: 'absent', record: row}), null);
  assert.equal(text({field: 'toString', record: row}), null);
});
