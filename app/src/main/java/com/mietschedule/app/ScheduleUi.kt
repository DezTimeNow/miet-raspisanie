package com.mietschedule.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.mutableStateOf

/**
 * Экран расписания и карточка пары.
 *
 * Вынесено из MainActivity без изменения поведения и внешнего вида.
 */
// ───────────────────────────── расписание ─────────────────────────────

/**
 * Расписание выбранной сущности.
 *
 * [role] решает, откуда берутся данные: у студента — своя группа с сервера,
 * у аудитории — её сетка занятости, у преподавателя — сборка из кэша расписаний
 * всех групп (сайт его расписание не отдаёт, см. [TeacherIndex]).
 *
 * [teacherCode] заполнен только для преподавателя, [group] хранит ФИО.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    api: MietApi,
    prefs: GroupPrefs,
    role: Role,
    group: String,
    teacherCode: String,
    roomName: String = "",
    dataGeneration: Int = 0,
    onRefreshAll: () -> Unit = {},
    refreshNote: String = "",
    /**
     * Идёт ли общее обновление по кнопке ⭯.
     *
     * Раньше шапка брала `refreshing` у самого экрана, то есть показывала
     * крутилку только пока грузится сам экран. Нажатие «Обновить всё»
     * обновляет 343 группы около 30 секунд, и всё это время кнопка выглядела
     * как обычная и нажималась снова — второй круг обхода поверх первого.
     * Теперь крутилка показывает и общее обновление.
     */
    refreshingAll: Boolean = false,
    onChangeEntity: () -> Unit,
    onChangeRole: () -> Unit,
    // Сообщает наружу, что сейчас на экране: сколько пар и есть ли ошибка.
    // Читает это экран отчёта об ошибке, открываемый из «О программе».
    onScreenState: (Int, String?) -> Unit = { _, _ -> },
) {
    // Данные и загрузка вынесены в rememberScheduleData (ScheduleData.kt):
    // здесь остаётся только отрисовка. Раньше те же 190 строк загрузки
    // стояли в теле экрана вместе с UI.
    // Метка «Обновлено …». Объявлена до вызова rememberScheduleData: тот же
    // экран читает значение здесь, а load() сообщает новую дату через
    // onLoaded — раньше метка отставала от фактической записи в кэш.
    var lastUpdated by remember { mutableStateOf(0L) }
    // Время последней ПОПЫТКИ обращения к серверу. Раньше его не показывали
    // вовсе, а подпись «обновлено» означала время записи кэша: при неудачной
    // сети читалось «обновлено сегодня», хотя ничего не обновилось.
    var lastChecked by remember { mutableStateOf(0L) }
    // Контекст нужен для планировщика напоминаний: он ставит системные
    // будильники и забирает кэш расписания.
    val ctx = LocalContext.current
    val data = rememberScheduleData(ctx, api, role, group, teacherCode, roomName) { ts ->
        lastUpdated = ts
    }
    val lessons = data.lessons
    val times = data.times
    val semestr = data.semestr
    val loading = data.loading
    val refreshing = data.refreshing
    val error = data.error
    val load = data.load
    // Состояние экрана наружу. Пишем и на пустом списке, и при ошибке:
    // «пар нет» и «показана ошибка» — разные ситуации, и в отчёте они
    // должны различаться.
    LaunchedEffect(lessons.size, error) { onScreenState(lessons.size, error) }
    // ─── СВЯЗКА «СЕГОДНЯ» И НЕДЕЛИ ───
    //
    // Фильтр «Сегодня» имеет смысл только на текущей неделе. Кнопки ‹ ›
    // листают учебную неделю, а showWeek жил своей жизнью: сдвинул неделю —
    // остался в режиме «Сегодня», где todayLessons при weekOffset != 0 пуст
    // по определению (isCurrentWeek == false), и экран показывал
    // «Пятница 9.10.2026 пар нет» вместо расписания недели.
    //
    // Теперь сдвиг недели САМ снимает фильтр и показывает всю неделю.
    // Обратно — нажатием на «Сегодня»: восстановить неделю 0 вручную
    // кнопкой «сейчас», потом переключить вкладку. Кнопка «Сегодня» —
    // единственное место, где showWeek задаётся напрямую.
    // Фильтр недели переживает поворот экрана: rememberSaveable вместо
    // remember, иначе recreate Activity возвращал экран на «Сегодня» и
    // сбрасывал выбранную учебную неделю на текущую.
    var showWeek by rememberSaveable { mutableStateOf(false) }
    // Метка «обновлено …» из кэша. Держим в state, а не читаем напрямую:
    // после нажатия «Обновить» значение должно смениться на глазах.
    var weekName by remember { mutableStateOf(WeekType.currentName()) }
    var weekRow by remember { mutableStateOf(WeekType.currentRowIndex()) }
    // Сдвиг учебной недели для кнопок «‹ неделя ›». 0 = текущая, +1 = следующая.
    // Хранится именно сдвиг, а не абсолютный тип недели: при смене семестра
    // абсолютное значение устареет, а сдвиг 0 всегда означает «текущая».
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    // Начало семестра нужно и для пересчёта сдвига, иначе после смены семестра
    // weekRow уедет. Кэшируем один раз на запуск экрана.
    var semestrStartIso by remember { mutableStateOf(WeekType.SEMESTR_START_ISO) }

    // API отдаёт Day 1..6 (Пн..Сб). Наш индекс: 0=Пн … 6=Вс.
    //
    // Calendar.DAY_OF_WEEK: SUNDAY=1, MONDAY=2, … SATURDAY=7 — то есть
    // неделя начинается с воскресенья, а нам нужно с понедельника. Формула
    // (cw + 5) % 7: Пн(2)→0, Вт(3)→1, … Сб(7)→5, Вс(1)→6.
    //
    // БЫЛО `if (cw == SUNDAY) 0 else cw - 1` — это сдвиг на единицу: для
    // пятницы cw=7 даёт 6 = «Вс», а показывало «Сб» из-за того же сдвига.
    // Из-за этого «Сегодня» называло неверный день и карточки чужих пар
    // подсвечивались как сегодняшние.
    // todayDay пересчитывается при каждом показе экрана, а не один раз:
    // раньше было remember без ключа, и если приложение висело открытым
    // через полночь, «Сегодня» продолжало называть вчерашний день.
    val todayDay = dayIndexFromCalendar(Calendar.getInstance().get(Calendar.DAY_OF_WEEK))
    val scope = rememberCoroutineScope()

    // Звезда в шапке расписания. prefs.isFav() — обычная функция, Compose о ней
    // не знает, поэтому её значение не пересчитывается само. Держим флаг в
    // state и перечитываем из хранилища после каждого переключения, иначе
    // звезда «нажимается», но визуально не меняется.
    var isFav by remember(group, role) { mutableStateOf(prefs.isFavFor(role, group)) }
    fun refreshFav() { isFav = prefs.isFavFor(role, group) }
    // Возврат на экран расписания мог оставить звезду устаревшей.
    LaunchedEffect(group, role) { refreshFav() }

    // Актуализируем неделю при запуске и возврате на экран
    LaunchedEffect(group) {
        val ss = withContext(Dispatchers.IO) { api.semestrStart() }
        semestrStartIso = ss
        weekOffset = 0
        // showWeek здесь НЕ трогаем: вход на экран расписания — это
        // «открыл посмотреть сегодня». Снятие фильтра относится только к
        // ПЕРЕХОДУ на другую неделю. Иначе приложение открывалось бы сразу
        // на «Вся неделя» (18 пар вместо сегодняшних двух), а стартовое
        // поведение менять нельзя.
        weekName = WeekType.currentName(ss)
        weekRow = WeekType.currentRowIndex(ss)
    }

    /** Пересчитать метку последнего обновления из кэша. */
    fun refreshLastUpdated() {
        scope.launch {
            val ts = withContext(Dispatchers.IO) { api.lastUpdatedAt(role, group) }
            lastUpdated = ts
        }
    }

    // Дата последнего обновления. Перечитываем и при смене роли/объекта, и
    // после каждой загрузки — иначе после «Обновить» осталась бы старая дата.
    LaunchedEffect(group, role) {
        refreshLastUpdated()
        lastChecked = withContext(Dispatchers.IO) { api.lastCheckAt(role, group) }
    }



    LaunchedEffect(group, teacherCode, role) { load(false) }

    // Поколение данных выросло — перечитываем кэш и пересчитываем дату.
    // Именно этого не хватало: refreshCurrent() писал в кэш, но этот экран
    // был отдельным composable со своим состоянием и показывал старое.
    LaunchedEffect(dataGeneration) {
        if (dataGeneration > 0) {
            Log.i("Schedule", "Поколение $dataGeneration — перечитываю")
            // Именно false, а не true: расписания уже обновлены выше, в
            // refreshCurrent(). С true экран аудитории уходил в СВОЙ второй
            // полный проход по 343 группам — последовательно, по одному, от
            // чего обновление на 343 группы занимало минуты вместо 35 секунд,
            // а кнопка выглядела зависшей. Здесь нужен только пересчёт кэша.
            load(false)
            // Дату считаем здесь, ПОСЛЕ load. Внутри load() она считалась в
            // конце, но ветка аудитории возвращалась из кэша раньше, чем
            // долетели записи расписаний, и подпись отставала на минуты.
            // Повтор с задержкой: пока идёт запись 343 расписаний, метка
            // едет вперёд — ловим последнюю, уже после записи.
            repeat(3) {
                val ts = withContext(Dispatchers.IO) { api.lastUpdatedAt(role, group) }
                lastUpdated = ts
                delay(1200)
            }
        }
    }

    // Только строка текущей учебной недели (DayNumber == weekType).
    // Фильтр по чётности тут давал бы дубли: строки 0 и 2 у части групп
    // содержат одинаковые пары, а у ИВТ-11/ПИН-11 — разные.
    // Короткая дата «02.10» для компактных подписей (табы, чипы).
    fun dateShort(day: Int, offset: Int, startIso: String): String {
        val d = WeekType.dateOfWeekDay(offset, day, startIso) ?: return ""
        return String.format("%02d.%02d", d.get(Calendar.DAY_OF_MONTH), d.get(Calendar.MONTH) + 1)
    }

    val activeWeekRow = remember(weekOffset, semestrStartIso) {
        WeekType.shiftedRow(weekOffset, semestrStartIso)
    }

    // Диапазон дат выбранной недели: «29.09 — 05.10».
    fun weekRangeLabel(): String {
        val mon = WeekType.dateOfWeekDay(weekOffset, 0, semestrStartIso)
            ?: return WeekType.name(activeWeekRow)
        val sun = WeekType.dateOfWeekDay(weekOffset, 6, semestrStartIso) ?: return ""
        fun f(d: java.util.Calendar) =
            String.format("%02d.%02d", d.get(java.util.Calendar.DAY_OF_MONTH), d.get(java.util.Calendar.MONTH) + 1)
        return "${f(mon)} \u2014 ${f(sun)}"
    }

    val weekLessons = remember(lessons, activeWeekRow) {
        lessons.filter { (it.dayNumber ?: 0) == activeWeekRow }
    }
    // Ключи byDay и индекс todayDay должны быть НА ОДНОЙ шкале.
    //
    // Сервер отдаёт Day = 1..6 (1=Пн … 6=Сб) — это 1-based. А dayIndexFromCalendar
    // возвращает 0..6 (0=Пн … 6=Вс) — 0-based. Смешивать их нельзя.
    //
    // БЫЛО: groupBy { it.day } (1..6) и byDay[todayDay] (0..6) — минус единица
    // на ровно одно место. В пятницу todayDay=4, и приложение показывало
    // byDay[4] — то есть ЧЕТВЕРГ, в шапке писало «Четверг», а карточки
    // четверга подсвечивались как сегодняшние. Отсюда «у Кузнецова сегодня
    // четверг», хотя на самом деле пятница.
    //
    // Теперь приводим к нашей шкале 0..6 сразу при группировке, а в шапке
    // берём DAY_NAMES[day] без «-1».
    val byDay = weekLessons.groupBy { (it.day ?: 1) - 1 }.toSortedMap()
    // «Сегодня» имеет смысл ТОЛЬКО на текущей неделе.
    //
    // Кнопки ‹ › листают учебную неделю, но todayDay остаётся пятницей
    // навсегда. Из-за этого на следующей неделе подсвечивалась пятница
    // 09.10 и писалось «— сегодня», хотя сейчас всё ещё 02.10. При сдвиге
    // показываем обычную неделю без подсветки, а вкладка называется не
    // «Сегодня», а по дате выбранной недели.
    val isCurrentWeek = weekOffset == 0
    val markedDay = if (isCurrentWeek) todayDay else -1
    val todayLessons = if (isCurrentWeek) byDay[todayDay].orEmpty() else emptyList()

    Scaffold(
        topBar = {
            MietTopBar(
                title = group,
                // П.5. Роль («Студент»/«Преподаватель»/«Аудитория») из подписи
                // убрана: её и так показывает галочка и цвет в списке ролей,
                // а на главной она была вторым упоминанием того же самого
                // прямо над собой. Осталось только семестр — он своё
                // обозначение не дублирует.
                subtitle = if (semestr.isBlank()) "Загрузка…" else semestr,
                onRefresh = onRefreshAll,
                onChangeRole = onChangeRole,
                // Крутилка на любой загрузке: своей или общей по кнопке.
                refreshing = refreshing || refreshingAll,
                // Назад — к списку своей сущности (смена группы/аудитории).
                onBack = onChangeEntity,
                // Звезда уехала из строки вкладок в шапку: там она стояла
                // ПРАВЕЕ «Сегодня»/«Вся неделя» и при смене ширины подписей
                // съезжала. В шапке у неё фиксированный слот.
                isFav = isFav,
                onToggleFav = {
                prefs.toggleFavFor(role, group)
                refreshFav()
                // Напоминания строятся по избранным группам, поэтому смена
                // отметки обязана сразу пересчитать будильники, иначе новая
                // группа молчала бы до следующей фоновой задачи (до 6 часов).
                runCatching { ReminderScheduler.reschedule(ctx, api) }
            },
                    )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            // ─── строка «обновлено …» ───
            // Кнопки здесь больше нет: она живёт в шапке, одна и та же на всех
            // экранах (просьба: «где бы я ни находился, обновлялось всё»).
            // Здесь остались дата и прогресс — чтобы 30 секунд ожидания
            // показывали счётчик, а не выглядели зависанием.
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    LastUpdated.fullLine(lastChecked, lastUpdated),
                    fontSize = 12.sp,
                    color = LocalAppColors.current.muted,
                    modifier = Modifier.weight(1f),
                )
                if (refreshNote.isNotEmpty()) {
                    Text(
                        refreshNote,
                        fontSize = 12.sp,
                        color = if (refreshing) MIET_BLUE else LocalAppColors.current.muted,
                    )
                }
            }

            // ─── строка учебной недели ───
            //
            // Раньше переключатель недели жил в шапке вместе с шестью другими
            // кнопками и не влезал. Здесь ему место: слева «‹», по центру —
            // семестр, тип недели и дата понедельника, справа «›» и сброс.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    // Сдвиг недели САМ снимает фильтр «Сегодня»: сдвинутая
                    // неделя — это обзор недели, а не «сегодня». Поэтому
                    // showWeek = true в обоих направлениях. Обратно фильтр
                    // возвращается только нажатием на «Сегодня».
                    onClick = { weekOffset--; showWeek = true },
                    enabled = weekOffset > -4,
                    // Стрелка нарисована текстом, а не иконкой, поэтому
                    // TalkBack читал «‹» как символ, а не как действие.
                    modifier = Modifier.semantics { contentDescription = "Предыдущая неделя" },
                ) {
                    Text("\u2039", color = MIET_BLUE, fontSize = 22.sp)
                }
                Text(
                    buildString {
                        if (semestr.isNotBlank()) { append(semestr); append(" • ") }
                        append(WeekType.name(activeWeekRow))
                        append(" • ")
                        append(weekRangeLabel())
                    },
                    fontSize = 13.sp,
                    color = MIET_BLUE,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                IconButton(
                    onClick = { weekOffset++ ; showWeek = true },
                    enabled = weekOffset < 4,
                    modifier = Modifier.semantics { contentDescription = "Следующая неделя" },
                ) {
                    Text("\u203A", color = MIET_BLUE, fontSize = 22.sp)
                }
                // Сброс на текущую неделю — только когда со сдвига. Стоит
                // ПОСЛЕ «›»: иначе при первом нажатии «›» он появлялся и
                // сдвигал «›» влево, и второе нажатие попадало уже в него.
                // Кнопка «сейчас» РАНЬШЕ появлялась только при сдвиге недели.
                // Она была выше строки вкладок, поэтому всё расписание
                // уезжало вниз на высоту кнопки — интерфейс «прыгал» при
                // каждом нажатии «›».
                //
                // Теперь место под неё зарезервировано ВСЕГДА: пустой Spacer
                // той же ширины, когда сдвига нет. Высота строки постоянна,
                // нажатие ничего не двигает, а кнопка появляется по запросу.
                if (weekOffset != 0) {
                    // «сейчас» возвращает текущую неделю И снимает фильтр
                    // «Сегодня»: показывается вся неделя. Возврат в режим
                    // «Сегодня» — отдельное нажатие на саму вкладку.
                    TextButton(onClick = { weekOffset = 0; showWeek = true }) {
                        Text("сейчас", fontSize = 12.sp, color = MIET_BLUE)
                    }
                } else {
                    Spacer(Modifier.width(56.dp))
                }
            }

            // ─── строка «что у меня дальше» ───
            //
            // Стоит между строкой обновления и вкладками: она отвечает на
            // вопрос «что сейчас», а переключатель «Сегодня / Вся неделя» —
            // на вопрос «что показать». Порядок именно такой, чтобы человек
            // читал сверху вниз как объяснение.
            //
            // Показывается только когда пара найдена. Пустая плашка занимала
            // бы место и повторяла то, что список ниже уже пишет крупно.
            if (!loading && error == null && weekLessons.isNotEmpty()) {
                NextLessonRow(
                    lessons = weekLessons,
                    times = times,
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Ширина вкладок ФИКСИРОВАНА: без неё «Вся неделя» уезжала
                // влево вместе с подписью первой вкладки, и интерфейс
                // «прыгал» вбок при каждом нажатии «›».
                //
                // Подпись вкладки ВСЕГДА с датой сегодняшнего дня. Раньше на
                // сдвинутой неделе она становилась «Пт 09.10» — то есть показывала
                // дату ПЕРВОЙ пары в выбранной неделе, а вкладка при этом была
                // нерабочей. Теперь подпись описывает фактическое действие
                // кнопки: возврат на сегодняшний день.
                FilterChip(
                    modifier = Modifier.width(196.dp),
                    selected = !showWeek,
                    // Кнопка «Сегодня» ВСЕГДА работает. Нажатие = «покажи
                    // мне сегодняшние пары»: сбрасывает неделю на текущую
                    // и включает фильтр «Сегодня».
                    //
                    // Раньше здесь стояла защита if (isCurrentWeek), и на
                    // сдвинутой неделе кнопка была МЁРТВОЙ: нажимаешь — ничего.
                    // Отсюда претензия. Теперь она не мёртвая, а ведёт домой:
                    // одно нажатие вместо двух («сейчас», потом «Сегодня»).
                    onClick = { weekOffset = 0; showWeek = false },
                    label = {
                        Text(
                            when {
                                !isCurrentWeek -> "Сегодня: ${DAY_SHORT[todayDay]}"
                                // На нашей шкале 0 = ПОНЕДЕЛЬНИК, а 6 = воскресенье
                                // (dayIndexFromCalendar = (cw + 5) % 7). Раньше здесь
                                // стояло `todayDay == 0`, то есть воскресенье искалось
                                // под индексом понедельника — и по понедельникам
                                // кнопка писала «Вс (выходной)».
                                todayDay == 6 -> "Вс (выходной)"
                                else -> "Сегодня: ${DAY_SHORT[todayDay]} ${dateShort(todayDay, weekOffset, semestrStartIso)}"
                            },
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }
                )
                FilterChip(
                    modifier = Modifier.width(120.dp),
                    selected = showWeek,
                    onClick = { showWeek = true },
                    label = { Text("Вся неделя", fontSize = 13.sp, maxLines = 1) }
                )
            }

            if (error != null) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                     colors = CardDefaults.cardColors(containerColor = LocalAppColors.current.errorContainer)) {
                    Text(error!!, Modifier.padding(12.dp), color = LocalAppColors.current.error, fontSize = 13.sp)
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                weekLessons.isEmpty() && error == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Расписание не найдено для $group", color = LocalAppColors.current.muted, fontSize = 14.sp)
                }
                !showWeek && todayLessons.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                when {
                                    !isCurrentWeek -> "${WeekType.dayWithDate(todayDay, weekOffset, semestrStartIso)} пар нет"
                                    // 6 = воскресенье на нашей шкале, а не 0 = понедельник.
                                    todayDay == 6 -> "Воскресенье — выходной"
                                    else -> "Сегодня ${WeekType.dayWithDate(todayDay, weekOffset, semestrStartIso).lowercase()} пар нет"
                                },
                                fontSize = 16.sp, color = LocalAppColors.current.muted
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                WeekType.name(activeWeekRow),
                                fontSize = 13.sp, color = MIET_BLUE
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("Переключи на «Вся неделя», чтобы посмотреть всё",
                                fontSize = 12.sp, color = LocalAppColors.current.muted)
                        }
                    }
                }
                else -> {
                    val shown = if (showWeek) byDay else mapOf(todayDay to todayLessons)
                    val count = shown.values.sumOf { it.size }
                    Text(
                        // Форму числительного: 1 пара, 2 пары, 5 пар. Раньше стояло
                        // жёсткое «пар», и на экране аудитории читалось «1 пар».
                        if (showWeek) "$count ${plural(count, "пара", "пары", "пар")} • ${WeekType.name(activeWeekRow)}"
                        else "$count ${plural(count, "пара", "пары", "пар")}" +
                            (if (isCurrentWeek) " сегодня" else "") +
                            " • ${WeekType.name(activeWeekRow)}",
                        fontSize = 12.sp, color = LocalAppColors.current.muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        // П.2. Дни БЕЗ ПАР пропускаем целиком — вместе с их
                        // заголовком. Раньше здесь рисовался заголовок дня
                        // «пятница 02.10.2026» даже там, где пар нет, и на
                        // сдвинутой неделе он оставался в синей рамке с
                        // подписью «— сегодня»: то есть приложение указывало
                        // на сегодняшний день, в котором уже ничего нет.
                        shown.filter { (day, ls) -> day == markedDay || ls.isNotEmpty() }
                            .forEach { (day, dayLessons) ->
                            item(key = "hdr$day") {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (day == markedDay) MIET_BLUE else LocalAppColors.current.rowGroup)
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            // day уже в нашей шкале 0..6 (0=Пн),
                                            // см. groupBy { (it.day ?: 1) - 1 } выше.
                                            // С датой: «пятница 02.10.2026». Без неё
                                            // карточки следующей недели выглядят
                                            // как сегодняшние — при листании «›».
                                            WeekType.dayWithDate(day, weekOffset, semestrStartIso),
                                            fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                            color = if (day == markedDay) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    if (day == markedDay) {
                                        Spacer(Modifier.width(8.dp))
                                        Text("— сегодня", fontSize = 12.sp, color = MIET_BLUE)
                                    }
                                }
                            }
                            items(
                                dayLessons.sortedBy { it.time?.code ?: 0 },
                                // Ключ обязан быть уникален внутри списка. У аудитории
                                // в одном слоте (день+неделя+пара) сидят НЕСКОЛЬКО
                                // групп — поэтому group в ключе обязателен, иначе
                                // LazyColumn падает с "Key was already used".
                                key = {
                                    "${it.day}_${it.time?.code}_${it.classInfo?.code}_" +
                                        "${it.dayNumber}_${it.group?.name.orEmpty()}_${it.room?.name.orEmpty()}"
                                }
                            ) { lesson ->
                                LessonCard(lesson, times, role)
                            }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

/**
 * Карточка пары. Содержимое зависит от роли: студенту важны преподаватель и
 * аудитория, аудитории — кто придёт и из какой группы, преподавателю — группы.
 * Показывать всё сразу значит забить карточку лишней строкой.
 */
@Composable
fun LessonCard(lesson: Lesson, times: List<PairTime>, role: Role) {
    val ci = lesson.classInfo
    val pairNo = lesson.time?.code ?: 0

    val pairTime = times.firstOrNull { it.code == pairNo }
    val timeLabel = pairTime?.let { p ->
        val from = p.timeFrom?.substringAfter("T")?.take(5)?.takeIf { it.isNotBlank() && it != "00:00" }
        val to = p.timeTo?.substringAfter("T")?.take(5)?.takeIf { it.isNotBlank() && it != "00:00" }
        if (from != null && to != null) "$from–$to" else (p.time ?: "")
    } ?: ""

    val isLab = ci?.form == true

    Card(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        elevation = CardDefaults.cardElevation(1.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(Modifier.padding(10.dp)) {
            // П.4: номер пары и время — в одной колонке фиксированной ширины,
            // обе строки центрируются ПО ВИДИМОМУ ТЕКСТУ.
            //
            // Ширина 66.dp была меньше, чем «09:00–10:20» при 10.sp, и
            // строка переносилась/уезжала вправо. Считаем ширину по самой
            // длинной подписи и центрируем явно.
            val timeWidth = if (timeLabel.isNotBlank()) 84.dp else 40.dp
            Column(
                Modifier.width(timeWidth),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "$pairNo",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MIET_BLUE,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                if (timeLabel.isNotBlank()) {
                    Text(
                        timeLabel,
                        fontSize = 10.sp,
                        color = LocalAppColors.current.muted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(ci?.name ?: "—", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        when (role) {
                            Role.STUDENT -> {
                                if (!ci?.teacherFull.isNullOrBlank()) append(ci?.teacherFull)
                                if (!lesson.room?.name.isNullOrBlank()) {
                                    if (isNotEmpty()) append("  •  ")
                                    append("ауд. ${lesson.room?.name}")
                                }
                            }
                            Role.AUDIENCE -> {
                                // Своя аудитория неинформативна — показываем,
                                // кто в неё придёт и из какой группы.
                                val g = lesson.group?.name
                                if (!g.isNullOrBlank()) append(g)
                                if (!ci?.teacherFull.isNullOrBlank()) {
                                    if (isNotEmpty()) append("  •  ")
                                    append(ci?.teacherFull)
                                }
                            }
                            Role.TEACHER -> {
                                val g = lesson.group?.name
                                if (!g.isNullOrBlank()) append(g)
                                if (!lesson.room?.name.isNullOrBlank()) {
                                    if (isNotEmpty()) append("  •  ")
                                    append("ауд. ${lesson.room?.name}")
                                }
                            }
                        }
                    },
                    fontSize = 12.sp, color = LocalAppColors.current.dim
                )
                if (isLab) {
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier.background(LocalAppColors.current.labBadge, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text("ЛАБОРАТОРНАЯ", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = LocalAppColors.current.favStar)
                    }
                }
            }
        }
    }
}
