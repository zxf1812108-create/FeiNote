package com.feiji.bulletnotes.ui

import android.widget.Toast
import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.feiji.bulletnotes.data.BulletType
import com.feiji.bulletnotes.data.NoteEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.LocalDate

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
    // 单击搜索图标限定搜索圆点笔记，长按搜索图标限定搜索五角星笔记。
    var searchBulletFilter by remember { mutableStateOf(BulletType.BULLET_DOT) }
    val visibleNotes = if (isSearchMode) searchResults else notes
    val trashNotes by viewModel.trashNotes.collectAsState()
    val editingNoteId by viewModel.editingNoteId.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()

    var showTrashDialog by remember { mutableStateOf(false) }
    var trashBounds by remember { mutableStateOf<Rect?>(null) }
    var hoveringTrash by remember { mutableStateOf(false) }
    var showBackupPicker by remember { mutableStateOf(false) }
    var showWebDavSettings by remember { mutableStateOf(false) }
    var backupToRestore by remember {
        mutableStateOf<com.feiji.bulletnotes.data.BackupManager.BackupFile?>(null)
    }
    val backupFiles by viewModel.backupFiles.collectAsState()
    val webDavConfig by viewModel.webDavConfig.collectAsState()
    val showWebDavAlert by viewModel.showWebDavAlert.collectAsState()
    val pendingEncryptedRestore by viewModel.pendingEncryptedRestore.collectAsState()
    var noteToChangeDate by remember { mutableStateOf<NoteEntity?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    // 多行笔记首次点击仅展开浏览（不进入编辑），再次点击才进入编辑模式。
    var expandedNoteId by remember { mutableStateOf<Long?>(null) }
    // 防止连续快速点击新增提示条时重复插入多条空白笔记。
    var isCreatingFromHint by remember { mutableStateOf(false) }

    LaunchedEffect(selectedDate, isSearchMode) {
        expandedNoteId = null
        isCreatingFromHint = false
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
    var pendingReorderIds by remember { mutableStateOf<List<Long>?>(null) }

    // 仅回车新增笔记时触发精准滚动，不影响点击其他笔记。
    var pendingNewNoteScrollFromId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(visibleNotes, draggingNoteId, pendingReorderIds, isSearchMode) {
        if (draggingNoteId == null) {
            val expectedOrder = pendingReorderIds

            // reorderNotes 是异步写入数据库的；在 Flow 返回新顺序前，
            // 不要用旧 notes 覆盖已经完成的本地拖动顺序。
            if (expectedOrder != null && visibleNotes.map { it.id } != expectedOrder) {
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

            if (expectedOrder != null) {
                pendingReorderIds = null
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                    pendingReorderIds = null
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
                            if (searchBulletFilter == BulletType.STAR) {
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
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(indicatorColor, CircleShape)
                                )
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
                                        pendingReorderIds = null
                                        if (!isToday) {
                                            viewModel.returnToToday()
                                        } else {
                                            searchBulletFilter = BulletType.BULLET_DOT
                                            isSearchMode = true
                                            searchText = ""
                                            viewModel.searchNotes("", BulletType.BULLET_DOT)
                                        }
                                    },
                                    onLongClick = {
                                        if (isToday) {
                                            closeEditor()
                                            pendingReorderIds = null
                                            searchBulletFilter = BulletType.STAR
                                            isSearchMode = true
                                            searchText = ""
                                            viewModel.searchNotes("", BulletType.STAR)
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
                                        pendingReorderIds = null
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(enabled = !isSearchMode) {
                            viewModel.insertNoteAfter(null, "")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (isSearchMode) "没有找到匹配的笔记" else "今天暂无笔记",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "点击此处开始记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
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
                    itemsIndexed(
                        displayNotes,
                        key = { _, note -> note.id }
                    ) { index, note ->

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
                                viewModel.cycleBulletType(note)
                            },
                            onBulletLongClick = {
                                closeEditor()
                                noteToChangeDate = note
                                showDatePicker = true
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
                                        pendingReorderIds =
                                            displayNotes.map { it.id }

                                        viewModel.reorderNotes(
                                            dragStartIndex,
                                            endIndex
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
                            }
                        )
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
            showRelativeShortcuts = noteToChangeDate != null,
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
                    // “备份设置”入口（点击打开备份设置窗体）
                    TextButton(
                        onClick = {
                            showBackupPicker = false
                            showWebDavSettings = true
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "备份设置",
                            maxLines = 1,
                            softWrap = false,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(
                        onClick = {
                            showBackupPicker = false
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("取消", maxLines = 1, softWrap = false)
                    }
                    TextButton(
                        onClick = {
                            viewModel.loadBackupFiles()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("刷新", maxLines = 1, softWrap = false)
                    }
                }
            }
        )
    }

    // 备份设置弹窗（WebDAV 上传备份 + 加密数据）
    if (showWebDavSettings) {
        var serverUrlInput by remember(showWebDavSettings) { mutableStateOf(webDavConfig.serverUrl) }
        var remoteFolderInput by remember(showWebDavSettings) { mutableStateOf(webDavConfig.remoteFolder) }
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

        val isEncryptionPasswordMismatch = encryptionPasswordInput.isNotEmpty() &&
            encryptionPasswordInput != confirmEncryptionPasswordInput
        val showMismatchError = encryptionPasswordMismatchError ||
            (confirmEncryptionPasswordInput.isNotEmpty() && isEncryptionPasswordMismatch)

        AlertDialog(
            onDismissRequest = {
                showWebDavSettings = false
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "WebDAV上传备份：",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        OutlinedTextField(
                            value = serverUrlInput,
                            onValueChange = { serverUrlInput = it },
                            label = { Text("服务器地址") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = remoteFolderInput,
                            onValueChange = { remoteFolderInput = it },
                            label = { Text("备份目录") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = usernameInput,
                            onValueChange = { usernameInput = it },
                            label = { Text("账号") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("应用密码(留空则不上传)") },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                TextButton(
                                    onClick = { passwordVisible = !passwordVisible },
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
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "加密备份：",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        OutlinedTextField(
                            value = encryptionPasswordInput,
                            onValueChange = {
                                encryptionPasswordInput = it
                                if (it.isEmpty()) {
                                    confirmEncryptionPasswordInput = ""
                                }
                                encryptionPasswordMismatchError = false
                            },
                            label = { Text("加密密码(留空则不加密)") },
                            singleLine = true,
                            visualTransformation = if (encryptionPasswordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                TextButton(
                                    onClick = { encryptionPasswordVisible = !encryptionPasswordVisible },
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
                            label = { Text("确认加密密码") },
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
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isEncryptionPasswordMismatch) {
                            encryptionPasswordMismatchError = true
                            return@TextButton
                        }
                        viewModel.saveWebDavSettings(
                            serverUrl = serverUrlInput,
                            remoteFolder = remoteFolderInput,
                            username = usernameInput,
                            password = passwordInput,
                            encryptionPassword = encryptionPasswordInput
                        )
                        showWebDavSettings = false
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showWebDavSettings = false
                    }
                ) {
                    Text("取消")
                }
            }
        )
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
    modifier: Modifier = Modifier,
    onBulletClick: () -> Unit,
    onBulletLongClick: () -> Unit,
    onContentClick: (Boolean) -> Unit,
    onContentChange: (String) -> Unit,
    onEnterPressed: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float, Offset) -> Unit,
    onDragFinish: (Offset?) -> Unit
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

    var lastFinishEnterAt by remember(note.id, isEditing) { mutableStateOf(0L) }

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

    fun finishEditingFromEnter() {
        // 少数输入法/设备可能同时派发 KeyEvent 与 IME Action，避免一次按键重复新建笔记。
        val now = SystemClock.uptimeMillis()
        if (now - lastFinishEnterAt < 300L) return
        lastFinishEnterAt = now
        onEnterPressed()
    }

    // 专用“换段”按钮：在当前选区/光标位置插入换行，并将光标移到新段落开头。
    // 不再尝试检测软键盘回车键长按，因为多数输入法不会向应用提供完整的按下/抬起事件。
    fun insertParagraphAtCursor() {
        val selectionStart = minOf(editValue.selection.start, editValue.selection.end)
            .coerceIn(0, editValue.text.length)
        val selectionEnd = maxOf(editValue.selection.start, editValue.selection.end)
            .coerceIn(0, editValue.text.length)
        val updatedText = buildString {
            append(editValue.text.substring(0, selectionStart))
            append('\n')
            append(editValue.text.substring(selectionEnd))
        }
        undoHistory.add(editValue.text)
        if (undoHistory.size > 100) undoHistory.removeAt(0)
        redoHistory.clear()
        val cursorPosition = selectionStart + 1
        editValue = editValue.copy(
            text = updatedText,
            selection = TextRange(cursorPosition),
            composition = null
        )
        onContentChange(updatedText)
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
        if (!isEditing) return@LaunchedEffect

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

    Row(
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
            .padding(horizontal = 6.dp, vertical = 6.dp),
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
            when (note.bulletType) {
                BulletType.SQUARE -> {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .border(
                                1.5.dp,
                                MaterialTheme.colorScheme.onSurface,
                                RoundedCornerShape(3.dp)
                            )
                    )
                }

                BulletType.CHECKED -> {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .border(
                                1.5.dp,
                                MaterialTheme.colorScheme.onSurface,
                                RoundedCornerShape(3.dp)
                            )
                    )
                }

                BulletType.BULLET_DOT -> {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.onSurface, CircleShape)
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
                        drawPath(path = starPath, color = bulletColor)
                    }
                }
            }
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
                        .onGloballyPositioned { coordinates ->
                            textFieldCoordinates = coordinates
                        }
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.key == Key.Enter) {
                                // 外接实体键盘的 Enter 仍然结束当前笔记编辑；
                                // 软键盘的 Enter 则由下面的 IME onNext 处理。
                                if (
                                    keyEvent.nativeKeyEvent.action ==
                                    android.view.KeyEvent.ACTION_DOWN &&
                                    keyEvent.nativeKeyEvent.repeatCount == 0
                                ) {
                                    finishEditingFromEnter()
                                }
                                true
                            } else {
                                false
                            }
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
                    keyboardOptions = KeyboardOptions.Default.copy(
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        // 手机软键盘自带的回车/Next 动作仍保持原来的行为：结束编辑。
                        onNext = { finishEditingFromEnter() }
                    ),
                    decorationBox = { innerTextField ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (editValue.text.isEmpty()) {
                                    Text(
                                        text = "输入笔记内容...",
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
                                        )
                                    )
                                }
                                innerTextField()
                            }

                            // 四个操作统一使用同一套矢量绘制与 IconButton 布局，
                            // 不再依赖不同 Unicode 字符的字体基线，因此图形中心天然对齐。
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 撤销、恢复、换段放在左侧；收起按钮放在最右侧。
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
                                EditorActionButton(
                                    symbol = EditorActionSymbol.Paragraph,
                                    contentDescription = "换段",
                                    tint = MaterialTheme.colorScheme.primary,
                                    onClick = { insertParagraphAtCursor() }
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                EditorActionButton(
                                    symbol = EditorActionSymbol.Collapse,
                                    contentDescription = "收起编辑",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    onClick = { onContentClick(false) }
                                )
                            }
                        }
                    }
                )
            }
        }

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
    Redo,
    Paragraph
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

                EditorActionSymbol.Paragraph -> {
                    path.moveTo(point(17f, 5f).x, point(17f, 5f).y)
                    path.lineTo(point(17f, 13f).x, point(17f, 13f).y)
                    path.lineTo(point(6f, 13f).x, point(6f, 13f).y)
                    path.moveTo(point(10f, 9f).x, point(10f, 9f).y)
                    path.lineTo(point(6f, 13f).x, point(6f, 13f).y)
                    path.lineTo(point(10f, 17f).x, point(10f, 17f).y)
                }
            }

            drawPath(path = path, color = iconColor, style = stroke)
        }
    }
}
