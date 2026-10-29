package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Список пар дня для блока на главной.
 *
 * Требование владельца: показывать не только идущую и следующую пары, а весь
 * день целиком — прошедшие приглушёнными, идущую подсвеченной и с остатком
 * времени, будущие с отсчётом до начала. Когда день закончился, показать
 * ближайший день с занятиями.
 *
 * Момент задан явно (12.08.2026, среда, 1-й знаменатель при начале семестра
 * 04.08.2026): если бы он читался из системных часов, проверка проходила бы
 * утром и падала днём.
 */
class HomeDayListTest {

    private fun atDate(y: Int, m: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, h, mi)
        }.timeInMillis

    private fun time(code: Int, from: String, to: String) =
        PairTime(time = "$code пара", code = code, timeFrom = from, timeTo = to)

    private fun lesson(day: Int, pair: Int, name: String, weekRow: Int = 1) = Lesson(
        day = day,
        dayNumber = weekRow,
        time = PairCode(time = "$pair пара", code = pair, timeFrom = null, timeTo = null),
        classInfo = ClassInfo(name = name, teacherFull = "Иванов И.И."),
    )

    private val times = listOf(
        time(1, "09:00", "10:20"),
        time(2, "10:30", "11:50"),
        time(3, "12:30", "13:50"),
        time(4, "14:00", "15:20"),
    )

    @Test
    fun `все пары дня возвращаются по порядку начала`() {
        val lessons = listOf(
            lesson(3, 4, "Четвёртая"),
            lesson(3, 1, "Первая"),
            lesson(3, 3, "Третья"),
            lesson(3, 2, "Вторая"),
        )
        val day = NextLessonLogic.dayLessons(lessons, times, atDate(2026, 8, 12, 8, 0), 0, "2026-08-04")
        assertEquals(
            listOf("Первая", "Вторая", "Третья", "Четвёртая"),
            day.map { it.lesson.classInfo?.name },
        )
    }

    @Test
    fun `состояние пары считается по времени`() {
        val lessons = listOf(
            lesson(3, 1, "Прошла"),
            lesson(3, 2, "Идёт"),
            lesson(3, 3, "Будет"),
        )
        // 11:00: первая кончилась в 10:20, вторая идёт до 11:50, третья в 12:30.
        val day = NextLessonLogic.dayLessons(lessons, times, atDate(2026, 8, 12, 11, 0), 0, "2026-08-04")
        assertEquals(
            listOf(
                NextLessonLogic.LessonState.PAST,
                NextLessonLogic.LessonState.GOING,
                NextLessonLogic.LessonState.FUTURE,
            ),
            day.map { it.state },
        )
    }

    @Test
    fun `пара чужой учебной недели в день не попадает`() {
        // Регрессия: строка недели считается для конкретного дня, и пары
        // другой недели в списке дня появляться не должны.
        val lessons = listOf(
            lesson(3, 1, "Наша", weekRow = 1),
            lesson(3, 1, "Чужая", weekRow = 0),
        )
        val day = NextLessonLogic.dayLessons(lessons, times, atDate(2026, 8, 12, 8, 0), 0, "2026-08-04")
        assertEquals(listOf("Наша"), day.map { it.lesson.classInfo?.name })
    }

    @Test
    fun `остаток времени у идущей пары`() {
        val end = atDate(2026, 8, 12, 13, 50)
        assertEquals("осталось 50 мин", remainingText(end, atDate(2026, 8, 12, 13, 0)))
        assertEquals("осталось 1 ч 5 мин", remainingText(end, atDate(2026, 8, 12, 12, 45)))
        assertEquals("осталась 1 мин", remainingText(end, atDate(2026, 8, 12, 13, 49)))
        assertEquals("заканчивается", remainingText(end, end))
    }

    @Test
    fun `без времени конца остаток не выдумывается`() {
        // У части аудиторий корпуса 8 сервер не отдаёт конец пары: подставлять
        // «примерно 90 минут» и показывать это как расписание нельзя.
        assertEquals("", remainingText(null, atDate(2026, 8, 12, 13, 0)))
    }

    @Test
    fun `подпись следующего дня`() {
        val now = atDate(2026, 8, 12, 12, 0)
        assertTrue(
            "завтрашний день подписывается словом «Завтра»",
            nextDayTitle(now, 1).startsWith("Завтра,"),
        )
        assertTrue(
            "дальний день подписывается днём недели",
            nextDayTitle(now, 5).startsWith("Понедельник"),
        )
    }

    @Test
    fun `пустой день даёт пустой список`() {
        assertTrue(
            NextLessonLogic.dayLessons(emptyList(), emptyList(), atDate(2026, 8, 12, 9, 0)).isEmpty(),
        )
    }
}
