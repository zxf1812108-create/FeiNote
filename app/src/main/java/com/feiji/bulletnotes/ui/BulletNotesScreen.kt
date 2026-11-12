package com.feiji.bulletnotes.ui
 
import android.widget.Toast
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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feiji.bulletnotes.data.BulletType
import com.feiji.bulletnotes.data.NoteEntity
import com.feiji.bulletnotes.ui.theme.AccentDanger
import com.feiji.bulletnotes.ui.theme.AccentPrimary
import com.feiji.bulletnotes.ui.theme.CharcoalGray
import com.feiji.bulletnotes.ui.theme.InkBlack
import com.feiji.bulletnotes.ui.theme.LineBorder
import com.feiji.bulletnotes.ui.theme.SlateMuted
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.LocalDate

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BulletNotesScreen(viewModel: NoteViewModel) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    // 新建笔记期间的常驻焦点接力点，防止旧输入框失焦时键盘关闭。
    val enterFocusBridge = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val selectedDate by viewModel.selectedDate.collectAsState()
    val isToday by viewModel.isToday.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val trashNotes by viewModel.trashNotes.collectAsState()
    val editingNoteId by viewModel.editingNoteId.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()

    var showTrashDialog by remember { mutableStateOf(false) }
    var trashBounds by remember { mutableStateOf<Rect?>(null) }
    var hoveringTrash by remember { mutableStateOf(false) }
    var showBackupPicker by remember { mutableStateOf(false) }
    var backupToRestore by remember { mutableStateOf<com.feiji.bulletnotes.data.BackupManager.BackupFile?>(null) }
    val backupFiles by viewModel.backupFiles.collectAsState()
    var noteToChangeDate by remember { mutableStateOf<NoteEntity?>(null) }

    // 点击当前编辑项之外的区域时，统一退出编辑并触发已有的空笔记清理逻辑。
    fun closeEditor() {
        if (editingNoteId != null) {
            viewModel.setEditingNoteId(null)
            focusManager.clearFocus()
        }
    }

    val densityForNewNoteScroll = LocalDensity.current
    val listState = rememberLazyListState()
    // 拖动期间只更新内存中的显示顺序；松手后再保存到数据库。
    val displayNotes = remember { mutableStateListOf<NoteEntity>() }
    var draggingNoteId by remember { mutableStateOf<Long?>(null) }
    var draggingOffset by remember { mutableStateOf(0f) }
    var dragAnchorTop by remember { mutableStateOf(0f) }
    var dragStartIndex by remember { mutableStateOf(-1) }
    // 仅回车新增笔记时触发精准滚动，不影响点击其他笔记。
    var pendingNewNoteScrollFromId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(notes, draggingNoteId) {
        if (draggingNoteId == null) {
            if (displayNotes.map { it.id } != notes.map { it.id }) {
                displayNotes.clear()
                displayNotes.addAll(notes)
            } else {
                notes.forEachIndexed { index, item ->
                    if (displayNotes[index] != item) displayNotes[index] = item
                }
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
                val overlap = target.offset + target.size + clearance - layout.viewportEndOffset
                if (overlap > 0f) listState.animateScrollBy(overlap)
                pendingNewNoteScrollFromId = null
                return@LaunchedEffect
            }
            // 新行恰好落在屏幕外时，先只向下滚动约一行，再重新测量。
            val estimatedRow = layout.visibleItemsInfo.lastOrNull()?.size?.toFloat() ?: 56f
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
                    // 顶栏的空白区域也可结束编辑；日期、今天、回收站等子控件仍可正常点击。
                    .clickable(enabled = editingNoteId != null) { closeEditor() }
                    .statusBarsPadding()
                    .padding(top = 4.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左上角：日期显示区域（点击弹出日期选择对话框）
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                closeEditor()
                                DatePickerHelper.show(
                                    context = context,
                                    initialDate = selectedDate,
                                    onDatePicked = { pickedDate ->
                                        viewModel.selectDate(pickedDate)
                                    }
                                )
                            },
                        color = Color.Transparent
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp, horizontal = 4.dp)
                        ) {
                            Text(
                                text = selectedDate.format(viewModel.displayDateFormatter) + " " + "一二三四五六日"[selectedDate.dayOfWeek.value - 1],
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // 日期显示区域右边：日期标识区域
                    // 初始/今天时显示“今天”，非今天时显示“回到今天”
                    Surface(
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                            closeEditor()
                            if (!isToday) viewModel.returnToToday()
                        },
                        color = Color.Transparent
                    ) {
                        Text(
                            text = if (isToday) "今天" else "回到今天",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isToday) MaterialTheme.colorScheme.onSurface else AccentPrimary
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Box(
                        modifier = Modifier.size(40.dp)
                            .onGloballyPositioned { trashBounds = it.boundsInRoot() }
                            .clip(CircleShape)
                            .background(if (hoveringTrash) AccentDanger.copy(alpha = 0.2f) else Color.Transparent)
                            .combinedClickable(
                                onClick = { closeEditor(); showTrashDialog = true },
                                onLongClick = {
                                    closeEditor()
                                    showBackupPicker = true
                                    viewModel.loadBackupFiles()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = "回收站", tint = if (hoveringTrash) AccentDanger else SlateMuted)
                    }
                }
            }
        }
    ) { innerPadding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .clickable(enabled = editingNoteId != null) { closeEditor() }
        ) {
            // 始终留在组合树中的极小输入框：只在回车新增笔记时暂时接管焦点。
            // 新笔记的 BasicTextField 挂载后会自行 requestFocus()。
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
                            color = SlateMuted
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "点击此处开始记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SlateMuted.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        // 键盘出现时缩小列表可见区域，使列表能够滚动到键盘上方。
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(displayNotes, key = { _, note -> note.id }) { index, note ->
                        val isEditing = (editingNoteId == note.id)

                        BulletNoteRow(
                            modifier = Modifier
                                // 正在拖动的条目不参与 LazyColumn 的位置动画，避免双重动画造成反向跳动。
                                // 位置动画配置必须保持稳定：不要在按下/松开时切换所有条目的 placementSpec。
                                // 仅被拖动的条目禁用动画，避免与 graphicsLayer 的手指位移叠加。
                                .then(
                                    if (draggingNoteId == note.id) Modifier
                                    else Modifier.animateItem(
                                        fadeInSpec = null,
                                        fadeOutSpec = null,
                                        placementSpec = androidx.compose.animation.core.spring()
                                    )
                                )
                                .zIndex(if (draggingNoteId == note.id) 1f else 0f)
                                .graphicsLayer {
                                    if (draggingNoteId == note.id) {
                                        // 使用手指起点的绝对屏幕位置减去当前布局位置。
                                        // 列表交换位置时布局坐标会改变，但视觉坐标保持连续。
                                        val currentTop = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key == note.id }?.offset?.toFloat()
                                            ?: dragAnchorTop
                                        translationY = dragAnchorTop + draggingOffset - currentTop
                                        shadowElevation = 12.dp.toPx()
                                    }
                                },
                            note = note,
                            isEditing = isEditing,
                            onBulletClick = {
                                if (editingNoteId != note.id) closeEditor()
                                viewModel.cycleBulletType(note)
                            },
                            onBulletLongClick = {
                                closeEditor()
                                // 长按标识符弹出日期选择对话框
                                noteToChangeDate = note
                            },
                            onContentClick = {
                                if (editingNoteId == note.id) {
                                    closeEditor()
                                } else {
                                    // 在两条笔记之间切换时不要主动清除焦点，
                                    // 否则输入法会先收起再弹出，造成列表大幅上下跳动。
                                    // 新笔记的 LaunchedEffect(isEditing) 会主动请求焦点。
                                    viewModel.setEditingNoteId(note.id)
                                }
                            },
                            onDragStart = {
                                hoveringTrash = false
                                // 长按其他笔记开始拖动时，先结束之前笔记的编辑状态。
                                closeEditor()
                                val info = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == note.id }
                                if (info != null) {
                                    dragAnchorTop = info.offset.toFloat()
                                    draggingOffset = 0f
                                    dragStartIndex = notes.indexOfFirst { it.id == note.id }
                                    draggingNoteId = note.id
                                }
                            },
                            onDragDelta = dragDelta@{ delta, pointerRoot ->
                                hoveringTrash = trashBounds?.contains(pointerRoot) == true
                                if (draggingNoteId == note.id) {
                                    // 不在交换位置时重置或减去拖动距离。
                                    draggingOffset += delta
                                    if (hoveringTrash) return@dragDelta
                                    val current = displayNotes.indexOfFirst { it.id == note.id }
                                    val visible = listState.layoutInfo.visibleItemsInfo
                                    val dragged = visible.firstOrNull { it.key == note.id }
                                    if (current >= 0 && dragged != null) {
                                        val visualCenter = dragAnchorTop + draggingOffset + dragged.size / 2f
                                        // 只有手指带着条目越过相邻条目的中心点才交换。
                                        // 这样不会在阈值附近反复触发反向移动。
                                        val direction = when {
                                            delta > 0f -> 1
                                            delta < 0f -> -1
                                            else -> 0
                                        }
                                        val target = current + direction
                                        if (target in displayNotes.indices) {
                                            val neighborId = displayNotes[target].id
                                            val neighbor = visible.firstOrNull { it.key == neighborId }
                                            if (neighbor != null) {
                                                val neighborCenter = neighbor.offset + neighbor.size / 2f
                                                val crossed = if (direction > 0) {
                                                    visualCenter > neighborCenter
                                                } else {
                                                    visualCenter < neighborCenter
                                                }
                                                if (crossed) {
                                                    val moved = displayNotes.removeAt(current)
                                                    displayNotes.add(target, moved)
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            onDragFinish = { pointerRoot ->
                                val deleteOnDrop = pointerRoot != null && trashBounds?.contains(pointerRoot) == true
                                if (draggingNoteId == note.id && deleteOnDrop) {
                                    viewModel.moveToTrash(note.id)
                                } else if (draggingNoteId == note.id) {
                                    val endIndex = displayNotes.indexOfFirst { it.id == note.id }
                                    if (dragStartIndex >= 0 && endIndex >= 0 && dragStartIndex != endIndex) {
                                        viewModel.reorderNotes(dragStartIndex, endIndex)
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
                                // 按回车新增下一条笔记，自动附加当前日期
                                // 先将焦点转给常驻输入框，保证数据库插入期间输入法不失焦。
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
            onDismiss = { showTrashDialog = false },
            onRestore = { id -> viewModel.restoreFromTrash(id) },
            onDeleteBeforeToday = { viewModel.deleteTrashBeforeToday() }
        )
    }

    // 标识符长按修改日期的选择弹窗
    noteToChangeDate?.let { note ->
        DatePickerHelper.show(
            context = context,
            initialDate = selectedDate,
            onDatePicked = { pickedDate ->
                viewModel.changeNoteDate(note, pickedDate)
                noteToChangeDate = null
            },
            // 点击弹窗外部关闭时，同样清除待修改笔记，避免后续重组再次弹出。
            onDismiss = { noteToChangeDate = null }
        )
    }

    // 点击“今天”显示授权文件夹中的备份列表，选择后确认覆盖恢复。
    if (showBackupPicker) {
        AlertDialog(
            onDismissRequest = { showBackupPicker = false },
            title = { Text("选择要恢复的备份") },
            text = {
                Column {
                    if (backupFiles.isEmpty()) {
                        Text("没有找到飞记备份文件，或正在读取。")
                    } else {
                        backupFiles.forEach { file ->
                            TextButton(onClick = {
                                backupToRestore = file
                                showBackupPicker = false
                            }) { Text(file.name) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                viewModel.loadBackupFiles()
            }) { Text("刷新") } },
            dismissButton = { TextButton(onClick = { showBackupPicker = false }) { Text("取消") } }
        )
    }
    backupToRestore?.let { file ->
        AlertDialog(
            onDismissRequest = { backupToRestore = null },
            title = { Text("确认恢复备份") },
            text = { Text("将使用「${file.name}」覆盖软件中现有的全部笔记数据。此操作无法撤销，是否继续？") },
            confirmButton = { TextButton(onClick = {
                backupToRestore = null
                closeEditor()
                viewModel.restoreSelectedBackup(file.uri)
            }) { Text("确认覆盖恢复", color = AccentDanger) } },
            dismissButton = { TextButton(onClick = { backupToRestore = null }) { Text("取消") } }
        )
    }

}

/**
 * 单条子弹笔记组件：
 * - 前方标识符：方框、对勾、实心圆点，点击轮流切换，长按修改日期
 * - 笔记内容：未编辑时单行截断（maxLines = 1），点击进入编辑模式时显示完整内容并可多行输入
 * - 按回车触发 onEnterPressed 新增下一条
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BulletNoteRow(
    note: NoteEntity,
    isEditing: Boolean,
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
    val caretRequester = remember { BringIntoViewRequester() }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var dragCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var lastDragRoot by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    // 只在进入编辑状态时初始化光标；输入过程中保持 TextFieldValue 的选区。
    var editValue by remember(note.id, isEditing) {
        mutableStateOf(TextFieldValue(note.content, selection = TextRange(note.content.length)))
    }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            focusRequester.requestFocus()
            // 等待输入法实际出现、LazyColumn 重新测量可见高度后，再滚动当前笔记。
            // 不使用固定屏幕坐标，兼容不同键盘高度与屏幕尺寸。
            snapshotFlow { imeInsets.getBottom(density) }
                .first { it > 0 }
            delay(100)
            bringIntoViewRequester.bringIntoView()
        }
    }

    LaunchedEffect(isEditing, editValue.text, editValue.selection, textLayout) {
        if (isEditing && imeInsets.getBottom(density) > 0) {
            withFrameNanos { }
            textLayout?.let { layout ->
                val cursor = layout.getCursorRect(editValue.selection.end.coerceIn(0, editValue.text.length))
                val extra = with(density) { 8.dp.toPx() }
                caretRequester.bringIntoView(Rect(cursor.left, cursor.top, cursor.right + extra, cursor.bottom + extra))
            }
        }
    }

    Row(
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isEditing) MaterialTheme.colorScheme.surface else Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 标识符区域：方框 (SQUARE) / 对勾 (CHECKED) / 实心圆点 (BULLET_DOT)
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .combinedClickable(
                    onClick = onBulletClick,
                    onLongClick = onBulletLongClick
                ),
            contentAlignment = Alignment.Center
        ) {
            when (note.bulletType) {
                BulletType.SQUARE -> {
                    // 方框
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .border(1.5.dp, CharcoalGray, RoundedCornerShape(3.dp))
                    )
                }
                BulletType.CHECKED -> {
                    Box(modifier = Modifier.size(16.dp).border(1.5.dp, CharcoalGray, RoundedCornerShape(3.dp)))
                }
                BulletType.BULLET_DOT -> {
                    // 实心圆点
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(InkBlack, CircleShape)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 笔记内容区域
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(top = 4.dp)
                .then(
                    if (!isEditing) Modifier
                        .onGloballyPositioned { dragCoordinates = it }
                        .pointerInput(note.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { point ->
                                    lastDragRoot = dragCoordinates?.localToRoot(point)
                                    onDragStart()
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragCoordinates?.localToRoot(change.position)?.let { root ->
                                        lastDragRoot = root
                                        onDragDelta(amount.y, root)
                                    }
                                },
                                onDragEnd = { onDragFinish(lastDragRoot); lastDragRoot = null },
                                onDragCancel = { onDragFinish(null); lastDragRoot = null }
                            )
                        } else Modifier
                )
                .clickable(enabled = !isEditing) { onContentClick() }
        ) {
            if (!isEditing) {
                // 非编辑状态：当内容超过一行时，只显示一行内容 (maxLines = 1, TextOverflow.Ellipsis)
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                // 编辑状态：显示完整笔记内容，支持多行，回车在末尾创建下一条笔记
                BasicTextField(
                    value = editValue,
                    onValueChange = { newValue ->
                        editValue = newValue
                        if (newValue.text != note.content) onContentChange(newValue.text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(caretRequester)
                        .focusRequester(focusRequester)
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.key == Key.Enter) {
                                onEnterPressed()
                                true
                            } else {
                                false
                            }
                        },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    onTextLayout = { textLayout = it },
                    keyboardOptions = KeyboardOptions.Default.copy(
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { onEnterPressed() }
                    ),
                    decorationBox = { innerTextField ->
                        if (editValue.text.isEmpty()) {
                            Text(
                                text = "输入笔记内容...",
                                style = MaterialTheme.typography.bodyLarge.copy(color = SlateMuted)
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

    }
}
