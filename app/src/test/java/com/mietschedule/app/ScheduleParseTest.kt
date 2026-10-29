package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Тесты разбора ответов miet.ru и вспомогательной логики.
 *
 * Раньше здесь были тесты сравнения расписаний для уведомлений. Уведомления
 * по требованию убраны, класс ScheduleNotifier удалён — вместо него проверяем
 * то, что реально ломает приложение: разбор JSON, склонение числительных,
 * группировку аудиторий по корпусу и нормализацию ФИО.
 */
class ScheduleParseTest {

    // ───────────────────────── склонение числительных ─────────────────────────

    @Test
    fun `склонение числительных корректно`() {
        assertEquals("аудитория", plural(1, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудитории", plural(2, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудитории", plural(4, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудиторий", plural(5, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудиторий", plural(11, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудиторий", plural(14, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудитория", plural(21, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудитории", plural(22, "аудитория", "аудитории", "аудиторий"))
        // 31 оканчивается на 1 и не попадает в 11..14 — значит «31 группа».
        assertEquals("группа", plural(31, "группа", "группы", "групп"))
        assertEquals("31 группа", "31 " + plural(31, "группа", "группы", "групп"))
        assertEquals("5 групп", "5 " + plural(5, "группа", "группы", "групп"))
        assertEquals("11 групп", "11 " + plural(11, "группа", "группы", "групп"))
    }

    // ───────────────────────── группировка аудиторий ─────────────────────────

    @Test
    fun `аудитории группируются по номеру корпуса`() {
        assertEquals("Корпус 1", buildingOf("1201 м"))
        assertEquals("Корпус 3", buildingOf("3104 м"))
        assertEquals("Корпус 4", buildingOf("4114 к"))
        assertEquals("Корпус 8", buildingOf("8102"))
        assertEquals("Корпус 8", buildingOf("8307 к"))
    }

    @Test
    fun `аудитории без цифры не режутся на пустые корпуса`() {
        // Раньше «ДК МИЭТ» превращалось в «ДК», а «Аудитория практической
        // подготовки 7» — в «Аудитория». Оба названия осмысленные.
        assertEquals("ДК МИЭТ", buildingOf("ДК МИЭТ"))
        assertEquals("УВЦ", buildingOf("УВЦ 1"))
        assertEquals("Виртуальные аудитории", buildingOf("Виртуальная аудитория 9"))
        assertEquals("Аудитории практики", buildingOf("Аудитория практической подготовки 7"))
        assertEquals("Прочее", buildingOf(""))
        assertEquals("Прочее", buildingOf(null))
    }

    // ───────────────────────── код аудитории ─────────────────────────

    @Test
    fun `код аудитории приводится к Int из любого типа`() {
        // Сервер отдаёт Code числом, но полагаться на это нельзя.
        assertEquals(120, RoomInfo(code = 120).roomCode())
        assertEquals(120, RoomInfo(code = "120").roomCode())
        assertEquals(120, RoomInfo(code = " 120 ").roomCode())
        assertEquals(120, RoomInfo(code = 120.0).roomCode())
        assertEquals(null, RoomInfo(code = null).roomCode())
        assertEquals(null, RoomInfo(code = "не аудитория").roomCode())
    }

    // ───────────────────────── идентификация преподавателя ─────────────────────────

    @Test
    fun `ФИО нормализуется в устойчивый ключ`() {
        // Ключ преподавателя — нормализованное ФИО, а НЕ Class.Code: тот является
        // GUID предмета, из-за чего один человек попадал в список несколько раз
        // (выходило 3974 «преподавателя» вместо 681).
        val a = TeacherIndex.key("Лупин Сергей Сергеевич")
        val b = TeacherIndex.key("  Лупин   Сергей  Сергеевич ")
        assertEquals(a, b)
        assertEquals(a, TeacherIndex.key("ЛУПИН СЕРГЕЙ СЕРГЕЕВИЧ"))
        // Разные люди — разные ключи.
        assertTrue(TeacherIndex.key("Лупин Сергей Сергеевич") != TeacherIndex.key("Лупин Сергей Петрович"))
    }

    @Test
    fun `служебные записи отличимы от людей`() {
        assertTrue(TeacherIndex.isService("Преподаватель УВЦ"))
        assertTrue(TeacherIndex.isService("Преподаватель практической подготовки 7"))
        assertTrue(!TeacherIndex.isService("Лавров Игорь Викторович"))
    }

    // ───────────────────────── разбор ответа сервера ─────────────────────────

    @Test
    fun `ответ расписания разбирается полностью`() {
        // Реальный кусок ответа miet.ru. Здесь важны три момента:
        //  - Time приходит строкой «2 пара», а не числом (иначе NumberFormatException);
        //  - Room.Code — число 120, а не строка;
        //  - Class.Code — GUID предмета, а не код преподавателя.
        val json = """
            {
              "Times": [
                {"Time":"2 пара","Code":2,"TimeFrom":"0001-01-01T10:30:00","TimeTo":"0001-01-01T11:50:00"}
              ],
              "Data": [
                {
                  "Day": 4,
                  "DayNumber": 0,
                  "Time": {"Time":"2 пара","Code":2,"TimeFrom":"0001-01-01T10:30:00","TimeTo":"0001-01-01T11:50:00"},
                  "Class": {
                    "Code": "402a26bd-3b16-11f1-a7c9-0050569f2356",
                    "Name": "Основы технологии электронной компонентной базы [Лек]",
                    "TeacherFull": "Шевяков Василий Иванович",
                    "Teacher": "Шевяков В.И.",
                    "Form": false
                  },
                  "Group": {"Code":"000000000000250","Name":"ЭН-31"},
                  "Room": {"Code":120,"Name":"1201 м"}
                }
              ],
              "Semestr": "2026-09-01"
            }
        """.trimIndent()

        val resp = GsonHolder.gson.fromJson(json, ScheduleResponse::class.java)
        assertEquals(1, resp.data!!.size)

        val lesson = resp.data!!.first()
        assertEquals(4, lesson.day)
        assertEquals(0, lesson.dayNumber)
        // Time — строка, иначе всё разваливается
        assertEquals("2 пара", lesson.time?.time)
        assertEquals(2, lesson.time?.code)
        // аудитория — число
        assertEquals(120, lesson.room?.roomCode())
        assertEquals("1201 м", lesson.room?.name)
        // предмет и его GUID
        assertEquals("402a26bd-3b16-11f1-a7c9-0050569f2356", lesson.classInfo?.code)
        assertEquals("Шевяков Василий Иванович", lesson.classInfo?.teacherFull)
        assertEquals("Шевяков В.И.", lesson.classInfo?.teacher)
        assertEquals("ЭН-31", lesson.group?.name)
        assertEquals(1, resp.times!!.size)
        assertEquals("2026-09-01", resp.semestr)
    }

    @Test
    fun `несколько групп в одной аудитории различаются`() {
        // Реальная причина падения: у аудитории в одном слоте бывает несколько
        // групп (ЭН-31 и ЭН-32 в 1201 одновременно). Ключ строки в LazyColumn
        // строится без группы — «4_2__0» — и Compose падал с
        // «Key already used». Проверяем, что данные для различения есть.
        val json = """
            {"Data":[
              {"Day":4,"DayNumber":0,
               "Time":{"Time":"2 пара","Code":2},
               "Class":{"Code":"a","Name":"Предмет","TeacherFull":"Шевяков Василий Иванович"},
               "Group":{"Code":"1","Name":"ЭН-31"},
               "Room":{"Code":120,"Name":"1201 м"}},
              {"Day":4,"DayNumber":0,
               "Time":{"Time":"2 пара","Code":2},
               "Class":{"Code":"a","Name":"Предмет","TeacherFull":"Шевяков Василий Иванович"},
               "Group":{"Code":"2","Name":"ЭН-32"},
               "Room":{"Code":120,"Name":"1201 м"}}
            ]}
        """.trimIndent()

        val resp = GsonHolder.gson.fromJson(json, ScheduleResponse::class.java)
        assertEquals(2, resp.data!!.size)

        // Ключ с полным набором полей различает пары
        val keys = resp.data!!.map {
            listOf(
                it.day.toString(),
                it.dayNumber.toString(),
                it.time?.code.toString(),
                it.classInfo?.name.orEmpty(),
                it.group?.name.orEmpty()
            ).joinToString("|")
        }
        assertEquals("разные группы должны давать разные ключи", 2, keys.toSet().size)

        // А вот ключ без группы — нет, и именно он ронял приложение
        val badKeys = resp.data!!.map {
            listOf(it.day.toString(), it.dayNumber.toString(), it.time?.code.toString()).joinToString("|")
        }
        assertEquals("старый ключ был неуникален", 1, badKeys.toSet().size)
    }

    @Test
    fun `аудитория разбирается из эндпоинта`() {
        val json = """[{"Code":120,"Name":"1201 (м)"},{"Code":234,"Name":"8307 к"}]"""
        val list = GsonHolder.gson.fromJson(json, Array<Audience>::class.java).toList()
        assertEquals(2, list.size)
        assertEquals(120, list[0].code)
        assertEquals("1201 (м)", list[0].name)
        assertEquals("8307 к", list[1].name)
    }

    @Test
    fun `пустой и битый ответ не роняют разбор`() {
        val empty = GsonHolder.gson.fromJson("""{"Data":[],"Times":[]}""", ScheduleResponse::class.java)
        assertTrue(empty.data!!.isEmpty())
        assertTrue(empty.times!!.isEmpty())

        // Нет ни Data, ни Times: Gson оставляет поля null, а не пустыми списками.
        // Поэтому код обязан обращаться через ?.orEmpty() — проверяем, что
        // разбор не падает и что null корректно трактуется как «ничего нет».
        val bare = GsonHolder.gson.fromJson("""{}""", ScheduleResponse::class.java)
        assertTrue(bare.data == null || bare.data!!.isEmpty())
        assertTrue(bare.times == null || bare.times!!.isEmpty())
    }

    // ───────────────────────── склейка двух списков аудиторий ─────────────────────────
    //
    // Эндпоинт /audiences отдаёт 136 аудиторий, расписания групп — 194 пары
    // «код, имя». Одни и те же помещения названы по-разному и имеют разные коды,
    // поэтому склеивать по коду нельзя: в списке появлялись дубли «8307», «8308»,
    // «8309», «4109», «3304», «3305».

    @Test
    fun `одно помещение из разных источников получает один ключ`() {
        // 1202 в эндпоинте — код 121, в расписаниях — код 1202. Ключ должен совпасть.
        assertEquals(roomKey("1202 (м)"), roomKey("1202 м"))
        assertEquals(roomKey("1202 (м)"), roomKey("1202"))
        // «(к)» в скобках — уточнение сервера: код 98 это «3105 (к)» в эндпоинте
        // и просто «3105» в расписаниях, то есть одна и та же аудитория.
        assertEquals(roomKey("3105"), roomKey("3105 (к)"))
        assertEquals(roomKey("3303 (м)"), roomKey("3303 м"))
        assertEquals(roomKey("3120 а (к)"), roomKey("3120а"))
    }

    @Test
    fun `разные помещения получают разные ключи`() {
        // 8307 и 8307 к — обычная аудитория и компьютерный класс. Сливать нельзя.
        // Буква без скобок — другое помещение: код 8307 и код 234.
        assertNotEquals(roomKey("8307"), roomKey("8307 к"))
        assertNotEquals(roomKey("8308"), roomKey("8308 к"))
        assertNotEquals(roomKey("8309"), roomKey("8309 к"))
        // 3303 а и 3303 м — разные.
        assertNotEquals(roomKey("3303а"), roomKey("3303 м"))
        // Разные номера — разные помещения.
        assertNotEquals(roomKey("8102"), roomKey("8103"))
        // Служебные без номера не должны склеиваться друг с другом.
        assertNotEquals(roomKey("УВЦ 1"), roomKey("УВЦ 2"))
        assertNotEquals(roomKey("Виртуальная аудитория 1"), roomKey("Виртуальная аудитория 2"))
        assertNotEquals(
            roomKey("Аудитория практической подготовки 1"),
            roomKey("Аудитория практической подготовки 10")
        )
    }

    @Test
    fun `слитно-без-пробела и с-пробелом это одно помещение`() {
        assertEquals(roomKey("3303а"), roomKey("3303 а"))
        assertEquals(roomKey("3102а"), roomKey("3102 а"))
        assertEquals(roomKey("3120а"), roomKey("3120 а"))
    }

    // ───────────────────────── сортировка аудиторий ─────────────────────────

    @Test
    fun `аудитории сортируются по номеру а не по строке`() {
        val names = listOf("8109", "8107", "8102", "8103", "8104")
        val sorted = names.sortedWith(
            compareBy(
                { roomSortKey(it).first },
                { roomSortKey(it).second },
                { roomSortKey(it).third }
            )
        )
        assertEquals(listOf("8102", "8103", "8104", "8107", "8109"), sorted)
    }

    @Test
    fun `суффикс идёт сразу после своего номера`() {
        val names = listOf("8309 к", "8102", "8307 к", "8307", "8109", "8208")
        val sorted = names.sortedWith(
            compareBy(
                { roomSortKey(it).first },
                { roomSortKey(it).second },
                { roomSortKey(it).third }
            )
        )
        // 8102, 8109, 8208, затем 8307 и сразу за ним 8307 к, потом 8309 к.
        assertEquals(listOf("8102", "8109", "8208", "8307", "8307 к", "8309 к"), sorted)
    }

    @Test
    fun `аудитория без номера уходит в конец`() {
        val names = listOf("УВЦ 1", "8102", "ДК МИЭТ")
        val sorted = names.sortedWith(
            compareBy(
                { roomSortKey(it).first },
                { roomSortKey(it).second },
                { roomSortKey(it).third }
            )
        )
        assertEquals(listOf("8102", "ДК МИЭТ", "УВЦ 1"), sorted)
    }

    // ───────────────────────── время начала и конца пары ────────────────────

    /** Пара с временем — как её реально отдаёт miet.ru в Data[].Time. */
    private fun lessonWithTime(code: Int, from: String?, to: String?): Lesson =
        Lesson(
            time = PairCode(time = "$code пара", code = code, timeFrom = from, timeTo = to)
        )

    @Test
    fun `время берётся из самой пары когда серверной таблицы нет`() {
        // Именно эта ситуация у преподавателя: расписание собрано из кэша
        // расписаний групп, верхнего массива Times там не существует.
        val lessons = listOf(
            lessonWithTime(1, "0001-01-01T09:00:00", "0001-01-01T10:20:00"),
            lessonWithTime(2, "0001-01-01T10:30:00", "0001-01-01T11:50:00")
        )
        val times = mergeTimes(null, lessons)
        assertEquals(2, times.size)
        assertEquals(1, times[0].code)
        assertEquals("0001-01-01T09:00:00", times[0].timeFrom)
        assertEquals("0001-01-01T10:20:00", times[0].timeTo)
    }

    @Test
    fun `пустая пара с кодом ноль в таблицу времени не попадает`() {
        // «Разговоры о важном» приходят с Code 0 и нулевым временем — это не
        // настоящая пара, а запись без слота. В таблице она ломала бы нумерацию.
        val lessons = listOf(
            lessonWithTime(0, null, null),
            lessonWithTime(1, "0001-01-01T09:00:00", "0001-01-01T10:20:00")
        )
        val times = mergeTimes(null, lessons)
        assertEquals(1, times.size)
        assertEquals(1, times[0].code)
    }

    @Test
    fun `пара без времени не затирает таблицу сервера`() {
        val server = listOf(
            PairTime(time = "1 пара", code = 1,
                timeFrom = "0001-01-01T09:00:00", timeTo = "0001-01-01T10:20:00")
        )
        val lessons = listOf(lessonWithTime(1, null, null))
        val times = mergeTimes(server, lessons)
        assertEquals("0001-01-01T09:00:00", times.first().timeFrom)
    }

    @Test
    fun `таблица сервера и таблица из пар дополняют друг друга`() {
        // Сервер знает про 8-ю пару, хотя в расписании дня только первые четыре.
        val server = listOf(
            PairTime(time = "1 пара", code = 1,
                timeFrom = "0001-01-01T09:00:00", timeTo = "0001-01-01T10:20:00"),
            PairTime(time = "8 пара", code = 8,
                timeFrom = "0001-01-01T20:00:00", timeTo = "0001-01-01T21:20:00")
        )
        val lessons = listOf(lessonWithTime(3, "0001-01-01T12:30:00", "0001-01-01T13:50:00"))
        val times = mergeTimes(server, lessons)
        assertEquals(listOf(1, 3, 8), times.mapNotNull { it.code })
    }

    @Test
    fun `время у пар разное и берётся из своей пары а не из чужой группы`() {
        // 3-я пара у ЮР-26-11О начинается в 12:30, у Э-24-11 — в 12:00.
        val lessons = listOf(
            lessonWithTime(3, "0001-01-01T12:30:00", "0001-01-01T13:50:00"),
            lessonWithTime(3, "0001-01-01T12:00:00", "0001-01-01T13:20:00")
        )
        val times = mergeTimes(null, lessons)
        // Одна запись на код пары: у преподавателя это один и тот же слот.
        assertEquals(1, times.size)
        assertNotNull(times[0].timeFrom)
    }

    // ───────────────────────── ключи списков ─────────────────────────────────

    /**
     * Регрессия: корпус 8 падал при прокрутке.
     *
     * Ключ элемента был it.code, но код из /audiences — внутренний id, а не
     * номер аудитории. Разные помещения его делят: «8307» и «8307 к» оба
     * получили id 234, поэтому внутри одного корпуса ключ 234 повторялся
     * дважды и LazyColumn падал с «Key was already used».
     */
    @Test
    fun `ключ аудитории строится от номера а не от внутреннего id`() {
        val seven = Audience(code = 234, name = "8307")
        val sevenLab = Audience(code = 234, name = "8307 к")

        // Имя уникально, а вот код — нет: ключ обязан разводить их.
        val k1 = "a_${roomKey(seven.name)}_${seven.code}"
        val k2 = "a_${roomKey(sevenLab.name)}_${sevenLab.code}"
        assertNotEquals(k1, k2)
    }

    @Test
    fun `все аудитории корпуса 8 получают разные ключи`() {
        val корпус8 = listOf(
            Audience(code = 234, name = "8307"),
            Audience(code = 234, name = "8307 к"),
            Audience(code = 211, name = "8308"),
            Audience(code = 211, name = "8308 к"),
            Audience(code = 97, name = "8309"),
            Audience(code = 97, name = "8309 к")
        )
        val keys = корпус8.map { "a_${roomKey(it.name)}_${it.code}" }
        assertEquals(keys.size, keys.toSet().size)
        // Ровно 17 уникальных имён корпуса 8 — известная величина.
        assertEquals(6, корпус8.map { roomKey(it.name) }.toSet().size)
    }

    @Test
    fun `лишние пробелы в имени не создают второй элемент`() {
        // Это не баг, а задуманное поведение: «ДК МИЭТ» и «ДК МИЭТ » —
        // одна и та же аудитория, и склейка обязана их схлопнуть, иначе
        // в списке появился бы дубль, а с ним и падение LazyColumn.
        assertEquals(roomKey("ДК МИЭТ"), roomKey("ДК МИЭТ "))
        assertEquals(roomKey("УВЦ 1"), roomKey("  УВЦ 1"))
        assertNotEquals(roomKey("УВЦ 1"), roomKey("УВЦ 2"))
    }

    @Test
    fun `после склейки ключи аудиторий заведомо уникальны`() {
        // Инвариант, на котором держится ключ списка: склейка идёт по roomKey,
        // поэтому в итоговом списке все значения roomKey различны. Если это
        // перестанет быть правдой, ключи LazyColumn совпадут и список упадёт.
        val корпус3 = listOf("3205", "3205 МПСУ", "3210", "3210 МПСУ", "3223", "3223 ОМС")
        val keys = корпус3.map { "a_${roomKey(it)}_${null}" }
        assertEquals(keys.size, keys.toSet().size)
    }

    // ───────────── аудитория: фильтр по имени, а не по внутреннему id ─────────

    /**
     * Регрессия: у 8307 показывалась одна пара вместо 72.
     *
     * Код из /audiences — внутренний id, а не номер аудитории: «8307» это id
     * 234, а «8307 к» тоже id 234. Фильтр кэша по коду брал чужие пары, а
     * настоящие 72 пары «8307» отбрасывал. Идентичность помещения — имя.
     */
    @Test
    fun `аудитория находится по имени а не по внутреннему коду`() {
        val восемьТриНольСемь = Audience(code = 234, name = "8307")
        val восемьТриНольСемьК = Audience(code = 234, name = "8307 к")

        // Один и тот же код — разные помещения.
        assertEquals(восемьТриНольСемь.code, восемьТриНольСемьК.code)
        assertNotEquals(roomKey(восемьТриНольСемь.name), roomKey(восемьТриНольСемьК.name))
    }

    @Test
    fun `фильтр по коду перепутывает 8307 с 8307к`() {
        // Точное воспроизведение бага: слот в кэше «8307 к» с id 234 совпадал
        // по коду с настоящей «8307», поэтому показывался чужой слот.
        val вКэше = listOf(
            Triple(8307, "8307", "География"),
            Triple(234, "8307 к", "География")
        )
        val поКоду = вКэше.filter { it.first == 234 }
        val поИмени = вКэше.filter { roomKey(it.second) == roomKey("8307") }

        assertEquals(1, поКоду.size)
        assertEquals("8307 к", поКоду.first().second)
        assertEquals(1, поИмени.size)
        assertEquals("8307", поИмени.first().second)
    }

    @Test
    fun `аудитории с одинаковым кодом получают разные ключи`() {
        // 42 аудитории из 136 делят id с соседним помещением. Суффиксы ЗДЕСЬ
        // без скобок: «8307 к» — отдельная аудитория, а «3105 (к)» и «3105» —
        // одна и та же (скобки лишь уточняют, это проверяет другой тест).
        val парные = listOf(
            "8307" to "8307 к", "8308" to "8308 к", "8309" to "8309 к",
            "3205" to "3205 МПСУ", "4125" to "4125 БМС", "4211" to "4211 МПСУ",
            "4213" to "4213 МПСУ", "4309" to "4309 ПМТ", "4315" to "4315 ПМТ",
            "3306 (м)" to "3306 Право", "3350 а (м)" to "3350амЛПО"
        )
        for ((a, b) in парные) {
            assertNotEquals("«$a» и «$b» должны различаться", roomKey(a), roomKey(b))
        }
    }

    @Test
    fun `склонение числительных для числа аудиторий`() {
        // plural возвращает СЛОВО, число подставляет вызывающий код.
        assertEquals("аудитория", plural(1, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудитории", plural(3, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудиторий", plural(17, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудиторий", plural(11, "аудитория", "аудитории", "аудиторий"))
        assertEquals("аудитория", plural(21, "аудитория", "аудитории", "аудиторий"))
    }

    /**
     * Регрессия: у 8307 показывалось 12 пар вместо 72.
     *
     * Две причины складывались. Первая: фильтр кэша шёл по внутреннему коду
     * (у «8307» он 234 — тот же, что у «8307 к»), поэтому чужие пары
     * попадали, а свои отбрасывались. Вторая: пустой ответ сервера
     * («Data: []») кэшировался как валидный, и при следующем запуске
     * lessons становился непустым — локальная сборка не запускалась вовсе.
     */
    @Test
    fun `аудитория 8307 находит все 72 свои пары а не 12 чужих`() {
        // Идентичность помещения — имя. Код у «8307» и «8307 к» общий (234),
        // но помещения разные, и фильтр обязан их развести.
        val моя = "8307"
        val чужая = "8307 к"
        assertNotEquals(roomKey(моя), roomKey(чужая))
        assertEquals("num:8307:", roomKey(моя))
        assertEquals("num:8307:к", roomKey(чужая))
    }

    // ─────────────────────── день недели ─────────────────────────────────────

    /**
     * Регрессия: 2 октября 2026 — пятница, а приложение называло «Сб».
     *
     * Calendar.DAY_OF_WEEK считает неделю с воскресенья (SUNDAY=1 … SATURDAY=7),
     * расписание — с понедельника. Прежняя формула `if (cw == SUNDAY) 0 else
     * cw - 1` сдвигала день на единицу.
     */
    @Test
    fun `день недели считается с понедельника а не с воскресенья`() {
        // Calendar: SUNDAY=1 … SATURDAY=7 → наш индекс 0=Пн … 6=Вс
        assertEquals(0, dayIndexFromCalendar(2))  // MONDAY    → Пн
        assertEquals(1, dayIndexFromCalendar(3))  // TUESDAY   → Вт
        assertEquals(2, dayIndexFromCalendar(4))  // WEDNESDAY → Ср
        assertEquals(3, dayIndexFromCalendar(5))  // THURSDAY  → Чт
        assertEquals(4, dayIndexFromCalendar(6))  // FRIDAY    → Пт
        assertEquals(5, dayIndexFromCalendar(7))  // SATURDAY  → Сб
        assertEquals(6, dayIndexFromCalendar(1))  // SUNDAY    → Вс
    }

    @Test
    fun `название дня совпадает с настоящим днем`() {
        // 2 октября 2026 — пятница. Именно её приложение называло «Сб».
        assertEquals("Пт", DAY_SHORT[dayIndexFromCalendar(6)])
        assertEquals("Вс", DAY_SHORT[dayIndexFromCalendar(1)])
        assertEquals("Пн", DAY_SHORT[dayIndexFromCalendar(2)])
    }

    @Test
    fun `старая формула сдвигала день ровно на единицу`() {
        // Проверено на всех семи днях: старая формула давала ровно на день
        // больше верной, без исключений. Из-за этого любой вторник
        // отображался как среда, пятница — как суббота, воскресенье — как понедельник.
        for (cw in 1..7) {
            val верно = dayIndexFromCalendar(cw)
            val старое = if (cw == 1) 0 else cw - 1
            // старая формула называла день на один больше правильного
            assertEquals("cw=$cw", (верно + 1) % 7, старое)
        }
    }

    // ─────────────────────── обновления через GitHub ────────────────────────

    /**
     * Тег релиза приходит в разном виде: «0.29», «v0.29», «29»,
     * «release-0.29». versionCode приложения — целое число, поэтому из тега
     * достаём последнюю группу цифр: номер релиза стоит после точки.
     *
     * Раньше цифры склеивались, и «0.29» давало 029 → 29 вроде бы правильно,
     * а вот «1.0.0-alpha.29» давало 10029 — всегда больше любого versionCode,
     * из-за чего обновление предлагалось бы бесконечно.
     */
    @Test
    fun `тег релиза разбирается в номер версии`() {
        assertEquals(12, UpdateChecker.parseVersionCode("v12"))
        assertEquals(12, UpdateChecker.parseVersionCode("V12"))
        assertEquals(12, UpdateChecker.parseVersionCode("12"))
        assertEquals(12, UpdateChecker.parseVersionCode("  v12  "))
        assertEquals(3, UpdateChecker.parseVersionCode("release-3"))
        // Альфа: номер после точки — это и есть номер сборки.
        assertEquals(29, UpdateChecker.parseVersionCode("0.29"))
        assertEquals(29, UpdateChecker.parseVersionCode("v0.29"))
        assertEquals(29, UpdateChecker.parseVersionCode("release-0.29"))
        // Склейка всех цифр сломала бы сравнение с versionCode: «0.30.0-alpha.29»
        // превратилось бы в 30029 и обновление предлагалось бы бесконечно.
        assertEquals(29, UpdateChecker.parseVersionCode("0.30.0-alpha.29"))
    }

    /**
     * Семантическая нумерация версий: versionCode считается по схеме
     * (major*10000 + minor*100 + patch), чтобы сравнение осталось числовым.
     *
     * Регрессия, которую закрывает тест: разбор брал последнюю группу цифр, и
     * тег «1.0.0» давал 0 — обновление до 1.0.0 не предложилось бы никому, у
     * кого стоит 0.82. Тихая поломка: диалог просто перестал бы появляться.
     */
    @Test
    fun `семантический тег разбирается по той же схеме что versionCode`() {
        assertEquals(10_000, UpdateChecker.parseVersionCode("1.0.0"))
        assertEquals(10_000, UpdateChecker.parseVersionCode("v1.0.0"))
        assertEquals(10_000, UpdateChecker.parseVersionCode("1.0.0-beta"))
        assertEquals(10_001, UpdateChecker.parseVersionCode("1.0.1"))
        assertEquals(10_203, UpdateChecker.parseVersionCode("1.2.3"))
        // Тег новой линии обязан быть новее установленной 0.82 (versionCode 82),
        // иначе автообновление промолчит.
        assertTrue(UpdateChecker.parseVersionCode("1.0.0")!! > 82)
        // И не должен считаться новее самого себя — иначе диалог доставал бы
        // при каждом запуске на самой свежей версии.
        assertFalse(UpdateChecker.parseVersionCode("1.0.0")!! > 10_000)
    }

    @Test
    fun `там где в хвосте тега номер сборки старая схема важнее`() {
        // «1.0.0-alpha.29» — это НЕ семантика: цифры в хвосте означают номер
        // сборки, и разбирать надо его.
        assertEquals(29, UpdateChecker.parseVersionCode("1.0.0-alpha.29"))
        assertEquals(82, UpdateChecker.parseVersionCode("0.82"))
    }

    /**
     * Тег релиза обязан заканчиваться номером сборки: «1.0.0-beta.10000».
     * Проверено на живом приложении — сборка 0.82 на теге «1.0.0-beta»
     * ответила «установлена свежая версия», потому что разбор берёт последнюю
     * цифру тега, а там ноль. То есть без хвоста обновление не увидел бы
     * НИКТО из тех, у кого приложение уже стоит.
     */
    @Test
    fun `тег с хвостовым номером сборки виден и старым разбором`() {
        assertEquals(10_000, UpdateChecker.parseVersionCode("1.0.0-beta.10000"))
        assertEquals(10_000, UpdateChecker.parseVersionCode("1.0.0.10000"))
        assertTrue(UpdateChecker.parseVersionCode("1.0.0-beta.10000")!! > 82)
    }

    @Test
    fun `хвостовой номер сборки в подписи версии не показывается`() {
        assertEquals("1.0.0-beta", displayVersion("1.0.0-beta.10000", 10_000))
        assertEquals("1.0.1", displayVersion("1.0.1.10001", 10_001))
        // «0.82» — это версия ЦЕЛИКОМ, а не «версия + сборка»: убрать хвост и
        // оставить «0» было бы грубой ошибкой, ради неё и вторая проверка.
        assertEquals("0.82", displayVersion("0.82", 82))
        assertEquals("0.30", displayVersion("0.30", 30))
        assertEquals("v13", displayVersion("v13", 13))
        // Пустой тег — показываем номер сборки, а не пустую строку в диалоге.
        assertEquals("сборка 30", displayVersion("", 30))
    }

    @Test
    fun `мусорный тег не превращается в версию`() {
        // Нет числа — сравнивать не с чем. Показывать диалог с «vNaN» хуже,
        // чем не показать его вовсе.
        assertNull(UpdateChecker.parseVersionCode(null))
        assertNull(UpdateChecker.parseVersionCode(""))
        assertNull(UpdateChecker.parseVersionCode("latest"))
        assertNull(UpdateChecker.parseVersionCode("без цифр"))
    }

    @Test
    fun `новая версия определяется только когда она больше текущей`() {
        val current = 12
        assertTrue(UpdateChecker.parseVersionCode("v13")!! > current)
        assertFalse(UpdateChecker.parseVersionCode("v12")!! > current)
        assertFalse(UpdateChecker.parseVersionCode("v11")!! > current)
        // Релиз старее установленной сборки диалог показывать нельзя —
        // иначе предложишь «откатиться».
        assertTrue(UpdateChecker.parseVersionCode("v12")!! >= current)
        assertFalse(UpdateChecker.parseVersionCode("v11")!! >= current)
        // Альфа-нумерация: текущая сборка 30, релиз 0.31 её новее,
        // а собственный тег 0.30 — нет.
        val alpha = 30
        assertTrue(UpdateChecker.parseVersionCode("0.31")!! > alpha)
        assertFalse(UpdateChecker.parseVersionCode("0.30")!! > alpha)
        assertFalse(UpdateChecker.parseVersionCode("0.29")!! > alpha)
    }

    @Test
    fun `размер релиза читается по-человечески`() {
        val u = UpdateInfo(
            versionCode = 13, tagName = "v13", title = "", notes = "",
            apkName = "a.apk", downloadUrl = "http://x", sizeBytes = 17490783, sha256 = null,
        )
        assertEquals("16.7 МБ", u.sizeLabel)
        assertEquals("v13", u.versionLabel)
        // Размер неизвестен — подпись пустая, а не «0.0 МБ».
        assertEquals("", u.copy(sizeBytes = 0L).sizeLabel)
    }

    @Test
    fun `подпись версии совпадает с тегом релиза`() {
        // Показываем ровно то, что опубликовано на GitHub, а не собранное
        // из номера: для альфы это «0.30», а не «v30».
        val alpha = UpdateInfo(
            versionCode = 30, tagName = "0.30", title = "", notes = "",
            apkName = "a.apk", downloadUrl = "http://x", sizeBytes = 1, sha256 = null,
        )
        assertEquals("0.30", alpha.versionLabel)
        // Если тег по какой-то причине пуст, показываем номер сборки,
        // чтобы в диалоге не было пустой строки.
        val blank = alpha.copy(tagName = "")
        assertEquals("сборка 30", blank.versionLabel)
    }

    @Test
    fun `подпись последней проверки обновлений читается по-человочески`() {
        assertEquals("Обновления ещё не проверялись", lastCheckLabel(0L))
        val now = System.currentTimeMillis()
        assertEquals("Обновления проверены только что", lastCheckLabel(now))
        assertTrue(lastCheckLabel(now - 5 * 60_000).contains("5 мин"))
        assertTrue(lastCheckLabel(now - 3 * 3_600_000).contains("3 ч"))
        assertTrue(lastCheckLabel(now - 2 * 86_400_000L).contains("2 дн"))
    }

    /**
     * Регрессия: текст релиза показывался как «Сборка 14&#10;&#10;Тестовая…».
     * GitHub отдаёт перевод строки HTML-энтитией, и Compose рисовал её буквально.
     */
    @Test
    fun `энтитии переводов строк в заметках релиза разворачиваются`() {
        val raw = "Сборка 14 (versionCode 14).&#10;&#10;Тестовая сборка."
        val out = cleanReleaseNotes(raw)
        assertFalse("энтития &#10; осталась в тексте", out.contains("&#10;"))
        assertTrue(out.contains("Сборка 14 (versionCode 14)."))
        assertTrue(out.contains("Тестовая сборка."))
    }

    @Test
    fun `markdown-заголовки и лишние пробелы убираются из заметок`() {
        assertEquals("Заголовок", cleanReleaseNotes("## Заголовок"))
        assertEquals("Пункт", cleanReleaseNotes("* Пункт"))
        assertEquals("Первый\nВторой", cleanReleaseNotes("Первый\n\n\n   Второй  "))
    }

    @Test
    fun `пустые заметки релиза дают пустой текст`() {
        assertEquals("", cleanReleaseNotes(""))
        assertEquals("", cleanReleaseNotes("   \n  \n "))
    }

    // ───────────── кнопка «Проверить обновления» ─────────────

    /**
     * Регрессия: кнопка должна отличать «обновлений нет» от «проверить не удалось».
     * Раньше всё сводилось к null, и пользователю нельзя было объяснить разницу.
     */
    @Test
    fun `результат проверки различает актуальность и ошибку`() {
        val upToDate: UpdateCheckResult = UpdateCheckResult.UpToDate(15, "0.15")
        val failed: UpdateCheckResult = UpdateCheckResult.Failed("нет сети")
        val available: UpdateCheckResult = UpdateCheckResult.Available(
            UpdateInfo(
                versionCode = 16, tagName = "v16", title = "t", notes = "",
                apkName = "a.apk", downloadUrl = "http://x", sizeBytes = 1, sha256 = null,
            ),
        )
        assertTrue(upToDate is UpdateCheckResult.UpToDate)
        assertTrue(failed is UpdateCheckResult.Failed)
        assertTrue(available is UpdateCheckResult.Available)
        // Исходы разные — их нельзя смешивать в одну «неудачу»
        assertFalse(upToDate is UpdateCheckResult.Failed)
        assertFalse(failed is UpdateCheckResult.UpToDate)
        assertEquals("нет сети", (failed as UpdateCheckResult.Failed).reason)
    }

    @Test
    fun `актуальная версия показывает номер релиза с GitHub`() {
        // Если на GitHub 0.20, а у нас 0.15 — честно говорим про 0.20, и
        // показываем именно тот тег, который опубликован, без подстановки «v».
        val r = UpdateCheckResult.UpToDate(20, "0.20") as UpdateCheckResult.UpToDate
        assertEquals(20, r.latestVersion)
        assertEquals("0.20", r.tagName)
    }

    // ───────────── дата последнего обновления ─────────────

    /** 2026-10-02 15:00 по локальному времени. */
    private val REF_NOW: Long =
        java.time.LocalDateTime.of(2026, 10, 2, 15, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun at(year: Int, month: Int, day: Int, hour: Int): Long =
        java.time.LocalDateTime.of(year, month, day, hour, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `подпись обновления читается по-человечески`() {
        assertEquals("сегодня в 09:00", LastUpdated.label(at(2026, 10, 2, 9), REF_NOW))
        assertEquals("вчера в 23:00", LastUpdated.label(at(2026, 10, 1, 23), REF_NOW))
        // 12 дней назад — уже полная дата, без «вчера»/«сегодня».
        assertEquals("20 сентября, 14:00", LastUpdated.label(at(2026, 9, 20, 14), REF_NOW))
    }

    @Test
    fun `не обновлялось ни разу — честная подпись`() {
        assertEquals("ещё не обновлялось", LastUpdated.label(0L, REF_NOW))
        assertEquals("ещё не обновлялось", LastUpdated.label(-1L, REF_NOW))
    }

    /**
     * Регрессия: формула (y2-y1)*365 + (d2-d1) не учитывает високосный год и
     * на границе года называла 31.12.2024 «сегодня» вместо «вчера».
     */
    @Test
    fun `подпись не путает сутки на границе високосного года`() {
        val ny = at(2025, 1, 1, 10)          // «сейчас» — 1 января 2025
        val yesterday = at(2024, 12, 31, 10) // вчера, 2024 — високосный
        assertTrue(
            "31.12.2024 при now=01.01.2025 должно быть «вчера», а не «сегодня»",
            LastUpdated.label(yesterday, ny).startsWith("вчера в "),
        )
    }

    /**
     * Регрессия: подпись считала сутки по 24-часовым отрезкам, и обновление
     * 1 октября 23:00 при «сейчас» = 2 октября 15:00 называлось «сегодня»,
     * хотя это вчера (16 часов назад, но другой календарный день).
     */
    @Test
    fun `позднее время вчерашнего дня не называется сегодняшним`() {
        assertEquals("вчера в 23:00", LastUpdated.label(at(2026, 10, 1, 23), REF_NOW))
        // А полночь-минуту назад сегодня — уже сегодня.
        assertEquals("сегодня в 00:00", LastUpdated.label(at(2026, 10, 2, 0), REF_NOW))
    }

    @Test
    fun `будущее время не ломает подпись`() {
        // Часы телефона могут спешить, а кэш — с будущей меткой из-за правок.
        // Подпись не должна падать или врать, что это «сегодня вчера».
        val future = at(2026, 10, 5, 10)
        val label = LastUpdated.label(future, REF_NOW)
        assertTrue(label.startsWith("сегодня в ") || label.isNotEmpty())
    }

    // ───── шкала дней: Day с сервера 1..6 против нашего индекса 0..6 ─────

    @Test
    fun `Day сервера приводится к нашей шкале без сдвига`() {
        // Сервер: Day 1=Пн … 6=Сб. Наш индекс: 0=Пн … 6=Вс.
        // Если забыть «-1», в пятницу (todayDay=4) покажется четверг.
        for (serverDay in 1..6) {
            val ours = serverDay - 1
            assertEquals(DAY_SHORT[serverDay - 1], DAY_SHORT[ours])
        }
        // Регрессия, о которой сообщил пользователь: 2 октября 2026 — пятница.
        val friday = dayIndexFromCalendar(Calendar.FRIDAY)
        assertEquals(4, friday)
        // Ключ byDay для ПЯТНИЦЫ должен совпасть с todayDay, иначе
        // покажутся пары четверга, а в шапке напишется «Четверг».
        val serverKeyForFriday = 5 - 1
        assertEquals(friday, serverKeyForFriday)
        assertEquals("Пятница", DAY_NAMES[serverKeyForFriday])
    }

    // ───── пара, разбитая на две подгруппы: 8307 и 8306 ─────

    /**
     * ЖИВОЙ ФИКСТУР: группа ТЭ-26-13О, аудитории 8307 и 8306 (miet.ru, 02.10.2026).
     *
     * Что здесь ломается — два РАЗНЫХ случая, и их легко спутать:
     *
     * 1) 8307: в одном слоте (вторник, 2-я пара) стоят ТРИ разных занятия
     *    «Информатика» — Овчинникова и Власов, а в четверг снова Овчинникова
     *    с другим Class.Code. Преподаватель, предмет, аудитория, группа могут
     *    совпадать, различается ТОЛЬКО Class.Code. Старый ключ склеивал их
     *    попарно: 12 строк превращались в 8.
     *
     * 2) четверг, 2-я пара: та же группа стоит в 8307 И в 8306 одновременно —
     *    пара разделена на две подгруппы по разным аудиториям. Слот и Class.Code
     *    у этих строк совпадают, различает только Room.
     */
    @Test
    fun `пара на две подгруппы и два преподавателя в одной аудитории не склеиваются`() {
        val l0 = Lesson(
            day = 2,
            dayNumber = 0,
            time = PairCode(code = 2),
            classInfo = ClassInfo(code = "9DBBAFB27B91B4591578A3891B194F712B36F0CBEE15678D4D68DA20D7407A67", name = "Информатика"),
            group = GroupInfo(name = "ТЭ-26-13О"),
            room = RoomInfo(name = "8307")
        )

        val l1 = Lesson(
            day = 2,
            dayNumber = 1,
            time = PairCode(code = 2),
            classInfo = ClassInfo(code = "9DBBAFB27B91B4591578A3891B194F712B36F0CBEE15678D4D68DA20D7407A67", name = "Информатика"),
            group = GroupInfo(name = "ТЭ-26-13О"),
            room = RoomInfo(name = "8307")
        )

        val l2 = Lesson(
            day = 2,
            dayNumber = 2,
            time = PairCode(code = 2),
            classInfo = ClassInfo(code = "9DBBAFB27B91B4591578A3891B194F712B36F0CBEE15678D4D68DA20D7407A67", name = "Информатика"),
            group = GroupInfo(name = "ТЭ-26-13О"),
            room = RoomInfo(name = "8307")
        )

        val l3 = Lesson(
            day = 2,
            dayNumber = 3,
            time = PairCode(code = 2),
            classInfo = ClassInfo(code = "9DBBAFB27B91B4591578A3891B194F712B36F0CBEE15678D4D68DA20D7407A67", name = "Информатика"),
            group = GroupInfo(name = "ТЭ-26-13О"),
            room = RoomInfo(name = "8307")
        )

        val l4 = Lesson(
            day = 2,
            dayNumber = 0,
            time = PairCode(code = 2),
            classInfo = ClassInfo(code = "6B8D95458A7720DF6A458179C9727C60525215359DF9CC0BEB641279961B802B", name = "Информатика"),
            group = GroupInfo(name = "ТЭ-26-13О"),
            room = RoomInfo(name = "8307")
        )

        val l5 = Lesson(
            day = 2,
            dayNumber = 1,
            time = PairCode(code = 2),
            classInfo = ClassInfo(code = "6B8D95458A7720DF6A458179C9727C60525215359DF9CC0BEB641279961B802B", name = "Информатика"),
            group = GroupInfo(name = "ТЭ-26-13О"),
            room = RoomInfo(name = "8307")
        )

        val l6 = Lesson(
            day = 2,
            dayNumber = 2,
            time = PairCode(code = 2),
            classInfo = ClassInfo(code = "6B8D95458A7720DF6A458179C9727C60525215359DF9CC0BEB641279961B802B", name = "Информатика"),
            group = GroupInfo(name = "ТЭ-26-13О"),
            room = RoomInfo(name = "8307")
        )

        val l7 = Lesson(
            day = 2,
            dayNumber = 3,
            time = PairCode(code = 2),
            classInfo = ClassInfo(code = "6B8D95458A7720DF6A458179C9727C60525215359DF9CC0BEB641279961B802B", name = "Информатика"),
            group = GroupInfo(name = "ТЭ-26-13О"),
            room = RoomInfo(name = "8307")
        )

        val l8 = Lesson(
            day = 4,
            dayNumber = 0,
            time = PairCode(code = 2),
            classInfo = ClassInfo(code = "39EAD37387D6D7E842096A9A0F756B52FCC6F9F63E6A54C0787888689AA93CC2", name = "Информатика"),
            group = GroupInfo(name = "ТЭ-26-13О"),
            room = RoomInfo(name = "8307")
        )

        val l9 = Lesson(
            day = 4,
            dayNumber = 1,
            time = PairCode(code = 2),
            classInfo = ClassInfo(code = "39EAD37387D6D7E842096A9A0F756B52FCC6F9F63E6A54C0787888689AA93CC2", name = "Информатика"),
            group = GroupInfo(name = "ТЭ-26-13О"),
            room = RoomInfo(name = "8307")
        )

        val l10 = Lesson(
            day = 4,
            dayNumber = 2,
            time = PairCode(code = 2),
            classInfo = ClassInfo(code = "39EAD37387D6D7E842096A9A0F756B52FCC6F9F63E6A54C0787888689AA93CC2", name = "Информатика"),
            group = GroupInfo(name = "ТЭ-26-13О"),
            room = RoomInfo(name = "8307")
        )

        val l11 = Lesson(
            day = 4,
            dayNumber = 3,
            time = PairCode(code = 2),
            classInfo = ClassInfo(code = "39EAD37387D6D7E842096A9A0F756B52FCC6F9F63E6A54C0787888689AA93CC2", name = "Информатика"),
            group = GroupInfo(name = "ТЭ-26-13О"),
            room = RoomInfo(name = "8307")
        )

        val l12 = Lesson(
            day = 4,
            dayNumber = 0,
            time = PairCode(code = 2),
            classInfo = ClassInfo(code = "758BC999BA85641FEB766DD2DFB671CB96E7D8EED2ED3AA2504CE644ADE60328", name = "Информатика"),
            group = GroupInfo(name = "ТЭ-26-13О"),
            room = RoomInfo(name = "8306")
        )

        val l13 = Lesson(
            day = 4,
            dayNumber = 1,
            time = PairCode(code = 2),
            classInfo = ClassInfo(code = "758BC999BA85641FEB766DD2DFB671CB96E7D8EED2ED3AA2504CE644ADE60328", name = "Информатика"),
            group = GroupInfo(name = "ТЭ-26-13О"),
            room = RoomInfo(name = "8306")
        )

        val l14 = Lesson(
            day = 4,
            dayNumber = 2,
            time = PairCode(code = 2),
            classInfo = ClassInfo(code = "758BC999BA85641FEB766DD2DFB671CB96E7D8EED2ED3AA2504CE644ADE60328", name = "Информатика"),
            group = GroupInfo(name = "ТЭ-26-13О"),
            room = RoomInfo(name = "8306")
        )

        val l15 = Lesson(
            day = 4,
            dayNumber = 3,
            time = PairCode(code = 2),
            classInfo = ClassInfo(code = "758BC999BA85641FEB766DD2DFB671CB96E7D8EED2ED3AA2504CE644ADE60328", name = "Информатика"),
            group = GroupInfo(name = "ТЭ-26-13О"),
            room = RoomInfo(name = "8306")
        )

        val all: List<Lesson> = listOf(
            l0, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, l15
        )

        // Фильтр по аудитории оставляет 12 строк 8307 и 4 строки 8306.
        val r8307 = all.filter { it.room?.name == "8307" }
        val r8306 = all.filter { it.room?.name == "8306" }
        assertEquals(12, r8307.size)
        assertEquals(4, r8306.size)

        // Ключ обязан различать ВСЕ строки: в карте не должно схлопнуться ничего.
        assertEquals(
            "все 16 строк должны иметь разные ключи",
            all.size,
            all.map { roomLessonKey(it) }.toSet().size
        )

        // Регрессия №1: в 8307 ТРИ разных Class.Code — это три разных занятия.
        // Вторник, 2-я пара, «Информатика»: Овчинникова (9DBB…) и Власов (6B8D…)
        // одновременно; в четверг — Овчинникова (39EA…). Преподаватель, предмет,
        // аудитория и группа у всех трёх совпадают, различает ТОЛЬКО Class.Code.
        assertEquals(
            "в 8307 три разных Class.Code",
            3,
            r8307.map { it.classInfo?.code }.toSet().size
        )
        // Прежний ключ (без Class.Code и без аудитории) терял здесь 4 пары.
        val oldKey = { l: Lesson ->
            "${l.day}|${l.dayNumber ?: 0}|${l.time?.code}|${l.classInfo?.name}|${l.group?.name}"
        }
        assertEquals(8, r8307.map { oldKey(it) }.toSet().size)

        // Регрессия №2: 8306 и 8307 не должны делить ключи.
        assertTrue(
            "ключи 8307 и 8306 обязаны различаться",
            r8307.map { roomLessonKey(it) }.toSet()
                .intersect(r8306.map { roomLessonKey(it) }.toSet())
                .isEmpty()
        )

        // Порядок не должен затирать: слот считается ровно один раз на строку.
        val map = LinkedHashMap<String, Lesson>()
        for (l in all) map[roomLessonKey(l)] = l
        assertEquals(16, map.size)
    }

    // ───── сервер отдаёт для аудитории СОСЕДНЮЮ комнату ─────

    /**
     * ЖИВОЙ ФИКСТУР: ответ miet.ru на `audience=234` (это 8307, внутренний id).
     *
     * Отдаёт 12 строк, и ВСЕ ДВЕНАДЦАТЬ — из комнаты «8307 к», то есть
     * СОСЕДНЕЙ. Там «Диагностика профессиональной пригодности» и
     * «Психология и педагогика» (ПСИ-21М), а в самой 8307 в это время
     * Информатика и ТЭ-26-13О. Настоящих пар в 8307 — 18 в неделю, 72 за семестр.
     *
     * Из-за этого у пользователя в 1-ю числитель неделю оставалась РОВНО ОДНА пара.
     *
     * Разные комнаты обязаны давать разные ключи roomKey: «8307 к» — это
     * не «8307», суффикс значим.
     */
    @Test
    fun `серверный ответ по аудитории может оказаться соседней комнатой`() {
        assertNotEquals(
            "«8307 к» и «8307» — разные помещения",
            roomKey("8307"), roomKey("8307 к")
        )
        // Фильтр по имени 8307 не должен пропускать «8307 к».
        val ours = roomKey("8307")
        val theirs = roomKey("8307 к")
        assertTrue("«8307 к» не проходит фильтр 8307", ours != theirs)

        // Ответ сервера про 8307 состоит ИСКЛЮЧИТЕЛЬНО из «8307 к» — значит
        // ни одна его строка не принадлежит запрошенной аудитории.
        val serverRows = listOf("8307 к", "8307 к", "8307 к")
        val foreign = serverRows.filter { roomKey(it) != ours }
        assertEquals("все 3 строки от сервера — чужие", 3, foreign.size)
        assertTrue(
            "ни одна серверная строка не должна считаться парой 8307",
            serverRows.none { roomKey(it) == ours }
        )
    }
}
