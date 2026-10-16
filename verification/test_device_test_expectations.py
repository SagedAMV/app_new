"""Regression tests for the device-test expectation guard (no Android SDK required)."""

import contextlib
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import check_device_test_expectations as guard


class DeviceTestExpectationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "app/src/main/java").mkdir(parents=True)
        (self.root / "app/src/main/res").mkdir(parents=True)
        self.tests = self.root / "app/src/androidTest"
        self.tests.mkdir(parents=True)

    def shipped(self, text):
        (self.root / "app/src/main/java/Screen.kt").write_text(text, encoding="utf-8")

    def device_test(self, text):
        (self.tests / "ScreenTest.kt").write_text(text, encoding="utf-8")

    def run_guard(self):
        with patch.object(guard, "ROOT", self.root), \
                patch.object(guard, "DEVICE_TESTS", self.tests), \
                patch.object(guard, "APP_SOURCES", (self.root / "app/src/main/java",
                                                    self.root / "app/src/main/res")):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                return guard.main(), output.getvalue()

    def test_matching_positive_expectation_passes(self):
        self.shipped('Text("لا مجلدات بعد") # label')
        self.device_test('compose.onNodeWithText("لا مجلدات بعد").assertIsDisplayed()')
        status, output = self.run_guard()
        self.assertEqual(0, status, output)
        self.assertIn("checked 1 positive device-test expectations", output)

    def test_stale_positive_expectation_names_the_missing_string(self):
        # نفس الانحراف الذي أسقط تشغيل الجهاز: النصّ تغيّر في الإنتاج وبقي في الاختبار.
        self.shipped('Text("لا مجلدات بعد")')
        self.device_test('compose.onNodeWithText("لا توجد مجلدات بعد").assertIsDisplayed()')
        status, output = self.run_guard()
        self.assertEqual(1, status)
        self.assertIn("لا توجد مجلدات بعد", output)
        self.assertIn("ScreenTest.kt:1", output)

    def test_negative_checks_and_test_data_are_not_requirements(self):
        self.shipped('Text("لا مجلدات بعد")')
        self.device_test(
            'compose.onNodeWithText("Secret Access Key").assertDoesNotExist()\n'
            'compose.onNodeWithTag("auth.invitation").assertDoesNotExist()\n'
            'compose.onNodeWithText("مجلد 4999").performClick()\n'
            'compose.onNodeWithTag("galaxy-disk-1").assertIsDisplayed()\n'
        )
        status, output = self.run_guard()
        self.assertEqual(0, status, output)
        self.assertIn("checked 0 positive device-test expectations", output)

    def test_shipped_strings_are_read_from_sources_and_resources(self):
        (self.root / "app/src/main/res/values").mkdir(parents=True)
        (self.root / "app/src/main/res/values/strings.xml").write_text(
            "<resources><string name=\"app_name\">UniHub</string></resources>", encoding="utf-8")
        self.device_test('compose.onNodeWithText("UniHub").assertIsDisplayed()')
        status, output = self.run_guard()
        self.assertEqual(0, status, output)


if __name__ == "__main__":
    unittest.main()
