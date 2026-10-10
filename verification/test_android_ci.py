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


if __name__ == "__main__":
    unittest.main()
