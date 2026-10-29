package com.mietschedule.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

/**
 * Иконка приложения.
 *
 * Требование владельца: свой рисунок на белом фоне вместо прежнего значка,
 * причём уменьшенный — «бывают круглые, бывают квадратные иконки», и под
 * маской лаунчера края логотипа не должны срезаться.
 *
 * Проверяется не «файл существует», а свойства, от которых зависит вид:
 * белый фон, логотип отдельным слоем, растры во всех плотностях и границы
 * логотипа внутри безопасной зоны.
 *
 * Пиксели читаются своим разбором PNG: `javax.imageio` в android.jar
 * отсутствует, и тест с ним не компилируется.
 */
class AppIconTest {

    private val resDir = File("src/main/res")

    // ── Разбор PNG ─────────────────────────────────────────────────────────

    /** Разобранное изображение: размеры и пиксели в виде ARGB. */
    private class Png(val w: Int, val h: Int, val argb: IntArray) {
        /** Непрозрачность пикселя, 0..255. */
        fun alpha(x: Int, y: Int): Int = (argb[y * w + x] ushr 24) and 0xFF

        /** Цвет пикселя без альфы, 0xRRGGBB. */
        fun rgb(x: Int, y: Int): Int = argb[y * w + x] and 0xFFFFFF
    }

    private fun intAt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a)
        val pb = Math.abs(p - b)
        val pc = Math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    /**
     * Читает PNG без внешних библиотек.
     *
     * Поддерживается только нужный случай: 8 бит на канал, RGBA, без
     * чересстрочности — так сохраняет Pillow. Любой другой формат роняет
     * тест с понятным сообщением, а не с молчаливо неверными пикселями.
     */
    private fun readPng(f: File): Png {
        val b = f.readBytes()
        require(b.size > 8) { "${f.name}: файл короче заголовка PNG" }
        var p = 8
        var w = 0
        var h = 0
        var depth = 0
        var color = 0
        val idat = ByteArrayOutputStream()
        while (p + 8 <= b.size) {
            val len = intAt(b, p)
            val type = String(b, p + 4, 4, Charsets.US_ASCII)
            val data = p + 8
            when (type) {
                "IHDR" -> {
                    w = intAt(b, data)
                    h = intAt(b, data + 4)
                    depth = b[data + 8].toInt()
                    color = b[data + 9].toInt()
                }
                "IDAT" -> idat.write(b, data, len)
                "IEND" -> break
            }
            p = data + len + 4
        }
        require(depth == 8 && color == 6) {
            "${f.name}: ожидался PNG 8-бит RGBA, получено depth=$depth color=$color"
        }

        // Несжатый поток — это h строк по (1 байт фильтра + ширина*4).
        // Буфер обязан быть ровно таким: на меньшем inflate() вернёт
        // сколько поместилось, и разбор упадёт дальше с выходом за границы.
        val raw = ByteArray(h * (1 + w * 4))
        val inflater = Inflater()
        inflater.setInput(idat.toByteArray())
        var got = 0
        while (!inflater.finished() && got < raw.size) {
            val n = inflater.inflate(raw, got, raw.size - got)
            if (n == 0 && !inflater.finished()) {
                // Нужен словарь (такие PNG не пишет Pillow) — говорим прямо,
                // а не отдаём половину картинки.
                if (inflater.needsDictionary()) throw AssertionError("${f.name}: нужен словарь")
                break
            }
            got += n
        }
        inflater.end()
        require(got == raw.size) {
            "${f.name}: распаковано $got байт из ${raw.size} — данные неполные"
        }

        val bpp = 4
        val stride = w * bpp
        val out = IntArray(w * h)
        val prev = ByteArray(stride)
        val cur = ByteArray(stride)
        var off = 0
        for (y in 0 until h) {
            val filter = raw[off++].toInt()
            System.arraycopy(raw, off, cur, 0, stride)
            off += stride
            for (i in 0 until stride) {
                val x = cur[i].toInt() and 0xFF
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val bb = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val v = when (filter) {
                    0 -> x
                    1 -> x + a
                    2 -> x + bb
                    3 -> x + (a + bb) / 2
                    4 -> x + paeth(a, bb, c)
                    else -> throw AssertionError("${f.name}: неизвестный фильтр $filter")
                }
                cur[i] = (v and 0xFF).toByte()
            }
            for (x in 0 until w) {
                val i = x * bpp
                val r = cur[i].toInt() and 0xFF
                val g = cur[i + 1].toInt() and 0xFF
                val bl = cur[i + 2].toInt() and 0xFF
                val al = cur[i + 3].toInt() and 0xFF
                out[y * w + x] = (al shl 24) or (r shl 16) or (g shl 8) or bl
            }
            System.arraycopy(cur, 0, prev, 0, stride)
        }
        return Png(w, h, out)
    }

    // ── Проверки ───────────────────────────────────────────────────────────

    @Test
    fun `фон иконки белый`() {
        val colors = File(resDir, "values/colors.xml").readText()
        assertTrue(
            "Фон иконки должен быть белым: требование владельца — «фон так же белый»",
            Regex("""<color name="ic_launcher_background">#FFFFFF</color>""")
                .containsMatchIn(colors),
        )
    }

    @Test
    fun `адаптивная иконка берёт логотип отдельным слоем`() {
        val xml = File(resDir, "mipmap-anydpi-v26/ic_launcher.xml").readText()
        assertTrue(
            "Фон адаптивной иконки должен ссылаться на цвет",
            xml.contains("@color/ic_launcher_background"),
        )
        assertTrue(
            "Логотип должен лежать отдельным слоем, иначе маска обрежет его вместе с фоном",
            xml.contains("@mipmap/ic_launcher_fg"),
        )
    }

    /**
     * Растры есть во всех плотностях и нужного размера.
     *
     * Пропущенная плотность в коде не видна — значок просто размывается на
     * части устройств.
     */
    @Test
    fun `растры иконки есть во всех плотностях`() {
        val dens = mapOf(
            "mdpi" to 1.0, "hdpi" to 1.5, "xhdpi" to 2.0, "xxhdpi" to 3.0, "xxxhdpi" to 4.0,
        )
        for ((name, k) in dens) {
            val dir = File(resDir, "mipmap-$name")
            assertTrue("нет каталога mipmap-$name", dir.isDirectory)

            val fg = File(dir, "ic_launcher_fg.png")
            assertTrue("нет foreground для mipmap-$name", fg.isFile)
            val img = readPng(fg)
            val expect = Math.round(108 * k).toInt()
            assertEquals("неверная ширина foreground в mipmap-$name", expect, img.w)
            assertEquals("foreground в mipmap-$name не квадратный", img.w, img.h)

            for (fn in listOf("ic_launcher.png", "ic_launcher_round.png")) {
                assertTrue("нет $fn в mipmap-$name", File(dir, fn).isFile)
            }
        }
    }

    /**
     * Логотип умещается в безопасную зону.
     *
     * Маска лаунчера обрезает всё за пределами 66dp из 108dp: под квадратной
     * маской срезало бы края круга, под круглой — углы. Поэтому логотип
     * ужат до этой зоны, и за ней не должно быть ни одного непрозрачного
     * пикселя.
     */
    @Test
    fun `логотип не выходит за безопасную зону`() {
        val img = readPng(File(resDir, "mipmap-mdpi/ic_launcher_fg.png"))
        assertEquals("холст адаптивной иконки должен быть 108dp", 108, img.w)

        val safe = Math.round(66 * img.w / 108f)
        val lo = (img.w - safe) / 2
        val hi = lo + safe

        var outside = 0
        var inside = 0
        for (y in 0 until img.h) {
            for (x in 0 until img.w) {
                if (img.alpha(x, y) == 0) continue
                if (x < lo || x >= hi || y < lo || y >= hi) outside++ else inside++
            }
        }
        assertEquals("непрозрачных пикселей за безопасной зоной", 0, outside)
        assertTrue("логотип пустой — показывать нечего", inside > 0)
    }

    /**
     * Legacy-иконки (до Android 8) — на белом фоне.
     *
     * Маски там нет, но вид обязан совпадать с адаптивной иконкой, иначе на
     * старых телефонах значок выглядел бы крупнее и на прозрачном фоне.
     */
    @Test
    fun `legacy-иконки на белом фоне`() {
        for (dens in listOf("mdpi", "xxxhdpi")) {
            val img = readPng(File(resDir, "mipmap-$dens/ic_launcher.png"))
            var transparent = 0
            for (y in 0 until img.h) {
                for (x in 0 until img.w) {
                    if (img.alpha(x, y) != 255) transparent++
                }
            }
            assertEquals("в mipmap-$dens есть прозрачные пиксели", 0, transparent)
            // Угол заведомо вне логотипа, значит это фон.
            assertEquals("угол в mipmap-$dens не белый", 0xFFFFFF, img.rgb(1, 1))
        }
    }

    /**
     * Логотип в legacy-иконке занимает ту же долю, что и в адаптивной.
     *
     * Иначе на Android 7 значок был бы крупнее, чем на Android 8, — это
     * заметно при обновлении телефона, а в коде расхождение не видно.
     */
    @Test
    fun `доля логотипа одинакова в legacy и адаптивной иконке`() {
        val fg = readPng(File(resDir, "mipmap-xxxhdpi/ic_launcher_fg.png"))
        val leg = readPng(File(resDir, "mipmap-xxxhdpi/ic_launcher.png"))

        // Габарит логотипа в долях от холста: масштаб один и тот же, но
        // размеры в пикселях разные, поэтому сравнивать надо не площадь
        // (сглаженные края у мелкого растра дают разницу), а протяжённость.
        fun extent(img: Png, isLogo: (Int, Int) -> Boolean): Double {
            var minX = img.w
            var maxX = -1
            for (y in 0 until img.h) {
                for (x in 0 until img.w) {
                    if (!isLogo(x, y)) continue
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
            require(maxX >= 0) { "логотип не найден — иконка пустая" }
            return (maxX - minX + 1).toDouble() / img.w
        }

        // В адаптивном слое логотип — непрозрачные пиксели, вокруг поля.
        val fgExtent = extent(fg) { x, y -> fg.alpha(x, y) > 0 }
        // В legacy фон белый и непрозрачный, поэтому логотип — всё, что
        // заметно темнее белого. Порог обязателен: сглаженный край круга
        // даёт почти белые пиксели, и без порога габарит раздувается.
        val legExtent = extent(leg) { x, y ->
            val c = leg.rgb(x, y)
            ((c shr 16) and 0xFF) < 230 || ((c shr 8) and 0xFF) < 230 || (c and 0xFF) < 230
        }

        // Ожидание — доля безопасной зоны: 66 из 108.
        val expect = 66.0 / 108.0
        assertEquals(
            "габарит логотипа в адаптивном слое не совпал с безопасной зоной",
            expect,
            fgExtent,
            0.02,
        )
        assertEquals(
            "габарит логотипа в legacy-иконке расходится с адаптивной",
            fgExtent,
            legExtent,
            0.02,
        )
    }
}