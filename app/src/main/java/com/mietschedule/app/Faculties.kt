package com.mietschedule.app

/**
 * Разбивка групп МИЭТ по маркировкам направлений.
 *
 * Группы приходят с miet.ru в видах:
 *   - "ИВТ-11М"         — код направления + курс + суффикс (М/В/МВ)
 *   - "ТЭС-26-11О"      — код + год поступления + номер + форма (О=очно, С=заочно)
 *   - "Колледж 1"       — без дефиса
 *   - "Аспирантура 11"  — без дефиса
 *
 * Выбор двухуровневый: сначала маркировка («ИВТ», «ПИН», «ТЭС»…), затем полное
 * название группы. Уровень «направления/факультеты» убран намеренно — он стоил
 * одного лишнего клика и двух экранов скролла ради двух групп.
 */
object Faculties {

    /** Коды без дефиса: идут в один фолдер «Колледж и Аспирантура», не в маркировки. */
    private const val COLLEGE = "Колледж"
    private const val ASP = "Аспирантура"

    /**
     * Разобранная маркировка группы.
     * "ИВТ-11М"    → code=ИВТ, year=null, num=11, form=М   (11 = курс)
     * "ТЭС-26-11О" → code=ТЭС, year=26,   num=11, form=О   (26 = год поступления)
     */
    data class Mark(
        val code: String,
        val year: Int?,      // 26 в "ТЭС-26-11О" — год поступления
        val num: Int,        // 11 в "ИВТ-11М" (курс) либо 11 в "ТЭС-26-11О" (номер)
        val form: String     // М/В/МВ (маг/вечер/маг+вечер) либо О/С (очно/заочно)
    )

    /** Разбирает маркировку группы. Хвост всегда распознаётся, неизвестное идёт в [form]. */
    fun parse(group: String): Mark {
        val code = codeOf(group)
        if (code == COLLEGE || code == ASP) {
            val n = group.substringAfter(' ').takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            return Mark(code, null, n, "")
        }
        val tail = group.substringAfter('-', "").trim()
        // Формат "26-11О": год-номер-форма
        val m2 = Regex("""^(\d{2})-(\d{2})([ОС]?)$""").matchEntire(tail)
        if (m2 != null) {
            return Mark(code, m2.groupValues[1].toInt(), m2.groupValues[2].toInt(), m2.groupValues[3])
        }
        // Формат "11М": курс-суффикс
        val m1 = Regex("""^(\d{2})([А-ЯA-Z]?)$""").matchEntire(tail)
        if (m1 != null) return Mark(code, null, m1.groupValues[1].toInt(), m1.groupValues[2])
        // Колледж или нераспознанный хвост — просто по порядку
        return Mark(code, null, 0, tail)
    }

    /**
     * Порядок форм обучения: полный день → магистратура → вечер → магистратура+вечер
     * → очно → заочно. Совпадает с [parse] и у "ТЭС-26-11О" (форма О) и у
     * "ИВТ-11М" (суффикс М).
     */
    private val FORM_ORDER = mapOf("" to 0, "М" to 1, "В" to 2, "МВ" to 3, "О" to 4, "С" to 5)

    /**
     * Сorts группы внутри кода по-человечески: сначала по году поступления (если есть),
     * потом по номеру/курсу, потом по форме обучения, потом по имени как тай-брейкер.
     */
    fun sortGroups(groups: List<String>): List<String> = groups.sortedWith(
        compareBy(
            { parse(it).year ?: Int.MAX_VALUE },
            { parse(it).num },
            { FORM_ORDER[parse(it).form] ?: 9 },
            { it }
        )
    )

    /** Код направления из названия группы. */
    fun codeOf(group: String): String = when {
        group.startsWith("Колледж") -> COLLEGE
        group.startsWith("Аспирантура") -> ASP
        else -> group.substringBefore('-').trim()
    }

    /** Единственный маркировочный уровень. */
    fun groupByCode(groups: List<String>): Map<String, List<String>> {
        val byCode = LinkedHashMap<String, MutableList<String>>()
        for (g in groups) {
            byCode.getOrPut(codeOf(g)) { mutableListOf() }.add(g)
        }
        // Маркировки крупные первыми, при равенстве — по алфавиту.
        return byCode.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<String>>> { it.value.size }
                .thenBy { it.key })
            .associate { it.key to sortGroups(it.value) }
    }

    /** Группы одного кода, отсортированные по-человечески. */
    fun groupsOfCode(groups: List<String>, code: String): List<String> =
        sortGroups(groups.filter { codeOf(it) == code })
    }