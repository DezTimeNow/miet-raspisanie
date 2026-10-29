package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки выбора темы.
 *
 * Тема раньше была жёстко задана светлой: в `onCreate` стояло
 * `lightColorScheme`, а `isSystemInDarkTheme` в проекте не встречался
 * ни разу. Выбора не существовало вовсе, поэтому проверять тут нечего
 * было. Теперь режим хранится числом, и разбор этого числа — тоже
 * логика, которую легко сломать молча.
 */
class ThemeTest {

    @Test
    fun `константы режимов не пересекаются`() {
        // Иначе `when` по числу выберет не ту ветку и тема замрёт.
        val all = listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)
        assertEquals("режимы должны быть разными", all.size, all.toSet().size)
    }

    @Test
    fun `системный режим по умолчанию`() {
        // Пользователь, который ничего не выбирал, получает настройку
        // системы, а не светлую тему принудительно.
        assertEquals(0, THEME_SYSTEM)
    }

    @Test
    fun `светлая и тёмная темы различимы`() {
        assertNotEquals(THEME_LIGHT, THEME_DARK)
    }

    @Test
    fun `подпись режима читаема`() {
        assertEquals("Как в системе", themeModeLabel(THEME_SYSTEM))
        assertEquals("Светлая", themeModeLabel(THEME_LIGHT))
        assertEquals("Тёмная", themeModeLabel(THEME_DARK))
    }

    @Test
    fun `неизвестный режим показывается как системный`() {
        // Экран может увидеть значение из будущей версии приложения:
        // молчаливый выбор тёмной темы был бы хуже явного «как в системе».
        assertEquals("Как в системе", themeModeLabel(99))
        assertEquals("Как в системе", themeModeLabel(-1))
    }

    @Test
    fun `тёмные цвета отличаются от светлых`() {
        // Главный признак того, что тёмная тема вообще применима:
        // палитра не совпадает со светлой.
        assertNotEquals(
            ScheduleColorScheme.background,
            ScheduleColorScheme.darkBackground,
        )
        assertNotEquals(
            ScheduleColorScheme.onSurface,
            ScheduleColorScheme.darkOnSurface,
        )
        assertNotEquals(
            ScheduleColorScheme.primary,
            ScheduleColorScheme.darkPrimary,
        )
    }

    @Test
    fun `цвета карточек избранного различаются между темами`() {
        assertNotEquals(ScheduleColorScheme.fav, ScheduleColorScheme.darkFav)
        assertNotEquals(ScheduleColorScheme.currentGroup, ScheduleColorScheme.darkCurrentGroup)
        assertNotEquals(ScheduleColorScheme.favStar, ScheduleColorScheme.darkFavStar)
    }

    @Test
    fun `в тёмной теме текст светлее фона`() {
        // Регрессия: если в тёмной теме текст окажется темнее фона,
        // расписание станет нечитаемым — это хуже, чем не тёмная тема.
        val bg = ScheduleColorScheme.darkBackground
        val on = ScheduleColorScheme.darkOnSurface
        assertTrue(
            "текст должен быть светлее фона в тёмной теме",
            luminance(on) > luminance(bg),
        )
    }

    @Test
    fun `в светлой теме текст темнее фона`() {
        assertTrue(
            "текст должен быть темнее фона в светлой теме",
            luminance(ScheduleColorScheme.onSurface) < luminance(ScheduleColorScheme.background),
        )
    }

    @Test
    fun `текст на избранной карточке читается в обеих темах`() {
        assertTrue(
            luminance(ScheduleColorScheme.onFav) < luminance(ScheduleColorScheme.fav),
        )
        assertTrue(
            luminance(ScheduleColorScheme.darkOnFav) > luminance(ScheduleColorScheme.darkFav),
        )
    }

    /** Грубая яркость по стандартной формуле ITU-R BT.601. */
    private fun luminance(c: androidx.compose.ui.graphics.Color): Double =
        0.299 * c.red + 0.587 * c.green + 0.114 * c.blue
}