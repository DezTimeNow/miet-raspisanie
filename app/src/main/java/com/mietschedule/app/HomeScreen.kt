package com.mietschedule.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Заголовок списка избранного на главной.
 */
internal const val FAV_LIST_TITLE = "Избранное:"

/**
 * Пояснение при пустом избранном.
 *
 * Показывается вместо списка, пока не отмечено ни одной звезды. Пустое место
 * под заголовком читается как поломка, поэтому здесь сказано, что сделать,
 * чтобы список наполнился.
 */
internal const val FAV_EMPTY_HINT = "Отметь звездой группу — расписание появится здесь"

/**
 * Крупная строка пустого состояния избранного.
 *
 * Раньше под заголовком «Избранное:» висела одна мелкая подсказка, и
 * пустой экран читался как поломка. Теперь это иконка-звезда, крупная
 * строка-заголовок и та же подсказка действием.
 */
internal const val FAV_EMPTY_TITLE = "Здесь пока пусто"

/**
 * ГЛАВНАЯ СТРАНИЦА.
 *
 * Требование владельца от 0.65: «кнопку избранное и меню избранное убираем с
 * главной, вместо неё делаем на главной список Избранное и дальше кнопки для
 * быстрого доступа на расписание, которое было добавлено в избранное».
 *
 * Порядок сверху вниз:
 *
 *   1. Блок «сейчас и дальше» — то, ради чего приложение открывают чаще
 *      всего. Остаётся сверху по требованию владельца: он отвечает на вопрос
 *      «что сейчас», а список — навигация, и смешивать их значило бы снова
 *      потерять ответ на первый вопрос.
 *   2. Список избранного: по кнопке на каждую отмеченную группу,
 *      преподавателя и аудиторию. Кнопка открывает расписание сразу, минуя
 *      выбор роли и список групп.
 *   3. Кнопка «Добавить» — единственный вход в выбор группы, преподавателя
 *      и аудитории.
 *   4. Служебное подменю: Настройки, О программе.
 *
 * Список и блок строятся из ОДНОГО снимка избранного, но показываются
 * раздельно намеренно: блок молчит про избранное, у которого нет кэша
 * расписаний, а кнопка в списке обязана быть всегда — иначе избранное
 * выглядело бы потерянным сразу после установки.
 *
 * Прокрутка обязательна: при трёх избранных блок занимает полтора десятка
 * строк, и без прокрутки нижнее подменю уезжает за край. fillMaxSize() здесь
 * НЕ ставится намеренно — в прокручиваемой колонке он растягивает её и
 * обрезает содержимое.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
)
@Composable
fun HomeScreen(
    /** Данные для блока «сейчас и дальше». */
    api: MietApi,
    prefs: GroupPrefs,
    /** Открыть расписание: роль и значение избранного. */
    onOpenFavorite: (Role, String) -> Unit = { _, _ -> },
    onRefresh: () -> Unit = {},
    /** Открыть выбор группы / преподавателя / аудитории для добавления. */
    onAdd: () -> Unit = {},
    /** Прогресс обновления: обновляются все 343 группы, это ~35 секунд. */
    refreshNote: String = "",
    refreshing: Boolean = false,
    onAbout: () -> Unit = {},
    onSettings: () -> Unit = {},
    /** Открыть вкладку «Избранное» нижней навигации. */
    onOpenFavorites: () -> Unit = {},
    /**
     * Показывать ли список избранного на главной.
     *
     * Выключается переключателем в «Настройках». Когда список скрыт, главная
     * отвечает только на вопрос «что сейчас»: блок «Пары на сегодня» остаётся.
     * Добавление в избранное при этом доступно на вкладке «Избранное», поэтому
     * кнопка «Добавить» прячется вместе со списком.
     */
    showFavorites: Boolean = true,
) {
    // Снимок избранного читается на каждом входе на главную. remember без
    // ключа здесь безопасен: при переходе на другой экран главная выходит из
    // композиции (переходы идут через `when (screen)`), поэтому возврат
    // пересоздаёт её и список перечитывается. Без этого снятая звезда
    // оставалась бы в списке до перезапуска приложения.
    val favs = remember { favSnapshot(prefs) }

    Scaffold(
        topBar = {
            // На главной стрелки «‹» нет: возвращаться некуда, и пустое место
            // той же ширины резервируется, чтобы заголовок стоял на тех же
            // координатах, что на всех остальных экранах.
            MietTopBar(
                title = "Расписание МИЭТ",
                subtitle = "",
                onRefresh = onRefresh,
                // Домик в шапке ведёт на главную, то есть сам в себя; слот
                // пустой, иначе кнопка читалась бы как сломанная.
                onChangeRole = {},
                refreshing = refreshing,
                onBack = null,
                homeSlotEmpty = true,
            )
        }
        // Нижнее меню (Главная/Избранное/Настройки) здесь больше нет: оно
        // общее на всех экранах и живёт в AppRoot. Экран держит только свою
        // шапку и контент.
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            if (refreshing && refreshNote.isNotEmpty()) {
                Text(
                    refreshNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MIET_BLUE,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
                )
            }

            // ───── ЧТО СЕЙЧАС И ЧТО ДАЛЬШЕ ─────
            //
            // Блок пар на сегодня. Здесь была проверка «есть ли API и
            // хранилище» — она осталась с тех пор, когда эти параметры были
            // необязательными. Сейчас оба приходят обязательными, и компилятор
            // справедливо предупреждал, что условие всегда истинно.
            NextLessonCard(
                api = api,
                prefs = prefs,
                onOpen = onOpenFavorite,
            )

            Spacer(Modifier.height(14.dp))

            // ───── СПИСОК ИЗБРАННОГО ─────
            //
            // Блок целиком показывается только при включённой настройке
            // «Показывать избранное на главной». Вместе со списком прячется и
            // кнопка «Добавить»: добавлять можно на вкладке «Избранное», а
            // главная без списка отвечает только на вопрос «что сейчас».
            if (showFavorites) {
                //
                // Ушёл на отдельный экран-вкладку «Избранное» (см. [FavoritesScreen]).
                // Раньше он висел здесь, но с появлением вкладки «Избранное» в
                // нижней навигации один и тот же список в двух местах — это
                // дублирование: пользователь путается, какой из них главный.
                // Главная теперь отвечает на два вопроса: «что сейчас» (блок
                // «сейчас и дальше») и «куда добавить» (кнопка «Добавить»).
                // Сам список избранного живёт на своей вкладке.
                if (favs.isEmpty()) {
                    // Пустое состояние с иконкой: на главной оно подсказывает,
                    // что делать дальше. Звезда-контур прямо говорит, какое
                    // действие наполнит избранное.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Outlined.StarBorder,
                            contentDescription = null,
                            tint = LocalAppColors.current.starInactive,
                            modifier = Modifier.size(56.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            FAV_EMPTY_TITLE,
                            style = MaterialTheme.typography.titleSmall,
                            color = LocalAppColors.current.dim,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            FAV_EMPTY_HINT,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalAppColors.current.muted,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Text(
                        FAV_LIST_TITLE,
                        style = MaterialTheme.typography.titleSmall,
                        color = LocalAppColors.current.muted,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    )
                    favs.take(3).forEachIndexed { index, entry ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(animationSpec = tween(300, delayMillis = index * 60)) +
                                slideInVertically(animationSpec = tween(300, delayMillis = index * 60)) { it / 3 },
                        ) {
                            FavEntryCard(entry) { onOpenFavorite(entry.role, entry.value) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (favs.size > 3) {
                        Text(
                            "Ещё ${favs.size - 3} — во вкладке «Избранное»",
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalAppColors.current.muted,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                        )
                    }
            }

            Spacer(Modifier.height(12.dp))

            // ───── ДОБАВЛЕНИЕ ─────
            //
            // Единственный вход в выбор группы, преподавателя и аудитории.
            // Раньше на этом месте стояли две карточки — «Выбрать роль» и
            // «Избранное»; роль перестала быть постоянным понятием интерфейса
            // и нужна только как шаг добавления.
            HomeActionCard(
                icon = Icons.Filled.Add,
                title = "Добавить",
                onClick = onAdd,
            )
            }
        }
    }
}

/**
 * КНОПКА ИЗБРАННОГО НА ГЛАВНОЙ.
 *
 * Иконка по роли, само значение и стрелка. Открывает расписание сразу —
 * именно это требование владельца от 0.65: «кнопки для быстрого доступа на
 * расписание, которое было добавлено в избранное».
 */
@Composable
private fun FavEntryCard(entry: FavEntry, onClick: () -> Unit) {
    val icon: ImageVector = when (entry.role) {
        Role.STUDENT -> Icons.Filled.School
        Role.TEACHER -> Icons.Filled.Person
        Role.AUDIENCE -> Icons.Filled.Apartment
    }
    val interaction = remember { MutableInteractionSource() }
    val scale = Depth25.pressScale(interaction)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Анимация нажатия: карточка чуть уменьшается под пальцем —
            // продавленная поверхность. Вёрстку не трогает (graphicsLayer).
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            // Цветная тень вместо серой по умолчанию: под синей карточкой
            // синяя тень читается как настоящая, чёрная — как грязь.
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(14.dp), ambientColor = Depth25.cardShadow, spotColor = Depth25.cardShadow)
            .clickable(interactionSource = interaction, indication = ripple()) { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MIET_BLUE, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                favTitle(entry),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "\u203a",
                fontSize = 22.sp,
                color = MIET_BLUE,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * СТРОКА-КНОПКА ГЛАВНОЙ.
 *
 * Вид повторяет прежние карточки ролей: иконка, название и стрелка «›»
 * справа. Пояснение под названием не выводится: подпись однозначна, а
 * пустая вторая строка делала бы карточку выше без пользы.
 */
@Composable
private fun HomeActionCard(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = Depth25.pressScale(interaction)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(14.dp), ambientColor = Depth25.cardShadow, spotColor = Depth25.cardShadow)
            .clickable(interactionSource = interaction, indication = ripple()) { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MIET_BLUE, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "\u203a",
                fontSize = 22.sp,
                color = MIET_BLUE,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Что добавляется в избранное на экране добавления.
 *
 * Отдельная подпись, а не [Role.title]: роль называет точку зрения («Студент»),
 * а здесь выбирают, ЧТО добавить, — и для роли студента это группа.
 */
internal fun addLabel(role: Role): String = when (role) {
    Role.STUDENT -> "Группа"
    Role.TEACHER -> "Преподаватель"
    Role.AUDIENCE -> "Аудитория"
}

/**
 * ЭКРАН ДОБАВЛЕНИЯ: что добавить в избранное.
 *
 * Требование владельца от 0.65: выбор группы, преподавателя и аудитории
 * вызывается кнопкой «Добавить» с главной и нужен только как шаг добавления.
 * Отметки выбранной роли здесь нет и подсветки нет: это меню выбора, а не
 * показ состояния — какая роль и что выбрано, видно по расписанию, которое
 * эти кнопки открывают.
 *
 * Стрелка «‹» возвращает туда, откуда вошли, домик — всегда на главную.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPickerScreen(
    onPick: (Role) -> Unit,
    onRefresh: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    onHome: () -> Unit = {},
    refreshing: Boolean = false,
) {
    Scaffold(
        topBar = {
            MietTopBar(
                title = "Добавить",
                subtitle = "",
                onRefresh = onRefresh,
                onChangeRole = onHome,
                refreshing = refreshing,
                onBack = onBack,
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                "Что добавить в избранное",
                fontSize = 14.sp,
                color = LocalAppColors.current.muted,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
            Role.entries.forEach { role ->
                AddPickerCard(role) { onPick(role) }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/**
 * Карточка выбора на экране добавления.
 *
 * Состояния «выбрано» нет: это меню выбора, а не показ состояния. Раньше
 * текущая роль отмечалась галочкой вместо стрелки и подсветкой фона, из-за
 * чего экран читался как «где я нахожусь», хотя открывают его, чтобы выбрать,
 * что добавить.
 */
@Composable
private fun AddPickerCard(role: Role, onClick: () -> Unit) {
    val icon: ImageVector = when (role) {
        Role.STUDENT -> Icons.Filled.School
        Role.TEACHER -> Icons.Filled.Person
        Role.AUDIENCE -> Icons.Filled.Apartment
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MIET_BLUE, modifier = Modifier.padding(end = 14.dp))
            Text(
                addLabel(role),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "\u203a",
                fontSize = 22.sp,
                color = MIET_BLUE,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * ВКЛАДКА «ИЗБРАННОЕ» нижней навигации.
 *
 * Полный список отмеченных звёздами групп, преподавателей и аудиторий.
 * Раньше он висел прямо на главной, но с появлением вкладки «Избранное»
 * дублировать его в двух местах — значит путать пользователя: какой из
 * списков главный. Главная теперь отвечает на «что сейчас» и «куда
 * добавить», а весь список избранного живёт здесь.
 *
 * На главной показаны только первые три — быстрый доступ без перехода,
 * остальные — здесь.
 */
@Composable
fun FavoritesScreen(
    prefs: GroupPrefs,
    onOpenFavorite: (Role, String) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean = false,
    /** Открыть выбор группы/преподавателя/аудитории — вход в «Добавить». */
    onAdd: () -> Unit = {},
) {
    val favs = remember { favSnapshot(prefs) }
    Scaffold(
        topBar = {
            MietTopBar(
                title = "Избранное",
                subtitle = if (favs.isEmpty()) "" else "${favs.size} " + plural(favs.size, "запись", "записи", "записей"),
                onRefresh = onRefresh,
                onChangeRole = {},
                refreshing = refreshing,
                onBack = onBack,
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (favs.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.StarBorder,
                        contentDescription = null,
                        tint = LocalAppColors.current.starInactive,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        FAV_EMPTY_TITLE,
                        style = MaterialTheme.typography.titleSmall,
                        color = LocalAppColors.current.dim,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        FAV_EMPTY_HINT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalAppColors.current.muted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    // Кнопка действия прямо в пустом состоянии: без неё юзер
                    // видит «здесь пусто» и не понимает, куда тыкать — а куда,
                    // если на вкладке нет другой кнопки. Явное «Добавить»
                    // ведёт на выбор группы, преподавателя или аудитории.
                    Button(
                        onClick = onAdd,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MIET_BLUE),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Добавить в избранное", style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else {
                favs.forEachIndexed { index, entry ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(animationSpec = tween(300, delayMillis = index * 60)) +
                            slideInVertically(animationSpec = tween(300, delayMillis = index * 60)) { it / 3 },
                    ) {
                        FavEntryCard(entry) { onOpenFavorite(entry.role, entry.value) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // Кнопка «Добавить» — тот же вход в выбор, что и на главной.
                // Чтобы добавлять новое избранное прямо с этой вкладки, не
                // возвращаясь на главную.
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onAdd,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MIET_BLUE),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Добавить", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
