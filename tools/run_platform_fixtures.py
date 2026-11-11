"""在独立云端模拟器分阶段执行五项平台专项，并核验设置恢复。"""

import argparse
import json
import os
from pathlib import Path
import subprocess
import time

from run_device_matrix import (ROOT, OUTPUT as MATRIX_OUTPUT, FILES, TEST_PACKAGE,
                               artifacts, instrumentation_result, save_json,
                               startup_window_state)


OUTPUT = ROOT / 'build/reports/platform-fixtures'
TIMEOUTS = ('accessibility_non_interactive_ui_timeout_ms', 'accessibility_interactive_ui_timeout_ms')
SETTINGS = [('secure', key) for key in TIMEOUTS] + [
    ('global', key) for key in ('enable_freeform_support', 'force_resizable_activities',
                              'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale')]
CUTOUT = 'com.android.internal.display.cutout.emulation.tall'
STAGES = (
    ('noninteractive', 'KToastPlatformTest#recommendedTimeoutKeepsScopeVisibleBeyondLegacyShortDuration',
     'accessibilityTimeoutFixture', (10000, 0)),
    ('interactive-button', 'KToastPlatformTest#customButtonUsesInteractiveRecommendedTimeout_withoutBubbleCancel',
     'interactiveTimeoutFixture', (0, 10000)),
    ('interactive-text', 'KToastPlatformTest#customPlainTextDoesNotUseInteractiveTimeout',
     'interactiveTimeoutFixture', (0, 10000)),
    ('cutout', 'KToastWindowTest#cutoutFixtureKeepsEntireContentInsideSafeArea', 'displayCutoutFixture', (0, 0)),
    ('freeform', 'KToastMultiWindowTest#freeformFixtureUsesHostBounds_andHandlesTaskResize',
     'multiWindowFixture', (0, 0)),
)


def run_fixtures(serial, api=35):
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {'commit': os.environ.get('GITHUB_SHA'), 'serial': serial, 'success': False, 'stages': {}}
    original = {}
    overlays = {}

    def adb(*args, timeout=60):
        process = subprocess.run(['adb', '-s', serial, *args], text=True, stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, timeout=timeout)
        if process.returncode:
            raise ValueError('adb 失败：' + process.stdout)
        return process.stdout

    def setting(namespace, key, value):
        adb('shell', 'settings', 'delete' if value == 'null' else 'put', namespace, key,
            *([] if value == 'null' else [str(value)]))
        actual = adb('shell', 'settings', 'get', namespace, key).strip()
        if actual != str(value):
            raise ValueError('设置回读不一致：' + key + '=' + actual)

    def overlay_states():
        return {line[4:].strip(): line[:3] == '[x]' for line in
                adb('shell', 'cmd', 'overlay', 'list', '--user', '0').splitlines()
                if line.startswith(('[x] ', '[ ] ')) and '.display.cutout.emulation.' in line}

    def overlay(package, enabled):
        adb('shell', 'cmd', 'overlay', 'enable' if enabled else 'disable', '--user', '0', package)
        if overlay_states().get(package) != enabled:
            raise ValueError('缺口 overlay 回读不一致：' + package)

    def ready():
        adb('wait-for-device', timeout=120)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if adb('shell', 'getprop', 'sys.boot_completed').strip() == '1':
                adb('shell', 'input', 'keyevent', '82')
                state = startup_window_state(adb('shell', 'dumpsys', 'window'))
                if state['focusedWindows'] and not state['anrFocused'] and not state['keyguardShowing']:
                    return
            time.sleep(1)
        raise ValueError('专项模拟器启动检查超时')

    def diagnostics(folder):
        folder.mkdir(parents=True, exist_ok=True)
        for name, args in [('logcat', ('logcat', '-d', '-v', 'threadtime'))] + [
                (name, ('shell', 'dumpsys', name)) for name in ('window', 'activity', 'accessibility', 'display')]:
            try:
                (folder / (name + '.txt')).write_text(adb(*args, timeout=15), encoding='utf-8')
            except Exception as error:
                (folder / (name + '-error.txt')).write_text(str(error), encoding='utf-8')

    try:
        if not serial.startswith('emulator-') or adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
            raise ValueError('仅允许显式指定的专用模拟器')
        report['api'] = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
        if report['api'] != api or api < 30:
            raise ValueError('实际设备 API 与专项要求不一致')
        report['fingerprint'] = adb('shell', 'getprop', 'ro.build.fingerprint').strip()
        report['abi'] = adb('shell', 'getprop', 'ro.product.cpu.abi').strip()
        actual = artifacts()
        expected = json.loads((MATRIX_OUTPUT / 'input.json').read_text(encoding='utf-8'))
        if actual != expected['artifacts'] or (report['commit'] and report['commit'] != expected['commit']):
            raise ValueError('专项产物摘要或构建提交不一致')
        report.update(artifacts=actual, buildCommit=expected['commit'])
        original = {namespace + '/' + key: adb('shell', 'settings', 'get', namespace, key).strip()
                    for namespace, key in SETTINGS}
        overlays = overlay_states()
        report['originalSettings'] = dict(original)
        report['originalOverlays'] = dict(overlays)
        save_json(OUTPUT / 'result.json', report)
        if CUTOUT not in overlays:
            raise ValueError('专项模拟器不提供 tall 显示缺口')
        for key, value in (('enable_freeform_support', 1), ('force_resizable_activities', 1),
                           ('window_animation_scale', 0), ('transition_animation_scale', 0),
                           ('animator_duration_scale', 1)):
            setting('global', key, value)
        # 自由窗口支持在系统启动时读取；先配置后重启，避免只改数据库而未改变窗口能力。
        adb('reboot')
        ready()
        for name in ('demo', 'tests'):
            result = adb('install', '-r', str(ROOT / FILES[name]), timeout=120)
            (OUTPUT / (name + '-install.txt')).write_text(result, encoding='utf-8')
            if 'Success' not in result:
                raise ValueError('专项 APK 安装失败：' + name)
        for name, test, flag, timeouts in STAGES:
            stage = {'test': TEST_PACKAGE + test, 'success': False}
            report['stages'][name] = stage
            try:
                for key, value in zip(TIMEOUTS, timeouts):
                    setting('secure', key, value)
                for package in overlays:
                    overlay(package, name == 'cutout' and package == CUTOUT)
                stage['settings'] = {key: adb('shell', 'settings', 'get', 'secure', key).strip() for key in TIMEOUTS}
                stage['overlays'] = overlay_states()
                raw = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', stage['test'],
                          '-e', flag, 'true', 'com.logan.ktoastapp.test/androidx.test.runner.AndroidJUnitRunner',
                          timeout=180)
                (OUTPUT / (name + '-instrumentation.txt')).write_text(raw, encoding='utf-8')
                stage['instrumentation'] = instrumentation_result(raw, {stage['test']})
                stage['success'] = stage['instrumentation']['success'] and not stage['instrumentation']['skipped']
                if not stage['success']:
                    raise ValueError('专项用例未通过：' + test)
            except Exception as error:
                stage['error'] = str(error)
                if isinstance(error, subprocess.TimeoutExpired) and error.stdout:
                    partial = error.stdout.decode(errors='replace') if isinstance(error.stdout, bytes) else error.stdout
                    (OUTPUT / (name + '-instrumentation.txt')).write_text(partial, encoding='utf-8')
                diagnostics(OUTPUT / name)
                adb('shell', 'am', 'force-stop', 'com.logan.ktoastapp')
            finally:
                save_json(OUTPUT / 'result.json', report)
    except Exception as error:
        report['error'] = str(error)
    finally:
        restoration = {'success': True, 'settings': {}, 'overlays': {}, 'errors': []}
        for namespace, key in SETTINGS:
            identity = namespace + '/' + key
            if identity in original:
                try:
                    setting(namespace, key, original[identity])
                    restoration['settings'][identity] = original[identity]
                except Exception as error:
                    restoration['errors'].append(str(error))
        # 先关后开，避免同类 overlay 互相替换，最终整组回读核验。
        for enabled in (False, True):
            for package, state in overlays.items():
                if state == enabled:
                    try:
                        overlay(package, state)
                    except Exception as error:
                        restoration['errors'].append(str(error))
        if overlays:
            try:
                restoration['overlays'] = overlay_states()
                if restoration['overlays'] != overlays:
                    raise ValueError('缺口 overlay 整组恢复不一致')
                # 重启后再次检查设置，确保自由窗口配置也恢复到启动时的状态。
                adb('reboot')
                ready()
                for namespace, key in SETTINGS:
                    if adb('shell', 'settings', 'get', namespace, key).strip() != original[namespace + '/' + key]:
                        raise ValueError('重启后设置恢复不一致：' + key)
            except Exception as error:
                restoration['errors'].append(str(error))
        restoration['success'] = not restoration['errors']
        report['restoration'] = restoration
        report['success'] = (not report.get('error') and restoration['success'] and
                             len(report['stages']) == len(STAGES) and
                             all(stage['success'] for stage in report['stages'].values()))
        save_json(OUTPUT / 'result.json', report)
    if not report['success']:
        raise ValueError('平台专项未全部通过，参见 ' + str(OUTPUT / 'result.json'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--api', type=int, default=35)
    options = parser.parse_args()
    run_fixtures(options.serial, options.api)
