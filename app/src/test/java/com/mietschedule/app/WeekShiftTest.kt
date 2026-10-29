package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты кнопок «‹ неделя ›» в шапке расписания.
 *
 * Кнопка листает УЧЕБНУЮ неделю, а не календарную: тип недели ходит по кругу
 * 0..3 (1-й числитель, 1-й знаменатель, 2-й числитель, 2-й знаменатель),
 * поэтому «следующая неделя» — это +1 по модулю 4.
 */
class WeekShiftTest {

    /**
     * Сдвиг недели обязан ходить по кругу 0..3 при ЛЮБОМ текущем типе.
     * Раньше считалось вручную по календарной неделе, и на переходе
     * «2-й знаменатель → 1-й числитель» получался бы тип 4, которого
     * в расписании не существует: `DayNumber` у сервера только 0..3,
     * и фильтр `dayNumber == 4` отсекал бы ВСЕ пары — экран пустел.
     */
    @Test
    fun `сдвиг всегда в диапазоне DayNumber 0-3`() {
        for (current in 0..3) {
            for (offset in -8..8) {
                val row = WeekType.shiftedRow(offset, SEM)
                assertTrue(
                    "сдвиг $offset при типе $current дал $row — вне 0..3",
                    row in 0..3
                )
            }
        }
    }

    /** Четыре шага вперёд = полный круг = исходная неделя. */
    @Test
    fun `четыре шага вперед возвращают исходную неделю`() {
        for (current in 0..3) {
            val from = WeekType.name(current)
            val after4 = WeekType.name(WeekType.shiftedRow(4, startFor(current)))
            assertEquals("4 шага вперёд из «$from» вернули «$after4»", from, after4)
        }
    }

    /** Один шаг вперёд из текущей недели даёт именно следующую учебную. */
    @Test
    fun `один шаг вперед дает следующую учебную неделю`() {
        for (current in 0..3) {
            val expected = WeekType.name((current + 1) % 4)
            assertEquals(
                "из «${WeekType.name(current)}» шаг вперёд",
                expected,
                WeekType.shiftedName(1, startFor(current))
            )
        }
    }

    /** Один шаг назад из «1-й числитель» — это «2-й знаменатель», а не минус один. */
    @Test
    fun `шаг назад из нулевой недели не дает отрицательный тип`() {
        assertEquals(
            "2-й знаменатель",
            WeekType.shiftedName(-1, startFor(0))
        )
    }

    /**
     * Кнопка «к текущей неделе» (сдвиг 0) обязана вернуть актуальную неделю.
     * Сдвиг ±4 — полный круг, он тоже даёт текущую; шаг ±1 — соседнюю.
     */
    @Test
    fun `сдвиг ноль всегда дает текущую неделю`() {
        for (offset in listOf(0, 4, -4)) {
            assertEquals(
                "при сдвиге $offset",
                WeekType.currentName(SEM),
                WeekType.shiftedName(offset, SEM)
            )
        }
    }

    /** Подпись сдвинутой недели всегда непустая и известная. */
    @Test
    fun `подпись сдвинутой недели всегда заполнена`() {
        for (offset in -4..4) {
            val n = WeekType.shiftedName(offset, SEM)
            assertTrue("пустая подпись при сдвиге $offset", n.isNotBlank())
            assertTrue(
                "неизвестная неделя «$n» при сдвиге $offset",
                n in setOf("1-й числитель", "1-й знаменатель", "2-й числитель", "2-й знаменатель")
            )
        }
    }

    /**
     * Сдвиг назад на 4 шага = 0 шагов: кнопки «‹» не должны уводить в
     * бесконечное «минус четыре», из которого нельзя вернуться по кругу.
     */
    @Test
    fun `сдвиг назад на четыре шага эквивалентен нулю`() {
        val cur = WeekType.current(SEM)
        assertEquals(cur, WeekType.shifted(-4, SEM))
        assertEquals(cur, WeekType.shifted(0, SEM))
    }

    /** Сдвиг не меняет саму текущую неделю — он производный, а не мутация. */
    @Test
    fun `вызов shifted не меняет текущую неделю`() {
        val before = WeekType.current(SEM)
        WeekType.shiftedRow(3, SEM)
        WeekType.shiftedName(-2, SEM)
        assertEquals(before, WeekType.current(SEM))
    }

    // ── вспомогательное ──

    /**
     * Начало семестра, при котором текущая неделя равна [want].
     *
     * Считаем от понедельника ТЕКУЩЕЙ недели назад, а не от захардкоженной
     * даты. [WeekType.current] внутри читает `today()`, поэтому «текущая
     * неделя» всегда привязана к реальному сегодня — и подставлять дату,
     * посчитанную от 2026-08-04, можно было только пока сегодня была
     * «1-й числитель».
     *
     * На 5 октября 2026 (понедельник, 9-я неделя семестра) этот расчёт
     * расходился для всех четырёх вариантов: `current()` давал 1, а не 0, и
     * проверки «шаг вперёд из 1-й числитель» падали. Тест зависел от даты
     * запуска и был зелёным только в первую учебную неделю семестра.
     *
     * Порядок именно такой: сначала понедельник этой недели, потом typeFor
     * сдвигает начало семестра назад на [want] недель — тогда текущая неделя
     * всегда получается ровно [want], независимо от даты.
     */
    private fun startFor(want: Int): String {
        val monday = WeekType.parseIso("2000-01-03")!! // ПН
        monday.timeInMillis = WeekType.today().timeInMillis
        monday.set(java.util.Calendar.HOUR_OF_DAY, 0)
        monday.set(java.util.Calendar.MINUTE, 0)
        monday.set(java.util.Calendar.SECOND, 0)
        monday.set(java.util.Calendar.MILLISECOND, 0)
        val offset = (monday.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        monday.add(java.util.Calendar.DAY_OF_MONTH, -offset - want * 7)
        return String.format(
            "%04d-%02d-%02d",
            monday.get(java.util.Calendar.YEAR),
            monday.get(java.util.Calendar.MONTH) + 1,
            monday.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private companion object {
        const val SEM = "2026-08-04"
    }
}