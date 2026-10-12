package com.unihub.app.feature.auth

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** UI-only checks: no project credentials, network requests, or Hilt graph are used. */
class CloudLoginScreenTest {
    @get:Rule val rule = createComposeRule()
    private var attempts = 0
    private var passwordSubmitted = ""
    private fun screen(busy: Boolean = false, fontScale: Float = 1f) {
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    CloudLoginScreen(isBusy = busy, initialError = null, statusMessage = null,
                        onLogin = { _, password -> attempts++; passwordSubmitted = password },
                        onInputsChanged = {}, onRetry = {})
                }
            }
        }
    }

    @Test fun exactPasswordIsPassedWithoutTrimming() {
        screen()
        val password = "  an exact password 2026  "
        rule.onNodeWithTag("auth.username").performScrollTo().performTextInput("alice@example.test")
        rule.onNodeWithTag("auth.password").performScrollTo().performTextInput(password)
        rule.onNodeWithTag("auth.login").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, attempts); assertEquals(password, passwordSubmitted) }
    }
    @Test fun busyLoginCannotBeSubmittedAgain() {
        screen(busy = true)
        rule.onNodeWithTag("auth.login").performScrollTo().assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, attempts) }
    }
    @Test fun ownerNameDoesNotBootstrapAnAccount() {
        screen()
        rule.onNodeWithTag("auth.username").performScrollTo().performTextInput("saged")
        rule.onNodeWithTag("auth.password").performScrollTo().performTextInput("strong owner password 2026")
        rule.onNodeWithTag("auth.login").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("تأكيد تهيئة المالك").assertDoesNotExist()
    }
    @Test fun noInvitationOrServerKeysAreRequested() {
        screen()
        rule.onNodeWithTag("auth.invitation").assertDoesNotExist()
        rule.onNodeWithText("Secret Access Key").assertDoesNotExist()
        rule.onNodeWithText("Access Key ID").assertDoesNotExist()
        rule.onNodeWithTag("auth.login").performScrollTo().assertIsNotEnabled()
    }
    @Test fun validEmailEnablesLoginWithoutStorageProvisioning() {
        screen()
        rule.onNodeWithTag("auth.username").performScrollTo().performTextInput("student@example.test")
        rule.onNodeWithTag("auth.password").performScrollTo().performTextInput("correct horse battery")
        rule.onNodeWithTag("auth.login").performScrollTo().assertIsEnabled()
    }
    @Test fun controlsRemainReachableWithLargeFontScale() {
        screen(fontScale = 2f)
        rule.onNodeWithTag("auth.username").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("auth.password").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("auth.login").performScrollTo().assertIsDisplayed()
    }
}
