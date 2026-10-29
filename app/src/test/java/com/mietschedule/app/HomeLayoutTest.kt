package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Структура главной страницы после 0.62.
 *
 * Требование владельца, дословно: «пользователь видит избранное, затем
 * кнопки выбрать роли и Избранное, затем идёт нижнее подменю», плюс
 * отдельные пункты: роли — в отдельный экран, в шапке нет подписи
 * «Кто ты?», «Меню» заменён домиком, кнопка «Избранное» одна.
 *
 * Проверки текстовые, а не запуском Activity: компонуемый проверить на
 * устройстве, а правило «что за экран и что на нём должно быть» — здесь.
 */
class HomeLayoutTest {

    private val mainDir = File("src/main/java/com/mietschedule/app")

    private fun src(name: String): String =
        File(mainDir, name).readText()

    /** Комментарии содержат старый код как объяснение правки — их надо снять. */
    private fun code(name: String): String =
        src(name)
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    // ── 1. Роли вынесены в отдельный экран ─────────────────────────────────

    /**
     * Три карточки ролей больше не занимают первый экран.
     *
     * Замер на устройстве до правки: карточки «Студент / Преподаватель /
     * Аудитория» занимали около 330 px при трёх избранных, и избранное
     * уходило за нижний край.
     */
    @Test
    fun `на главной нет карточек ролей но есть карточка добавления`() {
        val home = code("HomeScreen.kt")
        // Функции карточек определены в этом же файле. Проверять надо
        // ВЫЗОВЫ: на главной карточек ролей быть не должно, а объявление
        // функции само по себе ничего не рисует.
        assertFalse(
            "Карточки ролей должны быть только на экране добавления",
            home.substringBefore("fun AddPickerScreen(").contains("AddPickerCard("),
        )
        assertTrue(
            "Главная должна вести на экран добавления карточкой",
            home.contains("onClick = onAdd"),
        )
    }

    /**
     * Две карточки в том же виде, что и прежние карточки ролей.
     *
     * Требование владельца от 0.64: «в предыдущих билдах были красивые
     * кнопки студент/преподаватель/аудитория, на главной должны быть такие
     * же две красивые кнопки "выбрать роль" и "избранное"». В 0.63 обе
     * строки были простым текстом, и оформление на главной пропало.
     */
    /**
     * Список избранного кнопками и единственная кнопка добавления.
     *
     * Требование владельца от 0.65: «кнопку избранное и меню избранное убираем
     * с главной, вместо неё делаем на главной список Избранное и дальше кнопки
     * для быстрого доступа на расписание». Добавление вызывается кнопкой
     * «Добавить» — решение владельца по варианту «а».
     */
    @Test
    fun `на главной список избранного и кнопка добавления`() {
        val home = code("HomeScreen.kt")
        val body = home.substringBefore("fun AddPickerScreen(")
        assertTrue("Нет заголовка списка избранного", body.contains("FAV_LIST_TITLE"))
        assertTrue("Нет кнопки на каждое избранное", body.contains("FavEntryCard(entry)"))
        assertTrue(
            "Нет кнопки «Добавить»",
            Regex("""HomeActionCard\([\s\S]{0,200}?"Добавить"""").containsMatchIn(body),
        )
        // Вид: иконка, название и стрелка — как у прежних карточек ролей.
        assertTrue(
            "Карточка должна рисовать иконку",
            body.contains("Icon(icon, contentDescription = null, tint = MIET_BLUE"),
        )
        assertTrue(
            "Карточка должна рисовать стрелку",
            body.contains("""\u203a"""),
        )
    }

    /**
     * Пояснений под названием нет — владелец просил только иконку и название.
     */
    @Test
    fun `на карточках главной только иконка и название`() {
        val home = code("HomeScreen.kt")
        val fn = home.substringAfter("private fun HomeActionCard(")
            .substringBefore("\n}\n")
        // Второй Text внутри карточки — только стрелка. Пояснительной
        // строки (12.sp, приглушённый цвет) быть не должно.
        assertFalse(
            "На карточках главной не должно быть пояснений под названием",
            fn.contains("12.sp"),
        )
        assertFalse(
            "Приглушённый цвет для пояснения не используется",
            Regex("""HomeActionCard[\s\S]{0,600}?LocalAppColors\.current\.muted""")
                .containsMatchIn(home.substringBefore("fun AddPickerScreen(")),
        )
    }

    @Test
    fun `экран добавления содержит все три роли и не отмечает выбор`() {
        val home = src("HomeScreen.kt")
        assertTrue("Нужен отдельный экран добавления", home.contains("fun AddPickerScreen("))
        // Роли перечисляются через enum, а не тремя вызовами подряд: иначе
        // при добавлении четвёртой роли её забыли бы внести.
        assertTrue(
            "Список ролей должен перечисляться через Role.entries",
            Regex("""Role\.entries\.forEach""").containsMatchIn(home),
        )
        // Требование владельца от 0.65: отметки выбранной роли на этом экране
        // нет — это меню выбора, а не показ состояния.
        val picker = home.substringAfter("fun AddPickerScreen(")
        assertFalse(
            "На экране добавления осталась отметка выбранной роли",
            picker.contains("selected"),
        )
    }

    // ── 2. Подпись «Кто ты?» убрана ────────────────────────────────────────

    @Test
    fun `в шапке главной нет подписи Кто ты`() {
        val main = code("MainActivity.kt")
        // Раньше главная отдавала subtitle = "Кто ты?". Подпись обещала, что
        // экран про выбор роли, а он про избранное — и роль на нём не
        // определяется, а выбирается кнопкой ниже.
        assertFalse(
            "Подпись «Кто ты?» должна быть убрана из главного меню",
            main.contains("\"Кто ты?\""),
        )
    }

    // ── 3. Домик вместо «Меню» ─────────────────────────────────────────────

    @Test
    fun `в шапке нет текстовой кнопки Меню`() {
        val bar = code("MietTopBar.kt")
        assertFalse(
            "Текстовая кнопка «Меню» должна быть заменена иконкой дома",
            bar.contains("Text(\"Меню\""),
        )
        // Домика в шапке тоже нет: переход на главную — в нижнем меню.
        assertFalse("В шапке не должно быть иконки дома", bar.contains("Icons.Filled.Home"))
        assertTrue("В шапке должна быть кнопка обновить", bar.contains("Icons.Filled.Refresh"))
    }

    // ── 4. Порядок на экране ───────────────────────────────────────────────

    /**
     * Избранное → выбор роли → подменю. Порядок задан требованием.
     */
    @Test
    fun `порядок на главной блок список добавление`() {
        val home = code("HomeScreen.kt")
        // Индексы считаем от начала самой функции: константы объявлены выше и
        // иначе нашли бы объявление, а не место отрисовки.
        val body = home.substringAfter("fun HomeScreen(")
        val blockIdx = body.indexOf("NextLessonCard(")
        val listIdx = body.indexOf("FAV_LIST_TITLE")
        val addIdx = body.indexOf("onClick = onAdd")
        assertTrue("Не найден блок «сейчас и дальше»", blockIdx > 0)
        assertTrue("Не найден список избранного", listIdx > 0)
        assertTrue("Не найдена кнопка добавления", addIdx > 0)
        assertTrue(
            "Порядок должен быть: блок, список, добавление. " +
                "На деле: блок=$blockIdx, список=$listIdx, добавление=$addIdx",
            blockIdx < listIdx && listIdx < addIdx,
        )
    }

    /**
     * Нижняя навигация: три вкладки — Главная, Избранное, Настройки.
     *
     * Прежние текстовые кнопки подменю заменены стандартным NavigationBar.
     * «О программе» — справка, а не раздел, поэтому остаётся отдельной
     * кнопкой в «Настройках», а не вкладкой.
     */
    @Test
    fun `нижняя навигация с тремя вкладками на всех экранах`() {
        // Меню общее на всех экранах: живёт в AppRoot (Scaffold+NavigationBar),
        // а не в HomeScreen. Проверяем MainActivity.
        val root = code("MainActivity.kt")
        assertTrue(
            "Должен быть NavigationBar — стандартная нижняя навигация Android",
            root.contains("NavigationBar("),
        )
        // Три вкладки: Главная, Избранное, Настройки.
        val navCount = Regex("""NavigationBarItem\(""").findAll(root).count()
        assertEquals("Вкладок должно быть три", 3, navCount)
        assertTrue("Нет вкладки «Настройки»", root.contains("""Text("Настройки")"""))
        assertTrue("Нет вкладки «Избранное»", root.contains("""Text("Избранное")"""))
        assertTrue("Нет вкладки «Главная»", root.contains("""Text("Главная")"""))
        // Вкладки переключают экран через goTo() — ядро навигации не задето.
        assertTrue(
            "Вкладка главной должна вести на главный экран",
            root.contains("goTo(Screen.HOME)"),
        )
        assertTrue(
            "Вкладка избранного должна вести на экран избранного",
            root.contains("goTo(Screen.FAVORITES)"),
        )
        assertTrue(
            "Вкладка настроек должна вести на экран настроек",
            root.contains("goTo(Screen.SETTINGS)"),
        )
        // В HomeScreen нижнего меню больше нет — только шапка и контент.
        assertFalse(
            "NavigationBar не должен дублироваться в HomeScreen",
            code("HomeScreen.kt").contains("NavigationBar("),
        )
    }

    /**
     * «О программе» остаётся кнопкой, а не вкладкой: это справка, а не
     * раздел. Четыре вкладки в BottomNav выглядят как лишняя.
     */
    @Test
    fun `о программе переехала в настройки`() {
        val home = code("HomeScreen.kt")
        val settings = code("SettingsScreen.kt")
        // «О программе» больше не на главной: она в настройках, раздел
        // «О приложении». Справка о версии и обновлениях — настройки.
        assertFalse(
            "«О программе» не должна оставаться на главной",
            home.contains("onClick = onAbout"),
        )
        // В настройках — ссылка на экран «О программе».
        assertTrue(
            "В настройках должна быть ссылка на «О программе»",
            settings.contains("onAbout()"),
        )
        // Заголовок раздела «О приложении» убран: кнопка «О программе» с
        // подписью говорит сама за себя.
        assertFalse(
            "Заголовок «О приложении» должен быть убран — кнопка самодостаточна",
            settings.contains("SectionTitle(\"О приложении\")"),
        )
    }

    /**
     * Идущая пара в блоке залита тем же цветом, что и на расписании.
     *
     * Требование владельца от 0.67: «можно как-то выделять в блоке ближайшие
     * пары идущую сейчас пару цветом?» — выбран вариант с заливкой. Цвет не
     * новый: им уже помечена идущая пара на экране расписания, поэтому одно
     * значение в двух местах означает для пользователя одно и то же.
     */
    @Test
    fun `идущая пара в блоке залита цветом текущей`() {
        val line = code("NextLessonCard.kt").substringAfter("private fun FavLessonLine(")
        assertTrue(
            "Строка идущей пары не залита цветом currentGroup",
            line.contains("Modifier.background(LocalAppColors.current.currentGroup)"),
        )
        // И на расписании цвет обязан остаться тем же: иначе правило
        // разъедется и «сейчас» будет выглядеть в двух местах по-разному.
        assertTrue(
            "На расписании идущая пара больше не подсвечивается currentGroup",
            Regex("""if \(going\)[\s\S]{0,160}?LocalAppColors\.current\.currentGroup""")
                .containsMatchIn(code("NextLessonRow.kt")),
        )
    }

    // ── 5. Подменю: три кнопки, «Избранное» не в нём ──────────────────────

    // ── 6. Навигация ───────────────────────────────────────────────────────

    /**
     * Главная — точка входа, из неё возвращаться некуда.
     */
    @Test
    fun `с главной назад ведёт в себя а не в подменю`() {
        assertEquals(Screen.HOME, backTargetFor(Screen.HOME, hasSelection = false))
        assertEquals(Screen.HOME, backTargetFor(Screen.HOME, hasSelection = true))
    }

    @Test
    fun `выбор роли это подменю главной`() {
        assertEquals(Screen.HOME, backTargetFor(Screen.PICK_ROLE, hasSelection = false))
        assertEquals(Screen.HOME, backTargetFor(Screen.PICK_ROLE, hasSelection = true))
    }

    /**
     * Переход на выбор роли идёт через goTo, а не прямым присваиванием.
     *
     * Прямое `screen = ...` не записывает метку источника, и «Назад» ушёл бы
     * по правилу экрана вместо фактического входа. Такая ошибка уже была в
     * 0.60 со стрелкой «‹».
     */
    @Test
    fun `переход к выбору роли идёт через goTo`() {
        val main = code("MainActivity.kt")
        assertTrue(
            "Переход на выбор роли должен идти через goTo, а не напрямую",
            main.contains("goTo(Screen.PICK_ROLE)"),
        )
        assertFalse(
            "Прямое присваивание screen = Screen.PICK_ROLE обходит метку источника",
            main.contains("screen = Screen.PICK_ROLE"),
        )
    }

    /**
     * Домик ведёт на главную со всех подменю.
     */
    @Test
    fun `домик ведёт на главную`() {
        val main = code("MainActivity.kt")
        // Экран выбора роли — единственный, где домик обязателен: из него
        // «Назад» уводит на главную, и без домика возврат был бы только
        // системной кнопкой.
        assertTrue(
            "Экран выбора роли должен передавать возврат на главную",
            main.contains("onHome = { goTo(Screen.HOME) }"),
        )
    }
    /**
     * Домик ведёт на главную из каждого экрана.
     *
     * Дефект, найденный при проверке на устройстве: на экране выбора группы
     * домик был подключён к тому же действию, что и стрелка «‹», то есть вёл
     * на выбор роли. Через код это не видно — обе кнопки рисуются одной
     * шапкой, и разница только в том, куда ведёт параметр.
     */
    @Test
    fun `в шапке нет экрана где домик ведёт не на главную`() {
        val screens = mainDir.listFiles().orEmpty().filter { it.name.endsWith(".kt") }
        val bad = mutableListOf<String>()

        for (f in screens) {
            val src = code(f.name)
            // Домик — это onChangeRole у общей шапки. Смотрим только вызовы
            // с лямбдой: там адрес виден прямо. Проброс параметра
            // (`onChangeRole = onChangeRole`) проверять тут нечего — его
            // значение задаёт вызывающий экран, и это отдельная проверка.
            // Пустая лямбда — это пустой слот домика на самой главной, где
            // кнопке некуда вести. Такой слот объявляется отдельным
            // параметром, и без него он был бы не отличим от забытой кнопки.
            val homeSlotEmpty = src.contains("homeSlotEmpty = true")
            for (m in Regex("""onChangeRole\s*=\s*(\{[^}]*\})""").findAll(src)) {
                val target = m.groupValues[1]
                if (target == "{}" && homeSlotEmpty) continue
                if (!target.contains("HOME")) {
                    bad += "${f.name}: onChangeRole = $target"
                }
            }
        }
        assertTrue(
            "Домик в шапке должен вести на главную. Неверные вызовы: $bad",
            bad.isEmpty(),
        )

        // Вызывающая сторона обязана передать именно возврат домой.
        val main = code("MainActivity.kt")
        assertTrue(
            "MainActivity должен передавать экрану групп возврат на главную",
            main.contains("onHome = { goTo(Screen.HOME) }"),
        )
        assertFalse(
            "Домик не должен вести на выбор роли",
            main.contains("onHome = { goTo(Screen.PICK_ROLE) }"),
        )
    }

    /**
     * Экран выбора группы различает «назад» и «на главную».
     */
    @Test
    fun `на экране групп стрелка назад и домик разведены`() {
        val ui = code("GroupPickerUi.kt")
        assertTrue(
            "У экрана групп должен быть отдельный параметр возврата домой",
            ui.contains("onHome: () -> Unit = {}"),
        )
        assertTrue(
            "Домик должен звать onHome",
            Regex("""onChangeRole\s*=\s*onHome""").containsMatchIn(ui),
        )
        assertTrue(
            "Стрелка должна звать onBack, а не то же действие, что домик",
            Regex("""onBack\s*=\s*onBack""").containsMatchIn(ui),
        )
        assertFalse(
            "Стрелка не должна звать действие домика",
            Regex("""onBack\s*=\s*onHome""").containsMatchIn(ui),
        )
    }

}
