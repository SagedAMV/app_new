"""Regression tests for clean-checkout APK verification (no Android SDK required)."""

import contextlib
import io
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts import android_ci


class AndroidCiTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.output = self.root / "app/build/outputs/apk/releaseFull"
        self.output.mkdir(parents=True)

    def apk(self, name="app-releaseFull-unsigned.apk", content=b"APK fixture"):
        (self.output / name).write_bytes(content)
        self.metadata([{"outputFile": name, "versionName": "1.3.1"}])
        return self.output / name

    def metadata(self, elements):
        (self.output / "output-metadata.json").write_text(json.dumps({"elements": elements}))

    def tools(self, version="35.0.0"):
        tools = self.root / "sdk/build-tools" / version
        tools.mkdir(parents=True)
        for name in ("zipalign", "apksigner"):
            (tools / name).touch()
        return tools

    def test_signed_and_unsigned_names_are_read_from_agp_metadata(self):
        for name in ("app-releaseFull.apk", "app-releaseFull-unsigned.apk"):
            with self.subTest(name=name):
                expected = self.apk(name)
                apk, version = android_ci.apk_from_metadata(self.output)
                self.assertEqual(expected, apk)
                self.assertEqual("1.3.1", version)

    def test_missing_empty_and_multiple_apks_fail(self):
        for elements in ([], [{"outputFile": "missing.apk"}], [{}, {}]):
            with self.subTest(elements=elements):
                self.metadata(elements)
                with self.assertRaises(ValueError):
                    android_ci.apk_from_metadata(self.output)
        self.apk(content=b"")
        with self.assertRaises(ValueError):
            android_ci.apk_from_metadata(self.output)

    def test_metadata_cannot_escape_output_directory(self):
        for name in ("../outside.apk", "/tmp/outside.apk", "other/not-an-apk.txt", ""):
            with self.subTest(name=name):
                self.metadata([{"outputFile": name}])
                with self.assertRaises(ValueError):
                    android_ci.apk_from_metadata(self.output)

    def test_apk_symlink_cannot_escape_output_directory(self):
        outside = self.root / "outside.apk"
        outside.write_bytes(b"not an APK")
        (self.output / "linked.apk").symlink_to(outside)
        self.metadata([{"outputFile": "linked.apk"}])
        with self.assertRaises(ValueError):
            android_ci.apk_from_metadata(self.output)

    def test_requiring_release_signature_fails_clearly_for_unsigned_apk(self):
        self.apk()
        with self.assertRaisesRegex(ValueError, "keystore.properties"):
            android_ci.verify("releaseFull", require_signed=True, root=self.root)

    @patch.dict(os.environ, {}, clear=True)
    @patch("scripts.android_ci.subprocess.run")
    def test_unsigned_apk_checks_alignment_and_is_explicitly_labelled(self, run):
        self.apk()
        self.tools()
        with patch("scripts.android_ci.sdk_directory", return_value=self.root / "sdk"):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                android_ci.verify("releaseFull", root=self.root)
        self.assertEqual(1, run.call_count)
        self.assertEqual("zipalign", Path(run.call_args.args[0][0]).name)
        self.assertIn("unsigned (sign before installing)", output.getvalue())

    @patch.dict(os.environ, {}, clear=True)
    @patch("scripts.android_ci.subprocess.run")
    def test_signed_apk_checks_alignment_and_signature(self, run):
        self.apk("app-releaseFull.apk")
        self.tools()
        with patch("scripts.android_ci.sdk_directory", return_value=self.root / "sdk"):
            with contextlib.redirect_stdout(io.StringIO()):
                android_ci.verify("releaseFull", require_signed=True, root=self.root)
        self.assertEqual(["zipalign", "apksigner"], [Path(call.args[0][0]).name for call in run.call_args_list])
        self.assertTrue(all(call.kwargs["check"] for call in run.call_args_list))

    @patch.dict(os.environ, {}, clear=True)
    @patch("scripts.android_ci.subprocess.run")
    def test_bad_signature_does_not_fall_back_to_unsigned_success(self, run):
        self.apk("app-releaseFull.apk")
        self.tools()
        run.side_effect = [None, subprocess.CalledProcessError(1, "apksigner")]
        with patch("scripts.android_ci.sdk_directory", return_value=self.root / "sdk"):
            with self.assertRaises(subprocess.CalledProcessError):
                android_ci.verify("releaseFull", root=self.root)

    def test_selects_newest_complete_stable_build_tools_numerically(self):
        self.tools("9.0.0")
        expected = self.tools("35.0.0")
        self.tools("36.0.0-rc1")
        incomplete = self.tools("36.0.0")
        (incomplete / "apksigner").unlink()
        self.assertEqual(expected, android_ci.build_tools(self.root / "sdk"))

    def test_missing_build_tools_do_not_skip_verification(self):
        with self.assertRaisesRegex(ValueError, "build-tools"):
            android_ci.build_tools(self.root / "sdk")

    @patch.dict(os.environ, {}, clear=True)
    def test_sdk_root_environment_is_supported(self):
        sdk = self.root / "sdk"
        sdk.mkdir()
        with patch.dict(os.environ, {"ANDROID_SDK_ROOT": str(sdk)}):
            self.assertEqual(sdk, android_ci.sdk_directory(self.root))

    @patch.dict(os.environ, {}, clear=True)
    def test_sdk_can_be_read_from_local_properties(self):
        sdk = self.root / "sdk"
        sdk.mkdir()
        (self.root / "local.properties").write_text(f"sdk.dir={sdk}\n")
        self.assertEqual(sdk, android_ci.sdk_directory(self.root))

    def test_xml_totals_include_failures_errors_and_skips(self):
        first = self.root / "TEST-first.xml"
        first.write_text('<testsuite tests="3" failures="1" errors="0" skipped="1"><testcase classname="First" name="fails"><failure message="bad value"/></testcase></testsuite>')
        second = self.root / "TEST-second.xml"
        second.write_text('<testsuite tests="2" failures="0" errors="1" skipped="0"/>')
        totals, failures = android_ci.test_totals([first, second])
        self.assertEqual(dict(tests=5, failures=1, errors=1, skipped=1), totals)
        self.assertEqual(["First.fails: bad value"], failures)

    def test_annotation_escapes_workflow_command_data(self):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            android_ci.annotation("error", "build, errors", "bad 50%\nnext\rline")
        self.assertEqual("::error title=build%2C errors::bad 50%25%0Anext%0Dline\n", output.getvalue())

    @patch.dict(os.environ, {}, clear=True)
    def test_missing_reports_do_not_claim_tests_or_lint_passed(self):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            android_ci.report(self.root)
        self.assertIn("tests are not verified", output.getvalue())
        self.assertIn("lint is not verified", output.getvalue())

    @patch.dict(os.environ, {}, clear=True)
    def test_device_reports_are_found_in_nested_result_directories(self):
        folder = self.root / "app/build/outputs/androidTest-results/connected/debug/device"
        folder.mkdir(parents=True)
        (folder / "TEST-galaxy.xml").write_text('<testsuite tests="11" failures="0" errors="0" skipped="0"/>')
        with contextlib.redirect_stdout(io.StringIO()) as output:
            android_ci.report(self.root, instrumentation=True)
        self.assertIn("Android UI test results", output.getvalue())
        self.assertIn("11 tests, 0 failures", output.getvalue())
        self.assertNotIn("JVM unit", output.getvalue())

    @patch.dict(os.environ, {}, clear=True)
    def test_missing_device_results_fail_instead_of_claiming_success(self):
        with contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(ValueError, "executed tests"):
                android_ci.report(self.root, instrumentation=True)

    @patch.dict(os.environ, {}, clear=True)
    def test_device_test_failures_are_annotated_and_fail_the_gate(self):
        folder = self.root / "app/build/outputs/androidTest-results/connected/debug"
        folder.mkdir(parents=True)
        (folder / "TEST-galaxy.xml").write_text('<testsuite tests="1" failures="1" errors="0" skipped="0"><testcase classname="Galaxy" name="disk"><failure message="disk center is wrong"/></testcase></testsuite>')
        with contextlib.redirect_stdout(io.StringIO()) as output:
            with self.assertRaises(ValueError):
                android_ci.report(self.root, instrumentation=True)
        self.assertIn("Android UI test failure", output.getvalue())
        self.assertIn("disk center is wrong", output.getvalue())

    def test_device_failure_details_include_the_xml_body(self):
        path = self.root / "TEST-galaxy.xml"
        path.write_text('<testsuite tests="1" failures="1"><testcase classname="Galaxy" name="resize"><failure>java.lang.AssertionError: wrong native center\n at GalaxyScreenTest.kt:160</failure></testcase></testsuite>')
        _, failures = android_ci.test_totals([path])
        self.assertIn("wrong native center", failures[0])
        self.assertIn("GalaxyScreenTest.kt:160", failures[0])

    @patch.dict(os.environ, {}, clear=True)
    def test_partial_device_suite_cannot_pass_the_expected_minimum(self):
        folder = self.root / "app/build/outputs/androidTest-results/connected/debug"
        folder.mkdir(parents=True)
        (folder / "TEST-galaxy.xml").write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"/>')
        with contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(ValueError, "at least 11 executed tests"):
                android_ci.report(self.root, instrumentation=True, minimum_tests=11)

    @patch.dict(os.environ, {}, clear=True)
    def test_device_process_crash_is_reported_even_without_test_xml(self):
        (self.root / "ui-logcat.log").write_text(
            "10-10 10:00:00 E AndroidRuntime: FATAL EXCEPTION: main\n"
            "10-10 10:00:00 E AndroidRuntime: java.lang.IllegalStateException: native crash fixture\n")
        with contextlib.redirect_stdout(io.StringIO()) as output:
            with self.assertRaises(ValueError):
                android_ci.report(self.root, instrumentation=True)
        self.assertIn("Android process crash", output.getvalue())
        self.assertIn("native crash fixture", output.getvalue())

    def test_device_script_preserves_gradle_status_when_logcat_succeeds_or_fails(self):
        script = Path(android_ci.__file__).with_name("run_android_ui_tests.sh")
        binary = self.root / "bin"
        binary.mkdir()
        adb = binary / "adb"
        gradle = self.root / "gradlew"
        environment = dict(os.environ, PATH=f"{binary}{os.pathsep}{os.environ.get('PATH', '')}")
        for gradle_status in (0, 7):
            for logcat_status in (0, 23):
                with self.subTest(gradle_status=gradle_status, logcat_status=logcat_status):
                    adb.write_text(f'#!/usr/bin/env bash\nif [[ "${{2:-}}" == "-d" ]]; then echo "native logcat fixture"; exit {logcat_status}; fi\nexit 0\n')
                    gradle.write_text(f'#!/usr/bin/env bash\necho "device test fixture"\nexit {gradle_status}\n')
                    adb.chmod(0o700)
                    gradle.chmod(0o700)
                    result = subprocess.run(["bash", str(script)], cwd=self.root, env=environment, capture_output=True, text=True)
                    self.assertEqual(gradle_status, result.returncode, result.stderr)
                    self.assertIn("device test fixture", (self.root / "ui-tests.log").read_text())
                    self.assertIn("native logcat fixture", (self.root / "ui-logcat.log").read_text())

    def run_deadline_fixture(self, gradle_body, adb_body, test_timeout="5"):
        binary = self.root / "bin"
        binary.mkdir()
        adb = binary / "adb"
        gradle = self.root / "gradlew"
        adb.write_text("#!/usr/bin/env bash\n" + adb_body + "\n")
        gradle.write_text("#!/usr/bin/env bash\n" + gradle_body + "\n")
        adb.chmod(0o700)
        gradle.chmod(0o700)
        environment = dict(os.environ, PATH=f"{binary}{os.pathsep}{os.environ.get('PATH', '')}",
                           ANDROID_UI_ADB_TIMEOUT_SECONDS="1", ANDROID_UI_TEST_TIMEOUT_SECONDS=test_timeout)
        return subprocess.run(["bash", str(Path(android_ci.__file__).with_name("run_android_ui_tests.sh"))],
                              cwd=self.root, env=environment, capture_output=True, text=True, timeout=10)

    def test_device_test_timeout_stops_the_pipeline_and_captures_diagnostics(self):
        result = self.run_deadline_fixture(
            'echo "test started fixture"; sleep 30',
            'if [[ "${2:-}" == "-d" ]]; then echo "captured after timeout fixture"; fi; exit 0',
            test_timeout="1")
        self.assertEqual(124, result.returncode, result.stderr)
        self.assertIn("Android UI timeout", result.stdout)
        self.assertIn("Device diagnostics finished", result.stdout)
        self.assertIn("captured after timeout fixture", (self.root / "ui-logcat.log").read_text())

    def test_device_logcat_timeout_preserves_the_test_failure(self):
        result = self.run_deadline_fixture(
            'echo "test failed fixture"; exit 7',
            'if [[ "${2:-}" == "-d" ]]; then echo "partial capture fixture"; sleep 30; fi; exit 0')
        self.assertEqual(7, result.returncode, result.stderr)
        self.assertIn("Could not finish device log capture", result.stdout)
        self.assertIn("partial capture fixture", (self.root / "ui-logcat.log").read_text())

    def test_device_log_clear_timeout_does_not_block_test_execution(self):
        result = self.run_deadline_fixture(
            'echo "test executed fixture"; exit 0',
            'if [[ "${2:-}" == "-c" ]]; then sleep 30; else echo "device log fixture"; fi; exit 0')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Could not clear the device log", result.stdout)
        self.assertIn("test executed fixture", (self.root / "ui-tests.log").read_text())


if __name__ == "__main__":
    unittest.main()
