package kr.neptune.linksaver.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kr.neptune.linksaver.core.DownloadRepo
import kr.neptune.linksaver.core.DownloadTask
import kr.neptune.linksaver.core.MediaImporter
import kr.neptune.linksaver.core.MediaMeta
import kr.neptune.linksaver.core.Platform
import kr.neptune.linksaver.core.Quality
import kr.neptune.linksaver.core.TaskState
import kr.neptune.linksaver.core.YtDlp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val url by vm.url.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val picker by vm.picker.collectAsStateWithLifecycle()
    val tasks by DownloadRepo.tasks.collectAsStateWithLifecycle()
    val initState by YtDlp.initState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeToast()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("링크세이버", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "설정")
                    }
                }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AnimatedVisibility(visible = initState !is YtDlp.InitState.Ready) {
                    InitBanner(initState)
                }
            }

            item {
                InputCard(
                    url = url,
                    quality = quality,
                    enabled = initState is YtDlp.InitState.Ready,
                    busy = picker is PickerState.Probing,
                    onUrlChange = vm::setUrl,
                    onQualityChange = vm::setQuality,
                    onPaste = { vm.pasteFromClipboard(context) },
                    onSubmit = vm::submit
                )
            }

            if (tasks.isEmpty()) {
                item { EmptyHint() }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "다운로드 (${tasks.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(onClick = vm::clearFinished) { Text("완료 항목 지우기") }
                    }
                }

                items(items = tasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onCancel = { vm.cancel(task.id) },
                        onRetry = { vm.retry(task.url, task.quality, task.playlistItems) },
                        onRemove = { vm.removeTask(task.id) },
                        onOpen = {
                            val uri = task.savedUris.firstOrNull() ?: return@TaskCard
                            val mime = context.contentResolver.getType(uri) ?: "*/*"
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mime)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(intent) }
                                .onFailure { vm.showToast("열 수 있는 앱이 없습니다") }
                        }
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // 조회 중 / 선택 단계
    when (val state = picker) {
        is PickerState.Probing -> ProbingDialog(onCancel = vm::cancelProbe)

        is PickerState.Choosing -> MediaPickerSheet(
            state = state,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onToggle = vm::toggleSelection,
            onSelectAll = vm::selectAll,
            onClearAll = vm::clearSelection,
            onConfirm = vm::confirmSelection,
            onDismiss = vm::dismissPicker
        )

        PickerState.Hidden -> Unit
    }

    if (showSettings) {
        SettingsSheet(
            vm = vm,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onDismiss = { showSettings = false }
        )
    }
}

// ---------------------------------------------------------------- 상단 배너

@Composable
private fun InitBanner(state: YtDlp.InitState) {
    val failed = state is YtDlp.InitState.Failed
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!failed) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Column {
                Text(
                    if (failed) "엔진 초기화 실패" else "다운로드 엔진 준비 중…",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    when (state) {
                        is YtDlp.InitState.Failed -> state.message
                        else -> "첫 실행에는 5~20초 정도 걸립니다"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 입력 카드

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputCard(
    url: String,
    quality: Quality,
    enabled: Boolean,
    busy: Boolean,
    onUrlChange: (String) -> Unit,
    onQualityChange: (Quality) -> Unit,
    onPaste: () -> Unit,
    onSubmit: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("인스타그램 · X(트위터) 링크") },
                placeholder = { Text("https://www.instagram.com/p/…") },
                singleLine = false,
                maxLines = 3,
                trailingIcon = {
                    if (url.isNotEmpty()) {
                        IconButton(onClick = { onUrlChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "지우기")
                        }
                    }
                }
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                Quality.entries.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = quality == item,
                        onClick = { onQualityChange(item) },
                        shape = SegmentedButtonDefaults.itemShape(index, Quality.entries.size)
                    ) {
                        Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPaste, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("붙여넣기")
                }
                Button(
                    onClick = onSubmit,
                    enabled = enabled && !busy,
                    modifier = Modifier.weight(1.4f)
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("다운로드")
                }
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("이렇게 쓰세요", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("1. 인스타 게시물 · 릴스 또는 X 게시물에서 [공유] → [링크세이버]", style = MaterialTheme.typography.bodySmall)
            Text("2. 또는 링크를 복사한 뒤 위의 [붙여넣기] → [다운로드]", style = MaterialTheme.typography.bodySmall)
            Text("3. 사진이 여러 장이면 받을 것만 골라서 저장할 수 있습니다", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "공개 계정의 게시물만 받을 수 있습니다. 저장 위치: ${MediaImporter.savedLocationText()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------- 조회 중 다이얼로그

@Composable
private fun ProbingDialog(onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* 조회 중에는 바깥 터치로 닫지 않음 */ },
        icon = { CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp) },
        title = { Text("미디어 확인 중") },
        text = {
            Text("게시물에 무엇이 들어 있는지 확인하고 있습니다. 잠시만 기다려 주세요.")
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text("취소") }
        }
    )
}

// ---------------------------------------------------------------- 선택 시트

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaPickerSheet(
    state: PickerState.Choosing,
    sheetState: androidx.compose.material3.SheetState,
    onToggle: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "받을 항목 선택",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "미디어 ${state.items.size}개 중 ${state.selected.size}개 선택됨",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = if (state.allSelected) onClearAll else onSelectAll) {
                    Text(if (state.allSelected) "전체 해제" else "전체 선택")
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = state.items, key = { it.playlistIndex }) { meta ->
                    MediaCell(
                        meta = meta,
                        selected = meta.playlistIndex in state.selected,
                        onClick = { onToggle(meta.playlistIndex) }
                    )
                }
            }

            Button(
                onClick = onConfirm,
                enabled = state.canConfirm,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (state.canConfirm) "선택한 ${state.selected.size}개 받기"
                    else "받을 항목을 골라 주세요"
                )
            }

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("취소")
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MediaCell(meta: MediaMeta, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val borderColor =
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(3.dp, borderColor, shape)
            .clickable(onClick = onClick)
    ) {
        // 썸네일을 못 불러와도 번호는 보이도록 뒤에 깔아 둔다
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "${meta.playlistIndex}",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (meta.thumbnail != null) {
            AsyncImage(
                model = meta.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
            )
        }

        // 좌상단 순번
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                "${meta.playlistIndex}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }

        // 우상단 체크
        Icon(
            imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = if (selected) "선택됨" else "선택 안 됨",
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(22.dp)
        )

        // 좌하단 종류 배지
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!meta.isImage) {
                Icon(
                    Icons.Rounded.PlayCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(Modifier.width(3.dp))
            }
            Text(
                meta.badge,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1
            )
        }
    }
}

// ---------------------------------------------------------------- 작업 카드

@Composable
private fun TaskCard(
    task: DownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(task)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        buildString {
                            append(task.platform.label)
                            task.uploader?.let { append(" · @$it") }
                            append(" · ${task.quality.label}")
                            if (task.playlistItems != null) append(" · 선택 ${task.expectedCount}개")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            when (task.state) {
                TaskState.QUEUED, TaskState.FETCHING, TaskState.SAVING ->
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                TaskState.DOWNLOADING ->
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth()
                    )

                else -> Unit
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = task.error ?: statusText(task),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(Modifier.width(8.dp))

                when {
                    !task.isFinished ->
                        TextButton(onClick = onCancel) { Text("취소") }

                    task.state == TaskState.DONE ->
                        Row {
                            TextButton(onClick = onOpen) {
                                Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("열기")
                            }
                            IconButton(onClick = onRemove) {
                                Icon(Icons.Rounded.Close, contentDescription = "목록에서 제거", modifier = Modifier.size(18.dp))
                            }
                        }

                    else ->
                        Row {
                            TextButton(onClick = onRetry) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("다시 시도")
                            }
                            IconButton(onClick = onRemove) {
                                Icon(Icons.Rounded.Close, contentDescription = "목록에서 제거", modifier = Modifier.size(18.dp))
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(task: DownloadTask) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (task.thumbnail != null) {
            AsyncImage(
                model = task.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Rounded.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (task.platform != Platform.OTHER) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(
                        if (task.platform == Platform.INSTAGRAM) Color(0xFFE1306C) else Color(0xFF111111),
                        RoundedCornerShape(topStart = 8.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    if (task.platform == Platform.INSTAGRAM) "IG" else "X",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
        }
    }
}

private fun statusText(task: DownloadTask): String = when (task.state) {
    TaskState.QUEUED -> "대기 중"
    TaskState.FETCHING -> "링크 확인 중…"
    TaskState.DOWNLOADING -> {
        val percent = (task.progress * 100).toInt()
        val eta = if (task.etaSec > 0) " · 약 ${task.etaSec}초 남음" else ""
        "다운로드 중 $percent%$eta"
    }
    TaskState.SAVING -> "갤러리에 저장 중…"
    TaskState.DONE -> "${task.savedCount}개 저장 완료"
    TaskState.CANCELED -> "취소됨"
    TaskState.FAILED -> "실패"
}

// ---------------------------------------------------------------- 설정 시트

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    vm: MainViewModel,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val version by vm.ytdlpVersion.collectAsStateWithLifecycle()
    val updating by vm.updating.collectAsStateWithLifecycle()
    val cookies by vm.cookiesName.collectAsStateWithLifecycle()
    var autoPaste by remember { mutableStateOf(vm.autoPaste) }

    val cookiePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> vm.importCookies(context, uri) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("설정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            SettingRow(
                title = "yt-dlp 엔진",
                subtitle = version?.let { "버전 $it" } ?: "버전 확인 중…"
            ) {
                if (updating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = vm::updateYtDlp) { Text("업데이트") }
                }
            }
            Text(
                "인스타/X 가 내부 구조를 바꾸면 다운로드가 실패할 수 있습니다. 그럴 때 여기서 엔진만 갱신하면 앱 재설치 없이 복구됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            SettingRow(
                title = "cookies.txt (선택)",
                subtitle = cookies ?: "미등록 — 인스타 실패가 잦으면 등록하세요"
            ) {
                if (cookies != null) {
                    TextButton(onClick = vm::clearCookies) { Text("해제") }
                } else {
                    TextButton(onClick = { cookiePicker.launch(arrayOf("text/plain", "*/*")) }) {
                        Text("선택")
                    }
                }
            }
            Text(
                "브라우저 확장(Get cookies.txt 등)으로 내보낸 파일을 넣으면 인스타그램 요청 차단을 크게 줄일 수 있습니다. 본인 계정 정보이므로 다른 곳에 공유하지 마세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            SettingRow(
                title = "클립보드 자동 인식",
                subtitle = "앱을 열 때 복사된 링크를 자동으로 채웁니다"
            ) {
                Switch(
                    checked = autoPaste,
                    onCheckedChange = {
                        autoPaste = it
                        vm.setAutoPaste(it)
                    }
                )
            }

            HorizontalDivider()

            Text("저장 위치", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                MediaImporter.savedLocationText(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                "※ 개인 소장 용도로만 사용하세요. 저작권자의 허락 없이 재업로드·배포하는 것은 위법일 수 있으며, 각 플랫폼의 이용약관에도 어긋납니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}
