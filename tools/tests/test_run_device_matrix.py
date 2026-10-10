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
SKIP = '''INSTRUMENTATION_STATUS: class=sample.Test
INSTRUMENTATION_STATUS: test=optional
INSTRUMENTATION_STATUS_CODE: -4
'''


class DeviceMatrixTest(unittest.TestCase):
    def test_real_required_source_count_and_modern_subset(self):
        self.assertEqual(28, len(required_tests(21)))
        self.assertEqual(31, len(required_tests(30)))
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
            candidate = root / 'build/reports/compatibility/candidate.json'
            candidate.parent.mkdir(parents=True)
            candidate.write_text(json.dumps({'sha256': 'wrong'}), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                artifacts(root)

    def test_empty_artifacts_cannot_pass(self):
        with tempfile.TemporaryDirectory(prefix='ktoast-matrix-test-') as folder:
            with self.assertRaisesRegex(ValueError, '缺失或空产物'):
                artifacts(Path(folder))

    def exercise_device(self, *, actual_api='21', instrumentation=PASS, install='Success'):
        """模拟 adb 协议完成整条入口，验证结果写入和失败收尾；不伪造真实设备证据。"""
        commands = []
        log_reads = 0

        def fake_adb(command, **kwargs):
            nonlocal log_reads
            args = command[3:]
            commands.append(args)
            text = ''
            if args[:2] == ['shell', 'getprop']:
                text = {'ro.kernel.qemu': '1', 'ro.build.version.sdk': actual_api,
                        'ro.build.fingerprint': 'fixture', 'ro.product.cpu.abi': 'x86'}[args[2]]
            elif args[0] == 'install':
                text = install
            elif args[:3] == ['shell', 'am', 'instrument']:
                text = instrumentation
            elif args[:2] == ['shell', 'ps']:
                text = 'u0_a1 10633 1 0 0 com.logan.ktoast.compatibility'
            elif args[0] == 'logcat':
                log_reads += 1
                if log_reads > 1:
                    text = '10-10 15:01:21.041 10633 10633 I KToastConsumer: PASS: fixture'
            return subprocess.CompletedProcess(command, 0, text)

        with tempfile.TemporaryDirectory(prefix='ktoast-matrix-flow-test-') as folder:
            output = Path(folder)
            (output / 'input.json').write_text(json.dumps({'commit': None, 'artifacts': {}}), encoding='utf-8')
            with patch.object(matrix, 'OUTPUT', output), patch.object(matrix, 'artifacts', return_value={}), \
                    patch.object(matrix, 'required_tests', return_value={'sample.Test#required'}), \
                    patch.object(matrix, 'all_tests', return_value={'sample.Test#required', 'sample.Test#filtered'}), \
                    patch.dict(matrix.os.environ, {'GITHUB_SHA': ''}), patch.object(matrix.subprocess, 'run', side_effect=fake_adb):
                try:
                    matrix.run_device(21, 'emulator-5554')
                except ValueError:
                    pass
            return json.loads((output / 'api-21/result.json').read_text(encoding='utf-8')), commands

    def test_device_entry_writes_success_and_unreported_separately(self):
        result, commands = self.exercise_device()
        self.assertTrue(result['success'])
        self.assertEqual('10633', result['r8Consumer']['pid'])
        self.assertEqual(['sample.Test#filtered'], result['instrumentation']['notReported'])
        self.assertEqual(3, sum(command[0] == 'install' for command in commands))

    def test_wrong_api_fails_before_install_and_preserves_report(self):
        result, commands = self.exercise_device(actual_api='30')
        self.assertFalse(result['success'])
        self.assertIn('实际设备 API', result['error'])
        self.assertFalse(any(command[0] == 'install' for command in commands))

    def test_instrumentation_failure_prevents_consumer_launch(self):
        result, commands = self.exercise_device(instrumentation=PASS.replace('STATUS_CODE: 0', 'STATUS_CODE: -2'))
        self.assertFalse(result['success'])
        self.assertEqual(['sample.Test#required'], result['instrumentation']['failed'])
        self.assertFalse(any(command[:3] == ['shell', 'am', 'start'] for command in commands))


if __name__ == '__main__':
    unittest.main()
