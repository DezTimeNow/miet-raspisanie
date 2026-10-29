package com.mietschedule.app

import androidx.compose.ui.graphics.Color

/**
 * Палитра приложения: светлая и тёмная.
 *
 * Зачем объект, а не только `darkColorScheme`: в коде много прямых
 * hex-цветов (карточки групп, избранное, звёздочка, «ЛАБОРАТОРНАЯ»),
 * и `darkColorScheme` на них не действует. На тёмном фоне светлая
 * карточка оставалась бы белой пятном. Поэтому у каждого такого цвета
 * есть тёмный двойник, а экраны берут значение из [AppColors].
 *
 * Контраст тёмных вариантов проверен вручную: текст на фоне даёт не
 * меньше 4.5:1, иначе читается плохо в вечернем свете.
 */
object ScheduleColorScheme {

    // ── Светлая тема ────────────────────────────────────────────────────

    /** Фирменный синий МИЭТ. */
    val primary = Color(0xFF0057B8)
    val onPrimary = Color(0xFFFFFFFF)
    val primaryContainer = Color(0xFFDBE9FF)
    val onPrimaryContainer = Color(0xFF001B33)

    val secondary = Color(0xFF565E71)
    val onSecondary = Color(0xFFFFFFFF)

    val background = Color(0xFFF5F7FA)
    val surface = Color(0xFFFFFFFF)
    val onSurface = Color(0xFF1A1C1A)
    val surfaceVariant = Color(0xFFE3E8EF)
    val onSurfaceVariant = Color(0xFF44474A)
    val outline = Color(0xFF73777F)

    /** Карточка избранной группы. */
    val fav = Color(0xFFFFECB3)
    val onFav = Color(0xFF3E2723)

    /** Подсветка текущей выбранной группы. */
    val currentGroup = Color(0xFFBBDEFB)
    val rowGroup = Color(0xFFE3F2FD)

    /** Звёздочка избранного. */
    val favStar = Color(0xFFE65100)
    val starInactive = Color(0xFFBDBDBD)

    /** Ошибка и подсветка поля с ошибкой. */
    val error = Color(0xFFC62828)
    val errorContainer = Color(0xFFFFEBEE)

    /** Плашка «ЛАБОРАТОРНАЯ» на карточке пары. */
    val labBadge = Color(0xFFFFF3E0)
    val onLabBadge = Color(0xFFE65100)

    /** Приглушённый текст: подписи, счётчики, время. */
    val muted = Color(0xFF6B7280)
    val dim = Color(0xFF555555)

    /** Полоса дней недели в недельном виде. */
    val dayHeader = Color(0xFFE3F2FD)
    val onDayHeader = Color(0xFF1A1A1A)

    // ── Тёмная тема ─────────────────────────────────────────────────────

    // Синий приходится осветлять: #0057B8 на чёрном фоне заметно темнее
    // по контрасту, чем тот же синий на белом.
    val darkPrimary = Color(0xFF9FC7FF)
    val darkOnPrimary = Color(0xFF00325B)
    val darkPrimaryContainer = Color(0xFF004880)
    val darkOnPrimaryContainer = Color(0xFFD6E3FF)

    val darkSecondary = Color(0xFFBEC6DC)
    val darkOnSecondary = Color(0xFF283041)

    val darkBackground = Color(0xFF101418)
    val darkSurface = Color(0xFF191C20)
    val darkOnSurface = Color(0xFFE2E2E6)
    val darkSurfaceVariant = Color(0xFF262A30)
    val darkOnSurfaceVariant = Color(0xFFC2C7CF)
    val darkOutline = Color(0xFF8C9199)

    /** Избранное в тёмной теме — приглушённый янтарный: чистый жёлтый
     *  на чёрном слепит. */
    val darkFav = Color(0xFF4A3A1E)
    val darkOnFav = Color(0xFFFFE0A3)

    val darkCurrentGroup = Color(0xFF1B3A57)

    val darkRowGroup = Color(0xFF152230)

    val darkFavStar = Color(0xFFFFB77C)
    val darkStarInactive = Color(0xFF5A5F66)

    val darkError = Color(0xFFFF8A80)
    val darkErrorContainer = Color(0xFF3E1D1D)

    val darkLabBadge = Color(0xFF3A2A18)
    val darkOnLabBadge = Color(0xFFFFB77C)

    val darkMuted = Color(0xFF9AA0A6)
    val darkDim = Color(0xFFB9BFC7)

    val darkDayHeader = Color(0xFF1B2A38)
    val darkOnDayHeader = Color(0xFFDCE6F2)
}