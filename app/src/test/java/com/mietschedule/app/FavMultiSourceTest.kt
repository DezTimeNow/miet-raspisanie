package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Блок «сейчас и дальше» в главном меню при нескольких избранных.
 *
 * Жалоба владельца: в избранном отмечены группа и преподаватель, а блок
 * показывает информацию только от группы — от преподавателя ничего.
 *
 * Причина была не в том, что преподаватель не поддерживается. Пары всех
 * избранных складывались в один общий список, а `currentAndNext` искала по
 * нему самую раннюю пару — одну на всё избранное. Чья пара начиналась
 * раньше, тот и занимал обе строки, а второе избранное молча исчезало.
 */
class FavMultiSourceTest {

    private val mainDir = File("src/main/java/com/mietschedule/app")

    private fun src(name: String): String = File(mainDir, name).readText()

    /** Исходник без комментариев: пояснения цитируют старый сломанный код. */
    private fun stripComments(source: String): String =
        source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `каждое избранное обрабатывается отдельно а не общим списком`() {
        val s = src("NextLessonCard.kt")
        // Больше нет одного общего списка пар на всё избранное: именно он
        // и схлопывал блок до одной пары.
        assertTrue(
            "Старый общий FavLessonData(lessons, times) должен быть заменён: " +
                "пары всех избранных складывались в один список, и блок " +
                "показывал только самую раннюю пару",
            !s.contains("data class FavLessonData"),
        )
        assertTrue(
            "Нужен тип с привязкой пар к конкретному избранному",
            Regex("""data class FavLessons\s*\(\s*val entry: FavEntry""").containsMatchIn(stripComments(s)),
        )
    }

    @Test
    fun `блок показывает строки по каждому избранному`() {
        val code = stripComments(src("NextLessonCard.kt"))
        // Разбор идёт по каждому элементу списка избранного: список пар дня
        // собирается внутри map по разным группам.
        assertTrue(
            "Нужен map/mapNotNull по избранному, а не один разбор на весь список",
            Regex("""groups\.mapNotNull\s*\{""").containsMatchIn(code),
        )
        assertTrue(
            "пары дня должны считаться по парам конкретного избранного",
            Regex("""dayLessons\(g\.lessons, g\.times, now""").containsMatchIn(code),
        )
    }

    @Test
    fun `избранное без пар не попадает в блок`() {
        val code = stripComments(src("NextLessonCard.kt"))
        // С блоком всего дня правило стало шире: избранное показывается, если
        // у него есть пары сегодня или ближайший день с занятиями. Если нет ни
        // того, ни другого — строки нет, иначе в блоке висела бы пустая группа.
        assertTrue(
            "Избранное без пар сегодня и без следующего дня с занятиями " +
                "обязано отбрасываться",
            Regex("""nextDayWithLessons\(g, now\) \?: return@mapNotNull null""")
                .containsMatchIn(code),
        )
    }

    @Test
    fun `у каждой группы строк есть подпись кому она принадлежит`() {
        val code = stripComments(src("NextLessonCard.kt"))
        assertTrue(
            "Нужен заголовок группы строк",
            code.contains("favTitle(row.entry)"),
        )
        assertTrue(
            "Нужна функция подписи",
            Regex("""fun favTitle\(entry: FavEntry\)""").containsMatchIn(code),
        )
        // Подпись обязана различать роли, иначе «ауд. 1201» и группа не
        // отличить, а у преподавателя имя и так понятно.
        assertTrue(
            "Подпись аудитории должна помечаться как аудитория",
            Regex("""Role\.AUDIENCE\s*->\s*"ауд\.""").containsMatchIn(code),
        )
    }

    @Test
    fun `группы строк разделены разделителем`() {
        val code = stripComments(src("NextLessonCard.kt"))
        assertTrue(
            "Между группами нужен разделитель, иначе строки сливаются в ленту",
            code.contains("HorizontalDivider"),
        )
    }

    @Test
    fun `список избранных не схлопывается и разбор идёт по каждому`() {
        val code = stripComments(src("NextLessonCard.kt"))
        // Загрузка: цикл по favs, а не один разбор всего списка.
        assertTrue(
            "Нужен цикл по избранному при сборе пар",
            Regex("""for \(fav in favs\.take\(limit\)\)""").containsMatchIn(code),
        )
        assertTrue(
            "Каждое избранное должно давать свою запись с привязкой к себе",
            Regex("""FavLessons\(entry = fav""").containsMatchIn(code),
        )
        // Прежняя проверка «первая подходящая» больше не нужна: у строки
        // теперь есть свой источник, к которому она и ведёт.
        assertTrue(
            "Результат — список, а не единственная запись",
            Regex("""\): List<FavLessons>""").containsMatchIn(code),
        )
    }

    @Test
    fun `строка открывает свой источник а не подобранный`() {
        val code = stripComments(src("NextLessonCard.kt"))
        // Раньше openFav подбирал, кому принадлежит пара: сначала по названию
        // группы, затем по аудитории, затем по фамилии, а если ничего не
        // совпало — брал первое избранное. При двух избранных строка
        // преподавателя могла открыть расписание группы, и это выглядело бы
        // как ошибка. Теперь у строки есть свой источник, и открывается он.
        assertTrue(
            "Подбор источника по совпадению полей должен быть удалён: у " +
                "строки теперь свой источник, а подбор вёл не туда",
            !code.contains("fun openFav"),
        )
        assertTrue(
            "Строка должна открывать своё избранное напрямую",
            Regex("""onOpen\(row\.entry\.role, row\.entry\.value\)""").containsMatchIn(code),
        )
    }
}