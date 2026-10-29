package com.mietschedule.app

import java.util.Calendar

/**
 * ЧТО ИДЁТ СЕЙЧАС И ЧТО СВОБОДНО ПРЯМО СЕЙЧАС.
 *
 * Отдельный файл потому, что обе задачи — про «сейчас», а время в приложении
 * приходит из таблицы пар [PairTime], которая живёт внутри расписания, а на
 * экране выбора аудитории её нет. Считать время прямо в UI нельзя: там уже
 * есть свой расчёт (см. [NextLessonLogic]), и две копии одного и того же
 * разъезжаются — фильтр отсеивает не те комнаты, а строка «сейчас» показывает
 * другую пару.
 *
 * Все функции чистые: принимают момент времени и таблицу пар, отдают значение.
 * Текущий день берётся из аргумента `now`, а не из Calendar.getInstance() —
 * иначе результат зависел бы от даты запуска теста.
 */
object NowLogic {

    /** Границы пары по её номеру. Обе null — времени у номера нет. */
    data class PairWindow(val code: Int, val fromMinutes: Int, val toMinutes: Int)

    /**
     * Таблица времени в виде «номер пара → минуты от полуночи».
     *
     * Разбирать один раз и хранить числами, а не строками: сравнение со
     * строкой «09:00» ломается на «10:00» (строка меньше, хотя пара позже),
     * и тогда фильтр считал бы комнату занятой не в те минуты.
     */
    fun windowTable(times: List<PairTime>): Map<Int, PairWindow> {
        val out = HashMap<Int, PairWindow>()
        for (t in times) {
            val code = t.code ?: continue
            // Номер 0 — не пара: у «Разговоров о важном» слота нет, сервер
            // отдаёт нули. В таблицу он не идёт, иначе он занял бы всё время
            // суток как «пара без времени».
            if (code <= 0) continue
            val from = NextLessonLogic.parseHhMm(t.timeFrom) ?: continue
            val to = NextLessonLogic.parseHhMm(t.timeTo) ?: continue
            out[code] = PairWindow(code, from[0] * 60 + from[1], to[0] * 60 + to[1])
        }
        return out
    }

    /** Минуты от полуночи для момента времени. */
    fun minutesOf(millis: Long): Int = Calendar.getInstance().apply {
        timeInMillis = millis
    }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    /**
     * Насколько длинная пауза ещё считается перерывом, а не концом дня.
     *
     * Между парами МИЭТ бывает 10–20 минут. Всё, что длиннее, — это уже не
     * перерыв, а вечер или ночь, и показывать «свободны по паре 1» в три
     * часа ночи бессмысленно: человек спит, а не ищет аудиторию.
     */
    const val MAX_BREAK_MINUTES = 90

    /**
     * Номер пары, идущей прямо сейчас.
     *
     * Отвечает на вопрос кнопки «Показать свободные»: «свободные на данную
     * минуту» — это «свободные в той паре, которая идёт». В перерыве между
     * парами отдаётся ближайшая следующая: человек спросил про сейчас, и
     * показывать ему «сейчас никто не занимает аудитории» — то есть все
     * 188 комнат — бесполезно. Лучше сказать «сейчас перерыв, свободны на
     * следующей паре», и человек получит полезный ответ.
     *
     * Откат на следующую пару ограничен [MAX_BREAK_MINUTES]. Без ограничения
     * ночью отдавалась первая пара завтрашнего дня: в 03:00 фильтр писал «занято
     * по первой паре» и убирал из списка комнаты, которые завтра утром как раз
     * освободятся.
     *
     * null — сейчас не время пар: ночь, выходной либо в таблице нет пар.
     */
    fun currentPairCode(table: Map<Int, PairWindow>, now: Long): Int? {
        if (table.isEmpty()) return null
        val m = minutesOf(now)
        // 1) Идущая пара: началась и ещё не кончилась.
        for (w in table.values.sortedBy { it.fromMinutes }) {
            if (m >= w.fromMinutes && m < w.toMinutes) return w.code
        }
        // 2) Перерыв: ближайшая пара, если до неё не дальше MAX_BREAK_MINUTES.
        val next = table.values.filter { it.fromMinutes > m }.minByOrNull { it.fromMinutes }
        return if (next != null && next.fromMinutes - m <= MAX_BREAK_MINUTES) next.code else null
    }

    /**
     * Номер пары для подписи фильтра: та же пара, что и в [currentPairCode],
     * но подпись всегда внятная.
     *
     * @return пара и признак «идёт ли она сейчас» для слов в интерфейсе.
     */
    fun pairLabelState(table: Map<Int, PairWindow>, now: Long): Pair<Int, Boolean> {
        val code = currentPairCode(table, now) ?: return 0 to false
        val m = minutesOf(now)
        val w = table[code] ?: return code to false
        return code to (m >= w.fromMinutes && m < w.toMinutes)
    }

    /**
     * Сколько минут осталось до конца идущей пары.
     *
     * Для подписи «осталось 12 мин»: без неё человек не понимает, дожидаться
     * ему пары или искать другую аудиторию. null — пара не идёт.
     */
    fun minutesLeft(table: Map<Int, PairWindow>, now: Long): Int? {
        val code = currentPairCode(table, now) ?: return null
        val w = table[code] ?: return null
        val m = minutesOf(now)
        return if (m >= w.fromMinutes && m < w.toMinutes) w.toMinutes - m else null
    }
}
