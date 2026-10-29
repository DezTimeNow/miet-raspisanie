package com.mietschedule.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.MaterialTheme

/**
 * Ширина, которую занимает стрелка «‹» в шапке.
 *
 * Она нужна как ЗАРЕЗЕРВИРОВАННОЕ место: на экранах, где назад есть, здесь
 * стоит стрелка, а где назад некуда (выбор роли) — пустота той же ширины.
 * Без этого резерва «Роль» и кнопка обновления прыгали бы между экранами.
 */
private val BACK_SLOT = 48.dp

/**
 * Сколько dp шапка отдаёт под кнопки справа: звезда + обновление.
 * Заголовок ужимается на эту величину, иначе он рассчитывается на всю
 * ширину окна и переносится.
 *
 * Домик «На главную» убран, поэтому резерв стал меньше — заголовок
 * получил больше места. Звезда держит слот всегда (резерв), обновление —
 * всегда на экране.
 */
private const val RESERVED_FOR_ACTIONS = 112

/**
 * ЕДИНАЯ ШАПКА для всех экранов приложения.
 *
 * Почему вынесено в отдельный файл, а не повторяется в каждом экране:
 * требование к единому интерфейсу: «меню должно быть одинаковое у всех
 * ролей» и «кнопка обновления всегда в одном месте». Пока каждый экран собирал свой блок
 * `actions` руками, они разъезжались: у студента в шапке стоял текст
 * «Сменить роль» СЛЕВА от обновления, у преподавателя и аудитории — стрелка
 * «‹» в navigationIcon. Ширина соседей отличалась, и кнопка обновления
 * измерялась то на 854 px, то на 728 px: код был похож, слот — разный.
 *
 * Теперь набор соседей задан один раз, и все экраны обязаны использовать
 * именно его. Слот кнопки обновления становится функцией от этого набора,
 * а не от того, что дописал разработчик в конкретном экране.
 *
 * СОСТАВ (слева направо), одинаковый на всех экранах:
 *   [‹ назад] [★] [⭯ обновить]
 *
 * Звезда присутствует только там, где есть что любить (расписание), но место
 * под неё зарезервировано всегда — иначе она снова сдвинет соседей.
 *
 * Домика «На главную» в шапке НЕТ: переход на главную живёт в нижнем меню
 * (Главная/Избранное/Настройки), которое есть на всех экранах. Второй вход
 * на главную в шапке был лишним и путал пользователя.
 */
/**
 * АДАПТИВНЫЙ КЕГЛЬ. Compose BOM 2024.12.01 не умеет autoSize (появился в
 * более новых версиях), поэтому размер считаем сами от ширины окна в dp.
 *
 * Зачем: на узких экранах заголовок «Расписание МИЭТ» не влезал в шапку
 * и переносился на вторую строку, наезжая на подзаголовок («АУДИТОРИ Я»).
 * Ширина берётся у LocalConfiguration/Density, поэтому результат
 * одинаков при любом разрешении: считаем в dp, а не в пикселях.
 *
 * Считаем в обычных Float, а не в `sp`: единица измерения — @Composable
 * функция, поэтому версии на TextUnit нельзя проверить обычным тестом.
 * Вызывающий код переводит число в sp сам.
 */
internal fun titleSizeSp(availableDp: Int): Float = when {
    availableDp < 330 -> 13f
    availableDp < 380 -> 15f
    availableDp < 460 -> 16f
    else -> 17f
}

internal fun subtitleSizeSp(availableDp: Int): Float = when {
    availableDp < 330 -> 10f
    availableDp < 380 -> 11f
    else -> 12f
}

@Composable
internal fun adaptiveTitleSize(availableDp: Int): TextUnit = titleSizeSp(availableDp).sp

@Composable
internal fun adaptiveSubtitleSize(availableDp: Int): TextUnit = subtitleSizeSp(availableDp).sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MietTopBar(
    title: String,
    subtitle: String,
    onRefresh: () -> Unit,
    onChangeRole: () -> Unit,
    refreshing: Boolean = false,
    onBack: (() -> Unit)? = null,
    isFav: Boolean? = null,
    onToggleFav: (() -> Unit)? = null,
    /**
     * Слот под иконку «домой» пуст — на главной.
     *
     * Домик ведёт на главную, и на главной самой он вёл бы в себя же. Пустая
     * кнопка читается как сломанная, поэтому слот резервируется той же
     * шириной, что и на остальных экранах. Без резерва заголовок уезжал бы
     * на 48 px вправо именно на главном экране.
     */
    homeSlotEmpty: Boolean = false,
) {
    TopAppBar(
        // Высота шапки: 48dp — минимум Material для зоны нажатия.
        //
        // Параметр expandedHeight у самого TopAppBar, а НЕ Modifier.height():
        // попытка задать высоту модификатором обрезала контент — заголовок в
        // Column с fillMaxHeight выпадал за границу и становился невидимым.
        // expandedHeight меняет высоту слота правильно, контент остаётся
        // внутри. 48dp — нижний предел: меньше — и кнопки выпадают из зоны
        // комфортного нажатия. Было 52dp, затем стандартные 64dp Material3.
        expandedHeight = 48.dp,
        // Градиент вместо плоского цвета: шапка выглядит приподнятой
        // панелью, а не окрашенной полосой. TopAppBar сам не принимает
        // Brush, поэтому прозрачный контейнер + фон-градиент модификатором.
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
        ),
        modifier = Modifier.background(Depth25.topBar),
        navigationIcon = {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    // Стрелка нарисована текстом, а не иконкой, поэтому у
                    // IconButton не оказалось описания: TalkBack читал «‹»
                    // как символ, а не как действие. Разметка даёт кнопке
                    // имя, не меняя внешний вид.
                    modifier = Modifier.semantics { contentDescription = "Назад" },
                ) {
                    Text("‹", color = MaterialTheme.colorScheme.surface, fontSize = 28.sp)
                }
            } else {
                Spacer(Modifier.width(BACK_SLOT))
            }
        },
        title = {
            // Шрифты АДАПТИВНЫЕ. На узких экранах (Honor Magic V2 — 1200 px
            // ширины при density 3) «Расписание МИЭТ» не влезало в одну строку
            // и переносилось на вторую, а заголовок съезжал на подзаголовок
            // («АУДИТОРИ / Я»). maxLines = 1 запрещает перенос, а
            // autoSize ужимает текст под доступную ширину.
            //
            // Column центрирован по вертикали: у кнопок действий (обновить,
            // звезда) высота IconButton равна всей высоте шапки, и без
            // выравнивания одна строка заголовка прижимается к её верху —
            // надпись «Избранное»/«Расписание МИЭТ» стоит выше кнопки
            // обновить, и шапка выглядит перекошенной. CenterVertically
            // ставит заголовок на одну линию с кнопками.
            Column(
                modifier = Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                val cfg = LocalConfiguration.current
                val density = LocalDensity.current
                // Ширина окна в dp: шапка занимает всю ширину минус слоты под
                // кнопки-действия (звезда/обновление/роль).
                val availDp = with(density) { cfg.screenWidthDp } - RESERVED_FOR_ACTIONS
                val titleSp = adaptiveTitleSize(availDp)
                val subtitleSp = adaptiveSubtitleSize(availDp)
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.surface,
                    fontSize = titleSp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                // Подзаголовок рисуется ТОЛЬКО когда он непустой. Пустой
                // Text всё равно занимает строку своей высоты, и вместе с
                // заголовком этот стек центрируется в шапке — заголовок
                // уезжает выше кнопки действий. На «Избранном» и главной
                // подзаголовка нет, и из-за этой строки надпись стояла
                // выше кнопки обновить. Проверено по bounds: центр
                // заголовка 181 против центра иконки 217.
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        color = LocalAppColors.current.currentGroup,
                        fontSize = subtitleSp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        actions = {
            if (isFav != null) {
                IconButton(onClick = { onToggleFav?.invoke() }) {
                    Icon(
                        imageVector = if (isFav) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (isFav) "Убрать из избранного" else "В избранное",
                        tint = if (isFav) LocalAppColors.current.favStar else LocalAppColors.current.currentGroup,
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else {
                // Резерв под звезду. Иначе на экране расписания (где звезда
                // есть) обновление стояло бы на 48 px левее, чем на остальных.
                Spacer(Modifier.width(BACK_SLOT))
            }
            // Место под «Избранное» в шапке больше не нужно: кнопка живёт
            // в главном меню под выбором роли. Слот убираем целиком, чтобы
            // заголовок стал шире на всех экранах.
            //
            // Кнопка «обновить» стоит последней (крайняя справа): домик
            // «На главную» из шапки убран — переход на главную теперь в
            // нижнем меню, которое есть на всех экранах. Второй вход на
            // главную в шапке был лишним и путал пользователя.
            IconButton(
                onClick = onRefresh,
                enabled = !refreshing,
                // Описание меняется вместе с состоянием: пока кнопка занята,
                // TalkBack не должен обещать действие, которого не будет.
                modifier = Modifier.semantics {
                    contentDescription = if (refreshing) "Обновляю всё" else "Обновить всё"
                },
            ) {
                // 343 группы — это ~50 секунд, поэтому вместо иконки крутилка:
                // молчащее ожидание читается как зависание.
                if (refreshing) CircularProgressIndicator(
                    Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.surface
                ) else Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    )
}
