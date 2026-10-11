package com.unihub.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import com.unihub.app.feature.files.EmphasizedDecelerate

/*
 * انتقالات وجهة الملفات (FilesRoute) فقط — بقية الوجهات تبقى على الانتقال الافتراضي.
 *
 * منطق الاتجاه: SlideDirection.Start/End تتبع اتجاه الواجهة، فالدخول لمجلد في RTL
 * يأتي من اليسار (جهة النهاية) ويرجع إليها، وفي LTR العكس، بلا أي معالجة يدوية.
 *
 * مجلد ← مجلد (الطرفان FilesRoute):
 *   دخول  : المجلد الجديد ينزلق كاملاً مع تلاشٍ، والسابق يتراجع ربعاً ويخفت ويصغر (عمق).
 *   رجوع  : السابق يعود من الربع الأول مكبّراً قليلاً، والحالي ينزلق للخارج ويخفت.
 * من/إلى وجهة أخرى (الرئيسية، الكاميرا، السحابة…):
 *   دخول الملفات = تلاشٍ + تكبير من 0.92 + صعود خفيف؛ الخروج يُترك للافتراضي كي
 *   لا ينكشف خلفية بين تلاشي سريع وتلاشي افتراضي بطيء للوجهة الأخرى.
 */

private const val FOLDER_SLIDE_MS = 420
private const val FOLDER_FADE_MS = 320

private fun NavBackStackEntry.isFiles(): Boolean = destination.hasRoute(FilesRoute::class)

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.filesEnter(): EnterTransition? =
    if (initialState.isFiles()) {
        slideIntoContainer(
            towards = SlideDirection.Start,
            animationSpec = tween(FOLDER_SLIDE_MS, easing = EmphasizedDecelerate)
        ) + fadeIn(tween(FOLDER_FADE_MS, easing = EmphasizedDecelerate))
    } else {
        fadeIn(tween(340, easing = EmphasizedDecelerate)) +
            scaleIn(initialScale = 0.92f, animationSpec = tween(420, easing = EmphasizedDecelerate)) +
            slideInVertically(tween(420, easing = EmphasizedDecelerate)) { it / 14 }
    }

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.filesExit(): ExitTransition? =
    if (targetState.isFiles()) {
        slideOutOfContainer(
            towards = SlideDirection.Start,
            animationSpec = tween(FOLDER_SLIDE_MS, easing = EmphasizedDecelerate),
            targetOffset = { it / 4 }
        ) + fadeOut(tween(FOLDER_FADE_MS)) +
            scaleOut(targetScale = 0.94f, animationSpec = tween(FOLDER_SLIDE_MS, easing = EmphasizedDecelerate))
    } else {
        null // الافتراضي: يتناغم مع تلاشي دخول الوجهة التالية
    }

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.filesPopEnter(): EnterTransition? =
    if (initialState.isFiles()) {
        slideIntoContainer(
            towards = SlideDirection.End,
            animationSpec = tween(FOLDER_SLIDE_MS, easing = EmphasizedDecelerate),
            initialOffset = { it / 4 }
        ) + fadeIn(tween(FOLDER_FADE_MS, easing = EmphasizedDecelerate)) +
            scaleIn(initialScale = 0.94f, animationSpec = tween(FOLDER_SLIDE_MS, easing = EmphasizedDecelerate))
    } else {
        // الرجوع إلى الملفات من الكاميرا/السحابة/غيرهما
        fadeIn(tween(320, easing = EmphasizedDecelerate)) +
            scaleIn(initialScale = 0.94f, animationSpec = tween(380, easing = EmphasizedDecelerate))
    }

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.filesPopExit(): ExitTransition? =
    if (targetState.isFiles()) {
        slideOutOfContainer(
            towards = SlideDirection.End,
            animationSpec = tween(FOLDER_SLIDE_MS, easing = EmphasizedDecelerate)
        ) + fadeOut(tween(FOLDER_FADE_MS))
    } else {
        null
    }
