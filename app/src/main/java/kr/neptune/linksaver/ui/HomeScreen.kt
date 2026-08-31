package kr.neptune.linksaver.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.io.File
import kr.neptune.linksaver.core.AppUpdater
import kr.neptune.linksaver.core.CookieExport
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
    val updateState by vm.updateState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var detailsTask by remember { mutableStateOf<DownloadTask?>(null) }

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
                UpdateBanner(
                    state = updateState,
                    onDownload = vm::downloadAppUpdate,
                    onInstall = { apk -> vm.installAppUpdate(context, apk) },
                    onDismiss = vm::dismissAppUpdate
                )
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
                        onRetry = {
                            vm.retry(task.url, task.quality, task.playlistItems, task.formatId)
                        },
                        onRemove = { vm.removeTask(task.id) },
                        onDetails = { detailsTask = task },
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

        is PickerState.ChoosingFormat -> FormatPickerSheet(
            state = state,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onChoose = vm::chooseFormat,
            onDismiss = vm::dismissPicker
        )

        PickerState.Hidden -> Unit
    }

    detailsTask?.let { task ->
        ErrorDetailsDialog(task = task, onDismiss = { detailsTask = null })
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
                    when (state) {
                        is YtDlp.InitState.Failed -> "엔진 초기화 실패"
                        is YtDlp.InitState.Updating -> "엔진 최신화 중…"
                        else -> "다운로드 엔진 준비 중…"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    when (state) {
                        is YtDlp.InitState.Failed -> state.message
                        is YtDlp.InitState.Updating ->
                            "yt-dlp 최신판을 받고 있습니다. 인스타/X 변경사항이 여기서 반영됩니다."
                        else -> "첫 실행에는 5~20초 정도 걸립니다"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 업데이트 배너

@Composable
private fun UpdateBanner(
    state: AppUpdater.State,
    onDownload: () -> Unit,
    onInstall: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val show = state is AppUpdater.State.Available ||
        state is AppUpdater.State.Downloading ||
        state is AppUpdater.State.ReadyToInstall

    AnimatedVisibility(visible = show) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (state) {
                    is AppUpdater.State.Available -> {
                        Text(
                            "새 버전 " + state.release.versionName + " 이 있습니다",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (state.release.notes.isNotBlank()) {
                            Text(
                                state.release.notes,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onDownload, modifier = Modifier.weight(1f)) {
                                Text("받기")
                            }
                            TextButton(onClick = onDismiss) { Text("나중에") }
                        }
                    }

                    is AppUpdater.State.Downloading -> {
                        Text(
                            "새 버전 받는 중 " + state.percent + "%",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    is AppUpdater.State.ReadyToInstall -> {
                        Text(
                            "설치할 준비가 됐습니다",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "설치 화면이 뜨면 그대로 진행하세요. 기존 앱을 지울 필요는 없습니다.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onInstall(state.file) },
                                modifier = Modifier.weight(1f)
                            ) { Text("설치") }
                            TextButton(onClick = onDismiss) { Text("나중에") }
                        }
                    }

                    else -> Unit
                }
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
                label = { Text("동영상 · 이미지 링크") },
                placeholder = { Text("https://…  링크를 붙여넣으세요") },
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
                        Text(item.short, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            if (quality == Quality.CUSTOM) {
                Text(
                    "조회한 뒤 실제 포맷 목록에서 고릅니다. 고를 게 하나뿐이면 바로 받습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    val context = LocalContext.current
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
            Text("1. 앱에서 [공유] → [링크세이버] 를 고르면 바로 시작됩니다", style = MaterialTheme.typography.bodySmall)
            Text("2. 또는 링크를 복사한 뒤 위의 [붙여넣기] → [다운로드]", style = MaterialTheme.typography.bodySmall)
            Text("3. 사진이 여러 장이면 받을 것만 골라서 저장할 수 있습니다", style = MaterialTheme.typography.bodySmall)
            Text("4. 인스타·X·틱톡을 비롯해 엔진이 아는 사이트면 그대로 시도합니다", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "받을 수 있는 사이트는 내장된 yt-dlp 엔진이 결정합니다. 지원하지 않는 곳은 실패로 표시됩니다. " +
                    "저장 위치: ${MediaImporter.savedLocationText(context)}",
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

// ---------------------------------------------------------------- 저장 폴더

@Composable
private fun AlbumNameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("저장 폴더 이름") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("폴더 이름") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "사진은 Pictures, 영상은 Movies, 오디오는 Music 아래 이 이름으로 저장됩니다. " +
                        "이미 저장된 파일은 그대로 남습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("저장") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

// ---------------------------------------------------------------- 오류 상세

/**
 * yt-dlp 가 실제로 뱉은 오류 원문을 그대로 보여준다.
 * 한국어 요약만으로는 원인을 알 수 없는 경우가 많아서 진단에 꼭 필요하다.
 */
@Composable
private fun ErrorDetailsDialog(task: DownloadTask, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val body = buildString {
        appendLine("요약: " + (task.error ?: "(없음)"))
        appendLine("플랫폼: " + task.platform.label + " / 화질: " + task.quality.label)
        appendLine("URL: " + task.url)
        appendLine()
        appendLine("--- 원문 ---")
        append(task.rawError ?: "(원문이 기록되지 않았습니다)")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("오류 상세") },
        text = {
            Column(modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(body))
                onDismiss()
            }) { Text("복사") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    )
}

// ---------------------------------------------------------------- 포맷 선택

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatPickerSheet(
    state: PickerState.ChoosingFormat,
    sheetState: androidx.compose.material3.SheetState,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "화질 선택",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "이 게시물에서 실제로 받을 수 있는 " + state.formats.size + "개입니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            // 맨 위는 항상 원본/최고화질 (엔진이 알아서 최적 조합을 고른다)
            FormatRow(
                title = "원본 / 최고 화질",
                subtitle = "엔진이 가장 좋은 조합을 고릅니다",
                highlight = true,
                onClick = { onChoose(null) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Column(
                modifier = Modifier
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                state.formats.forEach { format ->
                    FormatRow(
                        title = format.label,
                        subtitle = "포맷 " + format.formatId,
                        highlight = false,
                        onClick = { onChoose(format.formatId) }
                    )
                }
            }

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("취소")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FormatRow(
    title: String,
    subtitle: String,
    highlight: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    onOpen: () -> Unit,
    onDetails: () -> Unit
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
                val failed = task.state == TaskState.FAILED
                Text(
                    text = if (failed) (task.error ?: "실패") + "  ▸ 자세히"
                    else statusText(task),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .then(if (failed) Modifier.clickable(onClick = onDetails) else Modifier)
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
        val badgeColor = when (task.platform) {
            Platform.INSTAGRAM -> Color(0xFFE1306C)
            Platform.TWITTER -> Color(0xFF111111)
            Platform.TIKTOK -> Color(0xFF00F2EA)
            Platform.YOUTUBE -> Color(0xFFFF0000)
            Platform.OTHER -> Color(0xFF4A4A4A)
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(badgeColor, RoundedCornerShape(topStart = 8.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            Text(
                task.platform.short,
                style = MaterialTheme.typography.labelSmall,
                color = if (task.platform == Platform.TIKTOK) Color.Black else Color.White
            )
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
    TaskState.RETRY_WAIT -> task.statusLine.ifBlank { "재시도 대기 중" }
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
    val loggedIn by vm.loggedInSites.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refreshLoginState() }
    var autoPaste by remember { mutableStateOf(vm.autoPaste) }
    var igAnonFirst by remember { mutableStateOf(vm.instagramAnonymousFirst) }
    var albumName by remember { mutableStateOf(vm.albumName) }
    var concurrent by remember { mutableStateOf(vm.maxConcurrent) }
    var autoRetry by remember { mutableStateOf(vm.autoRetry) }
    var showAlbumDialog by remember { mutableStateOf(false) }

    if (showAlbumDialog) {
        AlbumNameDialog(
            initial = albumName,
            onConfirm = { entered ->
                vm.setAlbumName(entered)
                albumName = vm.albumName
                showAlbumDialog = false
            },
            onDismiss = { showAlbumDialog = false }
        )
    }

    val cookiePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> vm.importCookies(context, uri) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("설정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            SettingRow(
                title = "앱 업데이트",
                subtitle = when (val u = updateState) {
                    is AppUpdater.State.Checking -> "확인 중…"
                    is AppUpdater.State.UpToDate -> "최신입니다 (" + vm.appVersionName + ")"
                    is AppUpdater.State.Available -> "새 버전 " + u.release.versionName + " 있음"
                    is AppUpdater.State.Downloading -> "받는 중 " + u.percent + "%"
                    is AppUpdater.State.ReadyToInstall -> "설치 준비됨"
                    is AppUpdater.State.Failed -> "확인 실패: " + u.message
                    else -> "현재 " + vm.appVersionName
                }
            ) {
                when (val u = updateState) {
                    is AppUpdater.State.Available ->
                        TextButton(onClick = vm::downloadAppUpdate) { Text("받기") }

                    is AppUpdater.State.ReadyToInstall ->
                        TextButton(onClick = { vm.installAppUpdate(context, u.file) }) { Text("설치") }

                    is AppUpdater.State.Checking, is AppUpdater.State.Downloading ->
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)

                    else -> TextButton(onClick = vm::checkAppUpdate) { Text("확인") }
                }
            }

            HorizontalDivider()

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

            Text("로그인", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            CookieExport.ALL.forEach { site ->
                val on = site.key in loggedIn
                SettingRow(
                    title = site.label,
                    subtitle = if (on) "로그인됨" else "로그인 안 됨"
                ) {
                    if (on) {
                        TextButton(onClick = { vm.logout(site) }) { Text("해제") }
                    } else {
                        TextButton(onClick = {
                            loginLauncher.launch(LoginActivity.intent(context, site.key))
                        }) { Text("로그인") }
                    }
                }
            }

            Text(
                "로그인하면 X 연령제한 게시물, 인스타 스토리처럼 로그아웃 상태에서는 서버가 아예 " +
                    "내용을 주지 않는 것들까지 받을 수 있고, 인스타 요청 차단도 크게 줄어듭니다. " +
                    "비밀번호는 앱이 저장하지 않고 각 서비스 공식 페이지에 직접 입력합니다. " +
                    "자동화 도구로 세션을 쓰는 것은 약관 위반이라 차단 위험이 있으니 별도 계정을 권장합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SettingRow(
                title = "인스타는 익명으로 먼저 시도",
                subtitle = "로그인 세션 사용을 최소화합니다"
            ) {
                Switch(
                    checked = igAnonFirst,
                    onCheckedChange = {
                        igAnonFirst = it
                        vm.setInstagramAnonymousFirst(it)
                    }
                )
            }
            Text(
                "인스타는 세션을 쓸 때마다 계정에 흔적이 남고 \"의심스러운 로그인\" 경고가 올 수 있습니다. " +
                    "켜두면 익명으로 되는 게시물은 익명으로 받고, 안 되는 것(스토리 등)만 로그인으로 넘어갑니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            SettingRow(
                title = "cookies.txt 직접 등록 (고급)",
                subtitle = cookies ?: "미등록 — 보통은 위 로그인으로 충분합니다"
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
                "PC 브라우저 확장(Get cookies.txt 등)으로 내보낸 파일을 직접 넣는 방식입니다. " +
                    "위 로그인이 막히거나 이미 쿠키 파일이 있을 때만 쓰세요. 본인 계정 정보이므로 외부에 공유하지 마세요.",
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

            Text("다운로드", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            SettingRow(
                title = "실패 시 자동 재시도",
                subtitle = "요청 제한이나 연결 오류는 15초 · 60초 · 180초 간격으로 다시 시도합니다"
            ) {
                Switch(
                    checked = autoRetry,
                    onCheckedChange = {
                        autoRetry = it
                        vm.setAutoRetry(it)
                    }
                )
            }

            Text("동시 다운로드", style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(1, 2, 3).forEachIndexed { index, count ->
                    SegmentedButton(
                        selected = concurrent == count,
                        onClick = {
                            concurrent = count
                            vm.setMaxConcurrent(count)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, 3)
                    ) {
                        Text(count.toString() + "개")
                    }
                }
            }
            Text(
                "많이 올리면 빨라지지만, 인스타·틱톡은 요청 수에 민감해 차단에 더 빨리 걸립니다. " +
                    "바꾼 값은 다음 다운로드부터 적용됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            Text("저장 폴더", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            SettingRow(
                title = albumName,
                subtitle = MediaImporter.savedLocationText(context)
            ) {
                TextButton(onClick = { showAlbumDialog = true }) { Text("변경") }
            }

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
