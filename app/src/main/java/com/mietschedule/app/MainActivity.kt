package com.mietschedule.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope

internal val MIET_BLUE = Color(0xFF0057B8)

/** Ключ Intent: какую группу открыть из уведомления. */
internal const val EXTRA_GROUP = "extra_group"

/**
 * Ключ Intent: роль, к которой относится значение из уведомления.
 *
 * ВНИМАНИЕ: в файле ReminderScheduler.kt есть ВТОРОЙ ключ с тем же именем —
 * `ReminderScheduler.EXTRA_GROUP` со значением «group». Это разные ключи для
 * разных переходов: тот служит будильнику (система → приёмник), этот —
 * переходу из уведомления в Activity. Значения разные намеренно, и путать их
 * нельзя: с одним значением на оба перехода ломается один из них.
 */
internal const val EXTRA_GROUP_ROLE = "extra_group_role"

internal val DAY_NAMES = listOf(
    "Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье"
)
internal val DAY_SHORT = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/**
 * Номер дня недели Calendar → наш индекс (0=Пн … 6=Вс).
 *
 * Calendar считает неделю с воскресенья (SUNDAY=1 … SATURDAY=7), расписание —
 * с понедельника. Формула (cw + 5) % 7 даёт: Пн(2)→0, Вт(3)→1, Ср(4)→2,
 * Чт(5)→3, Пт(6)→4, Сб(7)→5, Вс(1)→6.
 *
 * Регрессия: раньше было `if (cw == SUNDAY) 0 else cw - 1`, то есть сдвиг на
 * единицу. 2 октября 2026 — пятница, cw=6, и приложение называло «Сб» вместо
 * «Пт», а карточки пар соседнего дня подсвечивались как сегодняшние.
 */
/**
 * Экран, который открывается при запуске.
 *
 * Требование владельца от 0.65: «при закрытии приложения оно должно
 * открываться на главной странице всегда». Сохранённый выбор на стартовый
 * экран больше не влияет: раньше он открывал расписание сразу, и главная —
 * точка входа и место, где живёт список избранного, — при повторном запуске
 * не показывалась вообще.
 *
 * Исключение одно и оно не про запуск: переход по напоминанию. В уведомлении
 * названа группа и пара, и тап по нему обязан открыть это расписание, иначе
 * напоминание перестаёт выполнять свою работу. Это адресный переход, а не
 * «закрыл и открыл», поэтому общему правилу он не подчиняется.
 *
 * Вынесено отдельной функцией, а не оставлено внутри remember: правило
 * проверяется тестом напрямую, без запуска Activity.
 */
internal fun startScreenFor(fromNotification: Boolean): Screen =
    if (fromNotification) Screen.SCHEDULE else Screen.HOME

/**
 * Куда ведёт системная кнопка «Назад» и стрелка «‹».
 *
 * Один источник правды для всех экранов. Раньше одна и та же логика
 * была продублирована в BackHandler, в переходах из «Избранного» и из
 * «О программе», и в двух местах ветвилась по-разному: пустой выбор
 * приводил к падению на `selection!!` в ветке SCHEDULE.
 *
 * Правило единое: **назад ведёт туда, откуда вошли**.
 *
 * Именно это чинит жалобу владельца: из «О программе», открытого из
 * главного меню, назад уводил в список групп, то есть «О программе»
 * навигационно приросло к подменю «студент». Причина — подменные экраны
 * решали по [hasSelection], а не по источнику: правило
 * `if (hasSelection) PICK_ENTITY else PICK_ROLE` было осмысленным, пока
 * единственным способом увидеть сохранённую группу был вход
 * «программа → расписание». С появлением избранного и прямого пути в
 * расписание оно устарело.
 *
 * @param hasSelection выбрана ли группа/аудитория/преподаватель. На экране
 *   выбора роли она пустая, и оттуда «Назад» означает выход из приложения.
 * @param origin экран, из которого пришли. Если он известен и отличается от
 *   текущего, возвращаем именно его. Иначе — экран по умолчанию из таблицы
 *   ниже, то есть поведение, когда источник неотличим от обычного входа.
 *
 * Отдельный список экранов с единственным входом (отчёт открывается только
 * из «О программе») держит [hasSelection] от лишней работы, но правило для
 * них тоже общее: если источник неизвестен, работает запись в таблице.
 */
internal fun backTargetFor(
    screen: Screen,
    hasSelection: Boolean,
    origin: Screen? = null,
): Screen {
    // Откуда пришли — приоритетнее таблицы. Отсекаем только два случая:
    // источник совпадает с текущим экраном (назад зациклился бы на месте)
    // и источник неизвестен.
    //
    // PICK_ROLE здесь НЕ исключается: главное меню — полноценная точка входа,
    // и именно его отбрасывание возвращало жалобу владельца. «О программе»
    // из главного меню обязано возвращать в главное меню.
    if (origin != null && origin != screen) return origin
    return when (screen) {
        // Запасной путь для входа без метки: подменю возвращает на главную,
        // а не на выбор роли — иначе «О программе» из главного меню вернул бы
        // в список ролей, то есть ровно то жалобу, что была исправлена в 0.60.
        Screen.PICK_ENTITY -> Screen.HOME
        Screen.PICK_ROLE -> Screen.HOME
        Screen.SETTINGS -> Screen.HOME
        Screen.ABOUT -> Screen.HOME
        // Избранное — вкладка нижней навигации, как и главная: из неё
        // «Назад» ведёт на главную, а не по истории переходов. Так же, как
        // настроек: вкладки равноправны, назад возвращает на главную.
        Screen.FAVORITES -> Screen.HOME
        Screen.SCHEDULE -> if (hasSelection) Screen.PICK_ENTITY else Screen.HOME
        // ГЛАВНАЯ — точка входа, из неё возвращаться некуда. Отдаём саму себя:
        // BackHandler на ней выключен, а стрелки в шапке у главной нет.
        Screen.HOME -> Screen.HOME
    }
}

internal fun dayIndexFromCalendar(dayOfWeek: Int): Int = (dayOfWeek + 5) % 7

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Приложение не инициализирует сторонних SDK: их здесь нет. Старт от
        // этого только быстрее — раньше инициализация SDK растягивала первую
        // отрисовку.

        // Выбор темы живёт здесь, а не в AppRoot: его меняет экран
        // «Настройки», а он лежит внутри AppRoot. Передаём лямбду.
        var themeModeState by mutableStateOf(loadThemeMode(this))

        // Показывать ли список избранного на главной. Живёт здесь, а не в
        // HomeScreen: настройка меняется на экране «Настройки», и главная
        // обязана отразить изменение сразу, без перезапуска приложения.
        // Значение по умолчанию — показывать.
        var showFavsOnHome by mutableStateOf(HomePrefs.showFavorites(this))

        // Фоновая актуализация раз в 6 часов.
        //
        // ВАЖНО: schedule() трогает WorkManager, а его первая инициализация
        // читает базу и регистрирует компоненты — это десятки миллисекунд на
        // ГЛАВНОМ потоке. Вызов прямо здесь давал «Skipped 485 frames» и
        // ANR на старте (Waited 5014ms for FocusEvent). Поэтому регистрация
        // уходит в отдельный поток: она не влияет на первый кадр.
        Thread {
            runCatching { RefreshWorker.schedule(this) }
                .onFailure { Log.w("MainActivity", "Фоновая задача не зарегистрирована", it) }
        }.start()

        // Пересчёт напоминаний при КАЖДОМ запуске.
        //
        // Будильники живут в системе и переживают перезапуск приложения, но не
        // переживают ОБНОВЛЕНИЕ: при установке новой версии Android снимает
        // PendingIntent'ы, и напоминания пропадают до первого открытия
        // расписания (пересчёт висит на загрузке данных, а со свежим кэшем
        // загрузки не происходит вовсе). Приложение обновляет себя само, то
        // есть теряло пуши после каждой установки — выглядело как «приходят
        // не все». Пересчёт идемпотентен и идёт в своём фоновом потоке:
        // выключенные напоминания он просто снимает и выходит.
        ReminderScheduler.reschedule(this, MietApi(this))

        setContent {
            // Один диалог на всё приложение: его показывает и фоновая проверка
            // при запуске, и кнопка «Проверить обновления» в «О программе».
            val updateDialogHandle = remember { UpdateDialogHandle() }
            ScheduleTheme(themeMode = themeModeState) {
                Surface(Modifier.fillMaxSize()) {
                    AppRoot(
                        requestedGroup = intent?.getStringExtra(EXTRA_GROUP),
                        // Роль из напоминания. Неизвестная строка даёт null, а
                        // не «студент» по умолчанию: иначе опечатка в ключе
                        // молча открывала бы расписание чужой роли.
                        requestedRole = intent?.getStringExtra(EXTRA_GROUP_ROLE)
                            ?.let { k -> Role.entries.firstOrNull { it.key == k } },
                        onUpdateFound = updateDialogHandle::show,
                        onThemeChange = { mode ->
                            saveThemeMode(this@MainActivity, mode)
                            themeModeState = mode
                        },
                        showFavoritesOnHome = showFavsOnHome,
                        onShowFavoritesChange = { on ->
                            HomePrefs.setShowFavorites(this@MainActivity, on)
                            showFavsOnHome = on
                        },
                    )
                // Проверка новой версии при запуске. Идёт в фоне: открытие
                // приложения не ждёт сеть, диалог всплывёт позже, если есть.
                UpdatePromptHost(
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    dialogHandle = updateDialogHandle,
                )
                }
            }
        }
    }
}

/**
 * Куда пользователь по выбору роли: сначала роль, потом своя сущность
 * (группа / преподаватель / аудитория), и только потом расписание.
 */
internal val RoleSaver = Saver<Role, String>(
    save = { it.key },
    restore = { key -> Role.fromKey(key) },
)

internal val ScreenSaver = Saver<Screen, String>(
    save = { it.name },
    // Экран не найден (например, приложение обновили, а экран убрали) —
    // открываем главную, а не подменю: главная это точка входа, и человек
    // с неё сам дойдёт куда надо.
    restore = { key -> Screen.entries.firstOrNull { it.name == key } ?: Screen.HOME },
)

/**
 * Saver для nullable источника.
 *
 * Отдельный, потому что [ScreenSaver] восстанавливает неизвестное значение как
 * `PICK_ROLE`, а здесь «неизвестно» — осмысленное состояние: источник не
 * записан, и «Назад» должен сработать по таблице экрана. Если бы источник
 * молча становился главным меню, то холодный старт сразу на расписании вёл
 * бы назад не туда: человек открыл приложение на расписании, а уходил в меню.
 *
 * Хранится nullable-тип, а Saver требует Saveable, то есть не null. Поэтому
 * неизвестный источник кодируется пустой строкой, а не null.
 */
internal val ScreenNullableSaver = Saver<Screen?, String>(
    save = { it?.name ?: "" },
    restore = { key ->
        if (key.isEmpty()) null else Screen.entries.firstOrNull { it.name == key }
    },
)

internal enum class Screen {
    /**
     * ГЛАВНАЯ: блок избранного, кнопка «Выбрать роль», нижнее подменю.
     *
     * Раньше главной была [PICK_ROLE] — экран с карточками «Студент /
     * Преподаватель / Аудитория». По требованию владельца от 0.62 эти три
     * карточки ушли в отдельный экран, а главная стала компактной: сначала
     * видно избранное (то, ради чего приложение открывают), ниже — одна
     * кнопка выбора роли и служебное подменю.
     *
     * Разделение на два экрана, а не переименование: у них разные обязанности.
     * Главная — вход, с которого некуда возвращаться, и её не должно быть
     * целью «Назад». PICK_ROLE — подменю выбора, куда «Назад» обязан вернуться.
     * Смешав их в один экран, пришлось бы гадать, куда ведёт «Назад» с главной.
     */
    HOME,
    PICK_ROLE, PICK_ENTITY, SCHEDULE, SETTINGS, ABOUT,
    /**
     * ИЗБРАННОЕ: список отмеченных звёздами групп, преподавателей и
     * аудиторий. Отдельный экран, а не блок на главной, потому что теперь
     * это вкладка нижней навигации: у вкладки должна быть своя шапка
     * и свой снимок избранного, и дублировать список на главной значило бы
     * показывать одно и то же дважды. Главная остаётся точкой входа —
     * блок «сейчас и дальше» и кнопка «Добавить».
     */
    FAVORITES,
}

@Composable
fun AppRoot(
    /** Значение из напоминания: что открыть сразу после запуска. */
    requestedGroup: String? = null,
    /**
     * Роль из напоминания.
     *
     * Без неё адресный переход открывал расписание ЧУЖОЙ роли: уведомление
     * несло только название группы, роль бралась из сохранённой, и при
     * сохранённой роли «преподаватель» тап по напоминанию о группе показывал
     * расписание преподавателя — данные выбираются по роли, а не по имени в
     * уведомлении.
     */
    requestedRole: Role? = null,
    onUpdateFound: (UpdateInfo) -> Unit = {},
    onThemeChange: (Int) -> Unit = {},
    /**
     * Показывать ли список избранного на главной.
     *
     * Состояние держится здесь, а не в HomeScreen: настройка меняется на
     * экране «Настройки», и главная обязана показать изменение сразу.
     */
    showFavoritesOnHome: Boolean = true,
    /** Изменилась настройка показа избранного на главной. */
    onShowFavoritesChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val api = remember { MietApi(context) }
    val prefs = remember { GroupPrefs(context) }

    // СОСТОЯНИЕ ПРИ ПОВОРОТЕ ЭКРАНА. rememberSaveable переживает recreate
    // Activity, обычный remember — нет: при повороте роль, выбранная группа
    // и открытый экран возвращались к значениям из prefs. Роль и экран — enum,
    // а rememberSaveable умеет сохранять только типы, поддерживаемые Bundle,
    // поэтому для них заданы Saver (выше): наружу отдаётся строка.
    var role by rememberSaveable(stateSaver = RoleSaver) { mutableStateOf(requestedRole ?: prefs.role()) }
    // Выбор в рамках роли. Для преподавателя храним ФИО, для аудитории — имя
    // (по имени проще искать в избранном), код аудитории добираем из списка.
    // Код преподавателя и имя аудитории тоже переживают поворот. Раньше это
    // был обычный remember: роль и группа восстанавливались, а эти два поля
    // обнулялись, и у преподавателя после поворота переставал грузиться
    // индекс (refreshTeacher/lessonsOf получали пустой код), у аудитории
    // терялось имя для фильтра кэша.
    var teacherCode by rememberSaveable { mutableStateOf("") }
    var roomNameArg by rememberSaveable { mutableStateOf("") }
    var selection by rememberSaveable { mutableStateOf(requestedGroup ?: prefs.load()) }

    // СТАРТОВЫЙ ЭКРАН.
    //
    // Требование владельца от 0.65: приложение открывается на главной всегда,
    // в том числе при сохранённой группе. Раньше сохранённый выбор открывал
    // сразу расписание (а до этого — список групп), и главная со списком
    // избранного при повторном запуске не показывалась вообще.
    //
    // Исключение одно и оно не про запуск: переход по напоминанию. Тогда
    // requestedGroup не пуст, и открыть надо расписание названной сущности.
    var screen by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf(startScreenFor(requestedGroup != null))
    }

    // Откуда открыли расписание — экран, в который ведёт «Назад».
    //
    // Одного флага «из избранного» мало: из избранного назад ведёт в
    // избранное, а из блока «сейчас и дальше» в главном меню — в меню. Оба
    // перехода выглядят снаружи одинаково (строка с названием группы), но
    // возвращать надо туда, откуда человек ушёл.
    //
    // rememberSaveable, а не remember: при повороте экрана происхождение
    // не должно теряться, иначе после поворота назад снова уводит не туда.
    // ПРОИСХОЖДЕНИЕ ЛЮБОГО ЭКРАНА, а не только расписания.
    //
    // Раньше метка была одна и только для расписания, а подменные экраны
    // («О программе», настройки, избранное) решали, куда ведёт «Назад»:
    // по наличию сохранённого выбора: `if (hasSelection) PICK_ENTITY else
    // PICK_ROLE`. Правило выглядело осмысленным, пока единственным способом
    // увидеть сохранённую группу был вход «программа → расписание».
    //
    // Когда появилось избранное, появился и прямой путь в расписание, и
    // правило устарело: из «О программе», открытого из ГЛАВНОГО МЕНЮ, назад
    // уводил в список групп — то есть «О программе» навигационно приросло к
    // подменю «студент». То же для настроек и избранного.
    //
    // Теперь источник общий и один: откуда вошли — туда и назад. Это ровно
    // то, что человек ожидает от кнопки, не задумываясь.
    var screenOrigin by rememberSaveable(stateSaver = ScreenNullableSaver) {
        mutableStateOf<Screen?>(null)
    }

    /**
     * Перейти на экран, запомнив, откуда пришли.
     *
     * Все переходы идут через неё, потому что прямой `screen = ...` забыл
     * бы метку, и «Назад» ушёл бы по правилу экрана, а не по факту. Исключение
     * — возврат назад: там источник меняется намеренно, и переход оформляется
     * явно через [backTargetFor].
     */
    fun goTo(target: Screen) {
        if (target != screen) screenOrigin = screen
        screen = target
    }
    // СИСТЕМНАЯ КНОПКА «НАЗАД». Проверено на эмуляторе: без этого перехвата
    // Android завершал Activity на всех пяти экранах (focus уходил на launcher),
    // потому что она была корнем стека. На экранах выбора группы и роли
    // стрелки «‹» в шапке либо нет, либо она ведёт на тот же экран, то есть
    // вернуться было нечем. Здесь те же переходы, что и у стрелки «‹».
    // На главной перехват выключен: оттуда возвращаться некуда, и «Назад»
    // должен закрывать приложение, а не открывать подменю выбора роли.
    BackHandler(enabled = screen != Screen.HOME) {
        screen = backTargetFor(screen, selection != null, screenOrigin)
    }
    // Кнопка «Обновить» есть на всех экранах, поэтому состояние живёт здесь и
    // передаётся вниз — иначе каждый экран вёл бы свой счётчик.
    var refreshing by remember { mutableStateOf(false) }

    // СЧЁТЧИК ПОКОЛЕНИЙ. Это главное: refreshCurrent() пишет в кэш, но экран
    // расписания — отдельный composable со своим состоянием, и без сигнала он
    // продолжает показывать старое. Именно поэтому «Обновить» отработал за
    // 30 секунд, а на экране осталось «ещё не обновлялось».
    // Любое успешное обновление увеличивает счётчик, и ScheduleScreen по нему
    // перечитывает кэш и пересчитывает дату.
    var dataGeneration by remember { mutableIntStateOf(0) }

    // СОСТОЯНИЕ ЭКРАНА РАСПИСАНИЯ ДЛЯ ОТЧЁТА ОБ ОШИБКЕ.
    //
    // Отчёт об ошибке открывается из «О программе», когда расписание уже
    // закрыто, поэтому его состояние надо сохранить здесь: сколько пар
    // было на экране и какой текст ошибки показывался. Без этого письмо
    // содержало бы «пар: 0, ошибок нет» при любом реальном сбое — то есть
    // ровно то самое, ради чего отчёт и нужен.
    var reportLessons by remember { mutableIntStateOf(0) }
    var reportError by remember { mutableStateOf<String?>(null) }

    // Прогресс обновления, чтобы «30 секунд крутит» не выглядели зависанием.
    var refreshNote by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    /**
     * Открыть расписание по строке блока «сейчас и дальше» в главном меню.
     *
     * Переход тот же, что из «Избранного»: восстанавливается роль, значение и
     * ключ поиска (ФИО для преподавателя, код и имя для аудитории). Без этого
     * строка показывала бы пару, но нажатие на неё приводило бы к пустому
     * расписанию — «Не удалось определить аудиторию».
     *
     * Происхождение помечается как «из избранного»: блок живёт в главном
     * меню, и назад с расписания должно вести в меню, а не в список групп.
     */
    fun openFromMenu(r: Role, value: String) {
        prefs.saveRole(r)
        role = r
        selection = value
        when (r) {
            Role.STUDENT -> {
                teacherCode = ""
                roomNameArg = ""
            }
            Role.TEACHER -> {
                teacherCode = TeacherIndex.key(value)
                roomNameArg = ""
            }
            Role.AUDIENCE -> {
                roomNameArg = value
                teacherCode = api.audienceCodeByName(value)?.toString().orEmpty()
            }
        }
        prefs.saveFor(r, value)
        // Блок живёт в главном меню, поэтому «Назад» с открытого расписания
        // обязан вести в меню. Метку происхождения проставляет goTo.
        goTo(Screen.SCHEDULE)
    }

    // ВОССТАНОВЛЕНИЕ КЛЮЧА РОЛИ ПРИ ХОЛОДНОМ СТАРТЕ.
    //
    // `selection` возвращается из prefs, а вот ключ, по которому расписание
    // находит преподавателя или аудиторию, — нет: `teacherCode` и
    // `roomNameArg` инициализируются пустыми. Для преподавателя это
    // терпимо (TeacherIndex.lessonsOf умеет искать по имени), но для
    // аудитории фатально: ScheduleData делает `teacherCode.toIntOrNull()`
    // и без кода показывает «Не удалось определить аудиторию», хотя имя
    // аудитории в prefs есть и экран запускается именно с ней.
    //
    // Замечено на 0.38 на эмуляторе: после force-stop и запуска аудитория
    // 1201 (м) открывалась с этой ошибкой, хотя через избранное работала.
    //
    // Значения пишутся в LaunchedEffect, а не прямо в теле composable:
    // rememberSaveable-переменные менять во время композиции нельзя —
    // это даёт нестабильное состояние и лишний повторный запуск эффекта.
    val startGroup = selection
    LaunchedEffect(startGroup, role) {
        if (startGroup == null) return@LaunchedEffect
        when (role) {
            Role.AUDIENCE -> if (teacherCode.isBlank()) {
                // Код аудитории ищем по имени в кэше списка аудиторий. Сети
                // здесь нет: список уже на диске, поэтому старт не задерживается.
                api.audienceCodeByName(startGroup)?.let { code ->
                    teacherCode = code.toString()
                    roomNameArg = startGroup
                }
            }
            Role.TEACHER -> if (teacherCode.isBlank()) {
                // Для преподавателя teacherCode — нормализованное ФИО.
                teacherCode = TeacherIndex.key(startGroup)
                roomNameArg = ""
            }
            Role.STUDENT -> {
                // У студента кода аудитории или преподавателя быть не должно:
                // иначе после смены роли в кэше ищется чужая комната.
                teacherCode = ""
                roomNameArg = ""
            }
        }
    }

    /**
     * Принудительное обновление данных под текущую роль.
     *
     * Для преподавателя это пересборка индекса (обход 343 групп), поэтому он
     * самый долгий; для студента и аудитории — точечное обновление. Ход работы
     * на IO-диспетчере: сетевой вызов из главного потока даёт
     * NetworkOnMainThreadException.
     */
    /**
     * ОБНОВЛЕНИЕ ВСЕГО, ЧТО ВИЖНО. Одна кнопка в любом меню.
     *
     * Раньше здесь было три разных поведения, и каждое обновляло не всё:
     * студент — свою группу, аудитория — список аудиторий, преподаватель —
     * индекс. Пользователь жал кнопку и получал «обновилось», а на экране
     * было по-прежнему. Теперь обновляется ВСЁ, независимо от роли:
     * список групп, расписания всех групп, список аудиторий, ответ по
     * выбранной аудитории, индекс преподавателей.
     *
     * 343 группы параллельно — около 35 секунд, поэтому показываем прогресс
     * («Обновляю… 120 из 343»), иначе это выглядит как зависшее приложение.
     */
    fun refreshCurrent() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            refreshNote = "Обновляю…"
            // Прогресс счётчика показываем отдельной корутиной на главном
            // потоке. Сам обход идёт в withContext(Dispatchers.IO), где пишет
            // в AtomicInteger, а надписью занимается только этот сборщик —
            // раньше refreshNote менялся прямо из шести потоков пула.
            val progress = java.util.concurrent.atomic.AtomicInteger(-1)
            val totalGroups = java.util.concurrent.atomic.AtomicInteger(0)
            val progressJob = launch {
                while (true) {
                    val n = progress.get()
                    if (n >= 0) refreshNote = "Обновляю… $n из ${totalGroups.get()}"
                    delay(300)
                }
            }
            val result = withContext(Dispatchers.IO) {
                // Счётчик обновляется из пула потоков, поэтому атомарный:
                // обычный `schedules++` из шести потоков терял инкременты, и
                // строка «Обновлено 287 из 343» показывала неверное число.
                val schedules = java.util.concurrent.atomic.AtomicInteger(0)
                var groupsN = 0
                runCatching {
                    // Список групп — с сайта, кэш не берём: кнопка обещает
                    // обновить вообще всё, а TTL в неделю прятал новые группы.
                    val groups = api.fetchGroups(force = true)
                    groupsN = groups.size
                    totalGroups.set(groups.size)
                    // Расписания групп — единственный источник для аудитории и
                    // преподавателя, поэтому обновляем их ВСЕГДА.
                    val total = groups.size
                    val done = java.util.concurrent.atomic.AtomicInteger(0)
                    // Результаты копим В ПАМЯТИ и пишем в кэш одним пакетом.
                    //
                    // Раньше каждый из 343 запроса сам делал prefs.commit(), то
                    // есть 343 синхронных записи на диск посреди сетевого обхода.
                    // Замерено на эмуляторе: 120 групп за 208 секунд. Живым curl
                    // те же 343 группы скачиваются за 30 секунд — то есть тормозил
                    // не сайт, а запись кэша. Здесь сеть идёт чисто и параллельно.
                    val fresh = java.util.concurrent.ConcurrentHashMap<String, String>()
                    val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
                    try {
                        pool.invokeAll(groups.map { g ->
                            java.util.concurrent.Callable {
                                runCatching { api.fetchSchedule(g) }
                                    .onSuccess { fresh[g] = it; schedules.incrementAndGet() }
                                    .onFailure { Log.w("Refresh", "Не обновили $g: ${it.message}") }
                                // Здесь только счётчики: refreshNote — Compose
                                // State, и писать его из потоков пула нельзя
                                // (запись вне главного потока). Надписью
                                // занимается корутина-прогресс выше.
                                progress.set(done.incrementAndGet())
                            }
                        })
                    } finally {
                        pool.shutdown()
                    }
                    // Один commit() на все расписания вместо 343.
                    refreshNote = "Сохраняю…"
                    api.saveSchedules(fresh)
                    Log.i("Refresh", "В кэш записано ${fresh.size} расписаний одним пакетом")
                    // Списки и ответ по выбранной аудитории — тоже.
                    runCatching { api.fetchAudiences() }
                    teacherCode.toIntOrNull()?.let { code ->
                        runCatching { api.fetchAudience(code) }
                            .onFailure { Log.w("Refresh", "Аудитория $code: ${it.message}") }
                    }
                    // Индекс преподавателей пересобираем из ТОЛЬКО ЧТО
                    // скачанного кэша (buildFromCache), а не через TeacherIndex.build:
                    // тот ходит на miet.ru по всем 343 группам заново, и «обновление
                    // всего» превращалось в два полных прохода подряд. Отсюда же
                    // берётся метка индекса на экране «О программе».
                    runCatching { TeacherIndex.buildFromCache(api, groups) }
                        .onSuccess { Log.i("Refresh", "Индекс преподавателей: $it") }
                        .onFailure { Log.w("Refresh", "Индекс преподавателей: ${it.message}") }

                    // Начало семестра. Оно кэшируется на сутки, а кнопка
                    // «обновить всё» его не трогала: после смены семестра
                    // приложение целый день показывало недели от старой
                    // даты. Кнопка обещает актуализировать всё — значит и
                    // семестр тоже.
                    runCatching { api.semestrStart() }
                        .onSuccess { Log.i("Refresh", "Семестр с $it") }
                        .onFailure { Log.w("Refresh", "Семестр: ${it.message}") }

                    // Отметка «проверено» под текущим объектом. Без неё после
                    // обновления на экране оставалось «проверено давно»,
                    // хотя кэш только что перезаписан, и в отчёте об ошибке
                    // уходила неверная метка.
                    selection?.trim()?.takeIf { it.isNotEmpty() }?.let { sel ->
                        runCatching { api.markCheck(role, sel) }
                            .onFailure { Log.w("Refresh", "Метка проверки: ${it.message}") }
                    }

                    Log.i("Refresh", "Готово: расписаний ${schedules.get()} из $groupsN")
                }.onFailure {
                    Log.w("Refresh", "Обновление с ошибкой: ${it.message}")
                }
                schedules.get() to groupsN
            }
            progressJob.cancel()
            // Сигнал экрану перечитать кэш. Без этого он показывал бы старое.
            dataGeneration++

            // Напоминания строятся по избранным группам, то есть по кэшу.
            // Кэш перезаписан, а будильники остались на старых временах:
            // человек получил звонок по расписанию, которого уже нет.
            // Пересчёт читает и разбирает JSON, поэтому идёт на IO.
            //
            // Раньше здесь ничего не было — перепланирование вызывал только
            // RefreshWorker, то есть кнопка обновления будильники не трогала.
            if (result.first > 0) {
                withContext(Dispatchers.IO) {
                    runCatching { ReminderScheduler.reschedule(context, api) }
                        .onFailure { Log.w("Refresh", "Напоминания не перепланированы: ${it.message}") }
                }
            }
            refreshNote = if (result.first > 0) {
                "Обновлено ${result.first} из ${result.second}"
            } else "Ничего не обновилось"
            refreshing = false
        }
    }


    // ───── НИЖНЯЯ НАВИГАЦИЯ НА ВСЕХ ЭКРАНАХ ─────
    //
    // Общий Scaffold с NavigationBar живёт здесь, а не в HomeScreen: меню
    // «Главная / Избранное / Настройки» должно быть на КАЖДОМ экране, а не
    // только на главной. Каждый экран держит свою шапку (TopAppBar), но
    // нижнее меню — общая оболочка, один источник правды.
    //
    // Переходы вкладок идут через goTo(): ядро навигации (screenOrigin,
    // backTargetFor) работает как прежде — вкладки просто меняют экран,
    // «Назад» по-прежнему ведёт туда, откуда пришли.
    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                NavigationBarItem(
                    selected = screen == Screen.HOME,
                    onClick = { if (screen != Screen.HOME) goTo(Screen.HOME) },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Главная") },
                )
                NavigationBarItem(
                    selected = screen == Screen.FAVORITES,
                    onClick = { if (screen != Screen.FAVORITES) goTo(Screen.FAVORITES) },
                    icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                    label = { Text("Избранное") },
                )
                NavigationBarItem(
                    selected = screen == Screen.SETTINGS,
                    onClick = { if (screen != Screen.SETTINGS) goTo(Screen.SETTINGS) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Настройки") },
                )
            }
        }
    ) { pad ->
        // pad — отступ под нижнее меню. Каждый экран получает его как
        // внутренний отступ контента, чтобы список не уезжал под панель.
        //
        // Column с весами: контент занимает всё свободное место (weight 1),
        // служебные элементы — свою естественную высоту. Ничего не должно
        // перекрывать нижнее меню или уезжать за экран.
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (screen) {
        // ГЛАВНАЯ. Блок «сейчас и дальше», список избранного кнопками,
        // кнопка добавления и служебное подменю.
        Screen.HOME -> HomeScreen(
            api = api,
            prefs = prefs,
            onOpenFavorite = { r, value -> openFromMenu(r, value) },
            onRefresh = { refreshCurrent() },
            onAdd = { goTo(Screen.PICK_ROLE) },
            refreshNote = refreshNote,
            onAbout = { goTo(Screen.ABOUT) },
            onSettings = { goTo(Screen.SETTINGS) },
            onOpenFavorites = { goTo(Screen.FAVORITES) },
            refreshing = refreshing,
            showFavorites = showFavoritesOnHome,
        )

        // ДОБАВЛЕНИЕ. Выбор группы, преподавателя и аудитории вызывается
        // кнопкой «Добавить» с главной. Отметки текущей роли здесь нет и
        // подсветки нет: это меню выбора, а не показ состояния.
        Screen.PICK_ROLE -> AddPickerScreen(
            onRefresh = { refreshCurrent() },
            onBack = { screen = backTargetFor(Screen.PICK_ROLE, selection != null, screenOrigin) },
            onHome = { goTo(Screen.HOME) },
            onPick = { r ->
                prefs.saveRole(r)
                role = r
                selection = prefs.loadFor(r)
                teacherCode = ""
                goTo(Screen.PICK_ENTITY)
            },
        )

        Screen.PICK_ENTITY -> {
            when (role) {
                Role.STUDENT -> GroupPickerScreen(
                    api, prefs, selection,
                    onChosen = { chosen ->
                        selection = chosen
                        // Группу нужно сохранить: без этого при следующем
                        // запуске prefs.load() пуст, приложение открывается на
                        // выборе роли, и каждый раз группу приходится выбирать
                        // заново. Преподаватель и аудитория здесь сохраняют
                        // своё значение — студент не сохранял ничего.
                        prefs.save(chosen)
                        goTo(Screen.SCHEDULE)
                    },
                    // Стрелка «‹» возвращает туда, откуда вошли (обычно на
                    // выбор роли), а домик — всегда на главную.
                    onBack = { screen = backTargetFor(Screen.PICK_ENTITY, selection != null, screenOrigin) },
                    onHome = { goTo(Screen.HOME) },
                    onRefresh = { refreshCurrent() },
                    refreshing = refreshing,
                    refreshNote = refreshNote,
                )
                Role.TEACHER -> TeacherPickerScreen(
                    api, prefs,
                    onChosen = { name, code ->
                        selection = name
                        teacherCode = code
                        // Ключ преподавателя — нормализованное ФИО, а показываем
                        // исходное: в избранном нужно видеть «Лупин Сергей Сергеевич»,
                        // а не внутренний ключ в нижнем регистре.
                        prefs.save(name)
                        goTo(Screen.SCHEDULE)
                    },
                    onBack = { screen = backTargetFor(Screen.PICK_ENTITY, selection != null, screenOrigin) },
                    // Раньше здесь передавались ТОЛЬКО onChosen и onBack.
                    // onRefresh не передавался, поэтому срабатывал его
                    // аргумент по умолчанию `= {}` из сигнатуры: кнопка в шапке нажималась и ничего
                    // не делала — «преподаватель ещё не выбран, обновление
                    // не жмётся». Заодно не передавались «Роль», refreshing
                    // и прогресс, то есть шапка у этой роли отличалась.
                    onRefresh = { refreshCurrent() },
                    onChangeRole = { goTo(Screen.HOME) },
                    refreshing = refreshing,
                    refreshNote = refreshNote,
                )
                Role.AUDIENCE -> AudiencePickerScreen(
                    api, prefs,
                    onChosen = { code, name ->
                        selection = name
                        teacherCode = code.toString()
                        // Имя аудитории нужно и для фильтра кэша: код из
                        // /audiences — внутренний id, а не номер (у «8307»
                        // id 234), и по коду расписание находилось не полностью.
                        roomNameArg = name
                        prefs.save(name)
                        goTo(Screen.SCHEDULE)
                    },
                    onBack = { screen = backTargetFor(Screen.PICK_ENTITY, selection != null, screenOrigin) },
                    onRefresh = { refreshCurrent() },
                    onChangeRole = { goTo(Screen.HOME) },
                    refreshing = refreshing,
                    refreshNote = refreshNote,
                    onAbout = { goTo(Screen.ABOUT) },
                )
            }
        }

        Screen.SETTINGS -> SettingsScreen(
            onBack = { screen = backTargetFor(Screen.SETTINGS, selection != null, screenOrigin) },
            onRefresh = { refreshCurrent() },
            onChangeRole = { goTo(Screen.HOME) },
            onThemeChange = onThemeChange,
            onAbout = { goTo(Screen.ABOUT) },
            refreshing = refreshing,
            showFavorites = showFavoritesOnHome,
            onShowFavoritesChange = onShowFavoritesChange,
        )

        Screen.ABOUT -> AboutScreen(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            onBack = { screen = backTargetFor(Screen.ABOUT, selection != null, screenOrigin) },
            onUpdateFound = onUpdateFound,
            onRefresh = { refreshCurrent() },
            onChangeRole = { goTo(Screen.HOME) },
            refreshing = refreshing,
            refreshNote = refreshNote,
            // Тема переехала в «Настройки», поэтому здесь её переключателя
            // нет и лямбда не передаётся.
        )

        
        // ───────── вкладка «Избранное» ─────────
        Screen.FAVORITES -> FavoritesScreen(
            prefs = prefs,
            onOpenFavorite = { r, value -> openFromMenu(r, value) },
            onBack = { screen = backTargetFor(Screen.FAVORITES, selection != null, screenOrigin) },
            onRefresh = { refreshCurrent() },
            refreshing = refreshing,
            onAdd = { goTo(Screen.PICK_ROLE) },
        )

        // Расписание без выбранной сущности показывать нечем: экран взял бы
        // пустую группу. Раньше здесь стояло selection!!, и любой путь,
        // обнулявший выбор, приводил к падению. Теперь это тихий переход
        // на выбор роли вместо исключения.
        Screen.SCHEDULE -> ScheduleBody(
            api = api,
            prefs = prefs,
            role = role,
            selection = selection,
            onNoSelection = { goTo(Screen.HOME) },
            teacherCode = teacherCode,
            roomName = roomNameArg,
            dataGeneration = dataGeneration,
            onRefreshAll = { refreshCurrent() },
            refreshNote = refreshNote,
            // Общее обновление идёт около 30 секунд. Без этого кнопка ⭯ на
            // экране расписания оставалась активной и нажималась повторно,
            // начиная второй круг обхода поверх первого.
            refreshingAll = refreshing,
            // Стрелка «‹» в шапке расписания идёт через тот же backTargetFor, что и
            // системная кнопка «Назад». Раньше здесь стояло
            // `{ screen = Screen.PICK_ENTITY }` — то есть стрелка всегда вела
            // в список групп, даже когда расписание открыли из избранного или
            // из блока «сейчас и дальше» в главном меню. Отмечено владельцем
            // как нелогичное: назад приводит не туда, откуда вошёл.
            onChangeEntity = {
                screen = backTargetFor(Screen.SCHEDULE, selection != null, screenOrigin)
            },
            // Из избранного аудитория открывается по имени (value), а код
            // неизвестен — фильтр кэша отработает по имени, это верно.
            onChangeRole = { goTo(Screen.HOME) },
            onScreenState = { n, err -> reportLessons = n; reportError = err },
        )
            }   // закрытие when(screen)
            }   // закрытие Box(weight 1f) — контент экрана
        }   // закрытие Column
    }   // закрытие Scaffold
}

/**
 * Экран расписания с защитой от пустого выбора.
 *
 * Раньше AppRoot писал `group = selection!!`: если выбор оказывался пустым,
 * приложение падало. Пустой выбор достижим — «Избранное» открывается и с
 * экрана выбора роли, где ничего ещё не выбрано. Здесь вместо исключения
 * тихий переход на экран выбора роли.
 */
@Composable
private fun ScheduleBody(
    api: MietApi,
    prefs: GroupPrefs,
    role: Role,
    selection: String?,
    onNoSelection: () -> Unit,
    teacherCode: String,
    roomName: String,
    dataGeneration: Int,
    onRefreshAll: () -> Unit,
    refreshNote: String,
    /** Идёт ли общее обновление по ⭯ — нужно для крутилки в шапке. */
    refreshingAll: Boolean = false,
    onChangeEntity: () -> Unit,
    onChangeRole: () -> Unit,
    onScreenState: (Int, String?) -> Unit = { _, _ -> },
) {
    val group = selection
    if (group == null) {
        LaunchedEffect(Unit) { onNoSelection() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    ScheduleScreen(
        api = api,
        prefs = prefs,
        role = role,
        group = group,
        teacherCode = teacherCode,
        roomName = roomName,
        dataGeneration = dataGeneration,
        onRefreshAll = onRefreshAll,
        refreshNote = refreshNote,
        refreshingAll = refreshingAll,
        onChangeEntity = onChangeEntity,
        onChangeRole = onChangeRole,
        onScreenState = onScreenState,
    )
}


// ───────────────────────────── расписание ─────────────────────────────
