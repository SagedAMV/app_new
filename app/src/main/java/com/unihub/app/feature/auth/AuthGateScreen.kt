package com.unihub.app.feature.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.R
import com.unihub.app.data.auth.AuthSessionState
import com.unihub.app.data.auth.CloudAuthRules

@Composable
fun AuthGateScreen(viewModel: AuthViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val session by viewModel.sessionState.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val status by viewModel.statusMessage.collectAsStateWithLifecycle()
    val error by viewModel.errorMessage.collectAsStateWithLifecycle()
    val dismissed by viewModel.sessionMessageDismissed.collectAsStateWithLifecycle()
    AnimatedContent(targetState = session, contentKey = { it.javaClass }, label = "AuthGateTransition") { state ->
        when (state) {
            AuthSessionState.Initializing -> Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(14.dp))
                        Text("جارٍ التحقق")
                    }
                }
            }
            is AuthSessionState.Authenticated -> content()
            else -> CloudLoginScreen(
                isBusy = busy,
                initialError = error ?: (state as? AuthSessionState.Unauthenticated)?.message.takeUnless { dismissed },
                statusMessage = status,
                onLogin = viewModel::login,
                onInputsChanged = viewModel::clearMessages,
                onRetry = viewModel::retryInitialization
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CloudLoginScreen(
    isBusy: Boolean,
    initialError: String?,
    statusMessage: String?,
    onLogin: (String, String) -> Unit,
    onInputsChanged: () -> Unit,
    onRetry: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    ProtectAuthWindow()
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val logoProgress by animateFloatAsState(if (entered) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow), label = "LoginLogoProgress")
    val textProgress by animateFloatAsState(if (entered) 1f else 0f,
        tween(520, 120, FastOutSlowInEasing), label = "LoginTextProgress")
    val formProgress by animateFloatAsState(if (entered) 1f else 0f,
        tween(560, 240, FastOutSlowInEasing), label = "LoginFormProgress")
    val breathing = rememberInfiniteTransition(label = "LoginLogoBreathing")
    val breathScale by breathing.animateFloat(1f, 1.035f,
        infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "LoginLogoScale")
    val canSubmit = !isBusy && AuthInteractionRules.inputError(email, password) == null
    val submit = {
        if (canSubmit) onLogin(email, password)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(92.dp).graphicsLayer {
                    alpha = logoProgress.coerceIn(0f, 1f)
                    scaleX = (0.8f + 0.2f * logoProgress) * breathScale; scaleY = scaleX
                }.shadow(10.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp))
                    .background(colorResource(R.color.launcher_background)), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(20.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer {
                    alpha = textProgress; translationY = (1f - textProgress) * 18f
                }) {
                    Text("UniHub", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("تسجيل الدخول", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(28.dp))
                Column(Modifier.fillMaxWidth().graphicsLayer {
                    alpha = formProgress; translationY = (1f - formProgress) * 28f
                }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { if (it.length <= CloudAuthRules.MAX_USERNAME_LENGTH) { email = it; onInputsChanged() } },
                        modifier = Modifier.fillMaxWidth().testTag("auth.username").authAutofill(AutofillType.EmailAddress) {
                            if (it.length <= CloudAuthRules.MAX_USERNAME_LENGTH) { email = it; onInputsChanged() }
                        },
                        label = { Text("البريد الإلكتروني") }, leadingIcon = { Icon(Icons.Filled.Person, null) },
                        singleLine = true, enabled = !isBusy, shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { if (it.length <= CloudAuthRules.MAX_PASSWORD_LENGTH) { password = it; onInputsChanged() } },
                        modifier = Modifier.fillMaxWidth().testTag("auth.password").authAutofill(AutofillType.Password) {
                            if (it.length <= CloudAuthRules.MAX_PASSWORD_LENGTH) { password = it; onInputsChanged() }
                        },
                        label = { Text("كلمة المرور") }, leadingIcon = { Icon(Icons.Filled.Lock, null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Crossfade(passwordVisible, label = "PasswordVisibilityIcon") { visible ->
                                    Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        if (visible) "إخفاء كلمة المرور" else "إظهار كلمة المرور")
                                }
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true, enabled = !isBusy, shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() })
                    )
                    AnimatedVisibility(!initialError.isNullOrBlank()) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
                            Text(initialError.orEmpty(), modifier = Modifier.fillMaxWidth().padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    AnimatedVisibility(!statusMessage.isNullOrBlank()) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
                            Text(statusMessage.orEmpty(), modifier = Modifier.fillMaxWidth().padding(12.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(onClick = submit, enabled = canSubmit, shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.testTag("auth.login").fillMaxWidth().heightIn(min = 54.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            if (isBusy) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(if (isBusy) "جارٍ التحقق…" else "تسجيل الدخول", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Text(
                        "لإعداد أول حساب مشرف، ينشئه مالك المشروع من لوحة Supabase ثم يمكنك تسجيل الدخول.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    TextButton(onClick = onRetry, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) { Text("إعادة فحص الجلسة") }
                }
            }
        }
    }
}
