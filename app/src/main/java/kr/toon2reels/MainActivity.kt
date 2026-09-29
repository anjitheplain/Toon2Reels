package kr.toon2reels

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

enum class Screen { HOME, PANELS, STYLE, PREVIEW, RESULT }

class EditorModel(app: Application) : AndroidViewModel(app) {
    var project by mutableStateOf(Project()); private set
    var screen by mutableStateOf(Screen.HOME); private set
    var note by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var progress by mutableIntStateOf(0); private set
    var exportFile by mutableStateOf<File?>(null); private set
    var savedUri by mutableStateOf<Uri?>(null); private set
    private val cancel = AtomicBoolean(false)
    private val images = ImageRepository(app)
    private var sequence = 0L
    fun images() = images
    fun notice(text: String) { note = text }
    fun clearNotice() { note = "" }
    fun update(fn: (Project) -> Project) { project = fn(project) }
    fun navigate(next: Screen) { screen = next }

    fun importSingle(uri: Uri) {
        busy = true; note = "컷을 찾고 있어요…"
        viewModelScope.launch {
            try {
                val crops = withContext(Dispatchers.Default) { PanelDetector.detect(images.load(uri)) }
                project = Project(panels = if (crops.isEmpty()) listOf(Panel(++sequence, uri))
                    else crops.map { Panel(++sequence, uri, it, detected = true) })
                note = if (crops.isEmpty()) "컷을 찾지 못했어요. 이 컷을 복제해 영역을 나누거나 여러 장으로 다시 가져와 주세요."
                    else "${crops.size}개의 컷을 찾았어요. 순서와 영역을 확인해 주세요."
                screen = Screen.PANELS
            } catch (_: Exception) { note = "이 이미지를 읽을 수 없어요. 다른 이미지로 시도해 주세요." }
            finally { busy = false }
        }
    }
    fun importMany(uris: List<Uri>, append: Boolean = false) {
        if (uris.isEmpty()) return
        busy = true
        viewModelScope.launch {
            val good = withContext(Dispatchers.IO) { uris.filter { uri -> runCatching { images.load(uri); true }.getOrDefault(false) } }
            if (good.isEmpty()) note = "이미지를 읽을 수 없어요. 다른 파일을 골라 주세요."
            else {
                val panels = good.map { Panel(++sequence, it) }
                project = if (append) project.copy(panels = project.panels + panels) else Project(panels = panels)
                screen = Screen.PANELS
                note = if (good.size < uris.size) "읽을 수 없는 이미지는 건너뛰었어요." else "${project.panels.size}개의 컷을 확인해 주세요."
            }
            busy = false
        }
    }
    fun changePanel(index: Int, fn: (Panel) -> Panel) = update { it.copy(panels = it.panels.mapIndexed { i, p -> if (i == index) fn(p) else p }) }
    fun remove(index: Int) = update { it.copy(panels = it.panels.filterIndexed { i, _ -> i != index }) }
    fun duplicate(index: Int) = update { old ->
        if (index !in old.panels.indices) old else {
            val list = old.panels.toMutableList(); list.add(index+1, list[index].copy(id = ++sequence)); old.copy(panels = list)
        }
    }
    fun swap(from: Int, to: Int) = update { old -> old.copy(panels = PanelOps.move(old.panels, from, to)) }
    fun render() {
        if (busy || project.panels.isEmpty()) return
        busy = true; progress = 0; note = ""; cancel.set(false); savedUri = null; exportFile = null
        val snapshot = project
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = VideoExporter(getApplication()).export(snapshot, cancel) { value -> progress = value }
                withContext(Dispatchers.Main) { exportFile = file; screen = Screen.RESULT }
            } catch (_: CancelledExport) { withContext(Dispatchers.Main) { note = "릴스 만들기를 취소했어요." } }
            catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    note = when (e) {
                        is java.io.IOException -> "저장 공간이나 파일 접근을 확인해 주세요. 공간을 비운 뒤 다시 시도할 수 있어요."
                        else -> "영상을 만들지 못했어요. 이미지나 음악 파일을 확인한 뒤 다시 시도해 주세요."
                    }
                }
            } finally { busy = false }
        }
    }
    fun cancelRender() { cancel.set(true); note = "렌더링을 중단하고 있어요…" }

    fun savePanelImages(ids: List<Long>) {
        if (busy || ids.isEmpty()) return
        val selected = project.panels.mapIndexedNotNull { index, panel ->
            if (panel.id in ids) (index + 1) to panel else null
        }
        if (selected.isEmpty()) return
        busy = true; note = "컷 이미지를 저장하고 있어요…"
        viewModelScope.launch(Dispatchers.IO) {
            var saved = 0
            try {
                for ((number, panel) in selected) {
                    GallerySaver.savePanel(getApplication(), panel, number)
                    saved++
                }
                note = "${saved}개의 컷을 갤러리의 사진/Toon2Reels에 각각 저장했어요."
            } catch (_: Exception) {
                note = "${saved}개를 저장했어요. 나머지 컷을 저장하지 못했어요. 원본 파일과 저장 공간을 확인해 주세요."
            } finally { busy = false }
        }
    }

    fun saveToGallery() {
        val file = exportFile ?: return
        busy = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                savedUri = GallerySaver.save(getApplication(), file)
                note = "갤러리의 동영상/Toon2Reels에 저장했어요."
            } catch (_: Exception) {
                note = "갤러리에 저장하지 못했어요. 저장 공간을 확인하고 다시 시도해 주세요."
            } finally { busy = false }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: EditorModel = viewModel()
            val single = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) { persist(uri); vm.importSingle(uri) }
            }
            val multiple = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                uris.forEach(::persist); vm.importMany(uris)
            }
            val add = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                uris.forEach(::persist); vm.importMany(uris, append = true)
            }
            val music = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) { persist(uri); vm.update { it.copy(music = uri) } }
            }
            val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = ComposeColor(0xFFB7D3FF))
                else lightColorScheme(primary = ComposeColor(0xFF355F9C), background = ComposeColor(0xFFF9F9FD))
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) {
                    Scaffold(topBar = {
                        Column(Modifier.statusBarsPadding()) {
                            Text("Toon2Reels", Modifier.padding(start = 20.dp, top = 15.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("만화를 릴스로", Modifier.padding(start = 20.dp, bottom = 10.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                            if (vm.note.isNotEmpty()) {
                                Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                                    Text(vm.note, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            when(vm.screen) {
                                Screen.HOME -> HomeScreen(vm.busy, { single.launch(arrayOf("image/*")) }, { multiple.launch(arrayOf("image/*")) })
                                Screen.PANELS -> PanelScreen(vm, { add.launch(arrayOf("image/*")) }, { single.launch(arrayOf("image/*")) }, { multiple.launch(arrayOf("image/*")) })
                                Screen.STYLE -> StyleScreen(vm, { music.launch(arrayOf("audio/*")) })
                                Screen.PREVIEW -> PreviewScreen(vm)
                                Screen.RESULT -> ResultScreen(vm, { share(vm) })
                            }
                        }
                    }
                }
            }
            BackHandler(vm.screen != Screen.HOME && !vm.busy) {
                vm.navigate(when(vm.screen) { Screen.RESULT -> Screen.PREVIEW; Screen.PREVIEW -> Screen.STYLE; Screen.STYLE -> Screen.PANELS; else -> Screen.HOME })
            }
        }
    }
    private fun persist(uri: Uri) {
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
    }
    private fun share(vm: EditorModel) {
        val file = vm.exportFile ?: return
        val uri = vm.savedUri ?: FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "릴스 공유"))
    }
}

@Composable private fun HomeScreen(busy: Boolean, one: () -> Unit, many: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("완성한 만화를 고르면\n릴스를 만들어 줄게요.", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(36.dp))
        Button(onClick = one, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("한 장의 만화 가져오기") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = many, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("여러 장의 컷 가져오기") }
        Spacer(Modifier.height(18.dp))
        Text("이미지와 영상은 휴대폰에서만 처리해요.", style = MaterialTheme.typography.bodySmall)
        if (busy) CircularProgressIndicator(Modifier.padding(18.dp))
    }
}

@Composable private fun ColumnScope.PanelScreen(vm: EditorModel, add: () -> Unit, one: () -> Unit, many: () -> Unit) {
    var selected by remember { mutableLongStateOf(-1L) }
    val panels = vm.project.panels
    val selectedIndex = panels.indexOfFirst { it.id == selected }
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    Text("컷 확인 · ${panels.size}개", style = MaterialTheme.typography.titleLarge)
    Text("≡ 부분을 위아래로 끌어 순서를 바꿔요.", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(panels, key = { _, item -> item.id }) { index, item ->
            Card(onClick = { selected = if (selected == item.id) -1 else item.id }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    var dragged by remember(item.id) { mutableFloatStateOf(0f) }
                    Text("≡", Modifier.width(30.dp).pointerInput(item.id, index) {
                        detectDragGestures(onDragEnd = { dragged = 0f }, onDrag = { change, amount ->
                            change.consume(); dragged += amount.y
                            if (abs(dragged) > threshold) {
                                vm.swap(index, index + if (dragged > 0) 1 else -1); dragged = 0f
                            }
                        })
                    }, style = MaterialTheme.typography.titleLarge)
                    PanelThumb(item, vm.images(), Modifier.size(76.dp, 88.dp))
                    Column(Modifier.weight(1f).padding(8.dp)) {
                        Text("${index+1}번 컷", fontWeight = FontWeight.Bold)
                        Text("${"%.1f".format(item.duration)}초${if (item.detected) " · 자동 검출" else ""}")
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { vm.savePanelImages(listOf(item.id)) }, enabled = !vm.busy) { Text("이미지 저장") }
                        TextButton(onClick = { vm.remove(index); if (selected == item.id) selected = -1 }, enabled = !vm.busy) { Text("삭제") }
                    }
                }
            }
        }
    }
    if (selectedIndex >= 0) {
        val item = panels[selectedIndex]
        HorizontalDivider()
        Text("${selectedIndex + 1}번 컷 · 영역과 시간", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { vm.duplicate(selectedIndex) }) { Text("이 컷 복제해서 영역 나누기") }
        Text("표시시간 ${"%.1f".format(item.duration)}초")
        Slider(value = item.duration, onValueChange = { value -> vm.changePanel(selectedIndex) { it.copy(duration = value) } }, valueRange = 0.5f..10f, steps = 18)
        CropControl("왼쪽", item.crop.left, 0f..(item.crop.right-.05f)) { value -> vm.changePanel(selectedIndex) { it.copy(crop = it.crop.copy(left = value)) } }
        CropControl("위쪽", item.crop.top, 0f..(item.crop.bottom-.05f)) { value -> vm.changePanel(selectedIndex) { it.copy(crop = it.crop.copy(top = value)) } }
        CropControl("오른쪽", item.crop.right, (item.crop.left+.05f)..1f) { value -> vm.changePanel(selectedIndex) { it.copy(crop = it.crop.copy(right = value)) } }
        CropControl("아래쪽", item.crop.bottom, (item.crop.top+.05f)..1f) { value -> vm.changePanel(selectedIndex) { it.copy(crop = it.crop.copy(bottom = value)) } }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = add) { Text("+ 컷 추가") }
        TextButton(onClick = one) { Text("한 장 다시") }
        TextButton(onClick = many) { Text("여러 장 다시") }
    }
    OutlinedButton(onClick = { vm.savePanelImages(panels.map { it.id }) }, enabled = panels.isNotEmpty() && !vm.busy, modifier = Modifier.fillMaxWidth()) {
        Text("모든 컷을 각각 갤러리에 저장")
    }
    Button(onClick = { vm.navigate(Screen.STYLE) }, enabled = panels.isNotEmpty() && !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("다음") }
}

@Composable private fun CropControl(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall)
        Slider(value, onChange, Modifier.weight(1f), valueRange = range)
        Text("${(value*100).roundToInt()}%", Modifier.width(42.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun PanelThumb(panel: Panel, repo: ImageRepository, modifier: Modifier = Modifier) {
    var bitmap by remember(panel.uri, panel.crop) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(panel.uri, panel.crop) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val source = repo.load(panel.uri)
                val c = panel.crop
                val src = Rect((c.left*source.width).toInt(), (c.top*source.height).toInt(),
                    (c.right*source.width).toInt().coerceAtLeast(1), (c.bottom*source.height).toInt().coerceAtLeast(1))
                val result = Bitmap.createBitmap(152,176,Bitmap.Config.ARGB_8888)
                val scale = min(152f/src.width(),176f/src.height())
                val width = src.width()*scale; val height = src.height()*scale
                Canvas(result).drawBitmap(source,src,RectF((152-width)/2,(176-height)/2,(152+width)/2,(176+height)/2),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                result
            }.getOrNull()
        }
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Fit)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("이미지") }
}

@Composable private fun ColumnScope.StyleScreen(vm: EditorModel, pickMusic: () -> Unit) {
    val p = vm.project
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("릴스 스타일", style = MaterialTheme.typography.titleLarge)
        Text("표시시간 · 모든 컷에 적용", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1.5f, 2.5f, 4f).forEach { time ->
                FilterChip(
                    selected = p.panels.isNotEmpty() && p.panels.all { abs(it.duration - time) < .001f },
                    onClick = { vm.update { it.copy(panels = it.panels.map { panel -> panel.copy(duration = time) }) } },
                    label = { Text("${"%.1f".format(time)}초") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }
        HorizontalDivider()
        Text("배경", style = MaterialTheme.typography.titleMedium)
        val names = listOf("검정", "흰색", "단색", "흐린 컷")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Background.entries.forEachIndexed { i, bg ->
                FilterChip(selected = p.background == bg, onClick = { vm.update { it.copy(background = bg) } }, label = { Text(names[i]) })
            }
        }
        if (p.background == Background.CUSTOM) {
            var hex by remember { mutableStateOf("#232630") }
            OutlinedTextField(hex, { text -> hex = text; runCatching { Color.parseColor(text) }.onSuccess { color -> vm.update { it.copy(color = color) } } }, label = { Text("배경색 #RRGGBB") }, singleLine = true)
        }
        HorizontalDivider()
        Text("전환", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Transition.NONE to "없음", Transition.FADE to "Fade", Transition.SLIDE to "Slide").forEach { (transition, name) ->
                FilterChip(selected = p.transition == transition, onClick = { vm.update { it.copy(transition = transition) } }, label = { Text(name) })
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("제목 표시", Modifier.weight(1f)); Switch(p.showTitle, { vm.update { old -> old.copy(showTitle = it) } })
        }
        if (p.showTitle) {
            OutlinedTextField(value = p.title, onValueChange = { text -> vm.update { it.copy(title = text) } }, label = { Text("제목") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!p.titleBottom, { vm.update { it.copy(titleBottom = false) } }, label = { Text("상단") })
                FilterChip(p.titleBottom, { vm.update { it.copy(titleBottom = true) } }, label = { Text("하단") })
            }
            Text("글자 크기 ${p.titleSize}")
            Slider(p.titleSize.toFloat(), { size -> vm.update { it.copy(titleSize = size.roundToInt()) } }, valueRange = 36f..76f)
        }
        HorizontalDivider()
        Text("음악", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = pickMusic) { Text(if (p.music == null) "음악 파일 선택" else "음악 바꾸기") }
            if (p.music != null) TextButton(onClick = { vm.update { it.copy(music = null) } }) { Text("음악 없음") }
        }
        if (p.music != null) {
            Text("음악 볼륨 ${(p.musicVolume*100).roundToInt()}%")
            Slider(p.musicVolume, { value -> vm.update { it.copy(musicVolume = value) } })
        }
    }
    Button(onClick = { vm.navigate(Screen.PREVIEW) }, modifier = Modifier.fillMaxWidth()) { Text("미리보기") }
}

@Composable private fun ColumnScope.PreviewScreen(vm: EditorModel) {
    val p = vm.project
    var playing by remember { mutableStateOf(true) }
    var time by remember { mutableFloatStateOf(0f) }
    var imageView by remember { mutableStateOf<ImageView?>(null) }
    val painter = remember(p) { FrameComposer(vm.images()) }
    Text("미리보기", style = MaterialTheme.typography.titleLarge)
    Text("${"%.1f".format(p.seconds)}초 · 9:16 · 음악은 완성된 영상에서 확인할 수 있어요.", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(10.dp))
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; layoutParams = ViewGroup.LayoutParams(360,640) } },
            modifier = Modifier.fillMaxHeight().aspectRatio(9f/16f), update = { imageView = it })
    }
    LaunchedEffect(p, playing, imageView) {
        val view = imageView ?: return@LaunchedEffect
        val bitmap = Bitmap.createBitmap(360,640,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        var last = SystemClock.elapsedRealtime()
        try {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                if (playing) time = if (p.seconds > 0) (time + (now-last)/1000f) % p.seconds else 0f
                last = now
                withContext(Dispatchers.Default) { painter.draw(canvas,p,time,360,640) }
                view.setImageBitmap(bitmap); view.invalidate()
                delay(if (playing) 33 else 100)
            }
        } catch (_: CancellationException) { }
    }
    Slider(time, { time = it; playing = false }, valueRange = 0f..p.seconds.coerceAtLeast(0.1f))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { playing = !playing }) { Text(if (playing) "일시정지" else "재생") }
        TextButton(onClick = { vm.navigate(Screen.STYLE) }) { Text("설정 수정") }
    }
    if (vm.busy) {
        LinearProgressIndicator(progress = { vm.progress / 100f }, modifier = Modifier.fillMaxWidth())
        Text("릴스를 만들고 있어요 · ${vm.progress}%")
        TextButton(onClick = vm::cancelRender) { Text("취소") }
    } else Button(onClick = vm::render, modifier = Modifier.fillMaxWidth()) { Text("릴스 만들기") }
}

@Composable private fun ResultScreen(vm: EditorModel, share: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("릴스가 완성됐어요", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(14.dp))
        Text("${"%.1f".format(vm.project.seconds)}초 · 1080 × 1920 · MP4")
        Spacer(Modifier.height(24.dp))
        Button(onClick = vm::saveToGallery, enabled = !vm.busy && vm.savedUri == null, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.savedUri == null) "갤러리에 저장" else "갤러리에 저장됨")
        }
        OutlinedButton(onClick = share, modifier = Modifier.fillMaxWidth()) { Text("공유하기") }
        TextButton(onClick = { vm.navigate(Screen.PREVIEW) }) { Text("미리보기로 돌아가기") }
    }
}
