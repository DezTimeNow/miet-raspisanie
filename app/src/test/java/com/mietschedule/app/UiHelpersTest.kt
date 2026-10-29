package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверки чистых функций интерфейса и разбора текста.
 *
 * Эти функции ломались по мелочам и незаметно: размер заголовка не
 * укладывался в узкий экран, а текст релиза приходил с HTML-сущностями
 * и Markdown-мусором прямо в диалог обновления.
 */
class UiHelpersTest {

    // ── Размер заголовка шапки ──────────────────────────────────────────
    //
    // `sp` — @Composable-функция, её нельзя вызывать из обычного теста,
    // поэтому сравниваем TextUnit по числовому значению.

    @Test
    fun `узкий экран даёт мелкий заголовок`() {
        assertEquals(13f, titleSizeSp(300), 0.01f)
    }

    @Test
    fun `средняя ширина даёт средний заголовок`() {
        assertEquals(15f, titleSizeSp(340), 0.01f)
    }

    @Test
    fun `широкий экран даёт крупный заголовок`() {
        assertEquals(17f, titleSizeSp(500), 0.01f)
    }

    @Test
    fun `на границе ширины заголовок не прыгает назад`() {
        // 330 — первая граница: ровно 330 уже относится к средней ветке.
        assertEquals(13f, titleSizeSp(329), 0.01f)
        assertEquals(15f, titleSizeSp(330), 0.01f)
    }

    @Test
    fun `подзаголовок на границе совпадает с заголовком по ветке`() {
        assertEquals(10f, subtitleSizeSp(329), 0.01f)
        assertEquals(11f, subtitleSizeSp(330), 0.01f)
        assertEquals(12f, subtitleSizeSp(400), 0.01f)
    }

    // ── Группировка аудиторий по корпусу ────────────────────────────────

    @Test
    fun `аудитория с цифрой попадает в свой корпус`() {
        assertEquals("Корпус 4", buildingOf("4-19"))
    }

    @Test
    fun `аудитории ДК попадают в одну группу`() {
        // Код особый случай: «ДК-1» и «ДК-2» — разные помещения, но одна
        // группа, иначе в списке появляются почти пустые разделы.
        assertEquals("ДК МИЭТ", buildingOf("ДК-1"))
        assertEquals("ДК МИЭТ", buildingOf("ДК МИЭТ"))
    }

    @Test
    fun `остальные аудитории группируются по первому слову`() {
        assertEquals("УВЦ", buildingOf("УВЦ-3"))
        assertEquals("Аудитории практики", buildingOf("Аудитория 12"))
        assertEquals("Виртуальные аудитории", buildingOf("Виртуальная 3"))
        assertEquals("Библиотека", buildingOf("Библиотека 2"))
    }

    @Test
    fun `ДК МИЭТ не режется на букву Д`() {
        // Регрессия: «ДК МИЭТ» — одно название, резать по первой букве
        // бессмысленно, аудитория уезжала в группу «Д».
        assertEquals("ДК МИЭТ", buildingOf("ДК МИЭТ"))
    }

    @Test
    fun `пустое имя аудитории идёт в Прочее`() {
        assertEquals("Прочее", buildingOf(null))
        assertEquals("Прочее", buildingOf("   "))
    }

    // ── Текст релиза в диалоге обновления ──────────────────────────────

    @Test
    fun `HTML-сущности декодируются`() {
        assertTrue(cleanReleaseNotes("A &amp; B").contains("A & B"))
        assertTrue(cleanReleaseNotes("&lt;b&gt;жирный&lt;/b&gt;").contains("<b>жирный</b>"))
        assertTrue(cleanReleaseNotes("кавычки &quot;в кавычках&quot;").contains("\"в кавычках\""))
        assertTrue(cleanReleaseNotes("апостроф &#39;ой&#39;").contains("'ой'"))
    }

    @Test
    fun `маркеры и пустые строки убираются`() {
        val clean = cleanReleaseNotes("# Заголовок\n\n- пункт один\n* пункт два\n\n  \n")
        assertEquals("Заголовок\nпункт один\nпункт два", clean)
    }

    @Test
    fun `CRLF не оставляет пустых строк`() {
        val clean = cleanReleaseNotes("строка1&#13;&#10;строка2")
        assertEquals("строка1\nстрока2", clean)
    }

    @Test
    fun `слишком длинный текст обрезается`() {
        val long = "я".repeat(3000)
        assertTrue(cleanReleaseNotes(long).length <= 1200)
    }

    @Test
    fun `двойное декодирование не портит текст`() {
        // Регрессия: `&amp;` разбиралась последней, поэтому `&amp;lt;`
        // превращался в `<` — символ из исходного текста, а не текст.
        val out = cleanReleaseNotes("&amp;lt;")
        assertEquals("&lt;", out)
    }

    @Test
    fun `пустой текст релиза не даёт мусора`() {
        assertEquals("", cleanReleaseNotes(""))
        assertEquals("", cleanReleaseNotes("   \n\n  \n"))
    }

    // ── Склонение ───────────────────────────────────────────────────────

    @Test
    fun `склонение групп`() {
        assertEquals("группа", plural(1, "группа", "группы", "групп"))
        assertEquals("группы", plural(2, "группа", "группы", "групп"))
        assertEquals("группы", plural(4, "группа", "группы", "групп"))
        assertEquals("групп", plural(5, "группа", "группы", "групп"))
        assertEquals("групп", plural(11, "группа", "группы", "групп"))
        assertEquals("групп", plural(12, "группа", "группы", "групп"))
        assertEquals("групп", plural(111, "группа", "группы", "групп"))
        assertEquals("группа", plural(21, "группа", "группы", "групп"))
        assertEquals("группы", plural(22, "группа", "группы", "групп"))
        // Ноль склоняется как «много»: «0 групп», а не «0 группа».
        assertEquals("групп", plural(0, "группа", "группы", "групп"))
    }

    // ── Ключ слота преподавателя ────────────────────────────────────────

    @Test
    fun `ключ слота различает пары`() {
        val l1 = Lesson(day = 1, time = PairCode(code = 1))
        val l2 = Lesson(day = 2, time = PairCode(code = 1))
        assertTrue(teacherSlotKey(l1) != teacherSlotKey(l2))
    }

    @Test
    fun `одинаковый слот даёт одинаковый ключ`() {
        val l1 = Lesson(day = 1, time = PairCode(code = 1))
        val l2 = Lesson(day = 1, time = PairCode(code = 1))
        assertEquals(teacherSlotKey(l1), teacherSlotKey(l2))
    }

    @Test
    fun `отсутствующие поля не роняют ключ`() {
        val l = Lesson(day = 1, time = null)
        // Просто должно посчитаться без исключения.
        assertTrue(teacherSlotKey(l).isNotEmpty())
    }

    @Test
    fun `пара без кода времени не путается с обычной`() {
        val безВремени = Lesson(day = 1, time = PairCode(code = 0))
        val обычная = Lesson(day = 1, time = PairCode(code = 1))
        assertTrue(teacherSlotKey(безВремени) != teacherSlotKey(обычная))
    }
}