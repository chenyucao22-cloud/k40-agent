#!/usr/bin/env python3
"""Emit a manual plan or submit fixed action enums. Never runs commands on a device."""
import argparse
import json
import os
import ssl
import urllib.parse
import urllib.request
from protocol import make_plan


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def submit(plan):
    origin = os.environ.get('BRIDGE_ORIGIN', '')
    url = urllib.parse.urlsplit(origin)
    if url.scheme != 'https' or not url.hostname or url.username or url.password or url.query or url.fragment or url.path not in ('', '/'):
        raise ValueError('BRIDGE_ORIGIN must be an HTTPS origin')
    token = os.environ.get('BRIDGE_OPERATOR_TOKEN', '')
    if not token:
        raise ValueError('operator credential missing')
    opener = urllib.request.build_opener(NoRedirect, urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    request = urllib.request.Request(origin.rstrip('/') + '/v1/operator/tasks', json.dumps(plan).encode(),
        {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}, method='POST')
    with opener.open(request, timeout=10) as response:
        if response.status != 204:
            raise ValueError('unexpected status')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', required=True)
    parser.add_argument('--actions', nargs='+', choices=['INSPECT', 'CLICK', 'INPUT', 'SWIPE'], default=['INSPECT', 'CLICK'])
    parser.add_argument('--submit', action='store_true', help='otherwise print JSON for manual import')
    args = parser.parse_args()
    plan = make_plan(args.device, args.actions)
    if args.submit:
        submit(plan)
        print('Queued task:', plan['taskId'])
    else:
        print(json.dumps(plan, ensure_ascii=False))


if __name__ == '__main__':
    main()
