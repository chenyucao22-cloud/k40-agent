#!/usr/bin/env python3
"""Small single-device HTTPS queue. No model invocation, shell or remote code endpoint."""
import argparse
import hmac
import json
import os
import re
import sqlite3
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from protocol import decode, validate, DEVICE, MAX_BYTES


class Queue:
    def __init__(self, path, device):
        self.path, self.device = str(path), device
        self.lock = threading.Lock()
        with self.db() as db:
            db.execute('CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, nonce TEXT UNIQUE, expires INTEGER, body TEXT, delivered INTEGER DEFAULT 0, result TEXT)')
        os.chmod(self.path, 0o600)

    def db(self):
        return sqlite3.connect(self.path)

    def submit(self, plan, now=None):
        validate(plan, self.device, now)
        with self.lock, self.db() as db:
            if db.execute('SELECT count(*) FROM tasks').fetchone()[0] >= 10000:
                raise ValueError('queue capacity; rotate database only after expiring outstanding tasks')
            db.execute('INSERT INTO tasks (id,nonce,expires,body) VALUES (?,?,?,?)',
                       (plan['taskId'], plan['nonce'], plan['expiresAt'], json.dumps(plan, separators=(',', ':'))))

    def take(self, now=None):
        now = int(time.time()) if now is None else now
        with self.lock, self.db() as db:
            row = db.execute('SELECT id,body FROM tasks WHERE delivered=0 AND expires>? ORDER BY rowid LIMIT 1', (now,)).fetchone()
            if not row:
                return None
            # At-most-once delivery: uncertain network failures require a new task, never replay.
            db.execute('UPDATE tasks SET delivered=1 WHERE id=?', (row[0],))
            return json.loads(row[1])

    def result(self, receipt):
        if not isinstance(receipt, dict) or set(receipt) != {'taskId', 'status', 'completedSteps'}:
            raise ValueError('result schema')
        code, count = receipt['status'], receipt['completedSteps']
        if code not in ('COMPLETE', 'CANCELLED', 'FAILED', 'EXPIRED') or type(count) is not int:
            raise ValueError('result values')
        with self.lock, self.db() as db:
            row = db.execute('SELECT body,delivered,result FROM tasks WHERE id=?', (receipt['taskId'],)).fetchone()
            if row is None or row[1] != 1:
                raise ValueError('unknown or undelivered task')
            total = len(json.loads(row[0])['actions'])
            if not 0 <= count <= total or (code == 'COMPLETE' and count != total):
                raise ValueError('step count')
            body = json.dumps(receipt, sort_keys=True, separators=(',', ':'))
            if row[2] is not None and row[2] != body:
                raise ValueError('conflicting terminal result')
            db.execute('UPDATE tasks SET result=? WHERE id=?', (body, receipt['taskId']))

    def read_result(self, task_id):
        with self.db() as db:
            row = db.execute('SELECT result FROM tasks WHERE id=?', (task_id,)).fetchone()
        return json.loads(row[0]) if row and row[0] else None


def handler_factory(queue, device_token, operator_token, credential_expires):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.0'

        def log_message(self, *_):
            pass  # No URLs, bearer credentials, request bodies or UI data in access logs.

        def setup(self):
            super().setup()
            self.connection.settimeout(8)

        def reply(self, status, body=None):
            raw = b'' if body is None else json.dumps(body, separators=(',', ':')).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(raw)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.end_headers()
            if raw:
                self.wfile.write(raw)

        def authenticated(self, operator=False):
            expected = operator_token if operator else device_token
            auth = self.headers.get('Authorization', '')
            if time.time() >= credential_expires or not hmac.compare_digest(auth.encode('utf-8'), ('Bearer ' + expected).encode('ascii')):
                self.reply(401, {'error': 'unauthorized'})
                return False
            return True

        def body(self):
            if self.headers.get('Transfer-Encoding') or self.headers.get_content_type() != 'application/json':
                raise ValueError('body headers')
            length = int(self.headers.get('Content-Length', '-1'))
            if not 0 < length <= MAX_BYTES:
                raise ValueError('body size')
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise ValueError('truncated')
            return decode(raw)

        def do_GET(self):
            path = f'/v1/devices/{queue.device}/next'
            if self.path == path:
                if self.authenticated():
                    body = queue.take()
                    self.reply(200 if body else 204, body)
            elif re.fullmatch(r'/v1/operator/results/[A-Za-z0-9_-]{16,80}', self.path):
                if self.authenticated(True):
                    body = queue.read_result(self.path.rsplit('/', 1)[1])
                    self.reply(200 if body else 204, body)
            else:
                self.reply(404, {'error': 'not_found'})

        def do_POST(self):
            operator = self.path == '/v1/operator/tasks'
            if not operator and self.path != f'/v1/devices/{queue.device}/result':
                self.reply(404, {'error': 'not_found'}); return
            if not self.authenticated(operator):
                return
            try:
                body = self.body()
                if operator:
                    queue.submit(body)
                else:
                    queue.result(body)
                self.reply(204)
            except (ValueError, TypeError, KeyError, sqlite3.IntegrityError):
                self.reply(400, {'error': 'invalid_or_duplicate'})
    return Handler


class BoundedHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, *args, **kwargs):
        self.slots = threading.BoundedSemaphore(8)
        super().__init__(*args, **kwargs)

    def handle_error(self, request, client_address):
        pass  # Fail closed without logging request data or TLS exception details.

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            request.close(); return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release(); raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bind', default='127.0.0.1')
    parser.add_argument('--port', default=8443, type=int)
    parser.add_argument('--cert', required=True)
    parser.add_argument('--key', required=True)
    parser.add_argument('--db', default='bridge.sqlite3')
    args = parser.parse_args()
    device = os.environ.get('BRIDGE_DEVICE_ID', '')
    device_token = os.environ.get('BRIDGE_DEVICE_TOKEN', '')
    operator_token = os.environ.get('BRIDGE_OPERATOR_TOKEN', '')
    expires = int(os.environ.get('BRIDGE_CREDENTIAL_EXPIRES', '0'))
    if not DEVICE.fullmatch(device) or not all(re.fullmatch(r'[A-Za-z0-9_-]{43,128}', t) for t in (device_token, operator_token)):
        parser.error('configure device and two independent random bridge tokens in environment')
    if hmac.compare_digest(device_token, operator_token) or not time.time() < expires <= time.time() + 86400:
        parser.error('use distinct tokens and a credential expiry within 24 hours')
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.cert, args.key)
    queue = Queue(Path(args.db), device)
    server = BoundedHTTPServer((args.bind, args.port), handler_factory(queue, device_token, operator_token, expires))
    server.socket = context.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
    print('HTTPS bridge ready; no remote shell or model endpoint; credentials expire within 24 hours', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
