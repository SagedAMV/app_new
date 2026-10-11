package com.unihub.app.feature.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.platform.LocalContext

/** Legacy Autofill bridge compatible with this project's Compose 1.7, without a dependency upgrade. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.authAutofill(type: AutofillType, onFill: (String) -> Unit): Modifier {
    val fill by rememberUpdatedState(onFill)
    val node = remember(type) { AutofillNode(autofillTypes = listOf(type), onFill = { fill(it) }) }
    val tree = LocalAutofillTree.current
    val autofill = LocalAutofill.current
    DisposableEffect(tree, node) {
        tree += node
        onDispose { tree.children.remove(node.id) }
    }
    return onGloballyPositioned { node.boundingBox = it.boundsInWindow() }.onFocusChanged {
        if (it.isFocused && node.boundingBox != null) autofill?.requestAutofillForNode(node)
        else autofill?.cancelAutofillForNode(node)
    }
}

@Composable
internal fun ProtectAuthWindow() {
    val window = LocalContext.current.activityOrNull()?.window
    DisposableEffect(window) {
        val alreadySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0 && window != null
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.activityOrNull()
    else -> null
}
