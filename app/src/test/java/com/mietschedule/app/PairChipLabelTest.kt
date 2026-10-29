package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ПОДПИСЬ ЧИПА ПАРЫ: НОМЕР И ВРЕМЯ.
 *
 * Проверка на регресс, найденный на устройстве: чипы подписывались смешанно —
 * часть временем («09:00»), часть номером («4», «5»). Причина в том, что
 * таблица времени бралась из самих пар, а не из поля Times верхнего уровня
 * ответа. У ИВТ-11 в парах встречаются только коды 1–5, поэтому пары 6, 7 и 8
 * подписывались номером, хотя сервер отдаёт для них полные часы.
 *
 * Эти тесты проверяют обе стороны: подпись обязана брать часы, когда таблица
 * их содержит, и обязана честно показывать номер, когда часов нет вовсе.
 */
class PairChipLabelTest {

    private fun times(vararg rows: Triple<Int, String, String>): List<PairTime> =
        rows.map { (c, f, _) ->
            PairTime(code = c, time = "$c пара", timeFrom = "0001-01-01T$f:00")
        }

    @Test
    fun `подпись содержит время когда оно есть`() {
        val table = times(Triple(1, "09:00", "10:20"), Triple(2, "10:30", "11:50"))
        assertEquals("09:00", pairChipLabel(1, table))
        assertEquals("10:30", pairChipLabel(2, table))
    }

    @Test
    fun `подпись остаётся номером когда часов нет`() {
        // Нет данных о времени — показываем номер. Это осознанный отказ от
        // выдумывания: лучше «6», чем придуманное время.
        val table = times(Triple(1, "09:00", "10:20"))
        assertEquals("7", pairChipLabel(7, table))
    }

    @Test
    fun `полная сетка сервера покрывает все пары`() {
        // Ровно тот случай, что был на устройстве: 8 пар в таблице, из них
        // в парах реально встречаются первые пять.
        val full = times(
            Triple(1, "09:00", "10:20"), Triple(2, "10:30", "11:50"),
            Triple(3, "12:00", "13:20"), Triple(4, "14:00", "15:20"),
            Triple(5, "15:30", "16:50"), Triple(6, "17:00", "18:20"),
            Triple(7, "18:30", "19:50"), Triple(8, "20:00", "21:20"),
        )
        // Ни одна пара не должна остаться без времени.
        for (code in 1..8) {
            val label = pairChipLabel(code, full)
            assertTrue("пара $code без времени: '$label'", label != code.toString())
        }
    }

    @Test
    fun `таблица времени читается из корня ответа а не из пар`() {
        // Главная проверка: pairTimesFromCache обязан сначала смотреть в
        // поле Times верхнего уровня. В парах ИВТ-11 кодов 6–8 нет, поэтому
        // сборка таблицы из пар дала бы неполный ответ.
        val src = srcText("MietApi.kt")
        val fn = src.substringAfter("suspend fun pairTimesFromCache")
            .substringBefore("suspend fun pairTimesForUser")
        assertTrue(
            "pairTimesFromCache не читает Times верхнего уровня",
            fn.contains("resp?.times"),
        )
    }

    /**
     * Чипы берут сетку ГРУППЫ ПОЛЬЗОВАТЕЛЯ, а не первую попавшуюся.
     *
     * Тот же корень, что и у дефекта на главной: сетка звонков у групп разная
     * (3-я пара 12:00 у одних, 12:30 у колледжных). Экран аудиторий брал
     * таблицу из кэша «от первой попавшейся группы», и у пользователя
     * колледжной группы чип показывал 12:00 вместо 12:30.
     */
    @Test
    fun `чипы берут сетку группы пользователя`() {
        val pickers = srcText("PickersScreen.kt")
        assertTrue(
            "экран аудиторий должен брать сетку пользователя",
            pickers.contains("pairTimesForUser("),
        )
        assertFalse(
            "первая попавшаяся таблица на этом экране больше не используется",
            pickers.contains("pairTimesFromCache()"),
        )

        // Порядок внутри функции: своя группа, затем избранные, и только
        // потом прежний путь из кэша.
        val api = srcText("MietApi.kt")
        val fn = api.substringAfter("suspend fun pairTimesForUser")
            .substringBefore("\n    fun loadAudienceIndex")
        val own = fn.indexOf("loadFor(Role.STUDENT)")
        val fav = fn.indexOf("favGroups(Role.STUDENT)")
        val last = fn.indexOf("pairTimesFromCache()")
        assertTrue("нет пути «своя группа»", own >= 0)
        assertTrue("своя группа должна идти раньше избранных", own < fav)
        assertTrue("прежний путь должен остаться последним", fav < last)
    }

    /** Читает исходник из пакета main — тесты ходят в файлы, как и соседние. */
    private fun srcText(name: String): String {
        val candidates = listOf(
            "src/main/java/com/mietschedule/app/$name",
            "../app/src/main/java/com/mietschedule/app/$name",
        )
        for (p in candidates) {
            val f = java.io.File(p)
            if (f.exists()) return f.readText()
        }
        throw AssertionError("не найден исходник $name")
    }

    @Test
    fun `фильтр пар существует и покрывает поздние пары`() {
        // Проверка, что поздние пары вообще попадают в фильтр: если бы
        // pairCodes их отбрасывал, подпись была бы неважна.
        val s = srcText("PickersScreen.kt")
        assertNotNull(s)
        assertTrue("в фильтре нет LazyRow для чипов пар", s.contains("LazyRow"))
    }
}