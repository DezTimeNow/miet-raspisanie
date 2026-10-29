package com.mietschedule.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow

import androidx.compose.runtime.Stable
import androidx.compose.material3.MaterialTheme


/**
 * Диалог «Доступна новая версия».
 *
 * Отдельный файл, а не кусок в MainActivity: там уже четыре экрана и роутинг,
 * а здесь — собственный состояние (скачивание/ошибка) и своя логика установки.
 * В MainActivity остаётся только вызов [UpdatePromptHost].
 */

/** Состояние диалога обновления. */
enum class UpdateStage { IDLE, DOWNLOADING, ERROR }

/**
 * Мост между экраном «О программе» и диалогом обновления.
 *
 * Диалог рисует [UpdatePromptHost], который живёт уровнем выше (один на всё
 * приложение, он же ловит фоновую проверку при запуске). Экран «О программе»
 * не может обратиться к нему напрямую, поэтому передаёт найденное обновление
 * через эту ручку, а хост подхватывает изменение.
 *
 * [pending] — обычное Compose-состояние, поэтому кнопка в «О программе» и
 * хост видят одно и то же значение.
 */
@Stable
class UpdateDialogHandle {
    var pending by mutableStateOf<UpdateInfo?>(null)
        internal set

    /** Открыть окно обновления для найденного релиза. */
    fun show(info: UpdateInfo) {
        pending = info
    }

    /** Закрыть окно, если оно открыто. */
    fun dismiss() {
        pending = null
    }
}

/**
 * Текст релиза приходит из GitHub как Markdown, где перевод строки — HTML-энтития
 * `&#10;`. Compose рисует такой текст буквально, и пользователь видит «&#10;»
 * вместо разрыва строки. Здесь разворачиваем энтитии в нормальный текст,
 * снимаем Markdown-заголовки и лишние пустые строки.
 */
internal fun cleanReleaseNotes(raw: String): String {
    // Порядок замен важен: `&amp;` разбирается последним. Иначе `&amp;lt;`
    // превратится в `<`, и в тексте релиза окажется символ, которого там нет.
    val decoded = raw
        .replace("&#13;", "")
        .replace("&#10;", "\n")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
    return decoded.lineSequence()
        // Заголовок и буллет в тексте релиза оформлены Markdown-звёздочками.
        // trimStart убирал их только в начале строки, а закрывающая пара
        // оставалась — пользователь читал «**Заголовок**» вместе со
        // звёздочками. Одиночный маркер буллета («* пункт») убирается, как и
        // раньше, а вот ПАРНЫЕ звёздочки внутри строки — это обращение
        // Markdown, и пользователь читал их как текст. Порядок важен: сначала
        // парные, потом маркер в начале.
        .map { it.trim().replace("**", "").trimStart('#', '*', '-', ' ').trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .take(1200)
}

/**
 * Проверка при запуске + диалог.
 *
 * Вызывается один раз из MainActivity. Само открытие приложения не ждёт сеть:
 * проверка идёт в фоне, и если новая версия есть — диалог всплывает позже.
 */
@Composable
fun UpdatePromptHost(
    currentVersionCode: Int,
    enabled: Boolean = true,
    onUpdateInfo: (UpdateInfo?) -> Unit = {},
    /**
     * Диалог, который уже открыт снаружи (например, кнопкой «Проверить
     * обновления» в экране «О программе»). Ссылка нужна, чтобы подставить
     * найденное обновление в существующий диалог, а не открывать второй.
     */
    dialogHandle: UpdateDialogHandle? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var stage by remember { mutableStateOf(UpdateStage.IDLE) }
    var errorText by remember { mutableStateOf("") }
    // Сколько байт уже скачано и сколько ожидаем. Храним байты, а не мегабайты:
    // пересчитывать в мегабайты на каждом обновлении — лишняя работа, а
    // разница видна только в последней цифре.
    var downloadedBytes by remember { mutableLongStateOf(-1L) }
    var totalBytes by remember { mutableLongStateOf(0L) }

    // Диалог, открытый снаружи: снимаем ссылку только когда её закрыли,
    // иначе приложение удержит старый UpdateInfo в памяти.
    LaunchedEffect(dialogHandle) {
        val handle = dialogHandle ?: return@LaunchedEffect
        snapshotFlow { handle.pending }
            .collect { info ->
                if (info != null) update = info
            }
    }

    // Фоновая проверка при появлении экрана — один раз за composition.
    LaunchedEffect(enabled, currentVersionCode) {
        if (!enabled) return@LaunchedEffect
        val found = UpdateChecker.checkForUpdate(ctx, currentVersionCode)
        onUpdateInfo(found)
        if (found != null) update = found
    }

    val info = update ?: return

    // Доля скачанного: null, когда размер неизвестен. Тогда полоса
    // неопределённая, а не выдуманная.
    fun progressValue(): Float? {
        if (downloadedBytes < 0 || totalBytes <= 0) return null
        return (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    }

    // Строка состояния загрузки. Показываем и проценты, и мегабайты: по
    // процентам видно движение, по мегабайтам — сколько ещё ждать.
    fun downloadLine(): String {
        val pct = progressValue()?.let { (it * 100).toInt() }
        return if (pct == null) {
            "Скачиваю обновление…"
        } else {
            // Формат «X.X МБ из Y.Y МБ» — тот же, что у sizeLabel в
            // UpdateInfo: свой разделитель, без String.format и его локали.
            fun mb(bytes: Long): String {
                val tenths = (bytes / 1048576.0 * 10 + 0.5).toInt()
                return "${tenths / 10}.${tenths % 10}"
            }
            "$pct% · ${mb(downloadedBytes)} МБ из ${mb(totalBytes)} МБ"
        }
    }

    // Когда окно закрыто (любым способом) — обнуляем и ручку, иначе возврат на
    // экран «О программе» снова подхватит то же обновление и покажет его.
    fun closeDialog() {
        update = null
        stage = UpdateStage.IDLE
        errorText = ""
        downloadedBytes = -1L
        totalBytes = 0L
        dialogHandle?.dismiss()
    }

    AlertDialog(
        onDismissRequest = { if (stage == UpdateStage.IDLE) closeDialog() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    tint = MIET_BLUE,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Доступна новая версия",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MIET_BLUE,
                    )
                    Text(
                        "${info.versionLabel}${if (info.sizeLabel.isNotEmpty()) "  ·  ${info.sizeLabel}" else ""}",
                        fontSize = 12.sp,
                        color = LocalAppColors.current.muted,
                    )
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 340.dp)) {
                when (stage) {
                    UpdateStage.DOWNLOADING -> {
                        val progress = progressValue()
                        Text(
                            downloadLine(),
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = MIET_BLUE,
                            )
                        } else {
                            // Размер неизвестен — крутимся, но честно: цифры
                            // не выдумываем, просто показываем «идёт загрузка».
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = MIET_BLUE,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Не закрывай приложение",
                            fontSize = 12.sp,
                            color = LocalAppColors.current.muted,
                        )
                    }

                    UpdateStage.ERROR -> {
                        Text("Не получилось скачать обновление", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(errorText, fontSize = 13.sp, color = LocalAppColors.current.error)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Открой релиз в браузере:\n${UpdateChecker.REPO}",
                            fontSize = 12.sp,
                            color = LocalAppColors.current.muted,
                        )
                    }

                    UpdateStage.IDLE -> {
                        // Текст релиза — то, что публикуется в release notes,
                        // но через cleanReleaseNotes: GitHub отдаёт переводы
                        // строк как &#10;, и Compose показал бы их буквально.
                        val notes = cleanReleaseNotes(info.notes)
                        if (notes.isNotEmpty()) {
                            Text(
                                notes.take(1200),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                            )
                        } else {
                            Text(
                                "Обновление с ${info.versionLabel}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (stage) {
                UpdateStage.DOWNLOADING -> TextButton(onClick = {}, enabled = false) {
                    Text("Скачиваю…", color = LocalAppColors.current.muted)
                }

                UpdateStage.ERROR -> Button(
                    onClick = {
                        stage = UpdateStage.IDLE
                        errorText = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MIET_BLUE),
                ) {
                    Text("Повторить", color = MaterialTheme.colorScheme.surface)
                }

                UpdateStage.IDLE -> {
                    if (!UpdateChecker.canRequestInstall(ctx)) {
                        // Android 8+ без явного разрешения: сначала в настройки,
                        // иначе установщик просто не запустится. Скачивать
                        // в этом случае бессмысленно — сначала разрешение.
                        Button(
                            onClick = {
                                // Настройка «Установка неизвестных приложений»
                                // есть не во всех прошивках и не во всех версиях
                                // Android. Если Activity нет, показываем ошибку в
                                // диалоге, вместо того чтобы уронить приложение.
                                val opened = runCatching {
                                    ctx.startActivity(UpdateChecker.unknownSourcesSettings(ctx))
                                }.isSuccess
                                if (opened) {
                                    closeDialog()
                                } else {
                                    errorText = "Настройка установки недоступна на этом устройстве"
                                    stage = UpdateStage.ERROR
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MIET_BLUE),
                        ) {
                            Text("Разрешить установку", color = MaterialTheme.colorScheme.surface)
                        }
                    } else {
                        Button(
                            onClick = {
                                stage = UpdateStage.DOWNLOADING
                                downloadedBytes = -1L
                                totalBytes = 0L
                                scope.launch {
                                    runCatching {
                                        UpdateChecker.downloadApk(ctx, info) { got, total ->
                                            // Колбэк приходит из потока ввода-вывода,
                                            // а состояние Compose живёт в main-потоке.
                                            // Без переключения обновление полосы было
                                            // бы запрещено и молча терялось.
                                            val g = got
                                            val t = total
                                            scope.launch {
                                                downloadedBytes = g
                                                totalBytes = t
                                            }
                                        }
                                    }.onSuccess { apk ->
                                        // Установщик на этом устройстве может быть
                                        // отключён или отсутствовать (например на
                                        // части прошивок без PackageInstaller).
                                        // Без защиты startActivity падал бы с
                                        // ActivityNotFoundException прямо из
                                        // обработчика результата.
                                        runCatching {
                                            ctx.startActivity(UpdateChecker.installIntent(ctx, apk))
                                            closeDialog()
                                        }.onFailure {
                                            errorText = "Не найдено приложение для установки"
                                            stage = UpdateStage.ERROR
                                        }
                                    }.onFailure { e ->
                                        errorText = e.message ?: "Неизвестная ошибка"
                                        stage = UpdateStage.ERROR
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MIET_BLUE),
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Скачать", color = MaterialTheme.colorScheme.surface)
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (stage != UpdateStage.DOWNLOADING) {
                TextButton(onClick = {
                    // «Позже» — не достаём до следующего релиза.
                    UpdateChecker.recordDismissed(ctx, info.versionCode)
                    closeDialog()
                }) {
                    Text("Позже", color = LocalAppColors.current.muted)
                }
            }
        },
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    )
}