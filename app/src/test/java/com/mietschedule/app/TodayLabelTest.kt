package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

/**
 * Подпись сегодняшнего дня на кнопке фильтра.
 *
 * НАЙДЕННАЯ ОШИБКА. Кнопка показывала «Вс (выходной)» по понедельникам.
 * Причина — путаница шкал: `dayIndexFromCalendar` возвращает индекс на
 * шкале 0 = понедельник … 6 = воскресенье (`(cw + 5) % 7`), а проверка
 * воскресенья искала `todayDay == 0`, то есть ПОНЕДЕЛЬНИК.
 *
 * Ошибка срабатывала ровно раз в неделю — по понедельникам, — поэтому её
 * легко было пропустить: в остальные шесть дней подпись была верной.
 */
class TodayLabelTest {

    private val mainDir = File("src/main/java/com/mietschedule/app")

    private fun src(name: String): String = File(mainDir, name).readText()

    /** Какой именно день считается выходным в исходнике. */
    private fun выходнойИндекс(): Int {
        val s = src("ScheduleUi.kt")
        val m = Regex("""todayDay\s*==\s*(\d+)\s*->\s*"Вс \(выходной\)"""").find(s)
        requireNotNull(m) { "Не найдена ветка «Вс (выходной)»" }
        return m.groupValues[1].toInt()
    }

    @Test
    fun `выходной это воскресенье а не понедельник`() {
        assertEquals(
            "Воскресенье на шкале приложения имеет индекс 6, а 0 — это понедельник",
            6, выходнойИндекс(),
        )
    }

    @Test
    fun `пустой экран про выходной тоже сравнивает с воскресеньем`() {
        val s = src("ScheduleUi.kt")
        val m = Regex("""todayDay\s*==\s*(\d+)\s*->\s*"Воскресенье — выходной"""").find(s)
        assertTrue("Не найдена ветка «Воскресенье — выходной»", m != null)
        assertEquals(
            "Второе место с той же ошибкой: пустой экран искал воскресенье " +
                "под индексом 0, то есть под понедельником",
            6, m!!.groupValues[1].toInt(),
        )
    }

    @Test
    fun `в коде нет сравнений с индексом 0 где речь о выходном`() {
        val s = src("ScheduleUi.kt")
        // Любая проверка `todayDay == 0` в КОДЕ ошибочна: 0 — понедельник.
        // Комментарии вырезаем, иначе ловим собственное пояснение правки.
        val код = s.lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        val ноль = Regex("""todayDay\s*==\s*0\b""").findAll(код).count()
        assertEquals(
            "Осталась проверка todayDay == 0: ноль — это понедельник, " +
                "а не воскресенье",
            0, ноль,
        )
    }

    @Test
    fun `подпись сегодняшнего дня совпадает с названием дня`() {
        // Сквозная проверка: для каждого дня недели подпись должна называть
        // именно тот день, который сегодня. Раньше в понедельник подпись
        // называла воскресенье.
        val выходной = выходнойИндекс()
        val дни = mapOf(
            Calendar.MONDAY to "Пн",
            Calendar.TUESDAY to "Вт",
            Calendar.WEDNESDAY to "Ср",
            Calendar.THURSDAY to "Чт",
            Calendar.FRIDAY to "Пт",
            Calendar.SATURDAY to "Сб",
            Calendar.SUNDAY to "Вс",
        )
        for ((calendarDay, название) in дни) {
            val idx = dayIndexFromCalendar(calendarDay)
            assertEquals("Неверный индекс дня $название", название, DAY_SHORT[idx])
            // Выходным помечается ровно один день недели — воскресенье.
            // Если под признание выходным попал не только он, это ошибка.
            val фактически = if (idx == выходной) название else "не выходной"
            val ожидается = if (calendarDay == Calendar.SUNDAY) "Вс" else "не выходной"
            assertEquals(
                "День $название (индекс $idx) определён неверно: $фактически",
                ожидается, фактически,
            )
        }
    }
}