package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Избранное: доступность кнопки и корректность открытия записи.
 *
 * Требование пользователя: «перемещаем из шапки в основное меню (ниже
 * выбора роли)» и «пользователь добавил группу, но если выберет её из
 * избранного выводит просто в выбор группы, короче криво-косо».
 *
 * Регрессии, которые закрывает этот файл.
 *
 * 1. Открытие из избранного вело в PICK_ENTITY: после тапа по группе
 *    пользователь попадал в список групп и должен был жать на неё ещё раз.
 *
 * 2. Для преподавателя не восстанавливался teacherCode. TeacherIndex ищет
 *    преподавателя по нормализованному ФИО (TeacherIndex.key), а из избранного
 *    приходит исходное имя, поэтому сравнение не проходило и экран показывал
 *    «Пар не найдено — проверь ФИО».
 *
 * 3. Для аудитории не восстанавливался внутренний код: расписание
 *    запрашивается по id из /audiences, а в избранном лежит только название.
 *    Без кода — ошибка «Не удалось определить аудиторию».
 *
 * 4. Выбор сохранялся через prefs.save(), который пишет под ключ ТЕКУЩЕЙ
 *    роли, а не той, по которой открывали: из вкладки «Преподаватели» при
 *    активной роли «студент» имя преподавателя попадало в слот студента.
 */
class FavoritesTest {

    private val mainDir = File("src/main/java/com/mietschedule/app")

    private fun src(name: String): String = File(mainDir, name).readText()

    /**
     * Исходник без комментариев.
     *
     * Обязательна для проверок «такого в коде нет»: файлы несут длинные
     * пояснения, которые цитируют старый сломанный код, и поиск по сырому
     * тексту находит их вместо кода.
     */
    private fun stripComments(source: String): String =
        source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    // ── Доступность кнопки ────────────────────────────────────────────────

    /**
     * Список избранного на главной: кнопка на каждое избранное.
     *
     * Требование владельца от 0.65: «кнопку избранное и меню избранное убираем
     * с главной, вместо неё делаем на главной список Избранное и дальше кнопки
     * для быстрого доступа на расписание, которое было добавлено в избранное».
     */
    /**
     * Главная: блок «сейчас и дальше» + превью избранного + «Добавить».
     *
     * Требование владельца от 0.65: «кнопку избранное и меню избранное
     * убираем с главной, вместо неё делаем на главной список Избранное…».
     * С вкладкой «Избранное» главная показывает только превью — первые
     * три карточки, остальное на вкладке. Полный список не дублируется.
     */
    @Test
    fun `на главной превью избранного кнопками`() {
        val home = stripComments(src("HomeScreen.kt"))
        assertTrue(
            "На главной нет снимка избранного",
            home.contains("favSnapshot(prefs)"),
        )
        assertTrue(
            "Кнопка избранного не открывает расписание",
            home.contains("onOpenFavorite(entry.role, entry.value)"),
        )
        assertTrue(
            "Нет пояснения при пустом избранном",
            home.contains("FAV_EMPTY_HINT"),
        )
        // Превью ограничено тремя карточками, полный список — на вкладке.
        assertTrue(
            "Превью избранного должно быть ограничено (take)",
            home.contains("favs.take(3)"),
        )
    }

    /** Единственный вход в выбор группы, преподавателя и аудитории. */
    @Test
    fun `на главной есть кнопка добавления`() {
        val home = stripComments(src("HomeScreen.kt"))
        assertTrue("Нет кнопки «Добавить»", home.contains("title = \"Добавить\""))
        assertTrue(
            "Кнопка добавления не ведёт на экран выбора",
            home.contains("onClick = onAdd"),
        )
    }

    @Test
    fun `на главной только превью а полный список на вкладке`() {
        val home = src("HomeScreen.kt")
        // Полный список вынесен в FavoritesScreen; на главной — превью.
        assertTrue(
            "Экран-вкладка «Избранное» должна существовать",
            home.contains("fun FavoritesScreen("),
        )
        assertTrue(
            "Превью ограничено тремя карточками",
            home.contains("favs.take(3)"),
        )
        // На вкладке — весь список, без ограничения take.
        val fav = home.substringAfter("fun FavoritesScreen(")
        assertFalse(
            "На вкладке «Избранное» не должно быть ограничения превью",
            fav.contains("favs.take(3)"),
        )
    }


    @Test
    fun `в шапке экранов нет кнопки избранного`() {
        // Кнопка в шапке означала «любить текущую группу» рядом со звёздочкой
        // и на других экранах сдвигала соседей. Избранное теперь вкладка
        // нижней навигации, а не кнопка в шапке.
        val s = src("MietTopBar.kt")
        assertTrue("В шапке остался вызов onOpenFavorites", !s.contains("onOpenFavorites"))
        assertTrue("В шапке осталась кнопка «Избранное»", !s.contains("\"Избранное\""))
    }

    @Test
    fun `запас шапки уменьшен под убранные кнопки`() {
        val s = src("MietTopBar.kt")
        // Было 208 dp (звезда+избранное+обновление+роль), затем 160 (без
        // избранного). Теперь домик «На главную» тоже убран — остаются звезда
        // и обновление: 112 dp, иначе заголовок ужимается зря и выглядит
        // обрезанным.
        assertTrue(
            "Ширина под кнопки не обновлена после убора домика",
            s.contains("RESERVED_FOR_ACTIONS = 112"),
        )
    }

    // ── Открытие из избранного ─────────────────────────────────────────────

    @Test
    fun `из избранного открывается сразу расписание`() {
        val s = src("MainActivity.kt")
        val onOpen = s.substringAfter("fun openFromMenu(").substringBefore("\n    }")
        // Переход идёт через goTo, а не прямым присваиванием: прямой потерял бы
        // метку происхождения и назад ушёл бы в список групп вместо избранного.
        assertTrue(
            "Из избранного ведёт не в SCHEDULE, а в " +
                (Regex("""goTo\((Screen\.\w+)\)""").find(onOpen)?.groupValues?.get(1) ?: "никуда"),
            onOpen.contains("goTo(Screen.SCHEDULE)"),
        )
        assertTrue(
            "Из избранного всё ещё ведёт в выбор сущности",
            !onOpen.contains("Screen.PICK_ENTITY"),
        )
    }

    @Test
    fun `преподавателю из избранного передаётся ключ ФИО`() {
        val s = src("MainActivity.kt")
        val onOpen = s.substringAfter("fun openFromMenu(").substringBefore("\n    }")
        // teacherCode для преподавателя — нормализованное ФИО, а не пустая
        // строка: с пустой lessonsOf ничего не находит.
        assertTrue(
            "Преподавателю не передаётся TeacherIndex.key(name)",
            onOpen.contains("TeacherIndex.key(value)"),
        )
    }

    @Test
    fun `аудитории из избранного находится внутренний код`() {
        val s = src("MainActivity.kt")
        val onOpen = s.substringAfter("fun openFromMenu(").substringBefore("\n    }")
        assertTrue(
            "Для аудитории не ищется код по названию",
            onOpen.contains("audienceCodeByName"),
        )
        assertTrue(
            "Для аудитории не сохраняется имя для фильтра кэша",
            onOpen.contains("roomNameArg = value"),
        )
    }

    @Test
    fun `выбор сохраняется под роль открытой вкладки`() {
        // prefs.save() берёт роль из хранилища, а не ту, по которой открыли.
        // Поэтому нужен saveFor(role, value).
        val prefs = src("GroupPrefs.kt")
        assertTrue("Нет GroupPrefs.saveFor(role, value)", prefs.contains("fun saveFor("))

        val s = src("MainActivity.kt")
        val onOpen = s.substringAfter("fun openFromMenu(").substringBefore("\n    }")
        assertTrue(
            "Выбор сохраняется через saveFor, а не save()",
            onOpen.contains("prefs.saveFor(r"),
        )
    }

    @Test
    fun `поиск кода аудитории добавлен в api`() {
        val s = src("MietApi.kt")
        assertTrue("Нет MietApi.audienceCodeByName", s.contains("fun audienceCodeByName("))
        // Сравнение по roomKey: имя в списке и в кэше может отличаться пробелами.
        assertTrue(
            "Поиск идёт по точному тексту, а не по roomKey",
            s.substringAfter("fun audienceCodeByName(").contains("roomKey(it.name) == want"),
        )
    }

    // ── Что именно хранится в избранном ────────────────────────────────────

    @Test
    fun `избранное преподавателя хранит имя а избранное аудитории название`() {
        val s = src("PickersScreen.kt")
        assertTrue("Преподаватель пишется в избранное не по имени", s.contains("toggleFavFor(Role.TEACHER, t.name)"))
        assertTrue("Аудитория пишется в избранное не по названию", s.contains("toggleFavFor(Role.AUDIENCE, n)"))
    }

    @Test
    fun `открытие избранного не сбрасывает выбор роли`() {
        // onOpen должен вызывать saveRole(r) ДО saveFor(r, ...): saveFor берёт
        // роль параметром, а save() — из хранилища. Проверяем, что порядок
        // в коде не нарушен, иначе после перезапуска откроется чужая роль.
        val s = src("MainActivity.kt")
        val onOpen = s.substringAfter("fun openFromMenu(").substringBefore("\n    }")
        val saveRoleAt = onOpen.indexOf("prefs.saveRole(r)")
        val saveForAt = onOpen.indexOf("prefs.saveFor(r")
        assertTrue("Нет saveRole(r)", saveRoleAt >= 0)
        assertTrue("Нет saveFor(r, ...)", saveForAt >= 0)
        assertTrue(
            "saveRole должен идти раньше saveFor",
            saveRoleAt < saveForAt,
        )
    }

    @Test
    fun `параметр открытия вкладки избранного только у главной`() {
        // onOpenFavorites живёт только у HomeScreen (вкладка нижней
        // навигации). На остальных экранах его быть не должно: избранное
        // открывается с главной, а не из шапки другого экрана.
        val screensWithout = listOf(
            "AboutScreen.kt", "GroupPickerUi.kt",
            "PickersScreen.kt", "ScheduleUi.kt", "SettingsScreen.kt",
        )
        for (name in screensWithout) {
            val s = src(name)
            assertTrue(
                "В $name остался onOpenFavorites — параметра там быть не должно",
                !s.contains("onOpenFavorites"),
            )
        }
        // У главной параметр обязан быть — иначе вкладка не откроется.
        assertTrue(
            "У HomeScreen должен быть параметр открытия вкладки избранного",
            src("HomeScreen.kt").contains("onOpenFavorites"),
        )
    }

    @Test
    fun `каждый переход в подменный экран запоминает происхождение`() {
        // Прямой `screen = Screen.ABOUT` забыл бы метку, и «Назад» ушёл бы по
        // правилу экрана (по наличию сохранённой группы), а не по факту
        // входа. Именно это ломало переход из главного меню.
        val s = stripComments(src("MainActivity.kt"))
        val direct = Regex("""screen\s*=\s*Screen\.(ABOUT|SETTINGS|FAVORITES|REPORT)""")
            .findAll(s)
            .map { it.value }
            .toList()
        assertTrue(
            "Прямые переходы в подменные экраны обходят goTo и теряют " +
                "происхождение: $direct",
            direct.isEmpty(),
        )
    }

    @Test
    fun `стрелка назад в расписании идёт через общий переход`() {
        // Регрессия, найденная на эмуляторе: backTargetFor был исправлен, но
        // стрелка «‹» в шапке расписания шла напрямую в PICK_ENTITY в обход
        // него. Системная кнопка «Назад» возвращала куда надо, а стрелка —
        // нет, то есть два «назад» на одном экране вели себя по-разному.
        val s = src("MainActivity.kt")
        assertTrue(
            "Стрелка «‹» идёт мимо backTargetFor",
            s.contains("screen = backTargetFor(Screen.SCHEDULE, selection != null, screenOrigin)"),
        )
    }

    // ── Холодный старт ────────────────────────────────────────────────────

    @Test
    fun `при старте восстанавливается код аудитории`() {
        // Регрессия, найденная на 0.38 на эмуляторе: после force-stop
        // аудитория открывалась с «Не удалось определить аудиторию», хотя
        // через избранное работала. Причина: selection возвращается из
        // prefs, а teacherCode и roomNameArg инициализируются пустыми, и
        // ScheduleData делает teacherCode.toIntOrNull() -> null.
        val s = src("MainActivity.kt")
        val start = s.substringAfter("val startGroup = selection")
        val effect = start.substringBefore("\n    }")
        assertTrue(
            "Нет восстановления ключа роли при старте",
            effect.contains("LaunchedEffect(startGroup, role)"),
        )
        assertTrue(
            "Аудитория не восстанавливается: нет поиска кода по имени",
            effect.contains("api.audienceCodeByName(startGroup)"),
        )
        assertTrue(
            "Аудитория не восстанавливается: не проставляется имя комнаты",
            effect.contains("roomNameArg = startGroup"),
        )
        assertTrue(
            "Преподавателю не восстанавливается ключ ФИО",
            effect.contains("TeacherIndex.key(startGroup)"),
        )
    }

    @Test
    fun `восстановление при старте не мутирует состояние во время композиции`() {
        // teacherCode/roomNameArg — rememberSaveable. Их изменение прямо в
        // теле composable даёт нестабильное состояние и повторный вход в
        // эффекты, поэтому запись обязана быть в LaunchedEffect.
        val s = src("MainActivity.kt")
        val after = s.substringAfter("val startGroup = selection")
        assertTrue(
            "Состояние мутируется в теле composable, а не в эффекте",
            after.substringBefore("LaunchedEffect(startGroup, role)").split("teacherCode =").size == 1,
        )
    }

    @Test
    fun `студенту не достаётся код аудитории`() {
        // Проверка на смену роли: если роль — студент, ключи должны быть
        // пустыми, иначе расписание ищется по чужой комнате.
        val s = src("MainActivity.kt")
        val effect = s.substringAfter("LaunchedEffect(startGroup, role)")
            .substringBefore("\n        }\n    }")
        assertTrue("У студента ключи не очищаются", effect.contains("Role.STUDENT"))
    }

    @Test
    fun `аудитория из избранного ищется в обоих источниках`() {
        // Регрессия, найденная через отчёт об ошибке из приложения.
        // Локальный список аудиторий собирался только на экране выбора и
        // не сохранялся, поэтому при запуске и при открытии из избранного
        // поиск кода смотрел лишь в /audiences — там 136 аудиторий из 194,
        // и корпус 8 (8102, 8109, 8307) не находился вовсе.
        val api = src("MietApi.kt")
        assertTrue(
            "Нет чтения локального списка аудиторий",
            api.contains("fun localAudiences()"),
        )
        assertTrue(
            "Нет сохранения локального списка",
            api.contains("fun saveLocalAudiences("),
        )
        assertTrue(
            "Локальный список аудиторий нигде не сохраняется",
            src("PickersScreen.kt").contains("saveLocalAudiences"),
        )
    }
}
