package com.mietschedule.app

import com.mietschedule.app.BuildConfig

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Locale

/**
 * Блок «сейчас и дальше» над расписанием.
 *
 * Смысл: человеку почти всегда нужны оба ответа сразу — «что сейчас» и «что
 * дальше». Расписание ниже отвечает на вопрос «что у меня вообще», этот блок
 * на вопрос «что происходит и что после».
 *
 * Две строки вместо одной — требование владельца: со строкой «сейчас»
 * студент не понимал, что происходит прямо сейчас. Когда пара не идёт,
 * верхняя строка не показывается вовсе, а не рисуется пустой: пустая плашка
 * занимала бы место и повторяла то, что расписание ниже уже пишет крупно.
 *
 * Время и предмет приходят одним куском из [NextLessonLogic.Hit]: считать
 * время второй раз, отдельно от выбора пары, нельзя — две копии расчёта
 * разъезжались, и счётчик показывал минуты до другой пары.
 */
@Composable
fun NextLessonRow(
    lessons: List<Lesson>,
    times: List<PairTime>,
) {
    // Тик раз в минуту: «через 12 минут» устаревает быстро, а перерисовывать
    // весь список пар из-за секунд незачем.
    //
    // SCREENSHOT_NOW_MS: отладочный сдвиг времени для скриншотов (см.
    // NextLessonCard). В debug задаёт фиксированное «сейчас», в release
    // флаг отсутствует и используется системное время.
    var now by remember {
        mutableLongStateOf(
            if (BuildConfig.SCREENSHOT_NOW_MS > 0L) BuildConfig.SCREENSHOT_NOW_MS
            else System.currentTimeMillis()
        )
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = if (BuildConfig.SCREENSHOT_NOW_MS > 0L) BuildConfig.SCREENSHOT_NOW_MS
            else System.currentTimeMillis()
        }
    }

    val (going, upcoming) = remember(lessons, times, now) {
        NextLessonLogic.currentAndNext(lessons = lessons, times = times, now = now)
    }

    if (going == null && upcoming == null) return

    Column(Modifier.fillMaxWidth()) {
        if (going != null) {
            LessonLine(hit = going, going = true, now = now)
        }
        if (upcoming != null) {
            LessonLine(hit = upcoming, going = false, now = now)
        }
    }
}

/**
 * Одна строка блока.
 *
 * @param going идёт ли пара прямо сейчас: от этого и цвет карточки, и слова
 *   в подписи — «сейчас» вместо часов, потому что при идущей паре время уже
 *   известно и часами её не описать.
 */
@Composable
private fun LessonLine(hit: NextLessonLogic.Hit, going: Boolean, now: Long) {
    val start = hit.start

    val teacher = hit.lesson.classInfo?.teacherFull?.takeIf { it.isNotBlank() }
    val room = hit.lesson.room?.name?.takeIf { it.isNotBlank() }

    // Когда пара. Для идущей — «сейчас», для будущей — время начала.
    val whenText = when {
        going -> "сейчас"
        start != null -> hm(start) + "–" + (hit.end?.let { hm(it) } ?: "")
        else -> ""
    }.trim()

    // Счётчик отдельно от времени: при «сейчас» он не нужен.
    val counter = if (going) "" else start?.let { counterText(it, now) }.orEmpty()

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (going)
                LocalAppColors.current.currentGroup else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(1.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Schedule,
                contentDescription = null,
                tint = MIET_BLUE,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    buildString {
                        append(hit.lesson.classInfo?.name ?: "Пара")
                        if (whenText.isNotEmpty()) {
                            append(" • ")
                            append(whenText)
                        }
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                val details = buildString {
                    if (room != null) append("ауд. $room")
                    if (teacher != null) {
                        if (isNotEmpty()) append(" • ")
                        append(teacher)
                    }
                }
                if (details.isNotEmpty()) {
                    Text(
                        details,
                        fontSize = 12.sp,
                        color = LocalAppColors.current.muted,
                        maxLines = 1,
                    )
                }
            }
            if (counter.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    counter,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MIET_BLUE,
                )
            }
        }
    }
}

/** «08:00» из момента времени. */
private fun hm(millis: Long): String =
    String.format(
        Locale.US, "%02d:%02d",
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY),
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.MINUTE),
    )

/**
 * «через 25 мин», «через 2 ч», «завтра 10:40».
 *
 * Час и больше показываем без минут: «через 187 мин» выглядит как точность
 * до минуты, которой нет. Для завтрашней пары вместо «через 26 ч» пишем
 * слово «завтра» — человек думает днями, а не часами.
 */
private fun counterText(start: Long, now: Long): String {
    val mins = ((start - now) / 60_000).toInt()
    if (mins <= 0) return "сейчас"
    if (mins < 60) return "через $mins мин"

    val days = daysBetween(now, start)
    if (days == 1) {
        return "завтра " + hm(start)
    }
    if (days in 2..6) {
        val names = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        val idx = Calendar.getInstance().apply { timeInMillis = start }
            .let { dayIndexFromCalendar(it.get(Calendar.DAY_OF_WEEK)) }
        return "${names.getOrElse(idx) { "" }} " + hm(start)
    }
    val hours = mins / 60
    val rem = mins % 60
    return if (rem == 0) "через $hours ч" else "через $hours ч $rem мин"
}

/** Сколько целых суток между двумя моментами. */
private fun daysBetween(from: Long, to: Long): Int {
    fun midnight(v: Long) = Calendar.getInstance().apply {
        timeInMillis = v
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val diff = midnight(to) - midnight(from)
    return Math.round(diff / (24.0 * 3600 * 1000)).toInt()
}