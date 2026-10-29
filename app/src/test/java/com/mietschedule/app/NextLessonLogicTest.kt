package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Проверки «ближайшей пары» и занятости аудиторий.
 *
 * Тесты идут по двум причинам. Первая — смысл: функция решает, что показать
 * человеку, и ошибка в ней выглядит как враньё («сейчас 10:40», когда пара
 * в 12:00). Вторая — время здесь берётся из строки разными способами, и
 * именно на этом уже ловились расхождения с сервером.
 *
 * Текущий момент задан явно: если бы он читался из системной даты, проверка
 * в один вторник проходила, а в среду падала.
 */
class NextLessonLogicTest {

    /** Понедельник этой календарной недели, заданное время суток. */
    private fun mondayAt(h: Int, m: Int): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        set(Calendar.HOUR_OF_DAY, h)
        set(Calendar.MINUTE, m)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, -(get(Calendar.DAY_OF_WEEK) + 5) % 7)
    }.timeInMillis

    /** Плюс [days] суток к моменту. */
    private fun plusDays(base: Long, days: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = base
            add(Calendar.DAY_OF_MONTH, days)
        }.timeInMillis

    private fun time(code: Int, from: String, to: String) =
        PairTime(time = "$code пара", code = code, timeFrom = from, timeTo = to)

    private fun lesson(
        day: Int,
        pair: Int,
        name: String = "Предмет",
        room: String? = null,
        // По умолчанию — ТЕКУЩАЯ учебная неделя. Тесты ниже берут «сейчас» от
        // настоящей даты (mondayAt), и пары в них должны быть того же дня той
        // же недели: иначе блок «Пары на сегодня» отбросит их как чужие, и
        // проверка упадёт не из-за дефекта, а из-за неверных данных.
        // Где пара относится к другой неделе, её задают явно.
        weekRow: Int = WeekType.currentRowIndex(),
    ) = Lesson(
        day = day,
        dayNumber = weekRow,
        time = PairCode(time = "$pair пара", code = pair, timeFrom = null, timeTo = null),
        classInfo = ClassInfo(
            code = "c$pair", name = name,
            teacherFull = "Иванов И.И.", teacher = "Иванов И.И.", form = false,
        ),
        group = GroupInfo(code = "g", name = "ИВТ-11"),
        room = room?.let {
            RoomInfo(code = it.filter { c -> c.isDigit() }.toIntOrNull(), name = it)
        },
    )

    /** Фиксированный момент: [y]-[m]-[d] [h]:[mi] по локальному времени. */
    private fun atDate(y: Int, m: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, h, mi)
        }.timeInMillis

    // ─────────────────── учебная неделя (DayNumber) ───────────────────
    //
    // Начало семестра 04.08.2026 (понедельник — 03.08), поэтому неделя
    // 10.08–16.08.2026 — это строка 1 (1-й знаменатель), а 17.08–23.08 —
    // строка 2 (2-й числитель).

    /**
     * Блок «Пары на сегодня» не показывает пару чужой учебной недели.
     *
     * Реальный случай: ТЭС-26-11О, среда, 4-я пара. В неделях 0 и 2 —
     * «География» (ауд. 8109, Ушканов), в неделях 1 и 3 — «Введение в
     * специальность» (ауд. 1203, Волкова). В знаменатель блок показывал
     * географию, хотя идёт введение в специальность: пара бралась из строки
     * чужой недели. Внутри расписания при этом было верно — экран расписания
     * фильтрует по строке недели, а блок нет.
     */
    @Test
    fun `блок ближайших пар не берёт пару чужой учебной недели`() {
        val times = listOf(time(4, "14:00", "15:20"))
        val geo = lesson(day = 3, pair = 4, name = "География", weekRow = 0)
        val intro = lesson(day = 3, pair = 4, name = "Введение в специальность", weekRow = 1)

        // Среда 12.08.2026, 13:30 — до 4-й пары полтора часа.
        val now = atDate(2026, 8, 12, 13, 30)

        val (going, upcoming) = NextLessonLogic.currentAndNext(
            listOf(geo, intro), times, now, 0, "2026-08-04",
        )

        assertNull("идущей пары быть не должно", going)
        assertEquals(
            "показана пара чужой учебной недели",
            "Введение в специальность",
            upcoming?.lesson?.classInfo?.name,
        )
    }

    /**
     * Окно в семь дней берёт пару СЛЕДУЮЩЕЙ учебной недели.
     *
     * Строка недели обязана считаться на каждый день окна: иначе фильтр по
     * одной общей строке отсекал бы пары конца недели, а окно смотрит на
     * семь дней вперёд.
     */
    @Test
    fun `окно в семь дней берёт пару следующей учебной недели`() {
        val times = listOf(time(1, "09:00", "10:20"))
        // Понедельник: в текущей неделе (строка 1) «Литература», в следующей
        // (строка 2) «Русский язык». Смотрим в среду — ближайший понедельник
        // ещё впереди, и показать надо пару следующей недели.
        val lit = lesson(day = 1, pair = 1, name = "Литература", weekRow = 1)
        val rus = lesson(day = 1, pair = 1, name = "Русский язык", weekRow = 2)

        val now = atDate(2026, 8, 12, 13, 30)
        val upcoming = NextLessonLogic.currentAndNext(
            listOf(lit, rus), times, now, 7, "2026-08-04",
        ).second

        assertEquals(
            "на следующей неделе должна быть своя пара",
            "Русский язык",
            upcoming?.lesson?.classInfo?.name,
        )
    }

    /** Расписание без разметки по неделям фильтровать нечем — как раньше. */
    @Test
    fun `без разметки по неделям пары не отбрасываются`() {
        val times = listOf(time(4, "14:00", "15:20"))
        val noWeek = Lesson(
            day = 3,
            time = PairCode(time = "4 пара", code = 4, timeFrom = null, timeTo = null),
            classInfo = ClassInfo(name = "География", teacherFull = "Ушканов А.В."),
        )
        val now = atDate(2026, 8, 12, 13, 30)
        val upcoming = NextLessonLogic.currentAndNext(
            listOf(noWeek), times, now, 0, "2026-08-04",
        ).second
        assertEquals("География", upcoming?.lesson?.classInfo?.name)
    }

    // ─────────────────────── разбор времени ───────────────────────

    @Test
    fun `время разбирается из короткой записи`() {
        assertEquals(listOf(8, 0), NextLessonLogic.parseHhMm("08:00")!!.toList())
    }

    @Test
    fun `время разбирается из полной даты сервера`() {
        assertEquals(listOf(13, 30), NextLessonLogic.parseHhMm("2026-09-01T13:30:00")!!.toList())
    }

    @Test
    fun `пустое время не считается временем`() {
        assertNull(NextLessonLogic.parseHhMm(null))
        assertNull(NextLessonLogic.parseHhMm(""))
        assertNull(NextLessonLogic.parseHhMm("   "))
    }

    @Test
    fun `мусорное время отбрасывается а не падает`() {
        assertNull(NextLessonLogic.parseHhMm("по расписанию"))
        assertNull(NextLessonLogic.parseHhMm("25:00"))
        assertNull(NextLessonLogic.parseHhMm("08:99"))
    }

    // ─────────────────────── ближайшая пара ───────────────────────

    @Test
    fun `находится ближайшая пара дня`() {
        val hit = NextLessonLogic.next(
            lessons = listOf(lesson(day = 1, pair = 2, name = "Физика")),
            times = listOf(time(1, "08:00", "09:30"), time(2, "10:00", "11:30")),
            now = mondayAt(9, 0),
        )
        assertNotNull(hit)
        assertEquals("Физика", hit!!.lesson.classInfo?.name)
        // Время приходит вместе с парой — считать его второй раз не нужно.
        assertEquals(10, Calendar.getInstance().apply { timeInMillis = hit.start!! }
            .get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `идущая пара показывается а не следующая`() {
        val hit = NextLessonLogic.next(
            lessons = listOf(
                lesson(day = 1, pair = 2, name = "Идущая"),
                lesson(day = 1, pair = 3, name = "Следующая"),
            ),
            times = listOf(time(2, "10:00", "11:30"), time(3, "12:00", "13:30")),
            now = mondayAt(10, 20),
        )
        assertEquals("Идущая", hit?.lesson?.classInfo?.name)
        assertTrue(NextLessonLogic.isGoing(hit!!, mondayAt(10, 20)))
    }

    @Test
    fun `после конца пары показывается следующая`() {
        val hit = NextLessonLogic.next(
            lessons = listOf(
                lesson(day = 1, pair = 2, name = "Прошлая"),
                lesson(day = 1, pair = 3, name = "Дальше"),
            ),
            times = listOf(time(2, "10:00", "11:30"), time(3, "12:00", "13:30")),
            now = mondayAt(11, 40),
        )
        assertEquals("Дальше", hit?.lesson?.classInfo?.name)
        assertFalse(NextLessonLogic.isGoing(hit!!, mondayAt(11, 40)))
    }

    @Test
    fun `за минуту до начала пара ещё не идущая`() {
        val hit = NextLessonLogic.next(
            lessons = listOf(lesson(day = 1, pair = 2, name = "Скоро")),
            times = listOf(time(2, "10:00", "11:30")),
            now = mondayAt(9, 59),
        )
        assertEquals("Скоро", hit?.lesson?.classInfo?.name)
        assertFalse(NextLessonLogic.isGoing(hit!!, mondayAt(9, 59)))
    }

    @Test
    fun `завтрашняя пара получает завтрашнюю дату`() {
        // Пара во вторник. Сегодня понедельник 20:00 — пара завтра.
        // Регрессия: раньше дата считалась через учебную неделю, и «завтра»
        // могло превратиться в дату следующей учебной недели.
        val monday = mondayAt(20, 0)
        val tuesday = plusDays(monday, 1)

        val hit = NextLessonLogic.next(
            lessons = listOf(lesson(day = 2, pair = 1, name = "Завтрашняя")),
            times = listOf(time(1, "08:00", "09:30")),
            now = monday,
        )
        assertNotNull(hit)

        val got = Calendar.getInstance().apply { timeInMillis = hit!!.start!! }
        val want = Calendar.getInstance().apply { timeInMillis = tuesday }
        assertEquals(want.get(Calendar.DAY_OF_MONTH), got.get(Calendar.DAY_OF_MONTH))
        assertEquals(want.get(Calendar.MONTH), got.get(Calendar.MONTH))
    }

    @Test
    fun `прошедшая пара не показывается как прошедшая`() {
        // Пара в четверг, сейчас суббота. Пара должна вернуться, но как
        // СЛЕДУЮЩИЙ четверг, а не как сегодняшний: проверяем именно дату.
        // Раньше дата считалась через учебную неделю, и «ближайшая пара»
        // могла оказаться двумя днями в прошлом.
        val sat = plusDays(mondayAt(12, 0), 5)
        val hit = NextLessonLogic.next(
            // Четверг наступит уже в СЛЕДУЮЩЕЙ учебной неделе: выходной между
            // «сейчас» и парой переводит окно на строку вперёд.
            lessons = listOf(
                lesson(day = 4, pair = 1, name = "Прошлая", weekRow = WeekType.shiftedRow(1)),
            ),
            times = listOf(time(1, "08:00", "09:30")),
            now = sat,
        )
        assertNotNull(hit)
        // Начало строго в будущем — то есть это не та минутая пара.
        assertTrue(hit!!.start!! > sat)
        // И это ближайший четверг: через 5 суток, не через 12.
        assertEquals(5L, daysApart(sat, hit.start!!))
    }

    /** Сколько целых суток между двумя моментами. */
    private fun daysApart(from: Long, to: Long): Long {
        fun midnight(v: Long) = Calendar.getInstance().apply {
            timeInMillis = v
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return (midnight(to) - midnight(from)) / (24L * 3600 * 1000)
    }

    @Test
    fun `пустое расписание не выдумывает пару`() {
        assertNull(NextLessonLogic.next(emptyList(), emptyList(), mondayAt(9, 0)))
    }

    @Test
    fun `пара без времени таблицы не показывается`() {
        // Часть аудиторий корпуса 8 приходит без Times: номер пары есть,
        // часов нет — показывать её в строке «сейчас» нечем.
        val hit = NextLessonLogic.next(
            lessons = listOf(lesson(day = 1, pair = 2, name = "Без времени")),
            times = emptyList(),
            now = mondayAt(9, 0),
        )
        assertNull(hit)
    }

    @Test
    fun `время начала и конца берётся из таблицы`() {
        val l = lesson(day = 1, pair = 2, name = "Физика")
        val day = Calendar.getInstance().apply { timeInMillis = mondayAt(0, 0) }

        val start = NextLessonLogic.startMillis(l, listOf(time(2, "10:15", "11:45")), day)!!
        val gotStart = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(10, gotStart.get(Calendar.HOUR_OF_DAY))
        assertEquals(15, gotStart.get(Calendar.MINUTE))

        val end = NextLessonLogic.endMillis(l, listOf(time(2, "10:15", "11:45")), day)!!
        val gotEnd = Calendar.getInstance().apply { timeInMillis = end }
        assertEquals(11, gotEnd.get(Calendar.HOUR_OF_DAY))
        assertEquals(45, gotEnd.get(Calendar.MINUTE))
    }

    // ─────────────────────── занятость аудиторий ───────────────────────

    @Test
    fun `занятая аудитория определяется по паре`() {
        val lessons = listOf(lesson(day = 2, pair = 3, room = "8307"))
        assertTrue(NextLessonLogic.isRoomBusy("8307", lessons, day = 1, pairCode = 3))
    }

    @Test
    fun `свободна в другой паре того же дня`() {
        val lessons = listOf(lesson(day = 2, pair = 3, room = "8307"))
        assertFalse(NextLessonLogic.isRoomBusy("8307", lessons, day = 1, pairCode = 4))
    }

    @Test
    fun `свободна в другой день`() {
        val lessons = listOf(lesson(day = 2, pair = 3, room = "8307"))
        assertFalse(NextLessonLogic.isRoomBusy("8307", lessons, day = 3, pairCode = 3))
    }

    @Test
    fun `сравнение идёт по roomKey а не по строке`() {
        // В списке «1201 (м)», в паре «1201» — это одна комната.
        val lessons = listOf(lesson(day = 1, pair = 1, room = "1201"))
        assertTrue(NextLessonLogic.isRoomBusy("1201 (м)", lessons, day = 0, pairCode = 1))
    }

    @Test
    fun `суффикс к различает комнаты`() {
        // «8307» и «8307 к» — РАЗНЫЕ аудитории, значит «к» нельзя отбрасывать.
        val lessons = listOf(lesson(day = 1, pair = 1, room = "8307 к"))
        assertFalse(NextLessonLogic.isRoomBusy("8307", lessons, day = 0, pairCode = 1))
    }

    @Test
    fun `пустое расписание не бывает занятым`() {
        assertFalse(NextLessonLogic.isRoomBusy("8307", emptyList(), day = 0, pairCode = 1))
    }

    @Test
    fun `пара чужой учебной недели не занимает аудиторию`() {
        // Регрессия: без фильтра по DayNumber аудитория считалась занятой
        // из-за пары, которая сегодня не проходит.
        val lessons = listOf(lesson(day = 2, pair = 3, room = "8307", weekRow = 2))
        assertTrue(NextLessonLogic.isRoomBusy("8307", lessons, 1, 3, dayNumberFilter = 2))
        assertFalse(NextLessonLogic.isRoomBusy("8307", lessons, 1, 3, dayNumberFilter = 0))
    }

    @Test
    fun `корпус берётся по первой цифре`() {
        assertEquals("Корпус 3", NextLessonLogic.buildingOf("3205"))
        assertEquals("Корпус 8", NextLessonLogic.buildingOf("8307"))
    }
    // ─────────────────────── идущая и следующая одновременно ───────────────────────

    /**
     * Идущая и следующая видны одновременно.
     *
     * Проверка на той ситуации, ради которой функция и писалась: когда идёт
     * пара, следующая раньше была не видна вовсе, и человек думал, что после
     * этой пары у него ничего нет.
     *
     * withinDays = 0 — намеренно. По умолчанию функция смотрит на 7 дней
     * вперёд, и пара «1 пара» попадает в результат дважды: сегодня и в
     * следующий понедельник. Из-за этого «следующая» бралась из будущей
     * недели, и тесты смотрели не на ту пару.
     */
    @Test
    fun `идущая и следующая видны одновременно`() {
        val times = listOf(time(1, "08:00", "09:30"), time(2, "09:50", "11:20"), time(3, "12:10", "13:40"))
        val now = mondayAt(10, 0) // идёт вторая пара
        val lessons = listOf(lesson(1, 1, "Физика"), lesson(1, 2, "Математика"), lesson(1, 3, "История"))
        val (going, upcoming) = NextLessonLogic.currentAndNext(lessons, times, now, withinDays = 0)
        assertEquals("Математика", going?.lesson?.classInfo?.name)
        assertEquals("История", upcoming?.lesson?.classInfo?.name)
    }

    /** Без идущей пары следующая всё равно находится — вторая позиция не пустая. */
    @Test
    fun `без идущей пары следующая всё равно есть`() {
        val times = listOf(time(1, "08:00", "09:30"), time(2, "09:50", "11:20"))
        val now = mondayAt(7, 0)
        val (going, upcoming) = NextLessonLogic.currentAndNext(
            listOf(lesson(1, 1, "Физика"), lesson(1, 2, "Математика")), times, now, withinDays = 0
        )
        assertNull(going)
        assertEquals("Физика", upcoming?.lesson?.classInfo?.name)
    }

    /** Пара не может быть одновременно идущей и следующей. */
    @Test
    fun `одна и та же пара не бывает обе`() {
        val now = mondayAt(8, 30)
        val (going, upcoming) = NextLessonLogic.currentAndNext(
            listOf(lesson(1, 1, "Физика")), listOf(time(1, "08:00", "09:30")), now, withinDays = 0
        )
        assertNotNull(going)
        assertNull(upcoming)
    }

    /** Пустое расписание не выдумывает ни одной пары. */
    @Test
    fun `пустое расписание пусто в обеих позициях`() {
        val (going, upcoming) = NextLessonLogic.currentAndNext(
            emptyList(), listOf(time(1, "08:00", "09:30")), mondayAt(8, 0)
        )
        assertNull(going)
        assertNull(upcoming)
    }

    /**
     * Пересекающиеся пары: идущая кончается ПОЗЖЕ следующей.
     *
     * Бывает у преподавателя: две аудитории одновременно, разная длительность.
     * Если следующая искалась по концу идущей, она молча пропадала бы.
     */
    @Test
    fun `пересекающиеся пары не теряют следующую`() {
        val times = listOf(time(1, "08:00", "10:00"), time(2, "08:40", "09:30"), time(3, "10:00", "11:30"))
        val now = mondayAt(9, 0)
        val lessons = listOf(lesson(1, 1, "Лекция"), lesson(1, 2, "Практика"), lesson(1, 3, "Семинар"))
        val (going, upcoming) = NextLessonLogic.currentAndNext(lessons, times, now, withinDays = 0)
        // Идущих две: «Лекция» 08:00–10:00 и «Практика» 08:40–09:30 Берётся более
        // поздняя по началу — та, что ближе к текущему моменту.
        assertEquals("Практика", going?.lesson?.classInfo?.name)
        // Следующая при этом не теряется: порог — «сейчас», а не конец идущей.
        assertEquals("Семинар", upcoming?.lesson?.classInfo?.name)
    }

    /** Старая next() не сломалась и отдаёт идущую, как раньше. */
    @Test
    fun `старая next отдаёт идущую как и раньше`() {
        val times = listOf(time(1, "08:00", "09:30"), time(2, "09:50", "11:20"))
        val now = mondayAt(10, 0)
        val lessons = listOf(lesson(1, 1, "Физика"), lesson(1, 2, "Математика"))
        assertEquals("Математика", NextLessonLogic.next(lessons, times, now, withinDays = 0)?.lesson?.classInfo?.name)
    }

    // ─────────── сетка звонков: у каждой группы своя ───────────

    /** Пара, которая знает своё время (как их отдаёт miet.ru). */
    private fun lessonWithOwnTime(
        day: Int,
        pair: Int,
        from: String,
        to: String,
        name: String = "Предмет",
    ) = Lesson(
        day = day,
        // Текущая неделя: «сейчас» в тестах строится от настоящей даты
        // (mondayAt), и пары должны лежать в той же учебной неделе.
        dayNumber = WeekType.currentRowIndex(),
        time = PairCode(time = "$pair пара", code = pair, timeFrom = from, timeTo = to),
        classInfo = ClassInfo(
            code = "c$pair", name = name,
            teacherFull = "Иванов И.И.", teacher = "Иванов И.И.", form = false,
        ),
        group = GroupInfo(code = "g", name = "ИВТ-11"),
    )

    private fun hm(millis: Long?): String {
        if (millis == null) return "нет"
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return String.format(java.util.Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /**
     * Пара показывает СВОЁ время, а не время чужой группы.
     *
     * Найдено на устройстве: на главной в «Ближайших парах» у трёх пар стояло
     * 12:00, тогда как в расписании те же пары стоят в 12:30. Причина — блок
     * брал общую таблицу времени из кэша, собранную от первой попавшейся
     * группы, и таблица побеждала время самой пары.
     *
     * Числа настоящие, сняты с miet.ru: у ЮР-26-11О третья пара 12:30–13:50,
     * у ПСИ-11М — 12:00–13:20. Сетка звонков у групп разная.
     */
    @Test
    fun `пара показывает своё время а не время чужой группы`() {
        // Чужая таблица (сетка 12:00) подсунута паре из группы с сеткой 12:30.
        val foreign = listOf(time(3, "0001-01-01T12:00:00", "0001-01-01T13:20:00"))
        val college = lessonWithOwnTime(
            1, 3, "0001-01-01T12:30:00", "0001-01-01T13:50:00", "Обществознание"
        )
        val (_, upcoming) = NextLessonLogic.currentAndNext(
            listOf(college), foreign, mondayAt(12, 10), withinDays = 0
        )
        assertNotNull("пара в 12:30 должна быть следующей, а не выпасть", upcoming)
        assertEquals("время начала пары — из самой пары", "12:30", hm(upcoming!!.start))
        assertEquals("время конца — тоже из пары", "13:50", hm(upcoming!!.end))
    }

    /** Две сетки в одном блоке: каждая пара считает по своей. */
    @Test
    fun `две сетки звонков не мешают друг другу`() {
        val foreign = listOf(time(3, "0001-01-01T12:00:00", "0001-01-01T13:20:00"))
        val late = lessonWithOwnTime(1, 3, "0001-01-01T12:30:00", "0001-01-01T13:50:00", "Поздняя")
        val early = lessonWithOwnTime(1, 3, "0001-01-01T12:00:00", "0001-01-01T13:20:00", "Ранняя")
        val day = Calendar.getInstance().apply { timeInMillis = mondayAt(0, 0) }
        assertEquals("12:30", hm(NextLessonLogic.startMillis(late, foreign, day)))
        assertEquals("12:00", hm(NextLessonLogic.startMillis(early, foreign, day)))
    }

    /**
     * Если у пары своего времени нет, берётся таблица.
     *
     * Запасной путь обязателен: у части аудиторий корпуса 8 сервер не отдаёт
     * часы вовсе, и без таблицы такая пара исчезла бы из блока совсем.
     */
    @Test
    fun `пара без своего времени берёт время из таблицы`() {
        val table = listOf(time(3, "0001-01-01T12:30:00", "0001-01-01T13:50:00"))
        val without = lesson(1, 3, "Без времени")
        val day = Calendar.getInstance().apply { timeInMillis = mondayAt(0, 0) }
        assertEquals("12:30", hm(NextLessonLogic.startMillis(without, table, day)))
        assertEquals("13:50", hm(NextLessonLogic.endMillis(without, table, day)))
    }
}
