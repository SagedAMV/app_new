package com.unihub.app.feature.auth

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Source-only until build authorization. No broker/R2 traffic and no Hilt dialog is opened. */
class CloudLoginScreenTest {
    @get:Rule val rule = createComposeRule()
    private var attempts = 0
    private var passwordSubmitted = ""
    private var bootstrapSubmitted = false

    private fun screen(busy: Boolean = false, configured: Boolean = true, fontScale: Float = 1f) {
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    CloudLoginScreen(
                        isBusy = busy, initialError = null, statusMessage = null,
                        cloudSettingsConfigured = configured, currentAccountId = "0".repeat(32),
                        currentBucketName = "test-bucket", isSavingCloudSettings = false,
                        serviceAvailable = true, setupSuccessVersion = 0,
                        onLogin = { _, password, bootstrap, _ -> attempts++; passwordSubmitted = password; bootstrapSubmitted = bootstrap },
                        onInputsChanged = {}, onRetry = {}, onCancelCloudOperation = {},
                        onSaveCloudCredentials = { _, _, _, _ -> }
                    )
                }
            }
        }
    }

    @Test fun exactPasswordIsPassedWithoutTrimming() {
        screen()
        val password = "  an exact password 2026  "
        rule.onNodeWithTag("auth.username").performScrollTo().performTextInput("alice")
        rule.onNodeWithTag("auth.password").performScrollTo().performTextInput(password)
        rule.onNodeWithTag("auth.login").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, attempts); assertEquals(password, passwordSubmitted); assertFalse(bootstrapSubmitted) }
    }

    @Test fun busyLoginCannotBeSubmittedAgain() {
        screen(busy = true)
        rule.onNodeWithTag("auth.login").performScrollTo().assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, attempts) }
    }

    @Test fun ownerBootstrapRequiresAnAdditionalConfirmation() {
        screen()
        rule.onNodeWithTag("auth.username").performScrollTo().performTextInput("saged")
        rule.onNodeWithTag("auth.password").performScrollTo().performTextInput("strong owner password 2026")
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).performScrollTo().performClick()
        rule.onNodeWithTag("auth.login").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(0, attempts) }
        rule.onNodeWithText("تأكيد تهيئة المالك").assertExists().performClick()
        rule.runOnIdle { assertEquals(1, attempts); assertTrue(bootstrapSubmitted) }
    }

    @Test fun unconfiguredUserSeesInvitationNotRawRootKeys() {
        screen(configured = false)
        rule.onNodeWithTag("auth.invitation").performScrollTo().assertExists()
        rule.onNodeWithText("Secret Access Key").assertDoesNotExist()
        rule.onNodeWithText("Access Key ID").assertDoesNotExist()
        rule.onNodeWithTag("auth.login").performScrollTo().assertIsNotEnabled()
    }

    @Test fun controlsRemainReachableWithLargeFontScale() {
        screen(fontScale = 2f)
        rule.onNodeWithTag("auth.username").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("auth.password").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("auth.login").performScrollTo().assertIsDisplayed()
    }
}
