package com.feiji.bulletnotes.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feiji.bulletnotes.data.BulletType
import com.feiji.bulletnotes.data.KeywordHintEngine
import com.feiji.bulletnotes.data.KeywordHintIndex
import com.feiji.bulletnotes.data.NoteEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BulletNotesScreen(viewModel: NoteViewModel, onToggleTheme: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // 新建笔记期间的常驻焦点接力点，防止旧输入框失焦时键盘关闭。
    val enterFocusBridge = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val selectedDate by viewModel.selectedDate.collectAsState()
    val isToday by viewModel.isToday.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val noteDates by viewModel.noteDates.collectAsState()
    var isSearchMode by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    // 单击搜索图标限定搜索方框或圆点笔记，长按搜索图标限定搜索五角星笔记。
    val squareOrDotSearchFilter = remember {
        setOf(BulletType.SQUARE, BulletType.CHECKED, BulletType.BULLET_DOT)
    }
    val starSearchFilter = remember { setOf(BulletType.STAR) }
    var searchBulletFilter by remember { mutableStateOf(squareOrDotSearchFilter) }
    // 今天日期浏览模式下默认只显示最前面的 1 条五角星笔记，其余折叠并提供极淡展开入口。
    val maxCollapsedTodayStars = 1
    var showAllTodayStars by remember { mutableStateOf(false) }
    var sessionExemptStarIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val trashNotes by viewModel.trashNotes.collectAsState()
    val keywordHintIndex by viewModel.keywordHintIndex.collectAsState()
    val editingNoteId by viewModel.editingNoteId.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()

    var showTrashDialog by remember { mutableStateOf(false) }
    var trashBounds by remember { mutableStateOf<Rect?>(null) }
    var hoveringTrash by remember { mutableStateOf(false) }
    var showBackupPicker by remember { mutableStateOf(false) }
    var showWebDavSettings by remember { mutableStateOf(false) }
    var showLanServerDialog by remember { mutableStateOf(false) }
    var backupToRestore by remember {
        mutableStateOf<com.feiji.bulletnotes.data.BackupManager.BackupFile?>(null)
    }
    val backupFiles by viewModel.backupFiles.collectAsState()
    val webDavConfig by viewModel.webDavConfig.collectAsState()
    val showWebDavAlert by viewModel.showWebDavAlert.collectAsState()
    val lanServerState by viewModel.lanServerState.collectAsState()
    val pendingEncryptedRestore by viewModel.pendingEncryptedRestore.collectAsState()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 无论是否授予通知权限，都启动局域网直连服务（授予时通知栏会显示停止按钮）
        viewModel.startLanServer()
    }
    val starReviewUndoEvent by viewModel.starReviewUndoEvent.collectAsState()
    var noteToChangeDate by remember { mutableStateOf<NoteEntity?>(null) }
    var noteToReview by remember { mutableStateOf<NoteEntity?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    // 多行笔记首次点击仅展开浏览（不进入编辑），再次点击才进入编辑模式。
    var expandedNoteId by remember { mutableStateOf<Long?>(null) }
    // 防止连续快速点击新增提示条时重复插入多条空白笔记。
    var isCreatingFromHint by remember { mutableStateOf(false) }

    val todayStarProjection = remember(
        isSearchMode,
        isToday,
        searchResults,
        notes,
        showAllTodayStars,
        sessionExemptStarIds,
        editingNoteId,
        expandedNoteId
    ) {
        if (isSearchMode) {
            TodayStarProjection(
                visibleNotes = searchResults,
                hiddenStarCount = 0,
                totalStarCount = 0,
                foldAnchorNoteId = null
            )
        } else if (!isToday) {
            TodayStarProjection(
                visibleNotes = notes,
                hiddenStarCount = 0,
                totalStarCount = 0,
                foldAnchorNoteId = null
            )
        } else {
            val totalStars = notes.count { it.bulletType == BulletType.STAR }
            if (showAllTodayStars) {
                TodayStarProjection(
                    visibleNotes = notes,
                    hiddenStarCount = 0,
                    totalStarCount = totalStars,
                    foldAnchorNoteId = notes.lastOrNull { it.bulletType == BulletType.STAR }?.id
                )
            } else {
                var shownQuotaStars = 0
                var hiddenStars = 0
                var lastQuotaStarId: Long? = null
                val filtered = ArrayList<NoteEntity>(notes.size)
                for (item in notes) {
                    if (item.bulletType != BulletType.STAR) {
                        filtered.add(item)
                    } else if (item.id in sessionExemptStarIds) {
                        filtered.add(item)
                    } else if (shownQuotaStars < maxCollapsedTodayStars) {
                        shownQuotaStars++
                        lastQuotaStarId = item.id
                        filtered.add(item)
                    } else if (item.id == editingNoteId || item.id == expandedNoteId) {
                        filtered.add(item)
                    } else {
                        hiddenStars++
                    }
                }
                TodayStarProjection(
                    visibleNotes = filtered,
                    hiddenStarCount = hiddenStars,
                    totalStarCount = totalStars,
                    foldAnchorNoteId = lastQuotaStarId ?: filtered.lastOrNull { it.bulletType == BulletType.STAR }?.id
                )
            }
        }
    }
    val visibleNotes = todayStarProjection.visibleNotes
    val hiddenTodayStarCount = todayStarProjection.hiddenStarCount
    val showTodayStarFoldToggle = !isSearchMode && isToday && (
        (!showAllTodayStars && hiddenTodayStarCount > 0) ||
            (showAllTodayStars && todayStarProjection.totalStarCount > maxCollapsedTodayStars)
        )

    LaunchedEffect(selectedDate, isSearchMode) {
        expandedNoteId = null
        isCreatingFromHint = false
        showAllTodayStars = false
        sessionExemptStarIds = emptySet()
    }

    LaunchedEffect(todayStarProjection.totalStarCount, isToday, isSearchMode) {
        if (showAllTodayStars && (!isToday || isSearchMode || todayStarProjection.totalStarCount <= maxCollapsedTodayStars)) {
            showAllTodayStars = false
        }
    }

    LaunchedEffect(editingNoteId, notes.size) {
        isCreatingFromHint = false
    }

    // 点击当前编辑项之外的区域时，统一退出编辑并收起展开浏览态，同时触发已有的空笔记清理逻辑。
    fun closeEditor() {
        if (editingNoteId != null) {
            viewModel.setEditingNoteId(null)
            focusManager.clearFocus()
        }
        expandedNoteId = null
    }

    val densityForNewNoteScroll = LocalDensity.current
    val listState = rememberLazyListState()
    var listViewportBounds by remember { mutableStateOf<Rect?>(null) }

    // 拖动期间只更新内存中的显示顺序；松手后再保存到数据库。
    val displayNotes = remember { mutableStateListOf<NoteEntity>() }
    var draggingNoteId by remember { mutableStateOf<Long?>(null) }
    var draggingOffset by remember { mutableStateOf(0f) }
    var dragAnchorTop by remember { mutableStateOf(0f) }
    var dragStartIndex by remember { mutableStateOf(-1) }

    // 松手后等待数据库排序结果同步，避免旧 notes 顺序短暂覆盖拖动后的显示顺序。
    var pendingReorderPreDragIds by remember { mutableStateOf<List<Long>?>(null) }

    // 仅回车新增笔记时触发精准滚动，不影响点击其他笔记。
    var pendingNewNoteScrollFromId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(visibleNotes, draggingNoteId, pendingReorderPreDragIds, isSearchMode) {
        if (draggingNoteId == null) {
            val preDragOrder = pendingReorderPreDragIds

            // reorderNoteRelativeTo 是异步写入数据库的；在 Flow 发出新顺序前，
            // 不要用拖动前的旧列表覆盖已经完成的本地拖动顺序。
            if (preDragOrder != null && visibleNotes.map { it.id } == preDragOrder) {
                return@LaunchedEffect
            }

            if (displayNotes.map { it.id } != visibleNotes.map { it.id }) {
                displayNotes.clear()
                displayNotes.addAll(visibleNotes)
            } else {
                visibleNotes.forEachIndexed { index, item ->
                    if (displayNotes[index] != item) {
                        displayNotes[index] = item
                    }
                }
            }

            if (preDragOrder != null) {
                pendingReorderPreDragIds = null
            }
        }
    }

    // 新笔记进入列表后，确保整行位于键盘上方。
    // 不等待键盘重新弹出（键盘已由常驻输入框保持开启）。
    LaunchedEffect(editingNoteId, displayNotes.size, pendingNewNoteScrollFromId) {
        val previousId = pendingNewNoteScrollFromId ?: return@LaunchedEffect
        val targetId = editingNoteId ?: return@LaunchedEffect

        if (targetId == previousId) return@LaunchedEffect

        val targetIndex = displayNotes.indexOfFirst { it.id == targetId }
        if (targetIndex < 0) return@LaunchedEffect

        // 等待新条目布局完成，再按实际遮挡量移动，避免跳到列表顶部。
        repeat(5) {
            androidx.compose.runtime.withFrameNanos { }

            val layout = listState.layoutInfo
            val target = layout.visibleItemsInfo.firstOrNull { it.key == targetId }
            val clearance = with(densityForNewNoteScroll) { 12.dp.toPx() }

            if (target != null) {
                val overlap =
                    target.offset + target.size + clearance - layout.viewportEndOffset

                if (overlap > 0f) {
                    listState.animateScrollBy(overlap)
                }

                pendingNewNoteScrollFromId = null
                return@LaunchedEffect
            }

            // 新行恰好落在屏幕外时，先只向下滚动约一行，再重新测量。
            val estimatedRow =
                layout.visibleItemsInfo.lastOrNull()?.size?.toFloat() ?: 56f

            listState.animateScrollBy(estimatedRow + clearance)
        }

        pendingNewNoteScrollFromId = null
    }

    // 进入搜索界面后，立即让搜索框获取焦点并弹出键盘进入编辑状态。
    LaunchedEffect(isSearchMode) {
        if (isSearchMode) {
            withFrameNanos { }
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // 搜索词变化后稍作等待再检索，避免每输入一个字符都立即读取全库。
    LaunchedEffect(isSearchMode, searchText, searchBulletFilter) {
        if (isSearchMode) {
            delay(180)
            viewModel.searchNotes(searchText, searchBulletFilter)
        }
    }

    // 显示提示 Toast
    LaunchedEffect(userMessage) {
        userMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearUserMessage()
        }
    }

    // 五角星知识卡片复习排期后的极简可撤销轻提示
    LaunchedEffect(starReviewUndoEvent?.eventId) {
        val event = starReviewUndoEvent ?: return@LaunchedEffect
        val parsedTarget = runCatching { LocalDate.parse(event.targetDateString) }.getOrNull()
        val displayTarget = parsedTarget?.format(viewModel.displayDateFormatter) ?: event.targetDateString
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = "已移至 $displayTarget 复习 (+${event.intervalDays}天)",
            actionLabel = "撤销",
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoStarReview(event.previousNote)
        }
        viewModel.clearStarReviewUndoEvent(event.eventId)
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.background,
                    shadowElevation = 2.dp,
                    tonalElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = data.visuals.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        data.visuals.actionLabel?.let { actionLabel ->
                            TextButton(onClick = { data.performAction() }) {
                                Text(
                                    text = actionLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .clickable(enabled = editingNoteId != null || expandedNoteId != null) {
                        closeEditor()
                    }
                    .statusBarsPadding()
                    .padding(top = 4.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSearchMode) {
                        val indicatorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        Surface(
                            modifier = Modifier
                                .width(44.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    closeEditor()
                                    focusManager.clearFocus()
                                    isSearchMode = false
                                    pendingReorderPreDragIds = null
                                    searchText = ""
                                    viewModel.searchNotes("", null)
                                    viewModel.returnToToday()
                                },
                            color = Color.Transparent
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "返回",
                                    maxLines = 1,
                                    softWrap = false,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        BasicTextField(
                            value = searchText,
                            onValueChange = { searchText = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                                .focusRequester(searchFocusRequester)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                            decorationBox = { innerTextField ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        innerTextField()
                                    }
                                    if (searchText.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .clickable {
                                                    searchText = ""
                                                    viewModel.searchNotes("", searchBulletFilter)
                                                    searchFocusRequester.requestFocus()
                                                    keyboardController?.show()
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "清空搜索",
                                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        )
                        // 统一固定 8dp 间距 + 28dp 符号槽位，与右侧 40dp 垃圾箱图标（含 8dp 左内边距）左右视觉间距完全对称。
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier.size(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (searchBulletFilter == starSearchFilter) {
                                Canvas(modifier = Modifier.size(18.dp)) {
                                    val scale = size.width / 18f
                                    val starPath = Path().apply {
                                        moveTo(9f * scale, 0f)
                                        lineTo(11.1f * scale, 6.2f * scale)
                                        lineTo(17.7f * scale, 6.5f * scale)
                                        lineTo(12.6f * scale, 10.6f * scale)
                                        lineTo(14.4f * scale, 17.2f * scale)
                                        lineTo(9f * scale, 13.4f * scale)
                                        lineTo(3.6f * scale, 17.2f * scale)
                                        lineTo(5.4f * scale, 10.6f * scale)
                                        lineTo(0.3f * scale, 6.5f * scale)
                                        lineTo(6.9f * scale, 6.2f * scale)
                                        close()
                                    }
                                    drawPath(path = starPath, color = indicatorColor)
                                }
                            } else {
                                // 方框内套一个实心圆点，表示当前搜索范围为方框或圆点笔记。
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .border(
                                            1.5.dp,
                                            indicatorColor,
                                            RoundedCornerShape(3.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(indicatorColor, CircleShape)
                                    )
                                }
                            }
                        }
                    } else {
                        // 固定宽度（44dp），确保“返回”与搜索图标切换时右侧日期标签位置完全不动。
                        Surface(
                            modifier = Modifier
                                .width(44.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .combinedClickable(
                                    onClick = {
                                        closeEditor()
                                        pendingReorderPreDragIds = null
                                        if (!isToday) {
                                            viewModel.returnToToday()
                                        } else {
                                            searchBulletFilter = squareOrDotSearchFilter
                                            isSearchMode = true
                                            searchText = ""
                                            viewModel.searchNotes("", squareOrDotSearchFilter)
                                        }
                                    },
                                    onLongClick = {
                                        if (isToday) {
                                            closeEditor()
                                            pendingReorderPreDragIds = null
                                            searchBulletFilter = starSearchFilter
                                            isSearchMode = true
                                            searchText = ""
                                            viewModel.searchNotes("", starSearchFilter)
                                        }
                                    }
                                ),
                            color = Color.Transparent
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!isToday) {
                                    Text(
                                        text = "返回",
                                        maxLines = 1,
                                        softWrap = false,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                } else {
                                    Icon(
                                        Icons.Outlined.Search,
                                        contentDescription = "搜索笔记",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .combinedClickable(
                                    onClick = {
                                        closeEditor()
                                        noteToChangeDate = null
                                        showDatePicker = true
                                    },
                                    onLongClick = {
                                        closeEditor()
                                        pendingReorderPreDragIds = null
                                        viewModel.jumpToEarliestStarNoteDate()
                                    }
                                ),
                            color = Color.Transparent
                        ) {
                            Text(
                                text = selectedDate.format(viewModel.displayDateFormatter) +
                                    " " + "一二三四五六日"[selectedDate.dayOfWeek.value - 1],
                                modifier = Modifier.padding(vertical = 2.dp, horizontal = 4.dp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    // 仅当开启 WebDAV 且 21:00 后仍未成功完成今日备份时，在垃圾箱旁显示感叹号提示。
                    if (showWebDavAlert) {
                        val alertColor = MaterialTheme.colorScheme.onSurface
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable {
                                    closeEditor()
                                    viewModel.triggerSilentWebDavBackup()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(18.dp)) {
                                val cx = size.width / 2f
                                drawLine(
                                    color = alertColor,
                                    start = Offset(cx, size.height * 0.16f),
                                    end = Offset(cx, size.height * 0.60f),
                                    strokeWidth = size.width * 0.14f,
                                    cap = StrokeCap.Round
                                )
                                drawCircle(
                                    color = alertColor,
                                    radius = size.width * 0.08f,
                                    center = Offset(cx, size.height * 0.80f)
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .onGloballyPositioned {
                                trashBounds = it.boundsInRoot()
                            }
                            .clip(CircleShape)
                            .background(
                                if (hoveringTrash) {
                                    MaterialTheme.colorScheme.background
                                } else {
                                    Color.Transparent
                                }
                            )
                            .combinedClickable(
                                onClick = {
                                    closeEditor()
                                    showTrashDialog = true
                                },
                                onLongClick = {
                                    closeEditor()
                                    showBackupPicker = true
                                    viewModel.loadBackupFiles()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "回收站",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .pointerInput(selectedDate, editingNoteId, isSearchMode) {
                    if (editingNoteId == null && !isSearchMode) {
                        var totalHorizontalDrag = 0f
                        val switchThreshold =
                            with(densityForNewNoteScroll) { 72.dp.toPx() }

                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                totalHorizontalDrag += dragAmount
                            },
                            onDragEnd = {
                                when {
                                    totalHorizontalDrag >= switchThreshold -> {
                                        viewModel.selectDate(selectedDate.minusDays(1))
                                    }

                                    totalHorizontalDrag <= -switchThreshold -> {
                                        viewModel.selectDate(selectedDate.plusDays(1))
                                    }
                                }
                            },
                            onDragCancel = {
                                totalHorizontalDrag = 0f
                            }
                        )
                    }
                }
                .clickable(enabled = editingNoteId != null || expandedNoteId != null) {
                    closeEditor()
                }
        ) {

            // 始终留在组合树中的极小输入框：只在回车新增笔记时暂时接管焦点。
            BasicTextField(
                value = "",
                onValueChange = {},
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(enterFocusBridge),
                keyboardOptions = KeyboardOptions.Default
            )

            if (isSearchMode && searchText.isBlank()) {
                // 搜索界面未输入关键词时保持空白，不显示日期笔记。
            } else if (displayNotes.isEmpty()) {
                if (!isSearchMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                viewModel.insertNoteAfter(null, "")
                            }
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .imePadding()
                        .onGloballyPositioned { coordinates ->
                            listViewportBounds = coordinates.boundsInRoot()
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    displayNotes.forEachIndexed { index, note ->
                        item(key = note.id) {
                            if (isSearchMode && (index == 0 || displayNotes[index - 1].dateString != note.dateString)) {
                                val resultDate = runCatching { LocalDate.parse(note.dateString) }.getOrNull()
                                if (resultDate != null) {
                                    Text(
                                        text = resultDate.format(viewModel.displayDateFormatter) +
                                            " " + "一二三四五六日"[resultDate.dayOfWeek.value - 1],
                                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            val isEditing = (editingNoteId == note.id)
                            val isExpanded = (expandedNoteId == note.id && !isEditing)

                            BulletNoteRow(
                                modifier = Modifier
                                    .then(
                                        if (draggingNoteId == note.id) {
                                            Modifier
                                        } else {
                                            Modifier.animateItem(
                                                fadeInSpec = null,
                                                fadeOutSpec = null,
                                                placementSpec = androidx.compose.animation.core.spring()
                                            )
                                        }
                                    )
                                    .zIndex(
                                        if (draggingNoteId == note.id) 1f else 0f
                                    )
                                    .graphicsLayer {
                                        if (draggingNoteId == note.id) {
                                            val currentTop = listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.key == note.id }
                                                ?.offset?.toFloat()
                                                ?: dragAnchorTop

                                            translationY =
                                                dragAnchorTop + draggingOffset - currentTop

                                            shadowElevation = 12.dp.toPx()
                                        }
                                    },
                                note = note,
                                isEditing = isEditing,
                                isExpanded = isExpanded,
                                listState = listState,
                                listViewportBounds = listViewportBounds,
                                keywordHintIndex = keywordHintIndex,
                                displayDateFormatter = viewModel.displayDateFormatter,
                                onBulletClick = {
                                    if (editingNoteId != note.id) {
                                        if (editingNoteId != null) {
                                            viewModel.setEditingNoteId(null)
                                            focusManager.clearFocus()
                                        }
                                        if (expandedNoteId != note.id) {
                                            expandedNoteId = null
                                        }
                                    }
                                    if (!isSearchMode && isToday && note.bulletType == BulletType.BULLET_DOT) {
                                        sessionExemptStarIds = sessionExemptStarIds + note.id
                                    }
                                    viewModel.cycleBulletType(note)
                                },
                                onBulletLongClick = {
                                    closeEditor()
                                    if (note.bulletType == BulletType.STAR) {
                                        noteToReview = note
                                    } else {
                                        noteToChangeDate = note
                                        showDatePicker = true
                                    }
                                },
                                onContentClick = { isCollapsedMultiLine ->
                                    if (editingNoteId == note.id) {
                                        closeEditor()
                                    } else if (isCollapsedMultiLine && expandedNoteId != note.id) {
                                        closeEditor()
                                        focusManager.clearFocus()
                                        expandedNoteId = note.id
                                    } else {
                                        expandedNoteId = null
                                        viewModel.setEditingNoteId(note.id)
                                    }
                                },
                                onDragStart = {
                                    if (!isSearchMode) {
                                        hoveringTrash = false
                                        closeEditor()

                                        val info = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key == note.id }

                                        if (info != null) {
                                            dragAnchorTop = info.offset.toFloat()
                                            draggingOffset = 0f
                                            dragStartIndex =
                                                visibleNotes.indexOfFirst { it.id == note.id }
                                            draggingNoteId = note.id
                                        }
                                    }
                                },
                                onDragDelta = dragDelta@{ delta, pointerRoot ->
                                    hoveringTrash =
                                        trashBounds?.contains(pointerRoot) == true

                                    if (draggingNoteId == note.id) {
                                        draggingOffset += delta

                                        if (hoveringTrash) {
                                            return@dragDelta
                                        }

                                        val current =
                                            displayNotes.indexOfFirst { it.id == note.id }

                                        val visible = listState.layoutInfo.visibleItemsInfo
                                        val dragged =
                                            visible.firstOrNull { it.key == note.id }

                                        if (current >= 0 && dragged != null) {
                                            val visualCenter =
                                                dragAnchorTop + draggingOffset + dragged.size / 2f

                                            val direction = when {
                                                delta > 0f -> 1
                                                delta < 0f -> -1
                                                else -> 0
                                            }

                                            val target = current + direction

                                            if (target in displayNotes.indices) {
                                                val neighborId = displayNotes[target].id
                                                val neighbor =
                                                    visible.firstOrNull { it.key == neighborId }

                                                if (neighbor != null) {
                                                    val neighborCenter =
                                                        neighbor.offset + neighbor.size / 2f

                                                    val crossed = if (direction > 0) {
                                                        visualCenter > neighborCenter
                                                    } else {
                                                        visualCenter < neighborCenter
                                                    }

                                                    if (crossed) {
                                                        val moved =
                                                            displayNotes.removeAt(current)
                                                        displayNotes.add(target, moved)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                onDragFinish = { pointerRoot ->
                                    val deleteOnDrop =
                                        pointerRoot != null &&
                                            trashBounds?.contains(pointerRoot) == true

                                    if (draggingNoteId == note.id && deleteOnDrop) {
                                        viewModel.moveToTrash(note.id)
                                    } else if (draggingNoteId == note.id && !isSearchMode) {
                                        val endIndex =
                                            displayNotes.indexOfFirst { it.id == note.id }

                                        if (
                                            dragStartIndex >= 0 &&
                                            endIndex >= 0 &&
                                            dragStartIndex != endIndex
                                        ) {
                                            pendingReorderPreDragIds =
                                                visibleNotes.map { it.id }

                                            val anchorNoteId: Long?
                                            val insertBeforeAnchor: Boolean
                                            if (endIndex < dragStartIndex) {
                                                anchorNoteId = displayNotes.getOrNull(endIndex + 1)?.id
                                                insertBeforeAnchor = true
                                            } else {
                                                anchorNoteId = displayNotes.getOrNull(endIndex - 1)?.id
                                                insertBeforeAnchor = false
                                            }

                                            viewModel.reorderNoteRelativeTo(
                                                movedNoteId = note.id,
                                                anchorNoteId = anchorNoteId,
                                                insertBeforeAnchor = insertBeforeAnchor
                                            )
                                        }
                                    }

                                    hoveringTrash = false
                                    draggingNoteId = null
                                    draggingOffset = 0f
                                    dragAnchorTop = 0f
                                    dragStartIndex = -1
                                },
                                onContentChange = { newText ->
                                    viewModel.updateNoteContent(note.id, newText)
                                },
                                onEnterPressed = {
                                    pendingNewNoteScrollFromId = note.id
                                    enterFocusBridge.requestFocus()
                                    viewModel.insertNoteAfter(note, "")
                                },
                                onHintBulletClick = { hintNote ->
                                    viewModel.cycleBulletType(hintNote)
                                },
                                onHintBulletLongClick = { hintNote ->
                                    if (hintNote.bulletType == BulletType.STAR) {
                                        noteToReview = hintNote
                                    } else {
                                        noteToChangeDate = hintNote
                                        showDatePicker = true
                                    }
                                },
                                onHintContentChange = { hintNoteId, newText ->
                                    viewModel.updateNoteContent(hintNoteId, newText)
                                },
                                onHintEditFinished = { hintNoteId ->
                                    viewModel.finishInlineNoteEdit(hintNoteId)
                                }
                            )
                        }

                        if (showTodayStarFoldToggle && note.id == todayStarProjection.foldAnchorNoteId) {
                            item(key = "today_star_fold_toggle") {
                                TodayStarFoldToggleRow(
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = null,
                                        fadeOutSpec = null,
                                        placementSpec = androidx.compose.animation.core.spring()
                                    ),
                                    expanded = showAllTodayStars,
                                    hiddenCount = hiddenTodayStarCount,
                                    onClick = {
                                        closeEditor()
                                        showAllTodayStars = !showAllTodayStars
                                    }
                                )
                            }
                        }
                    }

                    // 日期浏览模式且所有笔记均未进入编辑状态时，在最后一条笔记下方显示新增笔记提示条。
                    if (!isSearchMode && editingNoteId == null) {
                        item(key = "add_note_hint_bar") {
                            AddNoteHintRow(
                                onClick = {
                                    if (!isCreatingFromHint) {
                                        isCreatingFromHint = true
                                        expandedNoteId = null
                                        viewModel.insertNoteAfter(null, "")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // 回收站弹窗
    if (showTrashDialog) {
        TrashBinDialog(
            trashNotes = trashNotes,
            onDismiss = {
                showTrashDialog = false
            },
            onRestore = { id ->
                viewModel.restoreFromTrash(id)
            }
        )
    }

    // 日期选择弹窗
    if (showDatePicker) {
        DatePickerHelper(
            initialDate = noteToChangeDate?.dateString
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: selectedDate,
            noteDates = noteDates,
            onDatePicked = { pickedDate ->
                val note = noteToChangeDate

                if (note != null) {
                    viewModel.changeNoteDate(note, pickedDate)
                } else {
                    viewModel.selectDate(pickedDate)
                }

                noteToChangeDate = null
                showDatePicker = false
            },
            onDismiss = {
                noteToChangeDate = null
                showDatePicker = false
            }
        )
    }

    // 五角星知识卡片熟悉度选择弹窗
    noteToReview?.let { targetNote ->
        StarReviewDialog(
            note = targetNote,
            onSelectFamiliarity = { familiarity ->
                viewModel.reviewStarNote(targetNote, familiarity)
                noteToReview = null
            },
            onDismiss = {
                noteToReview = null
            }
        )
    }

    // 备份列表
    if (showBackupPicker) {
        AlertDialog(
            onDismissRequest = {
                showBackupPicker = false
            },
            title = {
                Text("选择要恢复的备份")
            },
            text = {
                Column {
                    if (backupFiles.isEmpty()) {
                        Text("没有找到飞记备份文件，或正在读取。")
                    } else {
                        backupFiles.forEach { file ->
                            TextButton(
                                onClick = {
                                    backupToRestore = file
                                    showBackupPicker = false
                                }
                            ) {
                                Text(file.name)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧入口：「电脑直连」+「备份设置」
                    TextButton(
                        onClick = {
                            showBackupPicker = false
                            showLanServerDialog = true
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (lanServerState.running) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF2E7D32))
                                )
                            }
                            Text(
                                text = "电脑直连",
                                maxLines = 1,
                                softWrap = false,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            showBackupPicker = false
                            showWebDavSettings = true
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "备份设置",
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(
                        onClick = {
                            showBackupPicker = false
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "取消",
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    TextButton(
                        onClick = {
                            viewModel.loadBackupFiles()
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "刷新",
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        )
    }

    // 局域网电脑/安卓设备直连控制弹窗
    if (showLanServerDialog) {
        val formattedPin = if (lanServerState.pin.length == 6) {
            lanServerState.pin.substring(0, 3) + " " + lanServerState.pin.substring(3)
        } else {
            lanServerState.pin
        }

        AlertDialog(
            onDismissRequest = {
                showLanServerDialog = false
            },
            title = {
                Text(
                    text = "局域网电脑直连",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!lanServerState.running) {
                        Text(
                            text = "开启后，同一 Wi-Fi 或热点下的电脑、平板或其他手机，可直接用浏览器读写本机笔记。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "· 锁屏或切到后台仍保持连接，通知栏可随时停止\n· 每次随机生成 6 位配对码，无操作 30 分钟自动关闭",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                        lanServerState.errorMessage?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        Text(
                            text = "在同局域网浏览器中打开：",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                        val displayUrls = if (lanServerState.urls.isNotEmpty()) {
                            lanServerState.urls
                        } else {
                            listOf("http://127.0.0.1:${lanServerState.port}")
                        }
                        displayUrls.forEach { url ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = url,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cm?.setPrimaryClip(ClipData.newPlainText("FeiNote LAN URL", url))
                                        Toast.makeText(context, "已复制访问地址", Toast.LENGTH_SHORT).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("复制", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        if (lanServerState.urls.isEmpty()) {
                            Text(
                                text = "未检测到 Wi-Fi/热点局域网 IP，请连接 Wi-Fi 或开启热点（也支持 USB 线 adb forward 访问本机地址）。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "配对码",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                )
                                Text(
                                    text = formattedPin,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            TextButton(
                                onClick = {
                                    viewModel.refreshLanPin()
                                    Toast.makeText(context, "配对码已刷新，旧连接已断开", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("换一个", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        val statusDetail = buildString {
                            if (lanServerState.activeSessions > 0 && !lanServerState.lastClientIp.isNullOrBlank()) {
                                append("已连接（${lanServerState.activeSessions} 台设备，最近：${lanServerState.lastClientIp}）")
                            } else {
                                append("等待浏览器连接")
                            }
                            append("\n锁屏切后台持续运行 · 无操作 30 分钟自动关闭")
                        }
                        Text(
                            text = statusDetail,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                }
            },
            confirmButton = {
                if (!lanServerState.running) {
                    TextButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.startLanServer()
                            }
                        }
                    ) {
                        Text("开启直连")
                    }
                } else {
                    TextButton(
                        onClick = {
                            viewModel.stopLanServer()
                        }
                    ) {
                        Text("停止直连", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showLanServerDialog = false
                    }
                ) {
                    Text(if (lanServerState.running) "后台保持" else "关闭")
                }
            }
        )
    }

    // 备份设置弹窗（WebDAV 同步备份 + 加密备份 + 局域网自动同步）
    if (showWebDavSettings) {
        var serverUrlInput by remember(showWebDavSettings) { mutableStateOf(webDavConfig.serverUrl) }
        var usernameInput by remember(showWebDavSettings) { mutableStateOf(webDavConfig.username) }
        var passwordInput by remember(showWebDavSettings) { mutableStateOf(webDavConfig.password) }
        var passwordVisible by remember(showWebDavSettings) { mutableStateOf(false) }
        var encryptionPasswordInput by remember(showWebDavSettings) {
            mutableStateOf(webDavConfig.encryptionPassword)
        }
        var encryptionPasswordVisible by remember(showWebDavSettings) { mutableStateOf(false) }
        var confirmEncryptionPasswordInput by remember(showWebDavSettings) {
            mutableStateOf(webDavConfig.encryptionPassword)
        }
        var confirmEncryptionPasswordVisible by remember(showWebDavSettings) { mutableStateOf(false) }
        var encryptionPasswordMismatchError by remember(showWebDavSettings) { mutableStateOf(false) }
        var wifiAutoSyncInput by remember(showWebDavSettings) {
            mutableStateOf(webDavConfig.wifiAutoSyncEnabled)
        }

        val isEncryptionPasswordMismatch = encryptionPasswordInput.isNotEmpty() &&
            encryptionPasswordInput != confirmEncryptionPasswordInput
        val showMismatchError = encryptionPasswordMismatchError ||
            (confirmEncryptionPasswordInput.isNotEmpty() && isEncryptionPasswordMismatch)

        val fieldTextStyle = MaterialTheme.typography.bodyMedium
        val fieldShape = RoundedCornerShape(8.dp)
        val dividerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        val dialogBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)

        Dialog(
            onDismissRequest = { showWebDavSettings = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.90f)
                    .widthIn(max = 360.dp)
                    .padding(vertical = 16.dp)
                    .border(
                        width = 1.dp,
                        color = dialogBorderColor,
                        shape = RoundedCornerShape(16.dp)
                    ),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "WebDAV同步备份",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            OutlinedTextField(
                                value = serverUrlInput,
                                onValueChange = { serverUrlInput = it },
                                label = { Text("地址", style = MaterialTheme.typography.labelMedium) },
                                textStyle = fieldTextStyle,
                                shape = fieldShape,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = usernameInput,
                                onValueChange = { usernameInput = it },
                                label = { Text("账号", style = MaterialTheme.typography.labelMedium) },
                                textStyle = fieldTextStyle,
                                shape = fieldShape,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("应用密码(留空则不上传)", style = MaterialTheme.typography.labelMedium) },
                                textStyle = fieldTextStyle,
                                shape = fieldShape,
                                singleLine = true,
                                visualTransformation = if (passwordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    TextButton(
                                        onClick = { passwordVisible = !passwordVisible },
                                        modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            text = if (passwordVisible) "隐藏" else "显示",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(dividerColor)
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "加密备份",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            OutlinedTextField(
                                value = encryptionPasswordInput,
                                onValueChange = {
                                    encryptionPasswordInput = it
                                    if (it.isEmpty()) {
                                        confirmEncryptionPasswordInput = ""
                                        wifiAutoSyncInput = false
                                    }
                                    encryptionPasswordMismatchError = false
                                },
                                label = { Text("加密密码(留空则不加密)", style = MaterialTheme.typography.labelMedium) },
                                textStyle = fieldTextStyle,
                                shape = fieldShape,
                                singleLine = true,
                                visualTransformation = if (encryptionPasswordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    TextButton(
                                        onClick = { encryptionPasswordVisible = !encryptionPasswordVisible },
                                        modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            text = if (encryptionPasswordVisible) "隐藏" else "显示",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = confirmEncryptionPasswordInput,
                                onValueChange = {
                                    confirmEncryptionPasswordInput = it
                                    encryptionPasswordMismatchError = false
                                },
                                label = { Text("确认加密密码", style = MaterialTheme.typography.labelMedium) },
                                textStyle = fieldTextStyle,
                                shape = fieldShape,
                                singleLine = true,
                                isError = showMismatchError,
                                visualTransformation = if (confirmEncryptionPasswordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    TextButton(
                                        onClick = { confirmEncryptionPasswordVisible = !confirmEncryptionPasswordVisible },
                                        modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            text = if (confirmEncryptionPasswordVisible) "隐藏" else "显示",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (showMismatchError) {
                                Text(
                                    text = "两次输入的加密密码不一致",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(dividerColor)
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val canEnableWifiSync = encryptionPasswordInput.isNotEmpty()
                            val effectiveWifiSyncChecked = wifiAutoSyncInput && canEnableWifiSync
                            Text(
                                text = "局域网自动同步",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 34.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = canEnableWifiSync) {
                                        wifiAutoSyncInput = !effectiveWifiSyncChecked
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = effectiveWifiSyncChecked,
                                    onCheckedChange = { checked ->
                                        wifiAutoSyncInput = checked
                                    },
                                    enabled = canEnableWifiSync,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "同一WiFi下同时打开时同步",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(
                                        alpha = if (canEnableWifiSync) 1f else 0.45f
                                    )
                                )
                            }
                            Text(
                                text = if (canEnableWifiSync) {
                                    "复用上方加密密码，两台设备密码一致时自动加密同步笔记与设置"
                                } else {
                                    "需先在上方设置加密密码，两台设备密码一致即可自动同步笔记与设置"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { showWebDavSettings = false },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "取消",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(
                            onClick = {
                                if (isEncryptionPasswordMismatch) {
                                    encryptionPasswordMismatchError = true
                                    return@TextButton
                                }
                                viewModel.saveWebDavSettings(
                                    serverUrl = serverUrlInput,
                                    username = usernameInput,
                                    password = passwordInput,
                                    encryptionPassword = encryptionPasswordInput,
                                    wifiAutoSyncEnabled = wifiAutoSyncInput && encryptionPasswordInput.isNotEmpty()
                                )
                                showWebDavSettings = false
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "保存",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

    backupToRestore?.let { file ->
        AlertDialog(
            onDismissRequest = {
                backupToRestore = null
            },
            title = {
                Text("确认恢复备份")
            },
            text = {
                Text(
                    "将使用「${file.name}」覆盖软件中现有的全部笔记数据。" +
                        "此操作无法撤销，是否继续？"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        backupToRestore = null
                        closeEditor()
                        viewModel.restoreSelectedBackup(file)
                    }
                ) {
                    Text("确认覆盖恢复", color = MaterialTheme.colorScheme.onSurface)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        backupToRestore = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    // 恢复加密备份时自动弹出的解密密码输入框
    pendingEncryptedRestore?.let { pending ->
        var decryptPasswordInput by remember(pending.file.uri) { mutableStateOf("") }
        var decryptPasswordVisible by remember(pending.file.uri) { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                viewModel.cancelEncryptedRestore()
            },
            title = {
                Text("输入备份加密密码")
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "「${pending.file.name}」为加密备份，请输入密码以解密并恢复。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    OutlinedTextField(
                        value = decryptPasswordInput,
                        onValueChange = { decryptPasswordInput = it },
                        label = { Text("加密密码") },
                        singleLine = true,
                        isError = pending.wrongPassword,
                        visualTransformation = if (decryptPasswordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            TextButton(
                                onClick = { decryptPasswordVisible = !decryptPasswordVisible },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = if (decryptPasswordVisible) "隐藏" else "显示",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pending.wrongPassword) {
                        Text(
                            text = "密码错误，请重新输入",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = decryptPasswordInput.isNotEmpty(),
                    onClick = {
                        viewModel.confirmEncryptedRestore(decryptPasswordInput)
                    }
                ) {
                    Text("解密并恢复")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.cancelEncryptedRestore()
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 单条子弹笔记组件
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BulletNoteRow(
    note: NoteEntity,
    isEditing: Boolean,
    isExpanded: Boolean,
    listState: LazyListState,
    listViewportBounds: Rect?,
    keywordHintIndex: KeywordHintIndex,
    displayDateFormatter: DateTimeFormatter,
    modifier: Modifier = Modifier,
    onBulletClick: () -> Unit,
    onBulletLongClick: () -> Unit,
    onContentClick: (Boolean) -> Unit,
    onContentChange: (String) -> Unit,
    onEnterPressed: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float, Offset) -> Unit,
    onDragFinish: (Offset?) -> Unit,
    onHintBulletClick: (NoteEntity) -> Unit,
    onHintBulletLongClick: (NoteEntity) -> Unit,
    onHintContentChange: (Long, String) -> Unit,
    onHintEditFinished: (Long) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    var textLayout by remember {
        mutableStateOf<TextLayoutResult?>(null)
    }
    // 记录实际排版后的第一行高度，项目符号据此动态垂直居中，避免固定 dp 偏移。
    var firstLineHeightPx by remember(note.id) { mutableStateOf(0f) }
    // 记录单行预览时是否发生视觉截断（超宽折行），与显式换行共同判定是否为多行笔记。
    var isFirstLineEllipsized by remember(note.id, note.content) { mutableStateOf(false) }
    val hasMoreParagraphs = note.content.contains('\n')
    val isCollapsedMultiLine = !isExpanded && (hasMoreParagraphs || isFirstLineEllipsized)
    var textFieldCoordinates by remember {
        mutableStateOf<LayoutCoordinates?>(null)
    }

    var dragCoordinates by remember {
        mutableStateOf<LayoutCoordinates?>(null)
    }

    var lastDragRoot by remember {
        mutableStateOf<Offset?>(null)
    }

    val density = LocalDensity.current
    val bulletColor = MaterialTheme.colorScheme.onSurface
    val imeInsets = WindowInsets.ime
    val firstLineHeightDp = with(density) { firstLineHeightPx.toDp() }
    val bulletVerticalOffset = if (firstLineHeightPx > 0f) {
        (firstLineHeightDp - 28.dp) / 2
    } else {
        0.dp
    }

    var editValue by remember(note.id, isEditing) {
        mutableStateOf(
            TextFieldValue(
                note.content,
                selection = TextRange(note.content.length)
            )
        )
    }
    // 编辑历史仅保存在当前编辑会话中，避免撤销操作影响其他笔记。
    val undoHistory = remember(note.id, isEditing) { mutableStateListOf<String>() }
    val redoHistory = remember(note.id, isEditing) { mutableStateListOf<String>() }
    val bodyLineHeight = MaterialTheme.typography.bodyLarge.lineHeight

    fun undoEdit() {
        if (undoHistory.isEmpty()) return
        redoHistory.add(editValue.text)
        val previous = undoHistory.removeAt(undoHistory.lastIndex)
        editValue = TextFieldValue(previous, selection = TextRange(previous.length))
        onContentChange(previous)
    }

    fun redoEdit() {
        if (redoHistory.isEmpty()) return
        undoHistory.add(editValue.text)
        val next = redoHistory.removeAt(redoHistory.lastIndex)
        editValue = TextFieldValue(next, selection = TextRange(next.length))
        onContentChange(next)
    }

    // 关键字即时提示卡片状态（仅绑定到当前笔记的本次编辑会话）
    var activeHintKeyword by remember(note.id, isEditing) { mutableStateOf<String?>(null) }
    var hintPageIndex by remember(note.id, isEditing) { mutableStateOf(0) }
    var expandedHintNoteId by remember(note.id, isEditing) { mutableStateOf<Long?>(null) }
    var editingHintNoteId by remember(note.id, isEditing) { mutableStateOf<Long?>(null) }
    var pinnedEditingHintNote by remember(note.id, isEditing) { mutableStateOf<NoteEntity?>(null) }
    val dismissedHintKeywords = remember(note.id, isEditing) { mutableStateListOf<String>() }
    var lastCheckedEditText by remember(note.id, isEditing) { mutableStateOf(note.content) }

    fun finishHintCardEditing(restoreMainFocus: Boolean = false) {
        val editingId = editingHintNoteId
        if (editingId != null) {
            editingHintNoteId = null
            pinnedEditingHintNote = null
            onHintEditFinished(editingId)
        }
        if (restoreMainFocus && isEditing) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    // 退出编辑或组件销毁时，确保卡片内未收尾的原地编辑也落库并触发备份/同步。
    DisposableEffect(note.id, isEditing) {
        onDispose {
            val editingId = editingHintNoteId
            if (editingId != null) {
                editingHintNoteId = null
                pinnedEditingHintNote = null
                onHintEditFinished(editingId)
            }
        }
    }

    // 输入防抖检测：聚焦本次新录入区间，支持 @关键字@ 和 #关键字#。
    LaunchedEffect(isEditing, editValue.text, editValue.composition, keywordHintIndex) {
        if (!isEditing) return@LaunchedEffect
        val currentText = editValue.text
        if (currentText.length < lastCheckedEditText.length) {
            lastCheckedEditText = currentText
            return@LaunchedEffect
        }
        if (currentText == lastCheckedEditText) return@LaunchedEffect

        delay(180)
        val match = KeywordHintEngine.findMatchOnEdit(
            previousText = lastCheckedEditText,
            currentText = currentText,
            cursorPosition = editValue.selection.end,
            index = keywordHintIndex,
            excludeNoteId = note.id,
            dismissedKeywords = dismissedHintKeywords.toSet()
        )
        if (match != null) {
            if (activeHintKeyword != match.normalizedKeyword) {
                finishHintCardEditing(restoreMainFocus = false)
                activeHintKeyword = match.normalizedKeyword
                hintPageIndex = 0
                expandedHintNoteId = null
            }
            lastCheckedEditText = currentText
        } else if (editValue.composition == null) {
            // 中文拼音仍在组词时不推进基线，待词组上屏后再与组词前基线比对。
            lastCheckedEditText = currentText
        }
    }

    val liveHintEntry = activeHintKeyword?.let { keywordHintIndex.byNormalizedKeyword[it] }
    val liveHintNotes = remember(liveHintEntry, note.id) {
        liveHintEntry?.notes?.filter { it.id != note.id }.orEmpty()
    }
    LaunchedEffect(liveHintNotes, editingHintNoteId) {
        val editingId = editingHintNoteId
        if (editingId != null) {
            liveHintNotes.firstOrNull { it.id == editingId }?.let { updated ->
                pinnedEditingHintNote = updated
            }
        } else if (activeHintKeyword != null && liveHintNotes.isEmpty()) {
            activeHintKeyword = null
            expandedHintNoteId = null
            hintPageIndex = 0
        }
    }
    val displayedHintNotes = remember(liveHintNotes, editingHintNoteId, pinnedEditingHintNote) {
        val editingId = editingHintNoteId
        val pinned = pinnedEditingHintNote
        if (editingId != null && pinned != null && liveHintNotes.none { it.id == editingId }) {
            listOf(pinned) + liveHintNotes
        } else {
            liveHintNotes
        }
    }

    // 提示卡片弹出或展开/进入原地编辑时，自动滚动到可视区域内，避免被键盘遮挡。
    LaunchedEffect(isEditing, activeHintKeyword, expandedHintNoteId, editingHintNoteId) {
        if (isEditing && activeHintKeyword != null) {
            withFrameNanos { }
            withFrameNanos { }
            bringIntoViewRequester.bringIntoView()
        }
    }

    // 编辑时请求焦点，并在键盘弹出后将整行移动到可视区域。
    LaunchedEffect(isEditing) {
        if (isEditing) {
            focusRequester.requestFocus()

            snapshotFlow {
                imeInsets.getBottom(density)
            }.first {
                it > 0
            }

            // 恢复原来的等待时间，避免液晶屏上出现明显迟滞。
            delay(100)
            bringIntoViewRequester.bringIntoView()
        }
    }

    // 直接根据光标和 LazyColumn 可视区域在屏幕根坐标中的位置计算滚动量。
    // 不再依赖 BasicTextField 内部的 BringIntoViewRequester 自动滚动。
    LaunchedEffect(
        isEditing,
        editValue.text,
        editValue.selection,
        textLayout,
        listViewportBounds
    ) {
        if (!isEditing || editingHintNoteId != null) return@LaunchedEffect

        snapshotFlow {
            imeInsets.getBottom(density)
        }.first {
            it > 0
        }

        // 恢复原来的光标滚动等待时间。
        delay(80)
        withFrameNanos { }
        withFrameNanos { }

        val layout = textLayout ?: return@LaunchedEffect
        val fieldCoordinates = textFieldCoordinates
            ?.takeIf { it.isAttached }
            ?: return@LaunchedEffect
        val viewport = listViewportBounds ?: return@LaunchedEffect

        // 不使用任何视觉文本转换，布局中的光标索引与原始文本索引一致。
        val cursorPosition = editValue.selection.end.coerceIn(0, editValue.text.length)
        val cursor = layout.getCursorRect(cursorPosition)

        // 将光标底部从输入框局部坐标转换为屏幕根坐标。
        val cursorRoot = fieldCoordinates.localToRoot(
            Offset(cursor.left, cursor.bottom)
        )

        val clearance = with(density) { 20.dp.toPx() }
        val visibleTop = viewport.top + clearance
        val visibleBottom = viewport.bottom - clearance

        // 正值表示列表需要向上滚动；负值表示需要向下滚动。
        val scrollAmount = when {
            cursorRoot.y > visibleBottom -> cursorRoot.y - visibleBottom
            cursorRoot.y < visibleTop -> cursorRoot.y - visibleTop
            else -> 0f
        }

        if (kotlin.math.abs(scrollAmount) > 1f) {
            listState.animateScrollBy(scrollAmount)
        }
    }

    Column(
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isEditing) {
                    MaterialTheme.colorScheme.surface
                } else {
                    Color.Transparent
                }
            )
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            // 多行笔记时，项目符号固定对齐第一行，不随整条笔记高度垂直居中。
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .offset(y = bulletVerticalOffset)
                    .clip(RoundedCornerShape(4.dp))
                    .combinedClickable(
                        onClick = onBulletClick,
                        onLongClick = onBulletLongClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                NoteBulletGlyph(bulletType = note.bulletType, color = bulletColor)
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (!isEditing) {
                            Modifier
                                .onGloballyPositioned {
                                    dragCoordinates = it
                                }
                                .pointerInput(note.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { point ->
                                            lastDragRoot =
                                                dragCoordinates?.localToRoot(point)
                                            onDragStart()
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()

                                            dragCoordinates
                                                ?.localToRoot(change.position)
                                                ?.let { root ->
                                                    lastDragRoot = root
                                                    onDragDelta(amount.y, root)
                                                }
                                        },
                                        onDragEnd = {
                                            onDragFinish(lastDragRoot)
                                            lastDragRoot = null
                                        },
                                        onDragCancel = {
                                            onDragFinish(null)
                                            lastDragRoot = null
                                        }
                                    )
                                }
                        } else {
                            Modifier
                        }
                    )
                    .clickable(enabled = !isEditing) {
                        onContentClick(isCollapsedMultiLine)
                    }
            ) {
                if (!isEditing) {
                    val paragraphStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = bodyLineHeight
                    )
                    if (isExpanded) {
                        // 展开浏览模式：完整显示多行笔记内容，不进入编辑状态。
                        Text(
                            text = note.content.ifEmpty { " " },
                            style = paragraphStyle,
                            onTextLayout = { layout ->
                                if (layout.lineCount > 0) {
                                    firstLineHeightPx =
                                        layout.getLineBottom(0) - layout.getLineTop(0)
                                }
                            }
                        )
                    } else {
                        // 收起浏览模式只显示第一行；换行符前的第一行能完整显示时不添加省略号，
                        // 仅当第一行内容超出显示宽度时由 Ellipsis 自动在行末显示省略号。
                        val firstLine = note.content.substringBefore('\n')
                        val previewText = firstLine.ifEmpty { " " }
                        Text(
                            text = previewText,
                            style = paragraphStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { layout ->
                                if (layout.lineCount > 0) {
                                    firstLineHeightPx =
                                        layout.getLineBottom(0) - layout.getLineTop(0)
                                }
                                isFirstLineEllipsized = layout.hasVisualOverflow
                            }
                        )
                    }
                } else {
                    BasicTextField(
                        value = editValue,
                        onValueChange = { newValue ->
                            val textChanged = newValue.text != editValue.text
                            if (textChanged) {
                                undoHistory.add(editValue.text)
                                if (undoHistory.size > 100) undoHistory.removeAt(0)
                                redoHistory.clear()
                            }
                            editValue = newValue

                            // 仅正文真正变化时写数据库，保留 selection 和 IME composition。
                            if (textChanged) {
                                onContentChange(newValue.text)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused && editingHintNoteId != null) {
                                    finishHintCardEditing(restoreMainFocus = false)
                                }
                            }
                            .onGloballyPositioned { coordinates ->
                                textFieldCoordinates = coordinates
                            },
                        // 编辑时段内行高保持系统主题默认值，不整体放大行高。
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = bodyLineHeight
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        onTextLayout = { layout ->
                            textLayout = layout
                            if (layout.lineCount > 0) {
                                firstLineHeightPx =
                                    layout.getLineBottom(0) - layout.getLineTop(0)
                            }
                        },
                        // 回车键直接输入换行符，不再结束当前笔记编辑。
                        keyboardOptions = KeyboardOptions.Default,
                        keyboardActions = KeyboardActions.Default,
                        decorationBox = { innerTextField ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    innerTextField()
                                }

                                // 操作图标统一使用同一套矢量绘制与 IconButton 布局，
                                // 不再依赖不同 Unicode 字符的字体基线，因此图形中心天然对齐。
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 撤销、恢复靠右排布，与最右侧的收起按钮之间空出一个按钮宽度（48.dp）。
                                    EditorActionButton(
                                        symbol = EditorActionSymbol.Undo,
                                        contentDescription = "撤销",
                                        enabled = undoHistory.isNotEmpty(),
                                        tint = MaterialTheme.colorScheme.primary,
                                        onClick = { undoEdit() }
                                    )
                                    EditorActionButton(
                                        symbol = EditorActionSymbol.Redo,
                                        contentDescription = "恢复",
                                        enabled = redoHistory.isNotEmpty(),
                                        tint = MaterialTheme.colorScheme.primary,
                                        onClick = { redoEdit() }
                                    )
                                    Spacer(modifier = Modifier.width(48.dp))
                                    EditorActionButton(
                                        symbol = EditorActionSymbol.Collapse,
                                        contentDescription = "收起编辑",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        onClick = {
                                            finishHintCardEditing(restoreMainFocus = false)
                                            onContentClick(false)
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }

        if (isEditing && activeHintKeyword != null && displayedHintNotes.isNotEmpty()) {
            val safePageIndex = hintPageIndex.coerceIn(0, displayedHintNotes.lastIndex)
            val currentHintNote = displayedHintNotes[safePageIndex]

            Spacer(modifier = Modifier.height(4.dp))
            KeywordHintCard(
                hintNote = currentHintNote,
                pageIndex = safePageIndex,
                totalPages = displayedHintNotes.size,
                displayDateFormatter = displayDateFormatter,
                isExpanded = expandedHintNoteId == currentHintNote.id,
                isEditingHint = editingHintNoteId == currentHintNote.id,
                onNavigatePage = { delta ->
                    if (displayedHintNotes.size > 1) {
                        finishHintCardEditing(restoreMainFocus = false)
                        expandedHintNoteId = null
                        hintPageIndex = (safePageIndex + delta + displayedHintNotes.size) % displayedHintNotes.size
                    }
                },
                onDismiss = {
                    finishHintCardEditing(restoreMainFocus = true)
                    activeHintKeyword?.let { normalized ->
                        if (normalized !in dismissedHintKeywords) {
                            dismissedHintKeywords.add(normalized)
                        }
                    }
                    activeHintKeyword = null
                    expandedHintNoteId = null
                    hintPageIndex = 0
                },
                onBulletClick = {
                    val toggledNext = when (currentHintNote.bulletType) {
                        BulletType.SQUARE, BulletType.CHECKED -> BulletType.BULLET_DOT
                        BulletType.BULLET_DOT -> BulletType.STAR
                        BulletType.STAR -> BulletType.SQUARE
                    }
                    if (pinnedEditingHintNote?.id == currentHintNote.id) {
                        pinnedEditingHintNote = currentHintNote.copy(bulletType = toggledNext)
                    }
                    onHintBulletClick(currentHintNote)
                },
                onBulletLongClick = {
                    onHintBulletLongClick(currentHintNote)
                },
                onContentClick = { isTruncatedPreview ->
                    if (editingHintNoteId == currentHintNote.id) {
                        finishHintCardEditing(restoreMainFocus = true)
                    } else if (isTruncatedPreview && expandedHintNoteId != currentHintNote.id) {
                        expandedHintNoteId = currentHintNote.id
                    } else {
                        expandedHintNoteId = null
                        pinnedEditingHintNote = currentHintNote
                        editingHintNoteId = currentHintNote.id
                    }
                },
                onContentChange = { newText ->
                    if (pinnedEditingHintNote?.id == currentHintNote.id) {
                        pinnedEditingHintNote = pinnedEditingHintNote?.copy(content = newText)
                    }
                    onHintContentChange(currentHintNote.id, newText)
                },
                onCollapseHintEdit = {
                    finishHintCardEditing(restoreMainFocus = true)
                }
            )
        }
    }
}

@Composable
private fun NoteBulletGlyph(
    bulletType: BulletType,
    color: Color
) {
    when (bulletType) {
        BulletType.SQUARE, BulletType.CHECKED -> {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .border(
                        1.5.dp,
                        color,
                        RoundedCornerShape(3.dp)
                    )
            )
        }

        BulletType.BULLET_DOT -> {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
        }

        BulletType.STAR -> {
            Canvas(modifier = Modifier.size(18.dp)) {
                val scale = size.width / 18f
                val starPath = Path().apply {
                    moveTo(9f * scale, 0f)
                    lineTo(11.1f * scale, 6.2f * scale)
                    lineTo(17.7f * scale, 6.5f * scale)
                    lineTo(12.6f * scale, 10.6f * scale)
                    lineTo(14.4f * scale, 17.2f * scale)
                    lineTo(9f * scale, 13.4f * scale)
                    lineTo(3.6f * scale, 17.2f * scale)
                    lineTo(5.4f * scale, 10.6f * scale)
                    lineTo(0.3f * scale, 6.5f * scale)
                    lineTo(6.9f * scale, 6.2f * scale)
                    close()
                }
                drawPath(path = starPath, color = color)
            }
        }
    }
}

/**
 * 笔记编辑态下内联展示的关键字即时提示卡片：
 * - 紧贴当前编辑笔记的工具栏下方，不抢焦点；
 * - 多条命中时支持右上角 ‹ 1/N › 循环翻页与卡片区域左右滑动切页；
 * - 默认最多展开 3 行并带省略号提示，点击展开全文，再次点击（或 3 行以内直接点击）原地进入编辑模式。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeywordHintCard(
    hintNote: NoteEntity,
    pageIndex: Int,
    totalPages: Int,
    displayDateFormatter: DateTimeFormatter,
    isExpanded: Boolean,
    isEditingHint: Boolean,
    onNavigatePage: (Int) -> Unit,
    onDismiss: () -> Unit,
    onBulletClick: () -> Unit,
    onBulletLongClick: () -> Unit,
    onContentClick: (Boolean) -> Unit,
    onContentChange: (String) -> Unit,
    onCollapseHintEdit: () -> Unit
) {
    val density = LocalDensity.current
    val bulletColor = MaterialTheme.colorScheme.onSurface
    val bodyLineHeight = MaterialTheme.typography.bodyLarge.lineHeight
    val hintFocusRequester = remember { FocusRequester() }
    val cardInteractionSource = remember { MutableInteractionSource() }

    var firstLineHeightPx by remember(hintNote.id) { mutableStateOf(0f) }
    var isPreviewOverflow by remember(hintNote.id, hintNote.content) { mutableStateOf(false) }

    val lines = remember(hintNote.content) { hintNote.content.split('\n') }
    val hasMoreThan3Paragraphs = lines.size > 3
    val previewText = remember(hintNote.content, hasMoreThan3Paragraphs) {
        if (hasMoreThan3Paragraphs) {
            lines.take(3).joinToString("\n") + "…"
        } else {
            hintNote.content.ifEmpty { " " }
        }
    }
    val isTruncatedPreview = !isExpanded && (hasMoreThan3Paragraphs || isPreviewOverflow)

    val firstLineHeightDp = with(density) { firstLineHeightPx.toDp() }
    val bulletVerticalOffset = if (firstLineHeightPx > 0f) {
        (firstLineHeightDp - 28.dp) / 2
    } else {
        0.dp
    }

    var hintEditValue by remember(hintNote.id, isEditingHint) {
        mutableStateOf(
            TextFieldValue(
                hintNote.content,
                selection = TextRange(hintNote.content.length)
            )
        )
    }
    val hintUndoHistory = remember(hintNote.id, isEditingHint) { mutableStateListOf<String>() }
    val hintRedoHistory = remember(hintNote.id, isEditingHint) { mutableStateListOf<String>() }

    fun undoHintEdit() {
        if (hintUndoHistory.isEmpty()) return
        hintRedoHistory.add(hintEditValue.text)
        val previous = hintUndoHistory.removeAt(hintUndoHistory.lastIndex)
        hintEditValue = TextFieldValue(previous, selection = TextRange(previous.length))
        onContentChange(previous)
    }

    fun redoHintEdit() {
        if (hintRedoHistory.isEmpty()) return
        hintUndoHistory.add(hintEditValue.text)
        val next = hintRedoHistory.removeAt(hintRedoHistory.lastIndex)
        hintEditValue = TextFieldValue(next, selection = TextRange(next.length))
        onContentChange(next)
    }

    LaunchedEffect(isEditingHint, hintNote.id) {
        if (isEditingHint) {
            withFrameNanos { }
            runCatching { hintFocusRequester.requestFocus() }
        }
    }

    val formattedDate = remember(hintNote.dateString, displayDateFormatter) {
        runCatching {
            LocalDate.parse(hintNote.dateString).format(displayDateFormatter)
        }.getOrDefault(hintNote.dateString)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.background)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                shape = RoundedCornerShape(8.dp)
            )
            // 吸收卡片空白区域点击，防止误触外层关闭主编辑器的逻辑。
            .clickable(
                interactionSource = cardInteractionSource,
                indication = null,
                onClick = {}
            )
            .then(
                if (!isEditingHint && totalPages > 1) {
                    Modifier.pointerInput(hintNote.id, totalPages, pageIndex) {
                        var totalHorizontalDrag = 0f
                        val swipeThreshold = with(density) { 48.dp.toPx() }
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                totalHorizontalDrag += dragAmount
                            },
                            onDragEnd = {
                                when {
                                    totalHorizontalDrag <= -swipeThreshold -> onNavigatePage(1)
                                    totalHorizontalDrag >= swipeThreshold -> onNavigatePage(-1)
                                }
                                totalHorizontalDrag = 0f
                            },
                            onDragCancel = {
                                totalHorizontalDrag = 0f
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )
            .padding(top = 4.dp, bottom = 8.dp)
    ) {
        // 卡片顶栏：“提示” + 淡化日期 + 多条翻页符号（仅 >1 条显示）+ 关闭按钮
        // 右侧留 10.dp 内边距 + 28.dp 关闭热区，使 × 中心位于卡片右边距 24.dp 处，
        // 与下方原地编辑栏（48.dp IconButton，中心距右边 24.dp）及上方主编辑栏收起符号完全垂直对齐。
        val headerMutedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "提示",
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = headerMutedColor
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = formattedDate,
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.labelSmall,
                color = headerMutedColor
            )
            Spacer(modifier = Modifier.weight(1f))

            if (totalPages > 1) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { onNavigatePage(-1) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "‹",
                        style = MaterialTheme.typography.titleMedium,
                        color = headerMutedColor
                    )
                }
                Text(
                    text = "${pageIndex + 1}/$totalPages",
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelSmall,
                    color = headerMutedColor
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { onNavigatePage(1) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleMedium,
                        color = headerMutedColor
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭提示",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, bottom = 6.dp)
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
        )

        // 卡片内单条笔记区：左侧符号 + 右侧正文（与主界面操作逻辑保持一致）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .offset(y = bulletVerticalOffset)
                    .clip(RoundedCornerShape(4.dp))
                    .combinedClickable(
                        onClick = onBulletClick,
                        onLongClick = onBulletLongClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                NoteBulletGlyph(bulletType = hintNote.bulletType, color = bulletColor)
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = !isEditingHint) {
                        onContentClick(isTruncatedPreview)
                    }
            ) {
                if (!isEditingHint) {
                    val paragraphStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = bodyLineHeight
                    )
                    if (isExpanded) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 10.dp)
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = hintNote.content.ifEmpty { " " },
                                style = paragraphStyle,
                                onTextLayout = { layout ->
                                    if (layout.lineCount > 0) {
                                        firstLineHeightPx =
                                            layout.getLineBottom(0) - layout.getLineTop(0)
                                    }
                                }
                            )
                        }
                    } else {
                        // 默认最多展开 3 行；超出 3 行时在末尾显示省略号。
                        Text(
                            text = previewText,
                            style = paragraphStyle,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 10.dp),
                            onTextLayout = { layout ->
                                if (layout.lineCount > 0) {
                                    firstLineHeightPx =
                                        layout.getLineBottom(0) - layout.getLineTop(0)
                                }
                                isPreviewOverflow = layout.hasVisualOverflow
                            }
                        )
                    }
                } else {
                    BasicTextField(
                        value = hintEditValue,
                        onValueChange = { newValue ->
                            val textChanged = newValue.text != hintEditValue.text
                            if (textChanged) {
                                hintUndoHistory.add(hintEditValue.text)
                                if (hintUndoHistory.size > 100) hintUndoHistory.removeAt(0)
                                hintRedoHistory.clear()
                            }
                            hintEditValue = newValue
                            if (textChanged) {
                                onContentChange(newValue.text)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(hintFocusRequester),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = bodyLineHeight
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        onTextLayout = { layout ->
                            if (layout.lineCount > 0) {
                                firstLineHeightPx =
                                    layout.getLineBottom(0) - layout.getLineTop(0)
                            }
                        },
                        keyboardOptions = KeyboardOptions.Default,
                        keyboardActions = KeyboardActions.Default,
                        decorationBox = { innerTextField ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(end = 10.dp)
                                        .heightIn(max = 200.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    innerTextField()
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    EditorActionButton(
                                        symbol = EditorActionSymbol.Undo,
                                        contentDescription = "撤销",
                                        enabled = hintUndoHistory.isNotEmpty(),
                                        tint = MaterialTheme.colorScheme.primary,
                                        onClick = { undoHintEdit() }
                                    )
                                    EditorActionButton(
                                        symbol = EditorActionSymbol.Redo,
                                        contentDescription = "恢复",
                                        enabled = hintRedoHistory.isNotEmpty(),
                                        tint = MaterialTheme.colorScheme.primary,
                                        onClick = { redoHintEdit() }
                                    )
                                    Spacer(modifier = Modifier.width(48.dp))
                                    EditorActionButton(
                                        symbol = EditorActionSymbol.Collapse,
                                        contentDescription = "收起编辑",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        onClick = onCollapseHintEdit
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

private data class TodayStarProjection(
    val visibleNotes: List<NoteEntity>,
    val hiddenStarCount: Int,
    val totalStarCount: Int,
    val foldAnchorNoteId: Long?
)

/**
 * 今天待复习五角星笔记的极轻折叠提示行：
 * 无边框无底色，左侧与笔记正文严格对齐，点击可临时展开或收起今天其余待复习星标。
 */
@Composable
private fun TodayStarFoldToggleRow(
    modifier: Modifier = Modifier,
    expanded: Boolean,
    hiddenCount: Int,
    onClick: () -> Unit
) {
    val mutedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val label = if (expanded) "收起待复习" else "还有 $hiddenCount 条待复习"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.width(36.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = mutedColor,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 2.dp, vertical = 4.dp)
        )
    }
}

/**
 * 日期浏览模式下位于最后一条笔记下方的新增笔记提示条。
 * 视觉区域与单行笔记正文严格对齐，采用低透明度正文色作为背景与加号颜色。
 */
@Composable
private fun AddNoteHintRow(
    onClick: () -> Unit
) {
    val hintBaseColor = MaterialTheme.colorScheme.onSurface
    val hintBackgroundColor = hintBaseColor.copy(alpha = 0.06f)
    val hintIconColor = hintBaseColor.copy(alpha = 0.35f)
    val bodyLineHeight = MaterialTheme.typography.bodyLarge.lineHeight

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 左侧占位宽度（28dp 项目符号槽 + 8dp 间距）与 BulletNoteRow 保持一致，确保覆盖区域与正文完全对齐。
        Spacer(modifier = Modifier.width(36.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(hintBackgroundColor)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            // 使用与单行笔记正文完全一致的空文本决定高度，保证随系统字体缩放自适应且高度完全一致。
            Text(
                text = " ",
                style = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = bodyLineHeight
                ),
                maxLines = 1,
                modifier = Modifier.alpha(0f)
            )
            Canvas(modifier = Modifier.size(14.dp)) {
                val strokeWidth = 1.6.dp.toPx()
                val halfW = size.width / 2f
                val halfH = size.height / 2f
                val inset = strokeWidth / 2f
                drawLine(
                    color = hintIconColor,
                    start = Offset(inset, halfH),
                    end = Offset(size.width - inset, halfH),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = hintIconColor,
                    start = Offset(halfW, inset),
                    end = Offset(halfW, size.height - inset),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}


/** 图标按钮使用统一的 24x24 坐标系绘制，彻底避开字体字形基线差异。 */
private enum class EditorActionSymbol {
    Collapse,
    Undo,
    Redo
}

@Composable
private fun EditorActionButton(
    symbol: EditorActionSymbol,
    contentDescription: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val iconColor = if (enabled) {
        tint
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    }

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.focusProperties { canFocus = false }
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val scale = size.width / 24f
            val strokeWidth = 2.dp.toPx()
            val stroke = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
            // 撤销/恢复图标整体下移 2.5 个坐标单位，修正箭头视觉重心偏高的问题。
            // 只调整 Canvas 内部绘制坐标，不改变 IconButton 的布局和点击区域。
            val verticalOffset = if (
                symbol == EditorActionSymbol.Undo || symbol == EditorActionSymbol.Redo
            ) 2.5f else 0f
            fun point(x: Float, y: Float) = Offset(x * scale, (y + verticalOffset) * scale)

            val path = Path()
            when (symbol) {
                EditorActionSymbol.Collapse -> {
                    path.moveTo(point(5f, 14f).x, point(5f, 14f).y)
                    path.lineTo(point(12f, 7f).x, point(12f, 7f).y)
                    path.lineTo(point(19f, 14f).x, point(19f, 14f).y)
                }

                EditorActionSymbol.Undo -> {
                    path.moveTo(point(19f, 12f).x, point(19f, 12f).y)
                    path.cubicTo(
                        point(19f, 8f).x, point(19f, 8f).y,
                        point(16f, 5f).x, point(16f, 5f).y,
                        point(12f, 5f).x, point(12f, 5f).y
                    )
                    path.lineTo(point(6f, 5f).x, point(6f, 5f).y)
                    path.moveTo(point(10f, 1f).x, point(10f, 1f).y)
                    path.lineTo(point(6f, 5f).x, point(6f, 5f).y)
                    path.lineTo(point(10f, 9f).x, point(10f, 9f).y)
                }

                EditorActionSymbol.Redo -> {
                    path.moveTo(point(5f, 12f).x, point(5f, 12f).y)
                    path.cubicTo(
                        point(5f, 8f).x, point(5f, 8f).y,
                        point(8f, 5f).x, point(8f, 5f).y,
                        point(12f, 5f).x, point(12f, 5f).y
                    )
                    path.lineTo(point(18f, 5f).x, point(18f, 5f).y)
                    path.moveTo(point(14f, 1f).x, point(14f, 1f).y)
                    path.lineTo(point(18f, 5f).x, point(18f, 5f).y)
                    path.lineTo(point(14f, 9f).x, point(14f, 9f).y)
                }
            }

            drawPath(path = path, color = iconColor, style = stroke)
        }
    }
}
