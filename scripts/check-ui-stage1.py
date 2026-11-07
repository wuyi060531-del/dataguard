#!/usr/bin/env python3
"""Real Chrome stage-one checks; no mocked API or frontend framework.

Requires websocket-client in the invoking Python environment and an isolated
Chrome --headless=new --remote-debugging-port session. See docs/frontend-stage1.md.
Only use a disposable DataGuard database: this creates synthetic datasets/tasks.
"""
import argparse
import base64
import json
from pathlib import Path
import time
import urllib.request
from urllib.parse import urlparse

import websocket


class Browser:
    def __init__(self, port):
        pages = json.load(urllib.request.urlopen(f'http://127.0.0.1:{port}/json/list'))
        page = next(page for page in pages if page['type'] == 'page')
        self.socket = websocket.create_connection(page['webSocketDebuggerUrl'], timeout=20,
                                                 suppress_origin=True)
        self.sequence = 0
        self.errors = []
        for method in ('Page.enable', 'Runtime.enable', 'Network.enable', 'DOM.enable'):
            self.call(method)

    def call(self, method, params=None):
        self.sequence += 1
        identity = self.sequence
        self.socket.send(json.dumps({'id': identity, 'method': method, 'params': params or {}}))
        while True:
            result = json.loads(self.socket.recv())
            if result.get('method') == 'Runtime.exceptionThrown':
                self.errors.append(result)
            if result.get('id') == identity:
                if 'error' in result:
                    raise RuntimeError(result['error'])
                return result.get('result', {})

    def js(self, expression):
        result = self.call('Runtime.evaluate', {'expression': expression, 'returnByValue': True,
                                               'awaitPromise': True, 'userGesture': True})
        if 'exceptionDetails' in result:
            raise RuntimeError(result['exceptionDetails'])
        return result.get('result', {}).get('value')

    def wait(self, expression):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if self.js(expression):
                return
            time.sleep(.1)
        raise TimeoutError(expression)

    def size(self, width, height, mobile=False):
        self.call('Emulation.setDeviceMetricsOverride', {'width': width, 'height': height,
                                                       'deviceScaleFactor': 1, 'mobile': mobile})

    def navigate(self, url):
        self.call('Page.navigate', {'url': url})
        self.wait('document.readyState === "complete"')

    def click(self, selector):
        point = self.js('''(() => { const e = document.querySelector(%s);
            e.scrollIntoView({block: 'center'}); const r = e.getBoundingClientRect();
            return {x: r.x + r.width / 2, y: r.y + r.height / 2}; })()''' % json.dumps(selector))
        for kind in ('mousePressed', 'mouseReleased'):
            self.call('Input.dispatchMouseEvent', dict(type=kind, button='left', clickCount=1, **point))

    def ready(self):
        self.wait('document.querySelector("#history tr") && !document.querySelector("#sample").disabled')

    def upload(self, path):
        document = self.call('DOM.getDocument')['root']['nodeId']
        node = self.call('DOM.querySelector', {'nodeId': document, 'selector': '#csv-file'})['nodeId']
        self.call('DOM.setFileInputFiles', {'nodeId': node, 'files': [str(path.resolve())]})
        self.click('#upload-form button[type=submit]')
        self.wait('!document.querySelector("#sample").disabled')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--cdp-port', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--fixtures', type=Path, required=True)
    parser.add_argument('--phase', choices=('before', 'after'), required=True)
    args = parser.parse_args()
    if urlparse(args.base_url).hostname not in ('127.0.0.1', 'localhost'):
        parser.error('Only an isolated loopback demo is supported.')
    args.output.mkdir(parents=True, exist_ok=True)
    args.fixtures.mkdir(parents=True, exist_ok=True)
    b = Browser(args.cdp_port)
    result = {'phase': args.phase, 'checks': [], 'measurements': {}}
    prefix = args.phase

    def check(name, condition):
        result['checks'].append({'name': name, 'passed': bool(condition)})

    def screenshot(name):
        data = b.call('Page.captureScreenshot', {'format': 'png'})
        (args.output / f'{prefix}-{name}.png').write_bytes(base64.b64decode(data['data']))

    def width():
        return b.js('({viewport:document.documentElement.clientWidth, width:document.documentElement.scrollWidth})')

    def inspect():
        previous = b.js('document.querySelector("#json-report").getAttribute("href")')
        b.click('#inspect')
        b.wait('!document.querySelector("#inspect").disabled')
        b.wait('document.querySelector("#json-report").getAttribute("href") !== ' + json.dumps(previous))
        url = b.js('document.querySelector("#json-report").href')
        return b.js('fetch(%s).then(r => r.json())' % json.dumps(url))

    def fixture(name, content):
        path = args.fixtures / name
        path.write_text(content, encoding='utf-8')
        b.upload(path)
        return inspect()

    def summary():
        return b.js('document.querySelector("#quality-summary")?.innerText || ""')

    b.size(1366, 900)
    b.navigate(args.base_url)
    b.ready()
    b.click('#sample')
    b.wait('!!document.querySelector("#dataset").value && !document.querySelector("#sample").disabled')
    sample = inspect()
    sample_url = b.js('document.querySelector("#html-report").href')
    check('sample API remains 40/29/11/11/72.50',
          [sample[k] for k in ('totalRecords', 'validRecords', 'invalidRecords', 'totalIssues', 'qualityScore')]
          == [40, 29, 11, 11, 72.5])
    b.js('window.scrollTo(0,0)')
    result['measurements']['desktopResultTop'] = b.js('document.querySelector("#result").getBoundingClientRect().top')
    screenshot('desktop')
    if prefix == 'after':
        check('desktop report overview is in first viewport',
              b.js('document.querySelector("#metrics").getBoundingClientRect().bottom <= innerHeight'))
        check('execution and dirty-data outcome are separately labelled',
              b.js('document.querySelector("#execution-state").textContent.includes("检测完成")')
              and '发现数据问题' in summary())
        check('dirty metrics are not styled as clean',
              b.js('document.querySelector("[data-metric=invalidRecords]").classList.contains("metric-error")'))

    b.size(390, 844, True)
    b.js('window.scrollTo(0,0)')
    screenshot('mobile-top')
    b.js('document.querySelector("#result").scrollIntoView({block:"start"})')
    screenshot('mobile-overview')
    result['measurements']['mobilePage'] = width()
    b.js('document.querySelector("#result .table-wrap").scrollIntoView({block:"start"})')
    screenshot('mobile-issues')
    result['measurements']['mobileIssueCellWidth'] = b.js('document.querySelector("#issues td:nth-child(2)").getBoundingClientRect().width')
    if prefix == 'after':
        check('390px main page has no whole-page overflow', width()['width'] <= 390)
        check('mobile issue identifiers have a readable full-width cell',
              result['measurements']['mobileIssueCellWidth'] >= 300)
        check('mobile issue labels match semantic headers',
              b.js('document.querySelector("#issues td:nth-child(2)").dataset.label === "订单编号"'))

    b.navigate(sample_url)
    result['measurements']['mobileHtmlReport'] = width()
    b.js('document.querySelectorAll("table")[1].scrollIntoView({block:"start"})')
    screenshot('mobile-html')
    if prefix == 'after':
        check('390px standalone HTML has no whole-page overflow', width()['width'] <= 390)
        check('HTML table overflow is locally scrollable',
              b.js('[...document.querySelectorAll(".table-wrap")].some(e => e.scrollWidth > e.clientWidth)'))

    header = 'order_id,customer_id,product_id,quantity,unit_price,order_time,status\n'
    b.size(1366, 900)
    b.navigate(args.base_url)
    b.ready()
    zero = fixture('header-only.csv', header)
    check('zero-record API still returns score 100', zero['totalRecords'] == 0 and zero['qualityScore'] == 100)
    if prefix == 'after':
        check('zero-record UI says no data to assess', '无数据可评估' in summary())
        check('zero-record score metric does not imply quality passed',
              b.js('document.querySelector("[data-metric=qualityScore] strong").textContent === "—"'))
        check('zero-record history labels quality as unassessed',
              b.js('document.querySelector("#history tr").innerText.includes("未评估")'))
    b.js('document.querySelector("#result").scrollIntoView({block:"start"})')
    screenshot('zero-records')
    zero_url = b.js('document.querySelector("#html-report").href')
    b.navigate(zero_url)
    if prefix == 'after':
        check('zero-record HTML explains convention without changing score',
              b.js('document.body.innerText.includes("无数据可评估") && document.body.innerText.includes("100.00%")'))
    b.navigate(args.base_url)
    b.ready()
    warning = fixture('warning-only.csv', header + 'W1,C1,P1,100,10000,2026-10-19T09:00:00,PAID\n')
    check('warning-only detection succeeds with one invalid record',
          warning['invalidRecords'] == 1 and warning['issuesBySeverity'].get('WARNING') == 1)
    if prefix == 'after':
        check('warning-only outcome is distinct from errors', '存在待复核警告' in summary())
    screenshot('warning-only')
    clean = fixture('clean.csv', header + 'V1,C1,P1,2,5,2026-10-19T09:00:00,PAID\n')
    check('clean API remains valid', clean['validRecords'] == 1 and clean['totalIssues'] == 0)
    if prefix == 'after':
        check('clean report explicitly references current rules', '通过当前规则检查' in summary())
    long_record = 'ORDER-' + 'X' * 280 + '<img src=x onerror=alert(1)>'
    long_report = fixture('中文长文件名订单演示.csv', header + long_record + ',C1,P1,0,5,2026-10-19T09:00:00,PAID\n')
    check('untrusted text stays text in main page',
          long_report['totalIssues'] == 1 and b.js('document.querySelector("#issues img") === null'))
    b.size(390, 844, True)
    if prefix == 'after':
        check('long untrusted values do not overflow main page', width()['width'] <= 390)
    b.navigate(b.js('document.querySelector("#html-report").href'))
    if prefix == 'after':
        check('long snapshot is escaped and does not overflow HTML page',
              width()['width'] <= 390 and b.js('document.querySelector("img") === null'))
    b.size(1366, 900)
    b.navigate(args.base_url)
    b.ready()
    path = args.fixtures / 'empty.csv'
    path.write_text('')
    b.upload(path)
    b.click('#inspect')
    b.wait('!document.querySelector("#inspect").disabled')
    check('empty-file task still fails rather than producing a report',
          b.js('document.querySelector("#message").textContent.includes("EMPTY_FILE") && document.querySelector("#result").hidden'))
    check('no unhandled browser runtime exceptions', not b.errors)
    result['browser'] = b.call('Browser.getVersion')['product']
    result['passed'] = all(item['passed'] for item in result['checks'])
    (args.output / f'{prefix}-checks.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False, indent=2))
    b.socket.close()
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
