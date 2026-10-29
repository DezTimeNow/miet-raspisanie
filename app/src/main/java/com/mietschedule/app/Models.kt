package com.mietschedule.app

import com.google.gson.annotations.SerializedName

// Ответ POST /schedule/data
data class ScheduleResponse(
    @SerializedName("Times") val times: List<PairTime>? = null,
    @SerializedName("Data") val data: List<Lesson>? = null,
    @SerializedName("Semestr") val semestr: String? = null
)

data class PairTime(
    @SerializedName("Time") val time: String? = null,
    @SerializedName("Code") val code: Int? = null,
    @SerializedName("TimeFrom") val timeFrom: String? = null,
    @SerializedName("TimeTo") val timeTo: String? = null
)

data class Lesson(
    @SerializedName("Day") val day: Int? = null,
    @SerializedName("DayNumber") val dayNumber: Int? = null,
    @SerializedName("Time") val time: PairCode? = null,
    @SerializedName("Class") val classInfo: ClassInfo? = null,
    @SerializedName("Group") val group: GroupInfo? = null,
    @SerializedName("Room") val room: RoomInfo? = null
)

data class PairCode(
    // ВАЖНО: Time приходит строкой ("2 пара"), а не числом. Если объявить Int?,
    // Gson падает с NumberFormatException на всём ScheduleResponse — и любые
    // вычисления над расписанием молча получают пустой список.
    @SerializedName("Time") val time: String? = null,
    @SerializedName("Code") val code: Int? = null,
    @SerializedName("TimeFrom") val timeFrom: String? = null,
    @SerializedName("TimeTo") val timeTo: String? = null
)

data class ClassInfo(
    @SerializedName("Code") val code: String? = null,
    @SerializedName("Name") val name: String? = null,
    @SerializedName("TeacherFull") val teacherFull: String? = null,
    @SerializedName("Teacher") val teacher: String? = null,
    @SerializedName("Form") val form: Boolean? = null
)

data class GroupInfo(
    @SerializedName("Code") val code: String? = null,
    @SerializedName("Name") val name: String? = null
)

data class RoomInfo(
    // ВАЖНО: сервер отдаёт Code НИМЕРОМ (например 120), а не строкой.
    // Gson по умолчанию не умеет «съедать» int в String и падает с
    // NumberFormatException, из-за чего весь ScheduleResponse не парсился
    // целиком, а diff молча возвращал пустой список. Тип Any? + свой
    // нормализатор в MietApi решают это для любых вариантов ответа.
    @SerializedName("Code") val code: Any? = null,
    @SerializedName("Name") val name: String? = null
)

/**
 * Код аудитории приходит как Any: проверено, miet.ru отдаёт `120` числом, но
 * полагаться на это нельзя. Приводим к Int или null — 120, "120" и " 120 "
 * дадут одинаковый результат, мусор — null.
 */
fun RoomInfo.roomCode(): Int? = when (val c = code) {
    is Int -> c
    is Number -> c.toInt()
    is String -> c.trim().toIntOrNull()
    else -> null
}

/**
 * Таблица времени пар, собранная из самих пар.
 *
 * Зачем: у преподавателя и у части аудиторий верхний массив `Times` не
 * доезжает вовсе — расписание преподавателя собирается из кэша расписаний
 * групп, где таблицы нет, поэтому карточка получала номер пары без времени.
 * При этом `TimeFrom`/`TimeTo` есть прямо в каждой паре: проверено на 1973 парах
 * 25 групп — заполнено 100%. Значит время можно взять оттуда всегда.
 *
 * Время у пары может отличаться от таблицы группы (3-я пара у ЮР-26-11О
 * начинается в 12:30, у Э-24-11 — в 12:00), поэтому за основу берём пару, а
 * таблицу используем только там, где у самой пары времени нет.
 */
fun collectTimes(lessons: List<Lesson>): List<PairTime> {
    val byCode = LinkedHashMap<Int, PairTime>()
    for (l in lessons) {
        val tc = l.time ?: continue
        val code = tc.code ?: continue
        // Code 0 — не настоящая пара: у «Разговоров о важном» слота нет,
        // сервер отдаёт нули вместо времени. В таблицу времени он не идёт.
        if (code == 0) continue
        if (!byCode.containsKey(code)) {
            byCode[code] = PairTime(time = tc.time, code = code, timeFrom = tc.timeFrom, timeTo = tc.timeTo)
        }
    }
    return byCode.values.sortedBy { it.code }
}

/**
 * Соединяет таблицу времени от сервера с тем, что нашлось в самих парах.
 *
 * Серверная таблица полнее (в ней до 8 пар, даже если в расписании дня только
 * четыре), поэтому она идёт первой. Но пара — источник истины, если в таблице
 * нужной пары нет или время в ней пустое.
 */
fun mergeTimes(server: List<PairTime>?, lessons: List<Lesson>): List<PairTime> {
    val byCode = LinkedHashMap<Int, PairTime>()
    for (t in server.orEmpty()) {
        val code = t.code ?: continue
        if (code == 0) continue
        byCode[code] = t
    }
    for (t in collectTimes(lessons)) {
        val code = t.code ?: continue
        val old = byCode[code]
        val oldHasTime = !old?.timeFrom.isNullOrBlank() && !old?.timeTo.isNullOrBlank()
        if (!oldHasTime) byCode[code] = t
    }
    return byCode.values.sortedBy { it.code }
}

// Эндпоинты miet.ru
object ApiPaths {
    const val BASE = "https://miet.ru/schedule/"
    const val GROUPS = "groups"
    const val DATA = "data"
    const val AUDIENCES = "audiences"
}

/** Аудитория из POST /schedule/audiences: [{"Code":120,"Name":"1201 (м)"}]. */
data class Audience(
    @SerializedName("Code") val code: Int? = null,
    @SerializedName("Name") val name: String? = null
)

/**
 * Кто смотрит расписание. Влияет и на экран выбора, и на формат избранного:
 * у студента избранное — группы, у преподавателя — ФИО, у аудитории — номера.
 */
enum class Role(val key: String, val title: String, val hint: String) {
    STUDENT("student", "Студент", "Моя группа"),
    TEACHER("teacher", "Преподаватель", "Расписание преподавателя"),
    AUDIENCE("audience", "Аудитория", "Кто и когда в ней");

    companion object {
        fun fromKey(k: String?): Role = entries.firstOrNull { it.key == k } ?: STUDENT
    }
}
