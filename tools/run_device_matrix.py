"""专用模拟器运行矩阵：校验同一批 APK，区分成功、过滤、失败与 Runner 中断。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'build/reports/device-matrix'
FILES = {
    'demo': 'app/build/outputs/apk/debug/app-debug.apk',
    'tests': 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
    'r8Consumer': 'tools/compatibility/android-consumer/build/outputs/apk/release/ktoast-android-consumer-release.apk',
    'aar': 'library-android/build/outputs/aar/library-android-release.aar',
    'lifecycleR8Consumer': 'tools/compatibility/lifecycle-consumer/build/outputs/apk/release/ktoast-lifecycle-consumer-release.apk',
    'lifecycleAar': 'library-lifecycle/build/outputs/aar/library-lifecycle-release.aar',
}
TEST_PACKAGE = 'com.logan.ktoastapp.app.'
CONSUMER = 'com.logan.ktoast.compatibility'
SNAPSHOT_MARKERS = {'window': 'WINDOW MANAGER', 'input': 'INPUT MANAGER',
                    'activity': 'ACTIVITY MANAGER', 'display': 'DISPLAY MANAGER'}


def validate_snapshot(name, content):
    if not content.strip() or (name in SNAPSHOT_MARKERS and SNAPSHOT_MARKERS[name] not in content):
        raise ValueError('诊断输出为空或不是系统快照：' + name)


def save_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')


def artifacts(root=ROOT):
    result = {}
    for name, relative in FILES.items():
        path = root / relative
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError('缺失或空产物：' + relative)
        result[name] = {'path': relative, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    candidate = json.loads((root / 'build/reports/compatibility/ktoast-candidate.json').read_text(encoding='utf-8'))
    if candidate['sha256'] != result['aar']['sha256']:
        raise ValueError('AAR 与候选报告 SHA-256 不一致')
    lifecycle = json.loads((root / 'build/reports/compatibility/ktoast-lifecycle-candidate.json').read_text(encoding='utf-8'))
    if lifecycle['sha256'] != result['lifecycleAar']['sha256']:
        raise ValueError('Lifecycle AAR 与报告 SHA-256 不一致')
    return result


def required_tests(api, root=ROOT):
    required = set()
    source = root / 'app/src/androidTest/java/com/logan/ktoastapp/app'
    for name in ('MainActivityTest', 'KToastLifecycleTest', 'KToastScopeTest', 'KToastLifecycleOwnerTest', 'KToastRuntimePolicyDeviceTest', 'WindowFailureDiagnosticsTest'):
        text = (source / (name + '.kt')).read_text(encoding='utf-8')
        methods = re.findall(r'@Test\b(?:(?!\bfun\b).)*\bfun\s+(\w+)', text, re.S)
        if not methods:
            raise ValueError('未找到必需回归：' + name)
        required.update(TEST_PACKAGE + name + '#' + method for method in methods)
    if api >= 30:
        # 这三个场景不需要无障碍、缺口或自由窗口专用设置。
        required.add(TEST_PACKAGE + 'KToastPlatformTest#imeAvoidancePlacesEntireContentAboveVisibleKeyboard')
        text = (source / 'KToastWindowTest.kt').read_text(encoding='utf-8')
        methods = re.findall(r'@Test\b(?:(?!\bfun\b).)*\bfun\s+(\w+)', text, re.S)
        required.update(TEST_PACKAGE + 'KToastWindowTest#' + method for method in methods if 'cutout' not in method.lower())
    return required


def all_tests(root=ROOT):
    source = root / 'app/src/androidTest/java/com/logan/ktoastapp/app'
    return {TEST_PACKAGE + path.stem + '#' + method
            for path in source.glob('*Test.kt')
            for method in re.findall(r'@Test\b(?:(?!\bfun\b).)*\bfun\s+(\w+)', path.read_text(encoding='utf-8'), re.S)}


def instrumentation_result(text, required):
    results = {}
    status = {}
    terminal = None
    for line in text.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            field = line.removeprefix('INSTRUMENTATION_STATUS: ')
            key, separator, value = field.partition('=')
            if separator:
                status[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.partition(':')[2].strip())
            if code != 1 and 'class' in status and 'test' in status:
                key = status['class'] + '#' + status['test']
                if key in results:
                    raise ValueError('重复测试终态：' + key)
                results[key] = 'passed' if code == 0 else 'skipped' if code in (-3, -4) else 'failed'
            status = {}
        elif line.startswith('INSTRUMENTATION_CODE: '):
            terminal = int(line.partition(':')[2].strip())
    passed = sorted(key for key, value in results.items() if value == 'passed')
    skipped = sorted(key for key, value in results.items() if value == 'skipped')
    failed = sorted(key for key, value in results.items() if value == 'failed')
    missing = sorted(required - set(passed))
    return {
        'passed': passed, 'skipped': skipped, 'failed': failed,
        'requiredNotPassed': missing, 'runnerCode': terminal,
        'success': terminal == -1 and bool(passed) and not failed and not missing,
    }


def consumer_line(text, pid, baseline):
    for line in text.splitlines():
        fields = line.split(maxsplit=6)
        if len(fields) < 7 or fields[2] != pid or line in baseline:
            continue
        if fields[4] == 'E' and fields[5].rstrip(':') == 'KToastConsumer':
            raise ValueError('R8 消费者报告失败：' + line)
        if fields[4] == 'I' and fields[5].rstrip(':') == 'KToastConsumer' and fields[6].startswith('PASS: '):
            return line
    return None


def run_device(api, serial):
    output = OUTPUT / ('api-' + str(api))
    output.mkdir(parents=True, exist_ok=True)
    report = {'expectedApi': api, 'serial': serial, 'commit': os.environ.get('GITHUB_SHA'), 'success': False}
    dedicated_emulator = False
    diagnostic_run = str(time.time_ns())
    instrumentation_started = False

    def adb(*args, timeout=60):
        process = subprocess.run(['adb', '-s', serial, *args], text=True, stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, timeout=timeout)
        if process.returncode:
            raise ValueError('adb 失败：' + process.stdout)
        return process.stdout

    def export_diagnostics(remote, destination):
        # Debug 宿主的 run-as 可访问私有目录，避免依赖外部存储或 root。
        names = adb('shell', 'run-as', 'com.logan.ktoastapp', 'ls', remote).split()
        if not names or any(name not in ('failure.txt', 'window.txt', 'input.txt', 'activity.txt', 'display.txt')
                            for name in names):
            raise ValueError('失败诊断文件列表缺失或不合法')
        destination.mkdir(parents=True, exist_ok=True)
        for name in names:
            content = adb('exec-out', 'run-as', 'com.logan.ktoastapp', 'cat', remote + '/' + name)
            validate_snapshot(name.removesuffix('.txt'), content)
            (destination / name).write_text(content, encoding='utf-8')
        return set(names)

    try:
        # 此入口只用于临时模拟器，禁止对日常真机执行安装和生命周期操作。
        if not serial.startswith('emulator-') or adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
            raise ValueError('仅允许显式指定的专用模拟器')
        dedicated_emulator = True
        report['api'] = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
        report['fingerprint'] = adb('shell', 'getprop', 'ro.build.fingerprint').strip()
        report['abi'] = adb('shell', 'getprop', 'ro.product.cpu.abi').strip()
        if report['api'] != api:
            raise ValueError('实际设备 API 与矩阵不一致')
        actual = artifacts()
        expected = json.loads((OUTPUT / 'input.json').read_text(encoding='utf-8'))
        if actual != expected['artifacts']:
            raise ValueError('下载的 APK/AAR 与构建任务报告不一致')
        report['artifacts'] = actual
        report['buildCommit'] = expected['commit']
        if report['commit'] and report['commit'] != report['buildCommit']:
            raise ValueError('构建与运行提交不一致')
        for name in ('demo', 'tests', 'r8Consumer', 'lifecycleR8Consumer'):
            result = adb('install', '-r', str(ROOT / FILES[name]), timeout=120)
            (output / (name + '-install.txt')).write_text(result, encoding='utf-8')
            if 'Success' not in result:
                raise ValueError('安装失败：' + name)
        instrumentation_started = True
        raw = adb('shell', 'am', 'instrument', '-w', '-r',
                  '-e', 'windowFailureDiagnostics', 'true', '-e', 'windowFailureRunId', diagnostic_run,
                  'com.logan.ktoastapp.test/androidx.test.runner.AndroidJUnitRunner', timeout=900)
        (output / 'instrumentation.txt').write_text(raw, encoding='utf-8')
        report['instrumentation'] = instrumentation_result(raw, required_tests(api))
        reported = set().union(*(report['instrumentation'][key] for key in ('passed', 'skipped', 'failed')))
        # SDK 过滤可能没有逐项状态；未报告项单列，不能算作成功或猜测为跳过。
        report['instrumentation']['notReported'] = sorted(all_tests() - reported)
        selfcheck = TEST_PACKAGE + 'WindowFailureDiagnosticsTest#failurePreservesOriginalAndWritesShellReadableSnapshots'
        if report['instrumentation']['success'] and selfcheck in report['instrumentation']['passed']:
            destination = output / 'diagnostics-selfcheck'
            names = export_diagnostics('files/window-failures/' + diagnostic_run + '/' +
                TEST_PACKAGE + 'WindowFailureDiagnosticsTest#syntheticFailure', destination)
            for name in ('failure', 'window', 'input', 'activity', 'display'):
                file = destination / (name + '.txt')
                if file.name not in names or not file.is_file() or not file.stat().st_size:
                    raise ValueError('采证设备自检缺失或为空：' + name)
            report['diagnosticsSelfcheck'] = {'runId': diagnostic_run,
                'files': sorted(str(path.relative_to(output)) for path in destination.iterdir() if path.is_file())}
        if not report['instrumentation']['success']:
            raise ValueError('设备回归未满足必需用例或 Runner 未正常完成')
        for key, consumer in (('r8Consumer', CONSUMER), ('lifecycleR8Consumer', 'com.logan.ktoast.lifecycleconsumer')):
            adb('shell', 'am', 'force-stop', consumer)
            baseline = set(adb('logcat', '-d', '-v', 'threadtime', 'KToastConsumer:I', '*:S').splitlines())
            launch = adb('shell', 'am', 'start', '-W', '-n', consumer + '/.ConsumerActivity')
            (output / (key + '-launch.txt')).write_text(launch, encoding='utf-8')
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                # API 21 没有通用 pidof/logcat --pid，使用 ps 与 threadtime 的 PID 列。
                processes = adb('shell', 'ps')
                pid = next((line.split()[1] for line in processes.splitlines()
                            if line.split() and line.split()[-1] == consumer), None)
                logs = adb('logcat', '-d', '-v', 'threadtime', 'KToastConsumer:I', '*:S')
                (output / (key + '-logcat.txt')).write_text(logs, encoding='utf-8')
                if pid:
                    line = consumer_line(logs, pid, baseline)
                    if line:
                        report[key] = {'pid': pid, 'passLine': line}
                        break
                time.sleep(0.5)
            if key not in report:
                raise ValueError('R8 消费者新进程未输出 PASS')
        report['success'] = True
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        report['error'] = str(error)
        if isinstance(error, subprocess.TimeoutExpired) and error.stdout:
            partial = error.stdout.decode(errors='replace') if isinstance(error.stdout, bytes) else error.stdout
            (output / 'timeout-output.txt').write_text(partial, encoding='utf-8')
        raise
    finally:
        if dedicated_emulator:
            if not report['success']:
                # 保存收尾时的系统状态，不能把它当作首个失败时刻的状态。
                # 用例内采证在宿主销毁前执行；独立 runId 避免读到旧运行的诊断。
                diagnostics = {'capturedAt': 'after-run', 'snapshots': {}}
                report['failureDiagnostics'] = diagnostics
                for name, service in (
                    ('window', ('window',)), ('input', ('input',)),
                    ('activity', ('activity', 'activities')), ('display', ('display',))
                ):
                    try:
                        filename = 'after-run-' + name + '.txt'
                        content = adb('shell', 'dumpsys', *service, timeout=15)
                        (output / filename).write_text(content, encoding='utf-8')
                        validate_snapshot(name, content)
                        diagnostics['snapshots'][name] = {'file': filename}
                    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
                        diagnostics['snapshots'][name] = {'error': str(error)}
                if instrumentation_started:
                    try:
                        test_output = output / 'window-failures' / diagnostic_run
                        test_output.parent.mkdir(parents=True, exist_ok=True)
                        remote = 'files/window-failures/' + diagnostic_run
                        tests = adb('shell', 'run-as', 'com.logan.ktoastapp', 'ls', remote).split()
                        if not tests or any(not re.fullmatch(r'[A-Za-z0-9_.#-]+', test) for test in tests):
                            raise ValueError('失败诊断用例目录缺失或不合法')
                        for test in tests:
                            export_diagnostics(remote + '/' + test, test_output / test)
                        files = sorted(str(path.relative_to(output)) for path in
                                       test_output.rglob('*') if path.is_file())
                        if not files:
                            raise ValueError('没有取得本次用例的失败诊断文件')
                        diagnostics['testFailures'] = {'runId': diagnostic_run, 'files': files}
                    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
                        diagnostics['testFailures'] = {'runId': diagnostic_run, 'error': str(error)}
            try:
                (output / 'device-logcat.txt').write_text(adb('logcat', '-d', '-v', 'threadtime'), encoding='utf-8')
            except (ValueError, OSError, subprocess.TimeoutExpired) as error:
                report['logcatError'] = str(error)
        save_json(output / 'result.json', report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare', action='store_true', help='构建后记录输入产物，不操作设备')
    parser.add_argument('--api', type=int, choices=(21, 24, 30, 31, 35, 36, 37))
    parser.add_argument('--serial')
    args = parser.parse_args()
    if args.prepare:
        if args.api or args.serial:
            parser.error('--prepare 不接受设备参数')
        save_json(OUTPUT / 'input.json', {'commit': os.environ.get('GITHUB_SHA'), 'artifacts': artifacts()})
    else:
        if not args.api or not args.serial:
            parser.error('运行须指定 --api 和 --serial')
        result = run_device(args.api, args.serial)
        print('API', args.api, 'PASS:', len(result['instrumentation']['passed']), 'tests; skipped:',
              len(result['instrumentation']['skipped']), '; not reported:',
              len(result['instrumentation']['notReported']), '; R8 PID:', result['r8Consumer']['pid'])


if __name__ == '__main__':
    main()
