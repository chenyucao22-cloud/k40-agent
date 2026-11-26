#!/usr/bin/env python3
"""Build-time architectural checks; not a replacement for device acceptance."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
ns = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
assert {p.get(ns+'name') for p in manifest.findall('uses-permission')} == {'android.permission.INTERNET', 'android.permission.RECORD_AUDIO', 'android.permission.POST_NOTIFICATIONS', 'com.android.alarm.permission.SET_ALARM'}
app = manifest.find('application')
assert app.get(ns+'allowBackup') == 'false'
assert app.get(ns+'usesCleartextTraffic') == 'false'
service = next(s for s in app.findall('service') if s.get(ns+'name') == '.AgentService')
assert service.get(ns+'permission') == 'android.permission.BIND_ACCESSIBILITY_SERVICE'
activities = {a.get(ns+'name'): a for a in app.findall('activity')}
assert all(a.get(ns+'exported') == 'false' for name, a in activities.items() if name != '.MainActivity')
config = ET.parse(root / 'app/src/main/res/xml/agent_service.xml').getroot()
assert config.get(ns+'packageNames') == 'dev.channing.androidagent'
assert config.get(ns+'canTakeScreenshot') != 'true'
source = '\n'.join(p.read_text() for p in (root / 'app/src/main/java').rglob('*.kt'))
# Strip comments before looking for prohibited APIs.
source = re.sub(r'/\*.*?\*/|//[^\n]*', '', source, flags=re.S)
for forbidden in ['Runtime.getRuntime', 'ProcessBuilder', 'getText(', 'takeScreenshot(',
                  'getSharedPreferences("credentials"', 'event.text', 'event.source',
                  'java.net.ServerSocket', 'android.webkit.', '.getPassword(',
                  'setHostnameVerifier', 'setSSLSocketFactory', 'TrustAll', 'X509TrustManager']:
    assert forbidden not in source, f'Forbidden API: {forbidden}'
assert not re.search(r'\b(node|root|event)\??\.text\b', source), 'No accessibility text reads'
assert {item.get(ns+'name') for item in manifest.findall('queries/package')} == {'com.tencent.mobileqq', 'com.tencent.mm', 'com.android.deskclock'}
print('PASS: restricted Android permissions and accessibility boundaries')
