#!/usr/bin/env python3
"""Owned, bounded fixture for storage_loopback_tests; never contacts public hosts.
Not a replacement for the existing test server (whose sibling-killing launcher is
intentionally not imported). Refuses occupied 34568 rather than reusing/killing it.
Authored fixture bytes are NOT an MWM format/Framework registration qualification.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PAYLOAD = bytes(range(256)) * 512
PORT = 34568


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--data', type=Path, required=True, help='Existing repository data resources')
    parser.add_argument('--work', type=Path, required=True, help='New, owned writable output directory')
    parser.add_argument('--series', required=True, help='Exact MAP_SERIES from the compiled private.h')
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9.]+', args.series):
        parser.error('series must be the literal numeric/dotted compiled MAP_SERIES')
    binary = args.binary.resolve(strict=True)
    data = args.data.resolve(strict=True)
    args.work.mkdir(parents=True, exist_ok=False)
    work = args.work.resolve()
    # testingmain also treats the resource path as its initial settings path.
    # Link public top-level inputs, but keep settings/writes inside this owned run.
    resources = work / 'resources'
    resources.mkdir()
    for entry in data.iterdir():
        if entry.is_file() and not entry.name.endswith('.ini'):
            (resources / entry.name).symlink_to(entry.resolve())
    records = []
    lock = threading.Lock()
    prefix = '/maps/' + args.series + '/991231/'
    routes = {prefix + name + '.mwm': name for name in ('Good', 'Corrupt', 'Resume', 'GroupA', 'GroupB', 'Keep')}

    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def log_message(self, format, *args):
            pass

        def do_GET(self):
            if self.path == "/meta/maps.json":
                body = json.dumps({"map-series": {args.series: {"latest": 991231, "status": "active"}}}).encode()
                with lock:
                    records.append({"path": self.path, "name": "Metadata", "status": 200})
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            name = routes.get(self.path)
            if name is None:
                with lock:
                    records.append({'path': self.path, 'status': 404})
                self.send_error(404)
                return
            body = PAYLOAD if name != 'Corrupt' else bytes([PAYLOAD[0] ^ 1]) + PAYLOAD[1:]
            raw = self.headers.get('Range')
            start, end, status = 0, len(body) - 1, 200
            if raw is not None:
                match = re.fullmatch(r'bytes=(\d+)-(\d*)', raw)
                if not match:
                    self.send_error(416)
                    return
                start = int(match[1])
                end = int(match[2]) if match[2] else len(body) - 1
                if not 0 <= start <= end < len(body):
                    self.send_error(416)
                    return
                status = 206
            with lock:
                records.append({'path': self.path, 'name': name, 'start': start,
                                'end': end, 'range': raw, 'status': status})
            self.send_response(status)
            self.send_header('Content-Length', str(end - start + 1))
            self.send_header('Accept-Ranges', 'bytes')
            if status == 206:
                self.send_header('Content-Range', f'bytes {start}-{end}/{len(body)}')
            self.end_headers()
            try:
                self.wfile.write(body[start:end + 1])
            except (BrokenPipeError, ConnectionResetError):
                pass  # Expected when the owned request controller is destroyed.

    class OwnedServer(ThreadingHTTPServer):
        allow_reuse_address = False
        daemon_threads = False
        block_on_close = True

    # Binding is the ownership check. Do not ping, reuse or kill an existing server.
    server = OwnedServer(('127.0.0.1', PORT), Handler)
    thread = threading.Thread(target=server.serve_forever, name='owned-dmaps-loopback')
    thread.start()
    env = {k: os.environ[k] for k in ('PATH', 'HOME', 'LANG', 'LC_ALL', 'TMPDIR') if k in os.environ}
    if sys.platform == 'darwin':
        env['QT_QPA_PLATFORM'] = 'offscreen'
    env['NO_PROXY'] = env['no_proxy'] = '127.0.0.1,localhost'
    cases = ['DMapsLoopback_RequestResumeExactBytes',
             'Runner_DMapsLoopback_StorageIntegrityAndIdempotence',
             'Runner_DMapsLoopback_GroupDeletePreservesSiblingAndRepeatsSafely']
    results = []
    try:
        with (work / 'native.log').open('w') as log:
            for case in cases:
                owned = work / case
                owned.mkdir()
                command = [str(binary), '--filter=::' + case + '$',
                           '--data_path=' + str(owned), '--user_resource_path=' + str(resources)]
                result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                        env=env, timeout=45, check=False)
                results.append(result.returncode)
                if result.returncode:
                    break
    finally:
        server.shutdown()
        thread.join()
        server.server_close()
        (work / 'http.json').write_text(json.dumps(records, indent=2) + '\n')
    if any(results):
        raise SystemExit(next(code for code in results if code))
    # Native exit zero alone cannot turn a wrong/empty filtered run into acceptance.
    native_log = (work / 'native.log').read_text()
    ran = re.findall(r'Running\s+\S+::(\w+)', native_log)
    expected = set(cases)
    assert len(ran) == len(cases) and set(ran) == expected, ran
    assert native_log.count('All tests passed.') == len(cases)
    assert len([r for r in records if r.get('name') == 'Metadata']) == 4
    assert records and all(r['status'] in (200, 206) for r in records), records
    for name in ('Good', 'Corrupt', 'GroupA', 'GroupB', 'Keep'):
        rows = [r for r in records if r.get('name') == name]
        assert rows and sum(r['end'] - r['start'] + 1 for r in rows) == len(PAYLOAD), rows
    resume = [r for r in records if r.get('name') == 'Resume']
    assert resume and all(r['status'] == 206 for r in resume), resume
    assert sum(r['start'] == 0 for r in resume) == 1, resume
    assert any(r['start'] > 0 for r in resume), resume
    print('Native exit 0 and fixture HTTP assertions satisfied; inspect native.log test totals.')


if __name__ == '__main__':
    main()
