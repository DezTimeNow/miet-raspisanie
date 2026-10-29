package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Проверки навигации и состояния.
 *
 * Логика переходов вынесена в чистые функции `startScreenFor` и
 * `backTargetFor`, поэтому проверяется напрямую, без запуска Activity.
 * Именно эта логика ломалась трижды: старт открывал список групп вместо
 * расписания, «Избранное» без выбранной группы вело в `SCHEDULE` и падало
 * на `selection!!`, а правило переходов было продублировано в трёх местах
 * и разошлось между ними.
 */
class NavigationAndStateTest {

    // ── Стартовый экран ─────────────────────────────────────────────────

    @Test
    fun `обычный запуск всегда открывает главную`() {
        // Требование владельца от 0.65: приложение обязано открываться на
        // главной странице всегда, в том числе при сохранённой группе — там
        // список избранного, ради которого его и открывают.
        assertEquals(Screen.HOME, startScreenFor(fromNotification = false))
    }

    @Test
    fun `переход по напоминанию открывает расписание`() {
        // Единственное исключение и оно не про запуск: в уведомлении названа
        // пара, и тап по нему обязан открыть именно это расписание.
        assertEquals(Screen.SCHEDULE, startScreenFor(fromNotification = true))
    }

    @Test
    fun `сохранённый выбор на стартовый экран не влияет`() {
        // Правило не должно снова начать смотреть на prefs.load(): именно это
        // скрывало главную при каждом запуске. Параметра сохранённого выбора
        // в функции больше нет, поэтому проверяем сам вызов.
        val main = File("src/main/java/com/mietschedule/app/MainActivity.kt").readText()
        assertTrue(
            "Стартовый экран снова зависит от сохранённого выбора",
            main.contains("startScreenFor(requestedGroup != null)"),
        )
        assertTrue(
            "В startScreenFor вернулся параметр сохранённого выбора",
            !main.contains("startScreenFor(prefs.load()"),
        )
    }

    // ── Системная кнопка «Назад» ────────────────────────────────────────

    @Test
    fun `с выбора группы назад ведёт на главную`() {
        assertEquals(Screen.HOME, backTargetFor(Screen.PICK_ENTITY, hasSelection = true))
    }

    @Test
    fun `с расписания назад ведёт на выбор сущности`() {
        assertEquals(Screen.PICK_ENTITY, backTargetFor(Screen.SCHEDULE, hasSelection = true))
    }

    @Test
    fun `с расписания без выбора назад ведёт на главную`() {
        assertEquals(Screen.HOME, backTargetFor(Screen.SCHEDULE, hasSelection = false))
    }

    @Test
    fun `свое расписание возвращает назад на выбор сущности а не в избранное`() {
        // Происхождение важно: то же расписание, открытое НЕ из избранного,
        // обязано вести назад по-старому — в выбор группы или аудитории.
        assertEquals(
            Screen.PICK_ENTITY,
            backTargetFor(Screen.SCHEDULE, hasSelection = true, origin = Screen.PICK_ENTITY),
        )
    }

    @Test
    fun `расписание открытое из блока избранного возвращает назад на главную`() {
        // Блок «сейчас и дальше» стоит на главной, и назад с открытого по
        // нему расписания обязан вести на главную. Проверено на эмуляторе:
        // раньше уводило в список групп, потому что стрелка «‹» шла напрямую
        // в PICK_ENTITY в обход backTargetFor.
        assertEquals(
            Screen.HOME,
            backTargetFor(Screen.SCHEDULE, hasSelection = true, origin = Screen.HOME),
        )
    }

    @Test
    fun `происхождение не мешает обычному переходу`() {
        // origin == SCHEDULE — вырожденный случай (расписание открыло расписание).
        // Такого не бывает, но функция не должна уводить на сам экран: иначе
        // назад зациклился бы на месте.
        assertEquals(
            Screen.PICK_ENTITY,
            backTargetFor(Screen.SCHEDULE, hasSelection = true, origin = Screen.SCHEDULE),
        )
    }

    @Test
    fun `из программы открытой из меню назад ведёт в меню`() {
        // Жалоба владельца: из «О программе», открытого из ГЛАВНОГО МЕНЮ,
        // назад уводил в список групп. Решение принималось по наличию
        // сохранённого выбора, а не по источнику, из-за чего «О программе»
        // навигационно приросло к подменю «студент».
        assertEquals(
            Screen.HOME,
            backTargetFor(Screen.ABOUT, hasSelection = true, origin = Screen.HOME),
        )
    }

    @Test
    fun `из программы открытой из списка сущностей назад ведёт в список`() {
        // Тот же экран, но другой вход: источник известен, значит и возврат
        // другой. Раньше оба входа давали одинаковый ответ по hasSelection.
        assertEquals(
            Screen.PICK_ENTITY,
            backTargetFor(Screen.ABOUT, hasSelection = true, origin = Screen.PICK_ENTITY),
        )
    }

    @Test
    fun `назад ведёт туда откуда пришли для всех подменных экранов`() {
        // Правило единое, а не частное для расписания: любой экран, у которого
        // есть входы из нескольких мест, возвращает туда, откуда вошли.
        for (screen in listOf(
            Screen.ABOUT, Screen.SETTINGS,
        )) {
            for (origin in listOf(Screen.HOME, Screen.PICK_ROLE, Screen.PICK_ENTITY)) {
                if (origin == screen) continue
                assertEquals(
                    "экран $screen из $origin вернул не туда",
                    origin,
                    backTargetFor(screen, hasSelection = true, origin = origin),
                )
            }
        }
    }

    @Test
    fun `без источника работает правило экрана`() {
        // Источник неизвестен только при холодном старте или если экран открыт
        // не через goTo. Тогда действует таблица.
        //
        // С 0.62 любое подменю возвращает на ГЛАВНУЮ, а не в список сущностей.
        // Раньше «О программе» при сохранённой группе возвращало в PICK_ENTITY
        // — то есть подменю навигационно прирастало к выбору группы, и
        // «Назад» уводило туда, куда человек не заходил. Правило «домой»
        // сделало главную единственным возвратом.
        assertEquals(Screen.HOME, backTargetFor(Screen.ABOUT, hasSelection = false))
        assertEquals(Screen.HOME, backTargetFor(Screen.ABOUT, hasSelection = true))
        assertEquals(Screen.HOME, backTargetFor(Screen.SETTINGS, hasSelection = true))
    }

    @Test
    fun `источник равный экрану не ведёт в себя`() {
        // Расписание открыло расписание (в базе не встречается) и подобное.
        // Без проверки назад зациклился бы на месте.
        assertEquals(
            Screen.PICK_ENTITY,
            backTargetFor(Screen.SCHEDULE, hasSelection = true, origin = Screen.SCHEDULE),
        )
        assertEquals(
            Screen.HOME,
            backTargetFor(Screen.ABOUT, hasSelection = false, origin = Screen.ABOUT),
        )
    }

    @Test
    fun `с выбора роли назад ведёт на главную`() {
        // На главной BackHandler выключен («Назад» = выход), но с выбора роли
        // возвращаться есть куда — на главную, откуда пришли.
        // Выбор роли — подменю главной, из него возвращаются на главную.
        assertEquals(Screen.HOME, backTargetFor(Screen.PICK_ROLE, hasSelection = true))
        assertEquals(Screen.HOME, backTargetFor(Screen.PICK_ROLE, hasSelection = false))
    }

    @Test
    fun `из расписания назад ведёт на выбор сущности а не обратно в расписание`() {
        // Возврат с экрана расписания в SCHEDULE без смены выбора был бы
        // возвратом в никуда; с выбранной сущностью ведём на её выбор.
        assertEquals(Screen.PICK_ENTITY, backTargetFor(Screen.SCHEDULE, hasSelection = true))
    }

    @Test
    fun `ни один экран не ведёт назад сам в себя кроме главной`() {
        // Проверка на «зацикливание»: BackHandler на главной выключен,
        // остальные экраны обязаны уводить в другой экран.
        for (screen in Screen.entries) {
            val target = backTargetFor(screen, hasSelection = true)
            if (screen == Screen.HOME) {
                assertEquals(Screen.HOME, target)
            } else {
                assertTrue("экран $screen ведёт сам в себя", target != screen)
            }
        }
    }

    @Test
    fun `ни один экран без выбора не ведёт на расписание`() {
        // Пустой выбор означает, что показывать нечего: SCHEDULE без
        // сущности и был причиной падения на `selection!!`.
        for (screen in Screen.entries) {
            val target = backTargetFor(screen, hasSelection = false)
            assertTrue(
                "экран $screen без выбора вёл на расписание",
                target != Screen.SCHEDULE,
            )
        }
    }

    // ── Шкала дней недели ───────────────────────────────────────────────

    @Test
    fun `все семь дней Calendar дают свою позицию без сдвига`() {
        // Calendar: SUNDAY=1, MONDAY=2 … SATURDAY=7. Наша шкала: 0=Пн…6=Вс.
        val expected = mapOf(1 to 6, 2 to 0, 3 to 1, 4 to 2, 5 to 3, 6 to 4, 7 to 5)
        for ((calendar, our) in expected) {
            assertEquals("день Calendar=$calendar", our, dayIndexFromCalendar(calendar))
        }
    }

    @Test
    fun `дни недели не повторяются и покрывают всю шкалу`() {
        val seen = (1..7).map { dayIndexFromCalendar(it) }
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6), seen.toSet())
    }

    @Test
    fun `пятница это четвёртый день а не суббота`() {
        // Сдвиг на единицу называл пятницу «Сб» и подсвечивал чужие карточки.
        assertEquals(4, dayIndexFromCalendar(6))
        assertEquals("Пт", DAY_SHORT[dayIndexFromCalendar(6)])
    }

    // ── Роль: ключ и восстановление ─────────────────────────────────────

    @Test
    fun `роль восстанавливается по своему ключу`() {
        for (role in listOf(Role.STUDENT, Role.TEACHER, Role.AUDIENCE)) {
            assertEquals(role, Role.fromKey(role.key))
        }
    }

    @Test
    fun `неизвестный или пустой ключ роли даёт студента`() {
        // Ключ мог испортиться: старый формат prefs, ручная правка файла.
        assertEquals(Role.STUDENT, Role.fromKey(null))
        assertEquals(Role.STUDENT, Role.fromKey(""))
        assertEquals(Role.STUDENT, Role.fromKey("что-то"))
    }
}