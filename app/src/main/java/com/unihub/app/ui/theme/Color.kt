package com.unihub.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * نظام UniHub التعبيري: أخضر زمردي واضح للهوية، طيني دافئ للإجراءات الثانوية،
 * وأزرق نيلي للتنوع البصري. تحافظ الألوان المحايدة على راحة القراءة، مع درجات
 * مستقلة للوضعين الفاتح والداكن حتى تبقى المكوّنات قابلة للقراءة في كليهما.
 */

// ====== الأساسي: أخضر زمردي ======
val Sage = Color(0xFF286B57)
val SageOn = Color(0xFFFFFFFF)
val SageContainer = Color(0xFFD6F3E5)
val SageOnContainer = Color(0xFF123B2D)

val MintSoft = Color(0xFFA7E5C8)
val MintOnSoft = Color(0xFF123D2E)
val MintContainerDark = Color(0xFF245642)

// ====== الثانوي: طيني/مرجاني دافئ ======
val Sand = Color(0xFFAE4F3B)
val SandContainer = Color(0xFFF9DED5)
val SandOnContainer = Color(0xFF5C2419)

val SandSoft = Color(0xFFFFB8A4)
val SandOnSoft = Color(0xFF53251D)
val SandContainerDark = Color(0xFF67362B)

// ====== الثالثي: نيلي واضح ======
val Slate = Color(0xFF5362A7)
val SlateContainer = Color(0xFFE1E5FF)
val SlateOnContainer = Color(0xFF29336B)

val SlateSoft = Color(0xFFBEC7FF)
val SlateOnSoft = Color(0xFF28326A)
val SlateContainerDark = Color(0xFF3F497C)

// ====== المحايدات الفاتحة (ورقية دافئة) ======
val PaperBackground = Color(0xFFF8F5EF)
val PaperSurface = Color(0xFFFFFCF7)
val PaperSurfaceVariant = Color(0xFFEEE8DF)
val InkOnPaper = Color(0xFF222B26)
val InkMuted = Color(0xFF535D56)
val PaperOutline = Color(0xFFB7C5BB)

// ====== المحايدات الداكنة (أخضر فحمي عميق) ======
val NightBackground = Color(0xFF111815)
val NightSurface = Color(0xFF1B2420)
val NightSurfaceVariant = Color(0xFF28332D)
val LightOnNight = Color(0xFFE3ECE6)
val LightMuted = Color(0xFFA9B8AF)
val NightOutline = Color(0xFF75857A)

// ====== ألوان الخطأ ======
val ClayError = Color(0xFFB94738)
val ClayErrorContainer = Color(0xFFFFDAD2)
val ClayOnErrorContainer = Color(0xFF5D1B13)

val ClayErrorDark = Color(0xFFFFB4A6)
val ClayErrorContainerDark = Color(0xFF762D24)

// ====== ألوان دلالية واضحة للأولويات والحالات ======
val SemanticSuccess = Color(0xFF347B57)
val SemanticWarning = Color(0xFFB98128)
val SemanticDanger = Color(0xFFB94738)
val SemanticInfo = Color(0xFF426BB5)

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
