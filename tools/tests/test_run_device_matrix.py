"""矩阵工具必须拒绝空运行、必需用例跳过、中断及旧进程 PASS。"""

import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import subprocess

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_device_matrix import FILES, artifacts, consumer_line, instrumentation_result, required_tests
import run_device_matrix as matrix


PASS = '''INSTRUMENTATION_STATUS: class=sample.Test
INSTRUMENTATION_STATUS: test=required
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=sample.Test
INSTRUMENTATION_STATUS: test=required
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_CODE: -1
'''
READY_WINDOW = 'WINDOW MANAGER\nmCurrentFocus=Window{42 u0 com.android.launcher/.Launcher}\nisKeyguardShowing=false'
SKIP = '''INSTRUMENTATION_STATUS: class=sample.Test
INSTRUMENTATION_STATUS: test=optional
INSTRUMENTATION_STATUS_CODE: -4
'''


class DeviceMatrixTest(unittest.TestCase):
    def test_real_required_source_count_and_modern_subset(self):
        self.assertEqual(38, len(required_tests(21)))
        self.assertEqual(41, len(required_tests(30)))
        self.assertEqual(required_tests(30), required_tests(36))
        self.assertFalse(any('cutout' in name.lower() for name in required_tests(36)))

    def test_success(self):
        self.assertTrue(instrumentation_result(PASS, {'sample.Test#required'})['success'])

    def test_optional_assumption_is_counted_as_skip(self):
        result = instrumentation_result(SKIP + PASS, {'sample.Test#required'})
        self.assertTrue(result['success'])
        self.assertEqual(['sample.Test#optional'], result['skipped'])

    def test_required_assumption_cannot_pass(self):
        self.assertFalse(instrumentation_result(SKIP + PASS, {'sample.Test#optional'})['success'])

    def test_required_sdk_filter_cannot_pass(self):
        filtered = SKIP.replace('CODE: -4', 'CODE: -3')
        self.assertFalse(instrumentation_result(filtered + PASS, {'sample.Test#optional'})['success'])

    def test_failure_with_normal_runner_exit_cannot_pass(self):
        failed = SKIP.replace('CODE: -4', 'CODE: -2')
        self.assertFalse(instrumentation_result(failed + PASS, {'sample.Test#required'})['success'])

    def test_runner_crash_and_empty_run_cannot_pass(self):
        self.assertFalse(instrumentation_result('INSTRUMENTATION_CODE: -1', set())['success'])
        self.assertFalse(instrumentation_result(PASS.replace('INSTRUMENTATION_CODE: -1', ''), set())['success'])
        self.assertFalse(instrumentation_result(PASS.replace('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: 0'), set())['success'])

    def test_duplicate_terminal_cannot_overwrite_failure(self):
        with self.assertRaisesRegex(ValueError, '重复'):
            instrumentation_result(PASS + PASS, set())

    def test_only_current_process_fresh_pass_is_accepted(self):
        line = '10-10 15:01:21.041 10633 10633 I KToastConsumer: PASS: scoped Java'
        self.assertEqual(line, consumer_line(line, '10633', set()))
        self.assertIsNone(consumer_line(line, '10634', set()))
        self.assertIsNone(consumer_line(line, '10633', {line}))

    def test_consumer_error_is_failure(self):
        with self.assertRaisesRegex(ValueError, '消费者报告失败'):
            consumer_line('10-10 15:01:21.041 10633 10633 E KToastConsumer: Consumer verification failed', '10633', set())

    def test_artifact_report_checksum_is_required(self):
        with tempfile.TemporaryDirectory(prefix='ktoast-matrix-test-') as folder:
            root = Path(folder)
            for relative in FILES.values():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'fixture')
            candidate = root / 'build/reports/compatibility/ktoast-candidate.json'
            candidate.parent.mkdir(parents=True)
            candidate.write_text(json.dumps({'sha256': 'wrong'}), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                artifacts(root)

    def test_empty_artifacts_cannot_pass(self):
        with tempfile.TemporaryDirectory(prefix='ktoast-matrix-test-') as folder:
            with self.assertRaisesRegex(ValueError, '缺失或空产物'):
                artifacts(Path(folder))

    def exercise_device(self, *, actual_api='21', instrumentation=PASS, install='Success',
                        diagnostic_failure=None, pull_files=True, instrumentation_timeout=False,
                        selfcheck=False, missing_selfcheck_file=None,
                        boot='1', window=READY_WINDOW):
        """模拟 adb 协议完成整条入口，验证结果写入和失败收尾；不伪造真实设备证据。"""
        commands = []
        log_reads = 0
        generation = 0
        active_package = ""
        window_reads = 0

        def fake_adb(command, **kwargs):
            nonlocal log_reads, generation, active_package, window_reads
            args = command[3:]
            commands.append(args)
            text = ''
            if args[:2] == ['shell', 'getprop']:
                text = {'ro.kernel.qemu': '1', 'ro.build.version.sdk': actual_api,
                        'ro.build.fingerprint': 'fixture', 'ro.product.cpu.abi': 'x86',
                        'sys.boot_completed': boot}[args[2]]
            elif args[0] == 'install':
                text = install
            elif args[:3] == ['shell', 'am', 'instrument']:
                if instrumentation_timeout:
                    raise subprocess.TimeoutExpired(command, 900, output='Runner partial output')
                text = instrumentation
                if selfcheck:
                    text = text.replace('INSTRUMENTATION_CODE:',
                        'INSTRUMENTATION_STATUS: class=com.logan.ktoastapp.app.WindowFailureDiagnosticsTest\n'
                        'INSTRUMENTATION_STATUS: test=failurePreservesOriginalAndWritesShellReadableSnapshots\n'
                        'INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_CODE:')
            elif args[:2] == ['shell', 'dumpsys']:
                if args[2] == diagnostic_failure and (args[2] != 'window' or window_reads > 0):
                    raise subprocess.TimeoutExpired(command, 15)
                text = matrix.SNAPSHOT_MARKERS[args[2].lower()] + ' fixture state'
                if args[2] == 'window':
                    text = window
                    window_reads += 1
            elif len(args) > 2 and args[1:3] == ['run-as', 'com.logan.ktoastapp']:
                if diagnostic_failure == 'pull':
                    return subprocess.CompletedProcess(command, 1, 'private directory does not exist')
                if args[3] == 'ls':
                    if args[4].count('/') == 2:
                        text = 'sample.Test#required' if pull_files else ''
                    elif pull_files:
                        names = ('failure', 'window', 'input', 'activity', 'display', 'surfaceflinger') if (
                            args[4].endswith('WindowFailureDiagnosticsTest#syntheticFailure')) else ('window',)
                        text = ' '.join(name + '.txt' for name in names if name != missing_selfcheck_file)
                elif args[3] == 'cat':
                    name = Path(args[4]).stem
                    text = matrix.SNAPSHOT_MARKERS.get(name, 'failure') + ' fixture state'
            elif args[:3] == ['shell', 'am', 'force-stop']:
                active_package = args[3]
                log_reads = 0
                generation += 1
            elif args[:2] == ['shell', 'ps']:
                text = 'u0_a1 10633 1 0 0 ' + active_package
            elif args[0] == 'logcat':
                log_reads += 1
                if log_reads > 1:
                    text = '10-10 15:01:21.041 10633 10633 I KToastConsumer: PASS: fixture ' + str(generation)
            return subprocess.CompletedProcess(command, 0, text)

        with tempfile.TemporaryDirectory(prefix='ktoast-matrix-flow-test-') as folder:
            output = Path(folder)
            (output / 'input.json').write_text(json.dumps({'commit': None, 'artifacts': {}}), encoding='utf-8')
            with patch.object(matrix, 'OUTPUT', output), patch.object(matrix, 'artifacts', return_value={}), \
                    patch.object(matrix, 'required_tests', return_value={'sample.Test#required'}), \
                    patch.object(matrix, 'all_tests', return_value={'sample.Test#required', 'sample.Test#filtered'}), \
                    patch.dict(matrix.os.environ, {'GITHUB_SHA': ''}), patch.object(matrix.subprocess, 'run', side_effect=fake_adb):
                if instrumentation_timeout:
                    with self.assertRaises(subprocess.TimeoutExpired):
                        matrix.run_device(21, 'emulator-5554')
                    self.assertEqual('Runner partial output',
                                     (output / 'api-21/timeout-output.txt').read_text(encoding='utf-8'))
                else:
                    try:
                        matrix.run_device(21, 'emulator-5554')
                    except ValueError:
                        pass
            result = json.loads((output / 'api-21/result.json').read_text(encoding='utf-8'))
            if 'startupPreflight' in result:
                self.assertEqual(window, (output / 'api-21/preflight-window.txt').read_text(encoding='utf-8'))
                self.assertTrue((output / 'api-21/device-logcat.txt').is_file())
            return result, commands

    def test_device_entry_writes_success_and_unreported_separately(self):
        result, commands = self.exercise_device()
        self.assertTrue(result['success'])
        self.assertEqual('10633', result['r8Consumer']['pid'])
        self.assertEqual(['sample.Test#filtered'], result['instrumentation']['notReported'])
        self.assertEqual(4, sum(command[0] == 'install' for command in commands))
        self.assertNotIn('failureDiagnostics', result)
        self.assertTrue(result['startupPreflight']['success'])
        self.assertEqual([['shell', 'dumpsys', 'window']],
                         [command for command in commands if command[:2] == ['shell', 'dumpsys']])
        self.assertFalse(any(command[0] == 'pull' for command in commands))

    def test_startup_anr_or_keyguard_blocks_runner_and_preserves_evidence(self):
        for window in (READY_WINDOW.replace('com.android.launcher/.Launcher',
                                            'Application Not Responding: com.android.launcher'),
                       READY_WINDOW.replace('isKeyguardShowing=false', 'isKeyguardShowing=true')):
            with self.subTest(window=window):
                result, commands = self.exercise_device(window=window)
                self.assertFalse(result['startupPreflight']['success'])
                self.assertIn('ANR 对话框或锁屏', result['error'])
                self.assertNotIn('testFailures', result['failureDiagnostics'])
                self.assertFalse(any(command[:3] == ['shell', 'am', 'instrument'] for command in commands))
                self.assertFalse(any(len(command) > 1 and command[1] == 'run-as' for command in commands))

    def test_startup_wait_is_bounded_for_incomplete_boot_or_absent_focus(self):
        for options in ({'boot': '0'}, {'window': 'WINDOW MANAGER\nmCurrentFocus=null'}):
            with self.subTest(options=options), patch.object(matrix.time, 'monotonic', side_effect=[0, 61]):
                result, commands = self.exercise_device(**options)
                self.assertIn('启动检查超时', result['error'])
                self.assertFalse(any(command[:3] == ['shell', 'am', 'instrument'] for command in commands))

    def test_startup_wait_can_recover_when_focus_becomes_ready(self):
        with patch.object(matrix, 'startup_window_state', side_effect=[
                {'focusedWindows': [], 'anrFocused': False, 'keyguardShowing': False},
                matrix.startup_window_state(READY_WINDOW)]), patch.object(matrix.time, 'sleep') as sleep:
            result, commands = self.exercise_device()
        self.assertTrue(result['success'])
        sleep.assert_called_once_with(1)
        self.assertEqual(2, commands.count(['shell', 'dumpsys', 'window']))

    def test_invalid_preflight_snapshot_cannot_start_runner(self):
        result, commands = self.exercise_device(window="Can't find service: window")
        self.assertIn('不是系统快照', result['error'])
        self.assertFalse(result['startupPreflight']['success'])
        self.assertFalse(any(command[:3] == ['shell', 'am', 'instrument'] for command in commands))

    def test_window_state_supports_legacy_keyguard_and_multiple_displays(self):
        for field in ('mShowingLockscreen', 'mDreamingLockscreen', 'isStatusBarKeyguard', 'isKeyguardShowing'):
            with self.subTest(field=field):
                self.assertTrue(matrix.startup_window_state(READY_WINDOW + '\n' + field + '=true')['keyguardShowing'])
                self.assertFalse(matrix.startup_window_state(READY_WINDOW + '\n' + field + '=false')['keyguardShowing'])
        state = matrix.startup_window_state(READY_WINDOW + '\nmCurrentFocus=null\nWindow #8 Application Not Responding')
        self.assertTrue(state['focusedWindows'])
        self.assertFalse(state['anrFocused'])

    def test_wrong_api_fails_before_install_and_preserves_report(self):
        result, commands = self.exercise_device(actual_api='30')
        self.assertFalse(result['success'])
        self.assertIn('实际设备 API', result['error'])
        self.assertFalse(any(command[0] == 'install' for command in commands))

    def test_device_selfcheck_requires_host_export_of_six_nonempty_files(self):
        result, commands = self.exercise_device(selfcheck=True)
        self.assertTrue(result['success'])
        self.assertEqual(6, len(result['diagnosticsSelfcheck']['files']))
        exports = [command for command in commands if command[:4] == ['exec-out', 'run-as', 'com.logan.ktoastapp', 'cat']]
        self.assertEqual(6, len(exports))
        self.assertTrue(any(command[4].endswith('/surfaceflinger.txt') for command in exports))
        self.assertTrue(all(command[4].startswith('files/window-failures/') for command in exports))

    def test_command_errors_and_empty_outputs_are_not_snapshots(self):
        for name in matrix.SNAPSHOT_MARKERS:
            for content in ('', "Can't find service: -t", 'Permission Denial'):
                with self.subTest(name=name, content=content), self.assertRaises(ValueError):
                    matrix.validate_snapshot(name, content)

    def test_legacy_dumpsys_command_has_no_unsupported_timeout_flag(self):
        _, commands = self.exercise_device(instrumentation=PASS.replace('STATUS_CODE: 0', 'STATUS_CODE: -2'))
        dumps = [command for command in commands if command[:2] == ['shell', 'dumpsys']]
        self.assertEqual(6, len(dumps))
        self.assertIn(['shell', 'dumpsys', 'SurfaceFlinger'], dumps)
        self.assertTrue(all('-t' not in command for command in dumps))

    def test_device_selfcheck_missing_file_blocks_consumers(self):
        for name in ('failure', *matrix.SNAPSHOT_MARKERS):
            with self.subTest(name=name):
                result, commands = self.exercise_device(selfcheck=True, missing_selfcheck_file=name)
                self.assertFalse(result['success'])
                self.assertIn('采证设备自检缺失或为空', result['error'])
                self.assertFalse(any(command[:3] == ['shell', 'am', 'start'] for command in commands))

    def test_instrumentation_failure_prevents_consumer_launch(self):
        result, commands = self.exercise_device(instrumentation=PASS.replace('STATUS_CODE: 0', 'STATUS_CODE: -2'))
        self.assertFalse(result['success'])
        self.assertEqual(['sample.Test#required'], result['instrumentation']['failed'])
        self.assertFalse(any(command[:3] == ['shell', 'am', 'start'] for command in commands))
        self.assertEqual({'window', 'input', 'activity', 'display', 'surfaceflinger'},
                         set(result['failureDiagnostics']['snapshots']))
        self.assertTrue(result['failureDiagnostics']['testFailures']['files'])
        command = next(command for command in commands if command[:3] == ['shell', 'am', 'instrument'])
        run_id = command[command.index('windowFailureRunId') + 1]
        self.assertTrue(run_id.isdigit())
        self.assertIn(run_id, result['failureDiagnostics']['testFailures']['files'][0])
        listing = next(command for command in commands if command[:4] == ['shell', 'run-as', 'com.logan.ktoastapp', 'ls'])
        self.assertEqual('files/window-failures/' + run_id, listing[4])

    def test_diagnostic_timeout_preserves_original_failure_and_other_snapshots(self):
        for service in ('window', 'SurfaceFlinger'):
            with self.subTest(service=service):
                result, _ = self.exercise_device(
                    instrumentation=PASS.replace('STATUS_CODE: 0', 'STATUS_CODE: -2'), diagnostic_failure=service)
                self.assertIn('设备回归未满足', result['error'])
                self.assertIn('error', result['failureDiagnostics']['snapshots'][service.lower()])
                self.assertIn('file', result['failureDiagnostics']['snapshots']['input'])
                self.assertTrue(result['failureDiagnostics']['testFailures']['files'])

    def test_missing_test_diagnostics_cannot_be_reported_as_collected(self):
        for options in ({'diagnostic_failure': 'pull'}, {'pull_files': False}):
            with self.subTest(options=options):
                result, _ = self.exercise_device(
                    instrumentation=PASS.replace('STATUS_CODE: 0', 'STATUS_CODE: -2'), **options)
                self.assertIn('设备回归未满足', result['error'])
                self.assertIn('error', result['failureDiagnostics']['testFailures'])
                self.assertNotIn('files', result['failureDiagnostics']['testFailures'])

    def test_runner_timeout_keeps_partial_output_and_collects_failure_diagnostics(self):
        result, _ = self.exercise_device(instrumentation_timeout=True)
        self.assertFalse(result['success'])
        self.assertIn('timed out', result['error'])
        self.assertIn('file', result['failureDiagnostics']['snapshots']['window'])
        self.assertTrue(result['failureDiagnostics']['testFailures']['files'])

    def test_failure_before_instrumentation_does_not_pull_old_test_diagnostics(self):
        result, commands = self.exercise_device(install='Failure [INSTALL_FAILED]')
        self.assertIn('安装失败', result['error'])
        self.assertNotIn('testFailures', result['failureDiagnostics'])
        self.assertFalse(any(len(command)>1 and command[1]=='run-as' for command in commands))


if __name__ == '__main__':
    unittest.main()
