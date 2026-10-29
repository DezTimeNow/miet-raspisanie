package com.mietschedule.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Смена избранного обязана пересчитывать напоминания в том экране, где
 * звезду нажали.
 *
 * Напоминания строятся по списку избранного из хранилища, а не по тому,
 * какой экран открыт. Поэтому каждый экран, умеющий менять избранное,
 * обязан сам инициировать пересчёт: иначе будильники висят до фоновой
 * задачи (до 6 часов), и группа, снятая с избранного, продолжает
 * присылать пуши.
 *
 * Проверяем разобранный исходник, а не экран: экран требует Android,
 * а суть дефекта — в том, вызывает ли обработчик пересчёт.
 */
class FavChangeRescheduleTest {

    private val mainDir = File("src/main/java/com/mietschedule/app")

    private fun src(name: String): String = File(mainDir, name).readText()

    /** Имена всех файлов экранов. */
    private val screens = listOf(
        "ScheduleUi.kt", "GroupPickerUi.kt", "HomeScreen.kt", "PickersScreen.kt"
    )

    /**
     * Убирает комментарии: иначе поиск ловит их текст, а в коде полно
     * пояснений именно про этот пересчёт.
     */
    private fun String.withoutComments(): String =
        replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `каждый экран со звездой пересчитывает напоминания`() {
        val offenders = mutableListOf<String>()
        for (file in screens) {
            val code = src(file).withoutComments()
            // Тело приватного хелпера пропускаем: пересчёт стоит у
            // обработчика, который этот хелпер вызывает, а не внутри него.
            val helper = helperRange(file, code)
            val toggles = Regex("""toggleFav(For)?\s*\(""")
                .findAll(code)
                .filterNot { m -> m.range.first in helper }
                // Преподаватель и аудитория — другая история: напоминания
                // по ним не строятся вовсе, и вызов пересчёта был бы не
                // только лишним, но вредным. Проверяем это отдельно, ниже.
                // Роль стоит первым аргументом ПОСЛЕ открывающей скобки,
                // поэтому смотрим и вперёд, и назад.
                .filterNot { m ->
                    val around = code.substring(
                        maxOf(0, m.range.first - 60),
                        minOf(code.length, m.range.last + 80),
                    )
                    "Role.TEACHER" in around || "Role.AUDIENCE" in around
                }
                .toList()
            if (toggles.isEmpty()) continue
            for (t in toggles) {
                val after = code.substring(t.range.last, minOf(code.length, t.range.last + 700))
                if (!after.contains("ReminderScheduler.reschedule") &&
                    !after.contains("onFavChanged")) {
                    offenders += "$file: переключение избранного без пересчёта"
                }
            }
        }
        assertTrue(
            "Экраны меняют избранное, но не пересчитывают напоминания. " +
                "Снятая с избранного группа продолжит присылать пуши:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** Диапазон тела приватного хелпера `Set<String>.toggle`, если он есть. */
    private fun helperRange(file: String, code: String): IntRange {
        val start = code.indexOf("private fun Set<String>.toggle")
        if (start < 0) return IntRange.EMPTY
        val end = code.indexOf("\n}", start)
        return start..(if (end < 0) code.length else end)
    }

    @Test
    fun `звезда у преподавателя и аудитории пересчет не вызывает`() {
        // Напоминания строятся только по группам студента. Пересчёт при
        // снятии преподавателя не просто лишний — он ВРЕДИТ: пересчёт
        // смотрит на выбранную роль, видит не «студент» и снимает все
        // будильники. То есть снятие звёзды у преподавателя погасило бы
        // напоминания по группам.
        val code = src("PickersScreen.kt").withoutComments()
        for (role in listOf("Role.TEACHER", "Role.AUDIENCE")) {
            val m = Regex("""toggleFavFor\(\s*$role\s*,""").find(code)
            if (m == null) continue
            val after = code.substring(m.range.last, minOf(code.length, m.range.last + 500))
            assertTrue(
                "Снятие избранного у $role не должно вызывать пересчёт: " +
                    "напоминания строятся только по группам, а пересчёт " +
                    "снимет все будильники",
                !after.contains("ReminderScheduler.reschedule"),
            )
        }
    }

    @Test
    fun `пересчёт защищён от наложения потоков`() {
        val code = src("ReminderScheduler.kt").withoutComments()
        assertTrue(
            "reschedule обязан брать блокировку: пересчёт читает planned_ids, " +
                "снимает будильники и перезаписывает список, и два потока " +
                "перетирают друг друга",
            Regex("""synchronized\s*\(\s*rescheduleLock\s*\)""").containsMatchIn(code),
        )
        assertTrue(
            "Блокировка должна существовать как поле объекта",
            Regex("""private val rescheduleLock\s*=\s*Any\(\)""").containsMatchIn(code),
        )
    }
}