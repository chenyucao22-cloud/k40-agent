"""Protocol shared by the operator CLI and the narrow HTTPS bridge."""
import json
import re
import secrets
import time

PACKAGE = 'dev.channing.androidagent'
TARGETS = {'INSPECT': None, 'CLICK': 'demo_click', 'INPUT': 'demo_input', 'SWIPE': 'demo_scroll'}
ID = re.compile(r'[A-Za-z0-9_-]{16,80}\Z')
DEVICE = re.compile(r'[A-Za-z0-9_-]{1,64}\Z')
MAX_BYTES = 16384


def unique_object(pairs):
    value = {}
    for k, v in pairs:
        if k in value:
            raise ValueError('duplicate key')
        value[k] = v
    return value


def decode(raw):
    if len(raw) > MAX_BYTES:
        raise ValueError('too large')
    return json.loads(raw, object_pairs_hook=unique_object,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite number')))


def validate(p, device, now=None):
    now = int(time.time()) if now is None else now
    fields = {'schemaVersion', 'taskId', 'deviceId', 'nonce', 'issuedAt', 'expiresAt', 'appPackage', 'actions'}
    if not isinstance(p, dict) or set(p) != fields:
        raise ValueError('schema')
    if type(p['schemaVersion']) is not int or p['schemaVersion'] != 1 or p['appPackage'] != PACKAGE:
        raise ValueError('version or package')
    if not all(isinstance(p[k], str) and ID.fullmatch(p[k]) for k in ('taskId', 'nonce')):
        raise ValueError('identifier')
    if not isinstance(p['deviceId'], str) or not DEVICE.fullmatch(p['deviceId']) or p['deviceId'] != device:
        raise ValueError('device')
    issued, expires = p['issuedAt'], p['expiresAt']
    if type(issued) is not int or type(expires) is not int or not (0 <= issued < expires <= 9999999999999):
        raise ValueError('timestamps')
    if issued > now + 60 or expires <= now or expires - issued > 300:
        raise ValueError('expired or lifetime')
    actions = p['actions']
    if not isinstance(actions, list) or not 1 <= len(actions) <= 10:
        raise ValueError('action count')
    for action in actions:
        if not isinstance(action, dict) or set(action) != {'type', 'target'}:
            raise ValueError('action schema')
        kind = action['type']
        if not isinstance(kind, str) or kind not in TARGETS or action['target'] != TARGETS[kind]:
            raise ValueError('action forbidden')
    return p


def make_plan(device, actions, now=None):
    now = int(time.time()) if now is None else now
    plan = dict(schemaVersion=1, taskId=secrets.token_urlsafe(24), deviceId=device,
                nonce=secrets.token_urlsafe(24), issuedAt=now, expiresAt=now + 300,
                appPackage=PACKAGE, actions=[{'type': a, 'target': TARGETS[a]} for a in actions])
    return validate(plan, device, now)
