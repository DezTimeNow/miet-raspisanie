package com.mietschedule.app

import com.mietschedule.app.BuildConfig

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * БЛОК «СЕЙЧАС И ДАЛЬШЕ» В ГЛАВНОМ МЕНЮ.
 *
 * Требование владельца: видеть текущую и следующую пару не внутри расписания,
 * а в самом главном меню — над кнопками «Студент / Преподаватель / Аудитория».
 * Раньше блок стоял только на экране расписания, то есть добраться до него
 * можно было лишь после выбора группы: человек, который ещё не открыл
 * расписание, вообще не видел, что у него сейчас.
 *
 * Условие владельца — «при условии, что я добавил в избранное». Блок строится
 * ПО ИЗБРАННОМУ, а не по текущему выбору: в меню может быть выбрана одна
 * группа, а человек хочет знать про другую, добавленную в избранное. При
 * пустом избранном блока нет — показывать нечего, и пустая плашка только
 * занимала бы место над кнопками ролей.
 *
 * Данные берутся из кэша расписаний на IO-диспетчере: разбор 343 расписаний
 * на главном потоке давал ANR, а этот блок стоит на первом экране и запускается
 * при каждом открытии меню.
 */

/** Высота заглушки блока, пока читается кэш расписаний. */
private val BLOCK_PLACEHOLDER_HEIGHT = 96.dp

/**
 * Подпись блока на главной.
 *
 * Требование владельца от 0.66: «верхний блок Избранное переименовать в
 * ближайшие пары». С 0.77 в блоке не только ближайшая пара, а весь день
 * целиком: прошедшие, идущая и будущие. Поэтому с 0.78 название —
 * «Пары на сегодня», требование владельца: заголовок обязан описывать то,
 * что видно на экране.
 */
internal const val FAV_BLOCK_TITLE = "Пары на сегодня"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NextLessonCard(
    api: MietApi,
    prefs: GroupPrefs,
    /** Открыть расписание: роль и значение из избранного. */
    onOpen: (Role, String) -> Unit,
) {
    // Избранное читается из хранилища, а Compose об этом не знает: значение
    // живёт в state, иначе добавление звезды не отражалось бы здесь до
    // перезапуска экрана.
    val favs = remember { favSnapshot(prefs) }
    if (favs.isEmpty()) return

    var groups by remember(favs) { mutableStateOf<List<FavLessons>>(emptyList()) }
    LaunchedEffect(favs) {
        val loaded = withContext(Dispatchers.IO) { favLessonData(api, favs) }
        groups = loaded
    }

    // ЗАГЛУШКА НА ВРЕМЯ ЧТЕНИЯ КЭША.
    //
    // Раньше блок в это время не рисовал ничего, и главная стояла пустой
    // сверху: список избранного и кнопка «Добавить» подпрыгивали вниз, когда
    // данные приходили. Замер на эмуляторе: блок наполнялся в разы позже,
    // чем рисовалась вся остальная главная.
    //
    // Высота заглушки фиксированная, поэтому прыжок ограничен одним
    // переходом «заглушка → содержимое». Точно зарезервировать высоту
    // нельзя: она зависит от числа избранных, и угадать её заранее нечем.
    if (groups.isEmpty()) {
        Card(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).height(BLOCK_PLACEHOLDER_HEIGHT),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(2.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = LocalAppColors.current.favStar,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    FAV_BLOCK_TITLE,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalAppColors.current.favStar,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "загружаю…",
                    fontSize = 12.sp,
                    color = LocalAppColors.current.muted,
                )
            }
        }
        return
    }

    // Тик раз в минуту: «через 12 минут» устаревает быстро, а перерисовывать
    // список пар из-за секунд незачем.
    //
    // SCREENSHOT_NOW_MS: отладочный сдвиг времени для скриншотов. В debug-
    // сборке (через BuildConfig) задаёт фиксированный «сейчас», чтобы снять
    // экран с идущей парой и остатком времени. В release не активен.
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

    // По каждому избранному — СВОЙ список пар дня: заголовок группы, ниже все
    // её пары сегодня. Прошедшие остаются видимыми и приглушёнными, идущая
    // подсвечена и показывает остаток времени, будущие — отсчёт до начала.
    // Если день закончился, показываем ближайший день с занятиями.
    val rows = remember(groups, now) {
        groups.mapNotNull { g ->
            val today = NextLessonLogic.dayLessons(g.lessons, g.times, now)
            val live = today.any { it.state != NextLessonLogic.LessonState.PAST }
            if (live) {
                FavRows(g.entry, today, null, emptyList())
            } else {
                // День кончился или занятий в нём нет: пустая плашка ничего не
                // сообщает, поэтому показываем, что будет дальше.
                val next = nextDayWithLessons(g, now) ?: return@mapNotNull null
                FavRows(g.entry, today, next.first, next.second)
            }
        }
    }
    if (rows.isEmpty()) return

    // ПОДКРУТКА К ИДУЩЕЙ ПАРЕ.
    //
    // Со списком всего дня нужная строка уезжает за пределы экрана: человек
    // открывает меню, а видит первые пары дня, и идущую приходится искать.
    // Просим прокрутить к ней один раз — и ещё раз, когда началась следующая
    // пара. На каждый тик раз в минуту подкрутки нет: иначе список дёргался бы
    // из-под пальца.
    val goingRequester = remember { BringIntoViewRequester() }
    val goingKey = remember(rows) {
        rows.firstNotNullOfOrNull { row ->
            row.today.firstOrNull { it.state == NextLessonLogic.LessonState.GOING }
                ?.let { it.start to (it.lesson.classInfo?.name ?: "") }
        }
    }
    LaunchedEffect(goingKey) {
        if (goingKey != null) {
            delay(250)
            runCatching { goingRequester.bringIntoView() }
        }
    }

    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = LocalAppColors.current.favStar,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                // Подпись блока — просто строка. С 0.65 список избранного
                // стоит на главной отдельными кнопками, и второго входа в
                // него здесь быть не должно.
                Text(
                    FAV_BLOCK_TITLE,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalAppColors.current.favStar,
                )
            }
            // Группы по избранному: заголовок отмечает, кому принадлежат пары.
            // Иначе при двух избранных строки выглядели бы как одна лента, и
            // непонятно, чьи это пары.
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(
                        color = LocalAppColors.current.muted.copy(alpha = 0.18f),
                    )
                }
                Text(
                    favTitle(row.entry),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = LocalAppColors.current.muted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 1.dp),
                )
                val hasGoing = row.today.any { it.state == NextLessonLogic.LessonState.GOING }
                row.today.forEach { item ->
                    FavDayLine(
                        item = item,
                        now = now,
                        goingRequester = if (
                            hasGoing && item.state == NextLessonLogic.LessonState.GOING
                        ) goingRequester else null,
                    ) { onOpen(row.entry.role, row.entry.value) }
                }
                // День закончился — говорим об этом прямо, а не оставляем
                // человека гадать, почему список кончился.
                if (row.today.isNotEmpty() && !hasGoing && row.next.isNotEmpty()) {
                    Text(
                        "Пары на сегодня закончились",
                        fontSize = 12.sp,
                        color = LocalAppColors.current.muted,
                        modifier = Modifier.padding(top = 6.dp, bottom = 1.dp),
                    )
                }
                if (row.today.isEmpty() && row.next.isNotEmpty()) {
                    Text(
                        "Сегодня пар нет",
                        fontSize = 12.sp,
                        color = LocalAppColors.current.muted,
                        modifier = Modifier.padding(top = 2.dp, bottom = 1.dp),
                    )
                }
                row.nextTitle?.let { title ->
                    Text(
                        title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = LocalAppColors.current.muted,
                        modifier = Modifier.padding(top = 6.dp, bottom = 1.dp),
                    )
                }
                row.next.forEach { item ->
                    FavDayLine(item = item, now = now) {
                        onOpen(row.entry.role, row.entry.value)
                    }
                }
            }
        }
    }
}

/**
 * Строка пары дня с переходом в расписание.
 *
 * Три состояния: прошедшая приглушена, идущая залита цветом и показывает
 * остаток времени, будущая — отсчёт до начала.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavDayLine(
    item: NextLessonLogic.DayLesson,
    now: Long,
    /** Просьба прокрутить список к этой строке — только у идущей пары. */
    goingRequester: BringIntoViewRequester? = null,
    onClick: () -> Unit,
) {
    val going = item.state == NextLessonLogic.LessonState.GOING
    val past = item.state == NextLessonLogic.LessonState.PAST
    val teacher = item.lesson.classInfo?.teacherFull?.takeIf { it.isNotBlank() }
    val room = item.lesson.room?.name?.takeIf { it.isNotBlank() }

    val whenText = if (going) "сейчас" else hmText(item.start)
    // Идущая — сколько ещё идёт, прошедшая — что прошла, будущая — сколько
    // ждать. Остаток считается только по времени сервера: у части аудиторий
    // корпуса 8 конца пары нет, и придумывать его нельзя.
    val counter = when {
        going -> remainingText(item.end, now)
        past -> "прошла"
        else -> counterText(item.start, now)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            // ИДУЩАЯ ПАРА ЗАЛИТА ЦВЕТОМ ТЕКУЩЕЙ.
            //
            // Тот же цвет, что у карточки идущей пары на экране расписания
            // (NextLessonRow): одно значение в двух местах означает одно и
            // то же — «идёт сейчас». Пользователь учится этому один раз.
            //
            // Цвет берётся из темы: в тёмной теме это тёмно-синий, потому что
            // светлая заливка на чёрном фоне слепит.
            //
            // Заливка ставится ДО padding: фон рисуется по всей строке, а не
            // только под текстом, иначе подсветка выглядела бы обрезанной по
            // краям. На измерение это не влияет — background только рисует,
            // поэтому строки не сдвигаются при появлении отметки.
            .then(
                if (going) Modifier.background(LocalAppColors.current.currentGroup) else Modifier
            )
            .then(goingRequester?.let { Modifier.bringIntoViewRequester(it) } ?: Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Schedule,
                contentDescription = null,
                tint = if (past) LocalAppColors.current.muted else MIET_BLUE,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    buildString {
                        append(item.lesson.classInfo?.name ?: "Пара")
                        append(" • ")
                        append(whenText)
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    // Прошедшая пара читается как история: тот же текст, но
                    // приглушённый — её не надо искать глазами.
                    color = if (past) LocalAppColors.current.muted
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
                val details = buildString {
                    val g = item.lesson.group?.name
                    if (!g.isNullOrBlank()) append(g)
                    if (room != null) {
                        if (isNotEmpty()) append("  •  ")
                        append("ауд. $room")
                    }
                    if (teacher != null) {
                        if (isNotEmpty()) append("  •  ")
                        append(teacher)
                    }
                }
                if (details.isNotEmpty()) {
                    Text(
                        details,
                        fontSize = 12.sp,
                        color = LocalAppColors.current.muted.copy(
                            alpha = if (past) 0.7f else 1f,
                        ),
                        maxLines = 2,
                    )
                }
            }
            if (counter.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    counter,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (past) LocalAppColors.current.muted else MIET_BLUE,
                )
            }
        }
    }
}

/**
 * Снимок избранного по всем ролям.
 *
 * Всё избранное, а не только текущей роли: человек может смотреть меню в
 * роли «студент», а в избранном держать преподавателя, и блок обязан показать
 * его пары. Снимок — обычная строка, поэтому его можно класть в ключ
 * remember и сравнивать покомпонентно.
 */
internal data class FavEntry(val role: Role, val value: String)

internal fun favSnapshot(prefs: GroupPrefs): List<FavEntry> =
    Role.entries.flatMap { role ->
        prefs.favGroups(role).sorted().map { FavEntry(role, it) }
    }

/**
 * Пары ОДНОГО избранного значения вместе с его таблицей времени.
 *
 * Раньше здесь был один список `lessons` на всё избранное, и `currentAndNext`
 * искал по нему самую раннюю пару. При двух избранных — группа и
 * преподаватель — победила та, чья пара начиналась раньше, а вторая молча
 * исчезала: блок отвечал на вопрос «что сейчас» один раз, а не по каждому.
 */
internal data class FavLessons(
    val entry: FavEntry,
    val lessons: List<Lesson> = emptyList(),
    val times: List<PairTime> = emptyList(),
)

/**
 * Готовые к отрисовке строки одного избранного.
 *
 * [today] — пары сегодняшнего дня по порядку. [nextTitle] и [next] — ближайший
 * день с занятиями после сегодняшнего: он нужен, когда сегодняшний день уже
 * закончился. Раньше здесь лежали только идущая и следующая пары, и всего дня
 * человек не видел.
 */
internal data class FavRows(
    val entry: FavEntry,
    val today: List<NextLessonLogic.DayLesson>,
    val nextTitle: String?,
    val next: List<NextLessonLogic.DayLesson>,
)

/** Подпись группы строк: чему принадлежит пара. */
internal fun favTitle(entry: FavEntry): String = when (entry.role) {
    Role.STUDENT -> entry.value
    Role.TEACHER -> entry.value
    Role.AUDIENCE -> "ауд. ${entry.value}"
}

/** Названия дней недели по индексу 0 = понедельник … 6 = воскресенье. */
private val DAY_NAMES_RU = listOf(
    "понедельник", "вторник", "среда", "четверг",
    "пятница", "суббота", "воскресенье",
)

/**
 * Ближайший день с занятиями после сегодняшнего.
 *
 * Нужен, когда пары на сегодня закончились: пустая плашка ничего не сообщает,
 * а человек хочет знать, что у него дальше. Дни без занятий пропускаются —
 * иначе в субботу блок показывал бы пустое воскресенье.
 */
internal fun nextDayWithLessons(
    group: FavLessons,
    now: Long,
    maxDays: Int = 7,
): Pair<String, List<NextLessonLogic.DayLesson>>? {
    for (offset in 1..maxDays) {
        val day = NextLessonLogic.dayLessons(group.lessons, group.times, now, offset)
        if (day.isNotEmpty()) return nextDayTitle(now, offset) to day
    }
    return null
}

/** Подпись следующего дня: «Завтра, суббота 11.10» или «Понедельник 13.10». */
internal fun nextDayTitle(now: Long, dayOffset: Int): String {
    val cal = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        add(java.util.Calendar.DAY_OF_MONTH, dayOffset)
    }
    val idx = dayIndexFromCalendar(cal.get(java.util.Calendar.DAY_OF_WEEK))
    val name = DAY_NAMES_RU.getOrElse(idx) { "" }.replaceFirstChar { it.uppercase() }
    val date = String.format(
        java.util.Locale.US, "%02d.%02d",
        cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.MONTH) + 1,
    )
    return if (dayOffset == 1) "Завтра, $name $date" else "$name $date"
}

/**
 * Остаток времени у идущей пары: «осталось 25 мин».
 *
 * Считается ТОЛЬКО по времени конца из данных сервера. У части аудиторий
 * корпуса 8 конца пары нет — там остаток не показывается вовсе: подставлять
 * «примерно 90 минут» и выдавать это за расписание нельзя.
 */
internal fun remainingText(end: Long?, now: Long): String {
    if (end == null) return ""
    val mins = ((end - now) / 60_000).toInt()
    if (mins <= 0) return "заканчивается"
    if (mins == 1) return "осталась 1 мин"
    if (mins < 60) return "осталось $mins мин"
    val hours = mins / 60
    val rem = mins % 60
    return if (rem == 0) "осталось $hours ч" else "осталось $hours ч $rem мин"
}

/**
 * Собрать пары по каждому избранному отдельно.
 *
 * Требование владельца: при двух-трёх избранных показывать информацию по
 * всем сразу, а не по одному. Раньше пары всех избранных складывались в общий
 * список, и `currentAndNext` возвращал одну самую раннюю пару на всё
 * избранное — то есть блок молчал про второе и третье.
 *
 * Только то, что уже лежит в кэше: сеть на первом экране запускать нельзя,
 * меню открывается мгновенно, а полная загрузка расписаний идёт около
 * тридцати секунд. Если кэша нет — избранное просто не попадёт в блок.
 *
 * Лимит на число избранных: разбор одного расписания занимает заметное
 * время. Двадцати хватает, на экране всё равно видны не все.
 *
 * @return по одному [FavLessons] на избранное, у которого есть кэш. Пустых
 *   нет: показывать строку «пар нет» незачем — блок отвечает на вопрос
 *   «что сейчас», и такая строка на него не отвечает.
 */
internal suspend fun favLessonData(
    api: MietApi,
    favs: List<FavEntry>,
    limit: Int = 20,
): List<FavLessons> {
    val out = ArrayList<FavLessons>()
    // Таблица времени читается ОДИН раз на весь блок, а не на каждое
    // избранное. pairTimesFromCache разбирает JSON до двенадцати расписаний
    // групп, и вызов внутри цикла при трёх избранных давал до 36 разборов
    // ради одной таблицы.
    //
    // ЭТА ТАБЛИЦА — ТОЛЬКО ЗАПАСНОЙ ПУТЬ. Она собрана от ПЕРВОЙ попавшейся
    // группы из кэша, а сетка звонков у групп разная (3-я пара: 12:00 у одних,
    // 12:30 у колледжных). Отдавать её в строки нельзя: пара, у которой есть
    // своё время, обязана показывать его. Поэтому у каждого избранного
    // сначала идёт СВОЯ таблица, а общая добирает лишь те коды, которых у
    // него нет вовсе.
    val fallback = runCatching { api.pairTimesFromCache() }.getOrDefault(emptyList())
    for (fav in favs.take(limit)) {
        val raw = runCatching { api.cachedLessonsOf(fav.role, fav.value) }.getOrNull() ?: continue
        if (raw.isEmpty()) continue
        val own = collectTimes(raw)
        val times = if (own.isEmpty()) fallback
        else own + fallback.filter { f -> own.none { it.code == f.code } }
        out += FavLessons(entry = fav, lessons = raw, times = times)
    }
    return out
}

private fun hmText(millis: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return String.format(
        java.util.Locale.US, "%02d:%02d",
        c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
    )
}

/**
 * «через 25 мин», «завтра 10:40», «Пн 09:00».
 *
 * Правило то же, что в NextLessonRow. Две копии здесь по необходимости:
 * функция там private, а блок стоит в другом файле. При правке править обе —
 * иначе на расписании и в меню будет разное время одной и той же пары.
 */
private fun counterText(start: Long, now: Long): String {
    val mins = ((start - now) / 60_000).toInt()
    if (mins <= 0) return "сейчас"
    if (mins < 60) return "через $mins мин"

    val days = Math.round((midnight(start) - midnight(now)) / (24.0 * 3600 * 1000)).toInt()
    if (days == 1) return "завтра " + hmText(start)
    if (days in 2..6) {
        val idx = dayIndexFromCalendar(
            java.util.Calendar.getInstance().apply { timeInMillis = start }.get(java.util.Calendar.DAY_OF_WEEK)
        )
        return "${listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").getOrElse(idx) { "" }} " + hmText(start)
    }
    val hours = mins / 60
    val rem = mins % 60
    return if (rem == 0) "через $hours ч" else "через $hours ч $rem мин"
}

private fun midnight(v: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = v
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis
