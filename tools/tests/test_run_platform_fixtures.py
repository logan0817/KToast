import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_platform_fixtures as fixtures


class PlatformFixturesTest(unittest.TestCase):
    def exercise(self, *, serial='emulator-5554', qemu='1', skipped=None, failed=None,
                 restore_failure=False, artifact_mismatch=False):
        commands = []
        settings = {namespace + '/' + key: 'null' for namespace, key in fixtures.SETTINGS}
        overlay = False
        instrument_count = 0

        def run(command, **kwargs):
            nonlocal overlay, instrument_count
            args = command[3:]
            commands.append(args)
            text = ''
            if args[:2] == ['shell', 'getprop']:
                text = {'ro.kernel.qemu': qemu, 'ro.build.version.sdk': '35',
                        'ro.build.fingerprint': 'test-only', 'ro.product.cpu.abi': 'x86_64',
                        'sys.boot_completed': '1'}[args[2]]
            elif args[:2] == ['shell', 'settings']:
                identity = args[3] + '/' + args[4]
                if args[2] == 'get':
                    text = settings[identity]
                elif args[2] == 'delete':
                    if not restore_failure:
                        settings[identity] = 'null'
                else:
                    settings[identity] = args[5]
            elif args[:3] == ['shell', 'cmd', 'overlay']:
                if args[3] == 'list':
                    text = ('[x] ' if overlay else '[ ] ') + fixtures.CUTOUT
                else:
                    overlay = args[3] == 'enable'
            elif args[:3] == ['shell', 'dumpsys', 'window']:
                text = 'WINDOW MANAGER\nmCurrentFocus=Window{abc com.android.launcher/.Launcher}'
            elif args[0] == 'install':
                text = 'Success'
            elif args[:3] == ['shell', 'am', 'instrument']:
                instrument_count += 1
                test = args[args.index('class') + 1]
                klass, method = test.split('#')
                code = -3 if skipped == instrument_count else -2 if failed == instrument_count else 0
                text = ('INSTRUMENTATION_STATUS: class=' + klass + '\nINSTRUMENTATION_STATUS: test=' + method +
                        '\nINSTRUMENTATION_STATUS_CODE: ' + str(code) + '\nINSTRUMENTATION_CODE: -1\n')
            return subprocess.CompletedProcess(command, 0, text)

        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            (output / 'input.json').write_text(json.dumps({'commit': 'same', 'artifacts': {}}))
            with patch.object(fixtures, 'OUTPUT', output / 'fixtures'), \
                    patch.object(fixtures, 'MATRIX_OUTPUT', output), \
                    patch.object(fixtures, 'artifacts', return_value={'bad': {}} if artifact_mismatch else {}), \
                    patch.dict(fixtures.os.environ, {'GITHUB_SHA': 'same'}), \
                    patch.object(fixtures.subprocess, 'run', side_effect=run):
                try:
                    fixtures.run_fixtures(serial)
                except ValueError:
                    pass
            report = json.loads((output / 'fixtures/result.json').read_text())
        return report, commands, settings, overlay

    def test_all_five_stages_require_real_terminal_success_and_restore_original_values(self):
        report, commands, settings, overlay = self.exercise()
        self.assertTrue(report['success'])
        self.assertEqual(5, len(report['stages']))
        self.assertEqual(5, sum(command[:3] == ['shell', 'am', 'instrument'] for command in commands))
        self.assertTrue(all(value == 'null' for value in settings.values()))
        self.assertFalse(overlay)
        self.assertTrue(report['restoration']['success'])
        self.assertEqual(['10000', '0'], list(report['stages']['noninteractive']['settings'].values()))
        self.assertEqual(['0', '10000'], list(report['stages']['interactive-button']['settings'].values()))
        self.assertTrue(report['stages']['cutout']['overlays'][fixtures.CUTOUT])

    def test_real_devices_and_unverified_artifacts_never_reach_mutations(self):
        for options in ({'serial': 'physical'}, {'qemu': '0'}, {'artifact_mismatch': True}):
            with self.subTest(options=options):
                report, commands, _, _ = self.exercise(**options)
                self.assertFalse(report['success'])
                self.assertFalse(any(command[0] in ('install', 'reboot') or
                                     command[:2] == ['shell', 'settings'] for command in commands))

    def test_skip_or_failure_collects_all_five_stages_and_restores_settings(self):
        for options in ({'skipped': 2}, {'failed': 5}):
            with self.subTest(options=options):
                report, _, settings, _ = self.exercise(**options)
                self.assertFalse(report['success'])
                self.assertEqual(5, len(report['stages']))
                self.assertTrue(report['restoration']['success'])
                self.assertTrue(all(value == 'null' for value in settings.values()))

    def test_restoration_failure_cannot_be_reported_as_success(self):
        report, _, _, _ = self.exercise(restore_failure=True)
        self.assertFalse(report['success'])
        self.assertTrue(all(stage['success'] for stage in report['stages'].values()))
        self.assertFalse(report['restoration']['success'])
        self.assertTrue(report['restoration']['errors'])


if __name__ == '__main__':
    unittest.main()
