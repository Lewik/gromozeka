#!/usr/bin/env python3
"""Verify real APK confirmation recovery on a disposable API 33+ emulator."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('old_apk', type=Path)
parser.add_argument('new_apk', type=Path)
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('Only an isolated emulator may be used')
sdk = os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))
adb = str(Path(sdk) / 'platform-tools/adb')
package = 'com.gromozeka.updatefixture'
store = 'no_backup/updates/' + hashlib.sha256(b'fixture').hexdigest()
remote_apk = '/data/local/tmp/gromozeka-update-fixture.apk'


def command(*parts, check=True):
    result = subprocess.run([adb, '-s', args.serial, *parts], capture_output=True, text=True, timeout=40)
    if check and result.returncode:
        raise RuntimeError(f'{parts}: {result.stdout} {result.stderr}')
    return result.stdout.strip()


def wait_for(probe, description, timeout=45):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = probe()
            if last:
                return last
        except (RuntimeError, ValueError) as error:
            last = error
        time.sleep(0.5)
    raise AssertionError(f'Timed out: {description}; last result: {last}')


def state():
    return json.loads(command('shell', 'run-as', package, 'cat', store + '/state.json'))['report']['phase']


def click_button(predicate):
    command('shell', 'uiautomator', 'dump', '/data/local/tmp/gromozeka-update-ui.xml')
    document = command('shell', 'cat', '/data/local/tmp/gromozeka-update-ui.xml')
    for node in ET.fromstring(document).iter('node'):
        if node.get('enabled') == 'true' and predicate(node):
            left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
            command('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
            return True
    return False


assert int(command('shell', 'getprop', 'ro.build.version.sdk')) >= 33
try:
    for notifications in (False, True):
        command('uninstall', package, check=False)
        command('install', str(args.old_apk.resolve()))
        command('shell', 'appops', 'set', package, 'REQUEST_INSTALL_PACKAGES', 'allow')
        command('shell', 'pm', 'grant' if notifications else 'revoke', package, 'android.permission.POST_NOTIFICATIONS')
        command('shell', 'pm', 'set-permission-flags', package, 'android.permission.POST_NOTIFICATIONS', 'user-set', 'user-fixed')
        command('push', str(args.new_apk.resolve()), remote_apk)
        command('shell', f'run-as {package} sh -c "mkdir -p files; cp {remote_apk} files/next.apk"')
        command('shell', 'am', 'start', '-n', package + '/.FixtureActivity', '--ez', 'begin', 'true')
        wait_for(lambda: state() == 'WAITING_FOR_USER', 'PackageInstaller user confirmation')
        old_pid = command('shell', 'pidof', package)
        assert re.fullmatch(r'\d+', old_pid), old_pid
        command('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        command('shell', 'run-as', package, 'kill', '-9', old_pid)
        wait_for(lambda: command('shell', 'pidof', package, check=False) != old_pid, 'fixture process death')
        command('shell', 'am', 'start', '-n', package + '/.FixtureActivity', '--ez', 'begin', 'false')
        wait_for(lambda: click_button(lambda n: n.get('text', '').lower() == 'install update'), 'enabled native confirmation button')
        wait_for(lambda: click_button(lambda n: 'packageinstaller' in n.get('package', '') and n.get('resource-id') == 'android:id/button1'), 'Android confirmation dialog')
        wait_for(lambda: 'versionCode=2 ' in command('shell', 'dumpsys', 'package', package), 'new APK installed', timeout=90)
        print(f'PASS: notification_permission={notifications}, killed_pid={old_pid}, versionCode=2', flush=True)
finally:
    command('uninstall', package, check=False)
    command('shell', 'rm', '-f', remote_apk, '/data/local/tmp/gromozeka-update-ui.xml', check=False)
