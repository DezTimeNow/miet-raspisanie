package com.mietschedule.app

import java.util.Calendar

/**
 * БЛИЖАЙШАЯ ПАРА И ЗАНЯТОСТЬ АУДИТОРИЙ.
 *
 * Две независимые вещи, которые обе упираются в одну нехватку данных: у
 * [Lesson] нет абсолютной даты — только день недели (1..6) и номер пары, а
 * время хранится строкой в [PairTime]. Значит время начала пары можно
 * получить только через таблицу времени, и без неё карточка остаётся с
 * номером, но без часов.
 *
 * Все функции чистые: принимают пары и момент времени, отдают значение.
 * Текущий день выводится из аргумента `now`, а не из Calendar.getInstance()
 * — иначе результат зависел бы от даты запуска теста.
 */
object NextLessonLogic {

    /**
     * Пара вместе со своим временем.
     *
     * Считать время в UI отдельно нельзя: раньше оно вычислялось вторым,
     * независимым проходом по расписанию, и две копии расчёта разъезжались
     * — строка показывала одну дату, а счётчик «через N минут» считался от
     * другой. Здесь время приходит вместе с парой, единым куском.
     */
    data class Hit(
        val lesson: Lesson,
        /** Момент начала в миллисекундах. null — времени нет ни в паре, ни в таблице. */
        val start: Long?,
        /** Момент окончания. null, если таблица времени неполна. */
        val end: Long?,
    )

    /**
     * Момент начала пары.
     *
     * ВРЕМЯ САМОЙ ПАРЫ ВАЖНЕЕ ТАБЛИЦЫ. Сетка звонков у групп разная: 3-я пара
     * у ЮР-26-11О начинается в 12:30, у Э-24-11 — в 12:00 (проверено на живом
     * ответе miet.ru). Таблица, собранная от другой группы, давала главному
     * экрану 12:00 для пары, которая в расписании стоит в 12:30: сам экран
     * расписания берёт время своей группы и показывает верно, а блок
     * «Пары на сегодня» брал общую таблицу из кэша и врал на полчаса.
     *
     * Таблица остаётся запасным путём — она нужна там, где у пары времени
     * нет вовсе (часть аудиторий корпуса 8).
     */
    fun startMillis(lesson: Lesson, times: List<PairTime>, dayDate: Calendar): Long? {
        val code = lesson.time?.code ?: return null
        val own = parseHhMm(lesson.time?.timeFrom)
        val from = own ?: parseHhMm(times.firstOrNull { it.code == code }?.timeFrom) ?: return null
        return atMinutes(dayDate, from)
    }

    /** Тот же момент, но со временем конца пары. */
    fun endMillis(lesson: Lesson, times: List<PairTime>, dayDate: Calendar): Long? {
        val code = lesson.time?.code ?: return null
        val own = parseHhMm(lesson.time?.timeTo)
        val to = own ?: parseHhMm(times.firstOrNull { it.code == code }?.timeTo) ?: return null
        return atMinutes(dayDate, to)
    }

    private fun atMinutes(date: Calendar, hm: IntArray?): Long? {
        if (hm == null) return null
        return Calendar.getInstance().apply {
            timeInMillis = date.timeInMillis
            set(Calendar.HOUR_OF_DAY, hm[0])
            set(Calendar.MINUTE, hm[1])
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * Вытащить «часы:минуты» из строки времени.
     *
     * Сервер отдаёт время двумя способами: «08:00» и «2026-09-01T08:00:00».
     * Второй длиннее, поэтому ищем вхождение, а не префикс.
     */
    fun parseHhMm(raw: String?): IntArray? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("(\\d{1,2}):(\\d{2})").find(raw) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        return intArrayOf(h, min)
    }

    /** Идёт ли пара прямо сейчас. */
    fun isGoing(hit: Hit, now: Long): Boolean {
        val start = hit.start ?: return false
        if (start >= now) return false
        // Времени конца нет у части аудиторий корпуса 8. Тогда считаем пару
        // идущей в пределах 90 минут от начала, иначе строка «сейчас»
        // зависала бы на всех парах без времени.
        val end = hit.end ?: (start + 90L * 60 * 1000)
        return now < end
    }

    /**
     * Пара, которую человек видит в строке «сейчас»: идущая или ближайшая.
     *
     * Идущая пара приоритетнее следующей: если пара идёт сейчас, отдаётся
     * именно она. Это ПРЕЖНЕЕ поведение функции, и менять его нельзя — на нём
     * стоит строка на главном экране.
     *
     * Обёртка над [currentAndNext] отдаёт ту же пару, что и раньше: сначала
     * идущая, иначе ближайшая. Первая попытка возвращала только следующую
     * (`.second`) — и существовавший тест «идущая пара показывается а не
     * следующая» упал сразу. Отсюда явный `?:`, а не `.second`.
     */
    fun next(
        lessons: List<Lesson>,
        times: List<PairTime>,
        now: Long,
        withinDays: Int = 7,
        semestrStartIso: String = WeekType.SEMESTR_START_ISO,
    ): Hit? {
        val (going, upcoming) = currentAndNext(lessons, times, now, withinDays, semestrStartIso)
        return going ?: upcoming
    }

    /**
     * Идущая пара и ближайшая следующая — обе сразу.
     *
     * Зачем две. Студент смотрит на телефон между парами, и ему нужны оба
     * ответа сразу: «что сейчас» и «что дальше». Одной строки мало: если шла
     * пара, следующая не была видна вовсе, и человек считал, что после этой
     * пары у него ничего нет.
     *
     * Идущая и следующая не могут совпасть: идущая по [isGoing] уже
     * началась, следующая по определению начинается позже [now].
     *
     * Пары фильтруются по строке УЧЕБНОЙ недели (`DayNumber`), причём строка
     * считается для каждого дня окна отдельно. Без этой проверки вовсе блок
     * показывал пару чужой недели: у ТЭС-26-11О в среду 4-й парой стоит
     * «География» (недели 0 и 2) и «Введение в специальность» (недели 1 и 3),
     * и в знаменатель на главной показывалась география, хотя внутри
     * расписания — введение в специальность. Экран расписания фильтрует
     * ровно так же, и расхождение было между экранами.
     */
    fun currentAndNext(
        lessons: List<Lesson>,
        times: List<PairTime>,
        now: Long,
        withinDays: Int = 7,
        semestrStartIso: String = WeekType.SEMESTR_START_ISO,
    ): Pair<Hit?, Hit?> {
        if (lessons.isEmpty()) return null to null

        val semestrStart = WeekType.parseIso(semestrStartIso)
        val hasWeekRows = lessons.any { it.dayNumber != null }

        val hits = mutableListOf<Hit>()
        for (dayIn in 0..withinDays) {
            hits += hitsOfDay(lessons, times, dateOf(now, dayIn), semestrStart, hasWeekRows)
        }

        // Идущая: началась раньше «сейчас» и ещё не кончилась.
        val going = hits.filter { isGoing(it, now) }.maxByOrNull { it.start ?: 0L }
        // Следующая — строго позже «сейчас». Порог `now`, а не конец идущей:
        // при пересекающихся парах идущая кончается позже следующей, и
        // фильтр по её концу молча проглатывал бы вторую пару.
        val upcoming = hits.filter { (it.start ?: 0L) > now }.minByOrNull { it.start ?: 0L }

        return going to upcoming
    }

    /** Полночь дня со сдвигом [dayOffset] от момента [now]. */
    private fun dateOf(now: Long, dayOffset: Int): Calendar =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, dayOffset)
        }

    /**
     * Пары одного календарного дня.
     *
     * ЕДИНСТВЕННОЕ место, где решается, какие пары попадают в день: день
     * недели и строка УЧЕБНОЙ недели. Строка считается для конкретного дня, а
     * не одна на всё окно: окно в семь дней переходит в следующую учебную
     * неделю, и её пары лежат в другой строке `DayNumber`.
     */
    private fun hitsOfDay(
        lessons: List<Lesson>,
        times: List<PairTime>,
        date: Calendar,
        semestrStart: Calendar?,
        hasWeekRows: Boolean,
    ): List<Hit> {
        val weekDay = dayIndexFromCalendar(date.get(Calendar.DAY_OF_WEEK))
        val weekRow = semestrStart?.let { WeekType.typeFor(date, it) }
        val out = mutableListOf<Hit>()
        for (l in lessons) {
            if ((l.day ?: 1) - 1 != weekDay) continue
            if (hasWeekRows && weekRow != null && l.dayNumber != weekRow) continue
            val start = startMillis(l, times, date)
            // Пара без времени таблицы пропускается: показывать её нечем.
            // Номер пары без часов в строке «сейчас» вводит в заблуждение.
            if (start == null) continue
            out += Hit(l, start, endMillis(l, times, date))
        }
        return out
    }

    /** Состояние пары в списке дня. */
    enum class LessonState {
        /** Пара закончилась. */
        PAST,

        /** Пара идёт прямо сейчас. */
        GOING,

        /** Пара ещё будет сегодня. */
        FUTURE,
    }

    /** Пара дня: когда начинается, когда кончается и что с ней сейчас. */
    data class DayLesson(
        val lesson: Lesson,
        val start: Long,
        /** Конец пары, если сервер его отдал. У части аудиторий корпуса 8 его нет. */
        val end: Long?,
        val state: LessonState,
    )

    /**
     * Все пары одного дня по порядку начала.
     *
     * Нужно блоку на главной: он показывает день целиком — прошедшие
     * приглушёнными, идущую подсвеченной с остатком времени, будущие с
     * отсчётом. До этого блок показывал только идущую и следующую пары, и
     * человек не видел, сколько у него всего на сегодня.
     */
    fun dayLessons(
        lessons: List<Lesson>,
        times: List<PairTime>,
        now: Long,
        dayOffset: Int = 0,
        semestrStartIso: String = WeekType.SEMESTR_START_ISO,
    ): List<DayLesson> {
        if (lessons.isEmpty()) return emptyList()
        return hitsOfDay(
            lessons = lessons,
            times = times,
            date = dateOf(now, dayOffset),
            semestrStart = WeekType.parseIso(semestrStartIso),
            hasWeekRows = lessons.any { it.dayNumber != null },
        )
            .sortedBy { it.start ?: 0L }
            .mapNotNull { hit ->
                val start = hit.start ?: return@mapNotNull null
                // Конец без данных сервера считаем так же, как в isGoing: пара
                // идёт в пределах 90 минут. Это приближение, поэтому остаток
                // времени показывается только когда конец известен.
                val end = hit.end
                val state = when {
                    start > now -> LessonState.FUTURE
                    now < (end ?: (start + 90L * 60 * 1000)) -> LessonState.GOING
                    else -> LessonState.PAST
                }
                DayLesson(hit.lesson, start, end, state)
            }
    }


    /**
     * Занятость аудитории [room] в указанной паре дня [day].
     *
     * Ответ на вопрос кнопки «Показать свободные»: комната свободна, если
     * в неё не стоит ни одна пара. Сравнение по [roomKey], а не по строке: в
     * списке и в паре имя может отличаться («1201 (м)» и «1201»).
     *
     * @param dayNumberFilter номер учебной недели (DayNumber 0..3) либо null —
     *   не фильтровать. Занятость считается по конкретной учебной неделе:
     *   в «1-й числитель» и «1-й знаменатель» у одной группы разные пары, и
     *   без этого фильтра комната выглядела бы занятой из-за пары, которая
     *   сегодня не проходит.
     */
    fun isRoomBusy(
        room: String,
        lessons: List<Lesson>,
        day: Int,
        pairCode: Int?,
        dayNumberFilter: Int? = null,
    ): Boolean = lessons.any { l ->
        (l.day ?: 1) - 1 == day &&
            (dayNumberFilter == null || (l.dayNumber ?: 0) == dayNumberFilter) &&
            (pairCode == null || (l.time?.code ?: -1) == pairCode) &&
            roomKey(l.room?.name) == roomKey(room)
    }

    /** Корпус аудитории для группировки списка. */
    fun buildingOf(room: String): String =
        com.mietschedule.app.buildingOf(room)
}