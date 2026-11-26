import copy
import http.client
import json
import os
from pathlib import Path
import secrets
import sqlite3
import ssl
import subprocess
import tempfile
import threading
import time
import unittest
from protocol import make_plan, validate, decode
from server import Queue, handler_factory, BoundedHTTPServer

DEVICE = 'test-device'


class ProtocolTest(unittest.TestCase):
    def setUp(self):
        self.now = 2000000000
        self.plan = make_plan(DEVICE, ['INSPECT', 'CLICK'], self.now)

    def test_valid(self):
        self.assertEqual(validate(self.plan, DEVICE, self.now), self.plan)

    def test_duplicates(self):
        with self.assertRaises(ValueError):
            decode(b'{"schemaVersion":1,"schemaVersion":1}')

    def test_unsafe_actions(self):
        for action in [{'type': 'SEND', 'target': 'demo_send'}, {'type': 'CLICK', 'target': 'demo_send'},
                       {'type': 'INPUT', 'target': 'demo_input', 'text': 'x'}]:
            p = copy.deepcopy(self.plan); p['actions'] = [action]
            with self.assertRaises(ValueError):
                validate(p, DEVICE, self.now)

    def test_extra_field(self):
        self.plan['approved'] = True
        with self.assertRaises(ValueError):
            validate(self.plan, DEVICE, self.now)

    def test_expiration_and_wrong_device(self):
        with self.assertRaises(ValueError):
            validate(self.plan, DEVICE, self.now + 300)
        with self.assertRaises(ValueError):
            validate(self.plan, 'other', self.now)

    def test_bool_version_and_fractional_time(self):
        for key, value in [('schemaVersion', True), ('issuedAt', float(self.now))]:
            p = copy.deepcopy(self.plan); p[key] = value
            with self.assertRaises(ValueError):
                validate(p, DEVICE, self.now)

    def test_action_count(self):
        for actions in [[], self.plan['actions'] * 6]:
            p = copy.deepcopy(self.plan); p['actions'] = actions
            with self.assertRaises(ValueError):
                validate(p, DEVICE, self.now)

    def test_nonfinite(self):
        with self.assertRaises(ValueError):
            decode(b'{"x":NaN}')


class QueueTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.queue = Queue(Path(self.tmp.name) / 'test.db', DEVICE)
        self.plan = make_plan(DEVICE, ['CLICK'])

    def tearDown(self):
        self.tmp.cleanup()

    def test_take_once_and_duplicate_rejected(self):
        self.queue.submit(self.plan)
        with self.assertRaises(sqlite3.IntegrityError):
            self.queue.submit(self.plan)
        self.assertEqual(self.queue.take()['taskId'], self.plan['taskId'])
        self.assertIsNone(self.queue.take())

    def test_expired_not_delivered(self):
        self.queue.submit(self.plan)
        self.assertIsNone(self.queue.take(self.plan['expiresAt']))

    def test_result_immutable_and_idempotent(self):
        self.queue.submit(self.plan); self.queue.take()
        r = dict(taskId=self.plan['taskId'], status='COMPLETE', completedSteps=1)
        self.queue.result(r); self.queue.result(r)
        self.assertEqual(self.queue.read_result(self.plan['taskId']), r)
        with self.assertRaises(ValueError):
            self.queue.result(dict(r, status='CANCELLED'))

    def test_result_rejects_unknown_undelivered_and_fake_completion(self):
        r = dict(taskId=self.plan['taskId'], status='COMPLETE', completedSteps=0)
        with self.assertRaises(ValueError):
            self.queue.result(r)
        self.queue.submit(self.plan)
        with self.assertRaises(ValueError):
            self.queue.result(r)
        self.queue.take()
        with self.assertRaises(ValueError):
            self.queue.result(r)

    def test_private_database(self):
        self.assertEqual(os.stat(self.queue.path).st_mode & 0o777, 0o600)


class HttpsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.cert, cls.key = [str(Path(cls.tmp.name) / name) for name in ('cert.pem', 'key.pem')]
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                        '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost',
                        '-keyout', cls.key, '-out', cls.cert], check=True, capture_output=True)
        cls.context = ssl.create_default_context(cafile=cls.cert)  # Test-only CA, never trust-all.

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def setUp(self):
        self.tmpdb = tempfile.TemporaryDirectory()
        self.queue = Queue(Path(self.tmpdb.name) / 'test.db', DEVICE)
        self.phone, self.operator = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        self.server = BoundedHTTPServer(('127.0.0.1', 0), handler_factory(self.queue, self.phone, self.operator, time.time() + 60))
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(self.cert, self.key)
        self.server.socket = context.wrap_socket(self.server.socket, server_side=True, do_handshake_on_connect=False)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(); self.tmpdb.cleanup()

    def request(self, method, path, token=None, body=None):
        conn = http.client.HTTPSConnection('localhost', self.server.server_port, context=self.context, timeout=3)
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        raw = None if body is None else json.dumps(body).encode()
        try:
            conn.request(method, path, raw, headers)
            response = conn.getresponse()
            return response.status, response.read()
        finally:
            conn.close()

    def test_https_end_to_end(self):
        plan = make_plan(DEVICE, ['INSPECT', 'CLICK'])
        self.assertEqual(self.request('POST', '/v1/operator/tasks', self.operator, plan)[0], 204)
        status, raw = self.request('GET', f'/v1/devices/{DEVICE}/next', self.phone)
        self.assertEqual(status, 200); self.assertEqual(json.loads(raw), plan)
        self.assertEqual(self.request('GET', f'/v1/devices/{DEVICE}/next', self.phone)[0], 204)
        result = dict(taskId=plan['taskId'], status='COMPLETE', completedSteps=2)
        self.assertEqual(self.request('POST', f'/v1/devices/{DEVICE}/result', self.phone, result)[0], 204)
        status, raw = self.request('GET', '/v1/operator/results/' + plan['taskId'], self.operator)
        self.assertEqual(status, 200); self.assertEqual(json.loads(raw), result)

    def test_missing_and_cross_role_credentials_rejected(self):
        self.assertEqual(self.request('GET', f'/v1/devices/{DEVICE}/next')[0], 401)
        self.assertEqual(self.request('GET', f'/v1/devices/{DEVICE}/next', self.operator)[0], 401)
        self.assertEqual(self.request('POST', '/v1/operator/tasks', self.phone, {})[0], 401)

    def test_wrong_device_and_shell_route_absent(self):
        self.assertEqual(self.request('GET', '/v1/devices/other/next', self.phone)[0], 404)
        self.assertEqual(self.request('POST', '/shell', self.operator, {'command': 'anything'})[0], 404)

    def test_invalid_tasks_and_results_rejected(self):
        plan = make_plan(DEVICE, ['CLICK']); plan['approved'] = True
        self.assertEqual(self.request('POST', '/v1/operator/tasks', self.operator, plan)[0], 400)
        self.assertEqual(self.request('POST', f'/v1/devices/{DEVICE}/result', self.phone, {})[0], 400)

    def test_untrusted_cert_fails(self):
        conn = http.client.HTTPSConnection('localhost', self.server.server_port, timeout=3)
        try:
            with self.assertRaises(ssl.SSLCertVerificationError):
                conn.request('GET', f'/v1/devices/{DEVICE}/next')
        finally:
            conn.close()


if __name__ == '__main__':
    unittest.main()
