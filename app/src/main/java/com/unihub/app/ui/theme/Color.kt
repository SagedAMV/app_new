package com.unihub.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * نظام UniHub التعبيري: أزرق بنفسجي حديث للهوية، ألوان ثانوية واضحة،
 * وأسطح محايدة لراحة القراءة. تحافظ الألوان المحايدة على راحة القراءة، مع درجات
 * مستقلة للوضعين الفاتح والداكن حتى تبقى المكوّنات قابلة للقراءة في كليهما.
 */

// ====== الأساسي: أخضر زمردي ======
val Sage = Color(0xFF5364F5)
val SageOn = Color(0xFFFFFFFF)
val SageContainer = Color(0xFFE4E8FF)
val SageOnContainer = Color(0xFF202D78)

val MintSoft = Color(0xFFB9C2FF)
val MintOnSoft = Color(0xFF202A70)
val MintContainerDark = Color(0xFF303E91)

// ====== الثانوي: طيني/مرجاني دافئ ======
val Sand = Color(0xFF8B5CF6)
val SandContainer = Color(0xFFF0E7FF)
val SandOnContainer = Color(0xFF4A267C)

val SandSoft = Color(0xFFD5BFFF)
val SandOnSoft = Color(0xFF402060)
val SandContainerDark = Color(0xFF49346F)

// ====== الثالثي: نيلي واضح ======
val Slate = Color(0xFF3978E8)
val SlateContainer = Color(0xFFE0EDFF)
val SlateOnContainer = Color(0xFF153B7A)

val SlateSoft = Color(0xFFB9D2FF)
val SlateOnSoft = Color(0xFF173A72)
val SlateContainerDark = Color(0xFF284B85)

// ====== المحايدات الفاتحة (ورقية دافئة) ======
val PaperBackground = Color(0xFFF6F7FF)
val PaperSurface = Color(0xFFFFFFFF)
val PaperSurfaceVariant = Color(0xFFEEF0FF)
val InkOnPaper = Color(0xFF171B32)
val InkMuted = Color(0xFF59617D)
val PaperOutline = Color(0xFFD8DDF0)

// ====== المحايدات الداكنة (أخضر فحمي عميق) ======
val NightBackground = Color(0xFF0C1020)
val NightSurface = Color(0xFF151B30)
val NightSurfaceVariant = Color(0xFF222B49)
val LightOnNight = Color(0xFFF0F2FF)
val LightMuted = Color(0xFFB7C0E0)
val NightOutline = Color(0xFF68749D)

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
    "#5364F5", // أزرق بنفسجي
    "#8B5CF6", // بنفسجي
    "#3978E8", // أزرق
    "#E85D75", // مرجاني
    "#16B8A6", // تركوازي
    "#F5A524", // كهرماني
    "#A78BFA", // بنفسجي فاتح
    "#64748B"  // رمادي مزرق
)

/** تحويل لون سداسي إلى Compose Color بأمان */
fun String.toComposeColor(fallback: Color = Sage): Color =
    runCatching {
        val hex = removePrefix("#")
        Color(("FF$hex").toLong(16))
    }.getOrDefault(fallback)
