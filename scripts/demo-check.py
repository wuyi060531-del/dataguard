#!/usr/bin/env python3
"""Reusable local/Compose HTTP demonstration check, with explicit persistence verification."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import uuid

EXPECTED = dict(totalRecords=40, validRecords=29, invalidRecords=11, totalIssues=11, qualityScore=72.5)


def check(base: str, phase: str, state: Path) -> dict:
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme != 'http' or parsed.hostname not in ('localhost', '127.0.0.1') or parsed.username \
            or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
        raise ValueError('Demonstration checks require a loopback HTTP origin')
    base = base.rstrip('/')

    def request(path, data=None, method=None, expected=200, content_type='application/json', html=False):
        payload = data if isinstance(data, bytes) else None if data is None else json.dumps(data).encode()
        req = urllib.request.Request(base + path, payload, {'Content-Type': content_type}, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            if response.status != expected:
                raise AssertionError(f'{path}: expected HTTP {expected}, got {response.status}')
            raw = response.read(17 * 1024 * 1024)
            if path.startswith('/api/'):
                assert response.headers.get('Cache-Control') == 'no-store'
            return raw.decode() if html else json.loads(raw)

    if phase == 'create':
        if state.exists():
            raise ValueError('State file already exists; do not overwrite previous demonstration evidence')
        request('/api/datasets/import', {'path': '../orders.csv'}, expected=400)
        dataset = request('/api/datasets/import', {'path': 'orders.csv'}, expected=201)
        task = request('/api/tasks', {'datasetId': dataset['id']}, expected=201)
        assert task['status'] == 'SUCCEEDED'
        report = request('/api/tasks/' + task['id'] + '/report')
        assert all(report[k] == v for k, v in EXPECTED.items())
        assert len(report['issues']) == 11 and all(x['recordNumber'] >= 1 and x['lineNumber'] >= 2 for x in report['issues'])
        html = request('/api/tasks/' + task['id'] + '/report.html', html=True)
        assert 'DataGuard' in html and '72.50' in html
        boundary = uuid.uuid4().hex
        multipart = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="empty.csv"\r\n'
                     f'Content-Type: text/csv\r\n\r\n\r\n--{boundary}--\r\n').encode()
        empty = request('/api/datasets', multipart, method='POST', expected=201,
                        content_type='multipart/form-data; boundary=' + boundary)
        failure = request('/api/tasks', {'datasetId': empty['id']}, expected=201)
        assert failure['status'] == 'FAILED' and failure['errorCode'] == 'EMPTY_FILE'
        unavailable = request('/api/tasks/' + failure['id'] + '/report', expected=409)
        assert unavailable['code'] == 'REPORT_NOT_AVAILABLE'
        saved = dict(dataset=dataset, task=task, report=report, failedTask=failure,
                     expected=EXPECTED, createdAt=datetime.now(timezone.utc).isoformat())
        state.parent.mkdir(parents=True, exist_ok=True)
        with state.open('x') as output:
            json.dump(saved, output, indent=2)
        result = dict(phase=phase, sampleReport=EXPECTED, htmlVerified=True, invalidPathRejected=True,
                      failedTaskQueryable=True, failedReportReturns409=True)
    else:
        saved = json.loads(state.read_text())
        task, dataset = saved['task'], saved['dataset']
        assert request('/api/tasks/' + task['id']) == task
        assert request('/api/tasks/' + task['id'] + '/report') == saved['report']
        assert request('/api/datasets/' + dataset['id']) == dataset
        assert request('/api/tasks/' + saved['failedTask']['id']) == saved['failedTask']
        repeat = request('/api/tasks', {'datasetId': dataset['id']}, expected=201)
        assert repeat['status'] == 'SUCCEEDED' and repeat['id'] != task['id']
        assert all(repeat[k] == v for k, v in EXPECTED.items())
        result = dict(phase=phase, reportPreserved=True, datasetPreserved=True,
                      failedHistoryPreserved=True, storedFileReusable=True)
    result['checkedAt'] = datetime.now(timezone.utc).isoformat()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['create', 'verify'])
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--state', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(check(args.base_url, args.phase, args.state), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
