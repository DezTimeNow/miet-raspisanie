package com.mietschedule.app

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/**
 * Режим темы: 0 — как в системе, 1 — светлая, 2 — тёмная.
 *
 * Раньше тема была жёстко задана светлой (`lightColorScheme` в `onCreate`),
 * и выбора не существовало: читающий расписание вечером получал белый
 * фон на полной яркости.
 */
const val THEME_SYSTEM = 0
const val THEME_LIGHT = 1
const val THEME_DARK = 2

private const val PREFS_THEME = "theme_prefs"
private const val KEY_MODE = "theme_mode"

/**
 * Цвета, которых нет в [androidx.compose.material3.ColorScheme].
 *
 * Отдельный [staticCompositionLocalOf], потому что набор не меняется
 * при рекомпозиции внутри темы — значения всегда те же для текущей темы.
 */
class AppColors(
    val fav: Color,
    val onFav: Color,
    val currentGroup: Color,
    val rowGroup: Color,
    val favStar: Color,
    val starInactive: Color,
    val error: Color,
    val errorContainer: Color,
    val labBadge: Color,
    val onLabBadge: Color,
    val muted: Color,
    val dim: Color,
    val dayHeader: Color,
    val onDayHeader: Color,
) {
    companion object {
        val Light = AppColors(
            fav = ScheduleColorScheme.fav,
            onFav = ScheduleColorScheme.onFav,
            currentGroup = ScheduleColorScheme.currentGroup,
            rowGroup = ScheduleColorScheme.rowGroup,
            favStar = ScheduleColorScheme.favStar,
            starInactive = ScheduleColorScheme.starInactive,
            error = ScheduleColorScheme.error,
            errorContainer = ScheduleColorScheme.errorContainer,
            labBadge = ScheduleColorScheme.labBadge,
            onLabBadge = ScheduleColorScheme.onLabBadge,
            muted = ScheduleColorScheme.muted,
            dim = ScheduleColorScheme.dim,
            dayHeader = ScheduleColorScheme.dayHeader,
            onDayHeader = ScheduleColorScheme.onDayHeader,
        )

        val Dark = AppColors(
            fav = ScheduleColorScheme.darkFav,
            onFav = ScheduleColorScheme.darkOnFav,
            currentGroup = ScheduleColorScheme.darkCurrentGroup,
            rowGroup = ScheduleColorScheme.darkRowGroup,
            favStar = ScheduleColorScheme.darkFavStar,
            starInactive = ScheduleColorScheme.darkStarInactive,
            error = ScheduleColorScheme.darkError,
            errorContainer = ScheduleColorScheme.darkErrorContainer,
            labBadge = ScheduleColorScheme.darkLabBadge,
            onLabBadge = ScheduleColorScheme.darkOnLabBadge,
            muted = ScheduleColorScheme.darkMuted,
            dim = ScheduleColorScheme.darkDim,
            dayHeader = ScheduleColorScheme.darkDayHeader,
            onDayHeader = ScheduleColorScheme.darkOnDayHeader,
        )
    }
}

val LocalAppColors = staticCompositionLocalOf { AppColors.Light }

/**
 * Типографика приложения.
 *
 * Одна шкала вместо захардкоженных `fontSize = N.sp` в каждом экране:
 * размер текста теперь свойство темы, а не случайное число в разметке.
 * Минимум подписи — 12sp: размер 11, которым были подписаны чипы пар и
 * даты, Material3 считает нижней границей читаемости.
 */
private val MietTypography = Typography(
    // Заголовок экрана в шапке — самый крупный текст.
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    // Подзаголовок под названием экрана (счётчик, роль, дата).
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    // Заголовок секции («Избранное:», «Пары на сегодня»).
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    // Основной текст: название пары, имя преподавателя, аудитория.
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    // Текст по умолчанию: подписи, значения, описания.
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    // Мелкие подписи: счётчики, время, второстепенное.
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    // Надписи на кнопках и чипах.
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

/**
 * Форма: более скруглённые углы вместо дефолтных Material3.
 *
 * 2.5D — это мягкость и глубина. Скругление 16-20dp на карточках и
 * кнопках читается объёмнее, чем резкие 4-8dp по умолчанию: угол
 * ловит свет, и поверхность выглядит приподнятой.
 */
private val MietShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val LightScheme = lightColorScheme(
    primary = ScheduleColorScheme.primary,
    onPrimary = ScheduleColorScheme.onPrimary,
    primaryContainer = ScheduleColorScheme.primaryContainer,
    onPrimaryContainer = ScheduleColorScheme.onPrimaryContainer,
    secondary = ScheduleColorScheme.secondary,
    onSecondary = ScheduleColorScheme.onSecondary,
    surface = ScheduleColorScheme.surface,
    onSurface = ScheduleColorScheme.onSurface,
    surfaceVariant = ScheduleColorScheme.surfaceVariant,
    onSurfaceVariant = ScheduleColorScheme.onSurfaceVariant,
    outline = ScheduleColorScheme.outline,
    background = ScheduleColorScheme.background,
    onBackground = ScheduleColorScheme.onSurface,
    error = ScheduleColorScheme.error,
)

private val DarkScheme = darkColorScheme(
    primary = ScheduleColorScheme.darkPrimary,
    onPrimary = ScheduleColorScheme.darkOnPrimary,
    primaryContainer = ScheduleColorScheme.darkPrimaryContainer,
    onPrimaryContainer = ScheduleColorScheme.darkOnPrimaryContainer,
    secondary = ScheduleColorScheme.darkSecondary,
    onSecondary = ScheduleColorScheme.darkOnSecondary,
    surface = ScheduleColorScheme.darkSurface,
    onSurface = ScheduleColorScheme.darkOnSurface,
    surfaceVariant = ScheduleColorScheme.darkSurfaceVariant,
    onSurfaceVariant = ScheduleColorScheme.darkOnSurfaceVariant,
    outline = ScheduleColorScheme.darkOutline,
    background = ScheduleColorScheme.darkBackground,
    onBackground = ScheduleColorScheme.darkOnSurface,
    error = ScheduleColorScheme.darkError,
)

/**
 * Тема приложения.
 *
 * [themeMode] — сохранённый выбор пользователя; при [THEME_SYSTEM] берётся
 * настройка системы. Экран переключения вызывает [saveThemeMode] и меняет
 * состояние, тема пересобирается целиком.
 */
@Composable
fun ScheduleTheme(
    themeMode: Int,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        THEME_DARK -> true
        THEME_LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkScheme else LightScheme
    val appColors = if (dark) AppColors.Dark else AppColors.Light

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = colors,
            typography = MietTypography,
            shapes = MietShapes,
            content = content,
        )
    }
}

// ── Хранение выбора ───────────────────────────────────────────────────

fun loadThemeMode(ctx: Context): Int =
    ctx.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE)
        .getInt(KEY_MODE, THEME_SYSTEM)

fun saveThemeMode(ctx: Context, mode: Int) {
    ctx.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE)
        .edit()
        .putInt(KEY_MODE, mode)
        .apply()
}

/** Читаемое название режима для переключателя. */
fun themeModeLabel(mode: Int): String = when (mode) {
    THEME_LIGHT -> "Светлая"
    THEME_DARK -> "Тёмная"
    else -> "Как в системе"
}