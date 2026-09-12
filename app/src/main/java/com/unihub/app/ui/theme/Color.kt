package com.unihub.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * نظام ألوان هادئ مريح للعين — أخضر مائل للرمادي (Sage) كلون أساسي مع محايدات
 * دافئة غير مشبعة. لا أبيض ناصع ولا أسود فاحم: خلفيات ورقية ناعمة في الوضع
 * الفاتح ورمادي مخضرّ عميق في الداكن. هذا استبدال مقصود للبنفسجي المشبع
 * في التطبيق المرجعي الذي يسبب إجهاداً بصرياً مع القراءة الطويلة.
 */

// ====== الأخضر الأساسي (Sage/Teal) ======
val Sage = Color(0xFF3E6B5E)
val SageOn = Color(0xFFFFFFFF)
val SageContainer = Color(0xFFD8E8DF)
val SageOnContainer = Color(0xFF14352A)

val MintSoft = Color(0xFFA3CDBB)
val MintOnSoft = Color(0xFF123A2E)
val MintContainerDark = Color(0xFF2C5245)

// ====== الثانوي: بني رملي دافئ ======
val Sand = Color(0xFF7A6A4F)
val SandContainer = Color(0xFFEBDFC9)
val SandOnContainer = Color(0xFF463D2A)

val SandSoft = Color(0xFFCDB894)
val SandOnSoft = Color(0xFF3A3324)
val SandContainerDark = Color(0xFF4C4433)

// ====== الثالثي: أزرق رمادي هادئ ======
val Slate = Color(0xFF5B6478)
val SlateContainer = Color(0xFFDEE3EE)
val SlateOnContainer = Color(0xFF2A3242)

val SlateSoft = Color(0xFFB4BDD3)
val SlateOnSoft = Color(0xFF2F3748)
val SlateContainerDark = Color(0xFF454D60)

// ====== المحايدات الفاتحة (ورقية دافئة) ======
val PaperBackground = Color(0xFFF5F3ED)
val PaperSurface = Color(0xFFFBFAF6)
val PaperSurfaceVariant = Color(0xFFEAE7DE)
val InkOnPaper = Color(0xFF232B27)
val InkMuted = Color(0xFF55605A)
val PaperOutline = Color(0xFFB9C2BB)

// ====== المحايدات الداكنة (رمادي مخضرّ عميق) ======
val NightBackground = Color(0xFF141816)
val NightSurface = Color(0xFF1B211E)
val NightSurfaceVariant = Color(0xFF2A312D)
val LightOnNight = Color(0xFFE0E5E1)
val LightMuted = Color(0xFFA9B4AD)
val NightOutline = Color(0xFF747F78)

// ====== ألوان الخطأ ======
val ClayError = Color(0xFFB05243)
val ClayErrorContainer = Color(0xFFF6DAD4)
val ClayOnErrorContainer = Color(0xFF4A1F15)

val ClayErrorDark = Color(0xFFE5A094)
val ClayErrorContainerDark = Color(0xFF6B332A)

// ====== ألوان دلالية هادئة (أولويات، حالات) ======
val SemanticSuccess = Color(0xFF4C8B6E)
val SemanticWarning = Color(0xFFC79A4B)
val SemanticDanger = Color(0xFFC25E52)
val SemanticInfo = Color(0xFF5B7FA6)

// ====== لوحة ألوان المجلدات (هادئة ومتمايزة) ======
val FolderPalette = listOf(
    "#4E7D6E", // أخضر بحيري
    "#7A6A4F", // بني رملي
    "#5B6478", // أزرق رمادي
    "#8A5E5E", // طوبي
    "#5E7A8A", // أزرق ضبابي
    "#6E7A4E", // زيتوني
    "#7D5A7A", // بنفسجي رمادي
    "#46707D"  // أخضر مزرق
)

/** تحويل لون سداسي إلى Compose Color بأمان */
fun String.toComposeColor(fallback: Color = Sage): Color =
    runCatching {
        val hex = removePrefix("#")
        Color(("FF$hex").toLong(16))
    }.getOrDefault(fallback)
