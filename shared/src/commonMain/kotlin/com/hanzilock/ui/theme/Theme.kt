package com.hanzilock.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFFB3261E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410001),
    secondary = Color(0xFF8A5A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDB0),
    onSecondaryContainer = Color(0xFF2C1800),
    tertiary = Color(0xFF3B6939),
    tertiaryContainer = Color(0xFFBCF0B4),
    onTertiaryContainer = Color(0xFF002204),
    background = Color(0xFFFFFBFA),
    surface = Color(0xFFFFFBFA),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB4AA),
    onPrimary = Color(0xFF690003),
    primaryContainer = Color(0xFF930007),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFFFBA4C),
    onSecondary = Color(0xFF482A00),
    secondaryContainer = Color(0xFF693F00),
    onSecondaryContainer = Color(0xFFFFDDB0),
    tertiary = Color(0xFFA1D39A),
    tertiaryContainer = Color(0xFF235024),
    onTertiaryContainer = Color(0xFFBCF0B4),
    background = Color(0xFF1A1111),
    surface = Color(0xFF1A1111),
)

val WinColor = Color(0xFF2E7D32)
val LossColor = Color(0xFFC62828)

/** Force Simplified-Chinese glyph shapes for Han characters, whatever the phone's language. */
val ZhLocale = LocaleList("zh-CN")

fun hanziStyle(size: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontSize = size.sp, fontWeight = weight, localeList = ZhLocale)

/** Text in a language being learned, with the right glyph forms (Japanese kanji vs Chinese hanzi). */
fun termStyle(size: Int, locale: String?, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontSize = size.sp, fontWeight = weight, localeList = LocaleList(locale ?: "zh-CN"))

/** Big display size for a word: CJK words are shown larger than long alphabetic ones. */
fun wordDisplaySize(term: String, cjk: Boolean): Int = when {
    cjk -> if (term.length > 3) 52 else 72
    term.length <= 8 -> 48
    term.length <= 14 -> 38
    else -> 30
}

@Composable
fun HanziTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
