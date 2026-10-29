package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * Проверки «сейчас» для фильтра свободных аудиторий.
 *
 * Здесь важнее всего не «посчиталось ли верно», а не ломается ли сравнение
 * часов: строками «09:00» против «10:00» даёт 09:00 < 10:00 текстово, что
 * совпадает с правдой, а вот «9:00» против «10:00» — уже наоборот. Таблица
 * времени в приложении приходит с обоими вариантами записи, поэтому
 * сравнение обязано идти через число минут.
 */
class NowLogicTest {

    /** Таблица времени как её отдаёт сервер: код, начало, конец. */
    private fun times(vararg rows: Triple<Int, String, String>): List<PairTime> =
        rows.map { (c, f, t) ->
            PairTime(code = c, time = "$f-$t", timeFrom = "2026-09-01T$f:00", timeTo = "2026-09-01T$t:00")
        }

    /** Момент времени сегодняшнего дня с указанными часами и минутами. */
    private fun at(h: Int, m: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, h)
        set(Calendar.MINUTE, m)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Стандартная сетка МИЭТ: шесть пар. */
    private val grid = times(
        Triple(1, "08:00", "09:30"),
        Triple(2, "09:40", "11:10"),
        Triple(3, "11:20", "12:50"),
        Triple(4, "13:20", "14:50"),
        Triple(5, "15:00", "16:30"),
        Triple(6, "16:40", "18:10"),
    )

    @Test
    fun `восемь тридцать идёт первая пара`() {
        assertEquals(1, NowLogic.currentPairCode(NowLogic.windowTable(grid), at(8, 30)))
    }

    @Test
    fun `в начале второй пары идёт вторая а не первая`() {
        // Граница включительно слева: в 09:40 первая пара уже закончилась.
        // Если бы сравнение было «до конца включительно», комната считалась бы
        // занятой ещё минуту после её конца.
        assertEquals(2, NowLogic.currentPairCode(NowLogic.windowTable(grid), at(9, 40)))
    }

    @Test
    fun `в конце пятой пары считается что пара идёт`() {
        // Пятая пара идёт 15:00–16:30, поэтому 16:29 — это всё ещё пятая.
        assertEquals(5, NowLogic.currentPairCode(NowLogic.windowTable(grid), at(16, 29)))
    }

    @Test
    fun `в момент окончания идёт шестая пара`() {
        // 16:40 — начало шестой. Окончание пятой (16:30) и десять минут
        // перерыва: пара 6 уже началась, значит смотрим на неё.
        assertEquals(6, NowLogic.currentPairCode(NowLogic.windowTable(grid), at(16, 40)))
    }

    @Test
    fun `после последней пары вечером пара не определяется`() {
        // Шестая кончилась в 18:10. Дальше до завтрашней первой — больше
        // MAX_BREAK_MINUTES, поэтому пара не определяется вовсе.
        assertNull(NowLogic.currentPairCode(NowLogic.windowTable(grid), at(21, 0)))
    }

    @Test
    fun `в перерыве берётся следующая пара`() {
        // 11:15 — между второй (до 11:10) и третьей (с 11:20). Если бы бралась
        // никакая пара, фильтр показал бы все 188 аудиторий свободными, а
        // человек через пять минут занял бы забытую комнату.
        assertEquals(3, NowLogic.currentPairCode(NowLogic.windowTable(grid), at(11, 15)))
    }

    @Test
    fun `ночью пара не определяется`() {
        assertNull(NowLogic.currentPairCode(NowLogic.windowTable(grid), at(3, 0)))
    }

    @Test
    fun `пустая таблица не ломает расчёт`() {
        assertNull(NowLogic.currentPairCode(emptyMap(), at(10, 0)))
    }

    @Test
    fun `одноцифровые часы сравниваются как числа а не как строки`() {
        // «9:00» текстово больше «10:00», потому что «9» > «1». Сравнение
        // строками объявило бы вторую парой раньше первой.
        val odd = times(Triple(1, "9:00", "10:30"), Triple(2, "11:00", "12:30"))
        assertEquals(1, NowLogic.currentPairCode(NowLogic.windowTable(odd), at(9, 45)))
    }

    @Test
    fun `пара с нулевым кодом не занимает сутки`() {
        // «Разговоры о важном» приходят с кодом 0 и без времени. Если бы код 0
        // попал в таблицу, он считался бы парой без времени и занял бы всё
        // время суток — фильтр показывал бы пустоту круглые сутки.
        val zero = times(Triple(0, "", ""), Triple(1, "08:00", "09:30"))
        val table = NowLogic.windowTable(zero)
        // Во время первой пары отвечает первая, а не нулевая.
        assertEquals(1, NowLogic.currentPairCode(table, at(8, 30)))
        // В 10:00 пара не идёт и нулевой код не подставляется вместо неё.
        assertNull(NowLogic.currentPairCode(table, at(10, 0)))
    }

    @Test
    fun `подпись различает идущую пару и следующую`() {
        val table = NowLogic.windowTable(grid)
        val (goingCode, going) = NowLogic.pairLabelState(table, at(8, 30))
        assertEquals(1, goingCode); assertEquals(true, going)

        // В перерыве номер тот же, но подпись должна говорить «следующая»,
        // иначе человек решит, что пара идёт, и не найдёт свободную комнату.
        val (nextCode, nextGoing) = NowLogic.pairLabelState(table, at(11, 15))
        assertEquals(3, nextCode); assertEquals(false, nextGoing)
    }

    @Test
    fun `остаток времени считается до конца идущей пары`() {
        val table = NowLogic.windowTable(grid)
        // Первая пара 08:00–09:30: в 08:00 остаётся все 90 минут.
        assertEquals(90, NowLogic.minutesLeft(table, at(8, 0)))
        assertEquals(1, NowLogic.minutesLeft(table, at(9, 29)))
        // В перерыве остатка нет: пара не идёт, показывать «осталось» не о чем.
        assertNull(NowLogic.minutesLeft(table, at(11, 15)))
    }
}
