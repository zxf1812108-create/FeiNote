package com.feiji.bulletnotes.ui

import android.widget.Toast
import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
    // 新建笔记期间的常驻焦点接力点，防止旧输入框失焦时键盘关闭。
    val enterFocusBridge = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val selectedDate by viewModel.selectedDate.collectAsState()
    val isToday by viewModel.isToday.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val noteDates by viewModel.noteDates.collectAsState()
    val trashNotes by viewModel.trashNotes.collectAsState()
    val editingNoteId by viewModel.editingNoteId.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()

    var showTrashDialog by remember { mutableStateOf(false) }
    var trashBounds by remember { mutableStateOf<Rect?>(null) }
    var hoveringTrash by remember { mutableStateOf(false) }
    var showBackupPicker by remember { mutableStateOf(false) }
    var backupToRestore by remember {
        mutableStateOf<com.feiji.bulletnotes.data.BackupManager.BackupFile?>(null)
    }
    val backupFiles by viewModel.backupFiles.collectAsState()
    var noteToChangeDate by remember { mutableStateOf<NoteEntity?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    // 点击当前编辑项之外的区域时，统一退出编辑并触发已有的空笔记清理逻辑。
    fun closeEditor() {
        if (editingNoteId != null) {
            viewModel.setEditingNoteId(null)
            focusManager.clearFocus()
        }
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

    LaunchedEffect(notes, draggingNoteId, pendingReorderIds) {
        if (draggingNoteId == null) {
            val expectedOrder = pendingReorderIds

            // reorderNotes 是异步写入数据库的；在 Flow 返回新顺序前，
            // 不要用旧 notes 覆盖已经完成的本地拖动顺序。
            if (expectedOrder != null && notes.map { it.id } != expectedOrder) {
                return@LaunchedEffect
            }

            if (displayNotes.map { it.id } != notes.map { it.id }) {
                displayNotes.clear()
                displayNotes.addAll(notes)
            } else {
                notes.forEachIndexed { index, item ->
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
                    .clickable(enabled = editingNoteId != null) {
                        closeEditor()
                    }
                    .statusBarsPadding()
                    .padding(top = 4.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                closeEditor()
                                noteToChangeDate = null
                                showDatePicker = true
                            },
                        color = Color.Transparent
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(
                                vertical = 2.dp,
                                horizontal = 4.dp
                            )
                        ) {
                            Text(
                                text = selectedDate.format(viewModel.displayDateFormatter) +
                                    " " +
                                    "一二三四五六日"[selectedDate.dayOfWeek.value - 1],
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                closeEditor()
                                if (isToday) {
                                    // 只有标签显示“今天”时才切换主题。
                                    onToggleTheme()
                                } else {
                                    // 标签显示“返回”时只回到今天，不改变主题。
                                    viewModel.returnToToday()
                                }
                            },
                        color = Color.Transparent
                    ) {
                        Text(
                            text = if (isToday) "今天" else "返回",
                            modifier = Modifier.padding(
                                horizontal = 8.dp,
                                vertical = 2.dp
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

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
                            tint = MaterialTheme.colorScheme.onSurface
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
                .pointerInput(selectedDate, editingNoteId) {
                    if (editingNoteId == null) {
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
                .clickable(enabled = editingNoteId != null) {
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

            if (displayNotes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            viewModel.insertNoteAfter(null, "")
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "今天暂无笔记",
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

                        val isEditing = (editingNoteId == note.id)

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
                            listState = listState,
                            listViewportBounds = listViewportBounds,
                            onBulletClick = {
                                if (editingNoteId != note.id) {
                                    closeEditor()
                                }
                                viewModel.cycleBulletType(note)
                            },
                            onBulletLongClick = {
                                closeEditor()
                                noteToChangeDate = note
                                showDatePicker = true
                            },
                            onContentClick = {
                                if (editingNoteId == note.id) {
                                    closeEditor()
                                } else {
                                    viewModel.setEditingNoteId(note.id)
                                }
                            },
                            onDragStart = {
                                hoveringTrash = false
                                closeEditor()

                                val info = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == note.id }

                                if (info != null) {
                                    dragAnchorTop = info.offset.toFloat()
                                    draggingOffset = 0f
                                    dragStartIndex =
                                        notes.indexOfFirst { it.id == note.id }
                                    draggingNoteId = note.id
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
                                } else if (draggingNoteId == note.id) {
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
            },
            onDeleteBeforeToday = {
                viewModel.deleteTrashBeforeToday()
            }
        )
    }

    // 日期选择弹窗
    if (showDatePicker) {
        DatePickerHelper(
            initialDate = selectedDate,
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
                TextButton(
                    onClick = {
                        viewModel.loadBackupFiles()
                    }
                ) {
                    Text("刷新")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBackupPicker = false
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
                        viewModel.restoreSelectedBackup(file.uri)
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
}

/**
 * 编辑模式段间距转换：仅改变显示，不改变 TextFieldState 中的原始文本。
 * 每个真实换行符后插入零宽字符和一个视觉换行，并将视觉空白段行高设为正文行高的 0.5 倍。
 * OutputTransformation 会自动维护原文与显示文本之间的光标/选区映射。
 */
@OptIn(ExperimentalFoundationApi::class)
private fun paragraphGapOutputTransformation(
    gapLineHeight: androidx.compose.ui.unit.TextUnit
): OutputTransformation = OutputTransformation {
    // 先记录原文中换行符的位置，再从后向前插入，避免原文索引发生偏移。
    val originalLength = length
    val newlinePositions = (0 until originalLength).filter { this[it] == '\n' }

    for (index in newlinePositions.asReversed()) {
        insert(index + 1, "\u200B\n")
    }

    // 所有字符插入完成后再添加 ParagraphStyle，避免后续插入使样式范围错位。
    var insertedCharactersBefore = 0
    for (originalIndex in newlinePositions) {
        val gapStart = originalIndex + 1 + insertedCharactersBefore
        addStyle(
            style = ParagraphStyle(lineHeight = gapLineHeight),
            start = gapStart,
            end = gapStart + 2
        )
        insertedCharactersBefore += 2
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
    listState: LazyListState,
    listViewportBounds: Rect?,
    modifier: Modifier = Modifier,
    onBulletClick: () -> Unit,
    onBulletLongClick: () -> Unit,
    onContentClick: () -> Unit,
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
    val imeInsets = WindowInsets.ime
    val firstLineHeightDp = with(density) { firstLineHeightPx.toDp() }
    val bulletVerticalOffset = if (firstLineHeightPx > 0f) {
        (firstLineHeightDp - 28.dp) / 2
    } else {
        0.dp
    }

    // 使用状态式文本输入，让输入法直接操作原始文本/光标/组合区间，
    // 不再通过 TextFieldValue + 自定义 OffsetMapping 来回转换。
    val editState = remember(note.id, isEditing) {
        TextFieldState(
            initialText = note.content,
            initialSelection = TextRange(note.content.length)
        )
    }
    // 编辑历史仅保存在当前编辑会话中，避免撤销操作影响其他笔记。
    val undoHistory = remember(note.id, isEditing) { mutableStateListOf<String>() }
    val redoHistory = remember(note.id, isEditing) { mutableStateListOf<String>() }
    var lastObservedText by remember(note.id, isEditing) {
        mutableStateOf(note.content)
    }
    val bodyLineHeight = MaterialTheme.typography.bodyLarge.lineHeight
    val paragraphGapTransformation = remember(bodyLineHeight) {
        paragraphGapOutputTransformation(bodyLineHeight * 0.5f)
    }

    // 监听 TextFieldState 的真实文本变化：输入法组合文本和光标由 Compose 自己维护。
    // 仅文本变化时写数据库，并把上一个完整文本状态加入撤销历史。
    LaunchedEffect(editState) {
        snapshotFlow { editState.text.toString() }
            .collect { newText ->
                if (newText != lastObservedText) {
                    undoHistory.add(lastObservedText)
                    if (undoHistory.size > 100) undoHistory.removeAt(0)
                    redoHistory.clear()
                    lastObservedText = newText
                    onContentChange(newText)
                }
            }
    }

    var lastFinishEnterAt by remember(note.id, isEditing) { mutableStateOf(0L) }

    fun undoEdit() {
        if (undoHistory.isEmpty()) return
        val current = editState.text.toString()
        redoHistory.add(current)
        val previous = undoHistory.removeAt(undoHistory.lastIndex)
        editState.edit {
            replace(0, length, previous)
            selection = TextRange(previous.length)
        }
        lastObservedText = previous
        onContentChange(previous)
    }

    fun redoEdit() {
        if (redoHistory.isEmpty()) return
        val current = editState.text.toString()
        undoHistory.add(current)
        val next = redoHistory.removeAt(redoHistory.lastIndex)
        editState.edit {
            replace(0, length, next)
            selection = TextRange(next.length)
        }
        lastObservedText = next
        onContentChange(next)
    }

    fun finishEditingFromEnter() {
        // 少数输入法/设备可能同时派发 KeyEvent 与 IME Action，避免一次按键重复新建笔记。
        val now = SystemClock.uptimeMillis()
        if (now - lastFinishEnterAt < 300L) return
        lastFinishEnterAt = now
        onEnterPressed()
    }

    // 专用“换段”按钮：只在原文当前选区/光标处插入一个真实换行符。
    // 视觉间距由 OutputTransformation 单独生成，不会进入数据库正文。
    fun insertParagraphAtCursor() {
        val currentText = editState.text.toString()
        val selectionStart = minOf(editState.selection.start, editState.selection.end)
            .coerceIn(0, currentText.length)
        val selectionEnd = maxOf(editState.selection.start, editState.selection.end)
            .coerceIn(0, currentText.length)

        undoHistory.add(currentText)
        if (undoHistory.size > 100) undoHistory.removeAt(0)
        redoHistory.clear()

        editState.edit {
            replace(selectionStart, selectionEnd, "\n")
            selection = TextRange(selectionStart + 1)
        }
        val updatedText = editState.text.toString()
        lastObservedText = updatedText
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
        editState.text,
        editState.selection,
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

        // TextLayoutResult 对应转换后的文本，因此光标索引也需要转换。
        val rawText = editState.text.toString()
        val originalCursor = editState.selection.end.coerceIn(0, rawText.length)
        // OutputTransformation 在每个真实换行后插入两个视觉字符；TextLayoutResult
        // 对应转换后的布局，因此这里只用于滚动定位，不参与输入法的光标映射。
        val transformedCursor = originalCursor +
            rawText.take(originalCursor).count { it == '\n' } * 2
        val cursor = layout.getCursorRect(transformedCursor)

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
                    onContentClick()
                }
        ) {
            if (!isEditing) {
                // 浏览模式始终只显示第一行；超出宽度时由 Ellipsis 自动显示省略号。
                val paragraphStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = bodyLineHeight
                )
                Text(
                    text = note.content.substringBefore('\n').ifEmpty { " " },
                    style = paragraphStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layout ->
                        if (layout.lineCount > 0) {
                            firstLineHeightPx =
                                layout.getLineBottom(0) - layout.getLineTop(0)
                        }
                    }
                )
            } else {
                BasicTextField(
                    state = editState,
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
                    outputTransformation = paragraphGapTransformation,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    onTextLayout = { getResult ->
                        getResult()?.let { layout ->
                            textLayout = layout
                            if (layout.lineCount > 0) {
                                firstLineHeightPx =
                                    layout.getLineBottom(0) - layout.getLineTop(0)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions.Default.copy(
                        imeAction = ImeAction.Next
                    ),
                    onKeyboardAction = { _ ->
                        // 手机软键盘自带的回车/Next 动作仍保持原来的行为：结束编辑。
                        finishEditingFromEnter()
                    },
                    decorator = { innerTextField ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (editState.text.isEmpty()) {
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

                            // 撤销、恢复与换段控制均固定在编辑区域末尾。
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { undoEdit() },
                                    enabled = undoHistory.isNotEmpty(),
                                    modifier = Modifier.focusProperties { canFocus = false }
                                ) {
                                    Text(
                                        text = "↶",
                                        color = if (undoHistory.isNotEmpty()) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                    )
                                }
                                TextButton(
                                    onClick = { redoEdit() },
                                    enabled = redoHistory.isNotEmpty(),
                                    modifier = Modifier.focusProperties { canFocus = false }
                                ) {
                                    Text(
                                        text = "↷",
                                        color = if (redoHistory.isNotEmpty()) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                    )
                                }
                                TextButton(
                                    onClick = { insertParagraphAtCursor() },
                                    modifier = Modifier.focusProperties { canFocus = false }
                                ) {
                                    Text(
                                        text = "↵",
                                        fontSize = 20.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }

    }
}
