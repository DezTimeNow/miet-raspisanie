package com.mietschedule.app

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/**
 * Подписи времени в шапке расписания.
 *
 * Разделяем два разных факта, которые раньше смешивались в одну строку
 * «Обновлено …»:
 *
 *  - когда данные УСПЕШНО записали в кэш (последняя реальная загрузка);
 *  - когда приложение ПОПЫТАЛОСЬ уточнить их у сайта (последняя проверка).
 *
 * Разница видна без сети: попытка была, а данные остались старыми. Если
 * подпись одна на оба случая, недельный кэш читается как «обновлено
 * сегодня».
 *
 * Время проверки хранится отдельно в [MietApi.lastCheckAt].
 */
object LastUpdated {

    /** Полная дата+время: «2 октября, 14:32». */
    private fun full(ts: Long): String =
        SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(ts))

    /** Короткое время: «14:32». */
    private fun time(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale("ru")).format(Date(ts))

    /**
     * Сколько КАЛЕНДАРНЫХ суток прошло между двумя моментами.
     *
     * Считаем через `LocalDate`, а не через `ChronoUnit.DAYS.between(Instant…)`
     * и не через `(y2 - y1) * 365 + (d2 - d1)`. Обе эти формулы меряют не то:
     *  - `ChronoUnit.DAYS` считает полные 24-часовые отрезки, поэтому 1 октября
     *    23:00 при «сейчас» = 2 октября 15:00 даёт 0 дней — то есть вчерашнее
     *    обновление подписывалось «сегодня в 23:00»;
     *  - формула с 365 не учитывает високосные годы (31.12.2024 → 01.01.2025 = 0).
     *
     * Разница именно `LocalDate` — это и есть «сегодня / вчера» с точки зрения
     * пользователя, а не арифметика часов.
     */
    private fun calendarDaysBetween(ts: Long, now: Long): Long {
        val zone = ZoneId.systemDefault()
        val then = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(then, today)
    }

    /**
     * Готовая строка для шапки.
     *
     * Формат зависит от давности, иначе подпись быстро устаревает сама по себе:
     *  - сегодня  → «сегодня в 14:32»;
     *  - вчера    → «вчера в 09:15»;
     *  - старше   → полная дата.
     *
     * [ts] = 0 означает «ещё ни разу не обновляли» — так бывает после
     * установки, до первого захода на сайт.
     */
    fun label(ts: Long, now: Long = System.currentTimeMillis()): String {
        if (ts <= 0L) return "ещё не обновлялось"
        return when (calendarDaysBetween(ts, now)) {
            in Long.MIN_VALUE..0L -> "сегодня в ${time(ts)}"
            1L -> "вчера в ${time(ts)}"
            else -> full(ts)
        }
    }

    /**
     * Подпись с явным указанием свежести данных.
     *
     * Сутки — граница: за это время расписание на сайте обычно уже не
     * меняется, а дальше подпись честно говорит, что данные устарели.
     */
    fun dataLabel(lastFetched: Long, now: Long = System.currentTimeMillis()): String {
        if (lastFetched <= 0L) return "данные ещё не загружены"
        val ageHours = (now - lastFetched) / 3_600_000L
        val base = label(lastFetched, now)
        return if (ageHours < 24) "данные: $base" else "данные устарели: $base"
    }

    /**
     * Полная подпись в шапке: отдельно данные и отдельно проверка сервера.
     *
     * Раньше была одна строка «Обновлено: <время записи кэша>», и при
     * недоступной сети выглядело так, будто расписание обновили только что.
     * Теперь видно обе величины, и расхождение между ними говорит о том,
     * что данные старые, а сеть была недоступна.
     */
    fun fullLine(
        lastCheck: Long,
        lastFetched: Long,
        now: Long = System.currentTimeMillis(),
    ): String {
        val data = dataLabel(lastFetched, now)
        if (lastCheck <= 0L) return data
        return "$data · проверено ${label(lastCheck, now)}"
    }
}