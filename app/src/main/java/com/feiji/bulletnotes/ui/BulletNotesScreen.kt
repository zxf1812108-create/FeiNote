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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
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
import java.time.LocalDate

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BulletNotesScreen(viewModel: NoteViewModel) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val selectedDate by viewModel.selectedDate.collectAsState()
    val isToday by viewModel.isToday.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val trashNotes by viewModel.trashNotes.collectAsState()
    val editingNoteId by viewModel.editingNoteId.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()

    var showTrashDialog by remember { mutableStateOf(false) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }
    var noteToChangeDate by remember { mutableStateOf<NoteEntity?>(null) }

    val listState = rememberLazyListState()
    // 拖动期间只更新内存中的显示顺序；松手后再保存到数据库。
    val displayNotes = remember { mutableStateListOf<NoteEntity>() }
    var draggingNoteId by remember { mutableStateOf<Long?>(null) }
    var draggingOffset by remember { mutableStateOf(0f) }
    var dragAnchorTop by remember { mutableStateOf(0f) }
    var dragStartIndex by remember { mutableStateOf(-1) }

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
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 12.dp)
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
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
                        ) {
                            Text(
                                text = selectedDate.format(viewModel.displayDateFormatter),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // 日期显示区域右边：日期标识区域
                    // 初始/今天时显示“今天”，非今天时显示“回到今天”
                    // 点击“回到今天”恢复今天；点击“今天”弹出恢复今日初始数据备份询问对话框
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                if (isToday) {
                                    // 点击“今天”：弹出对话框询问用户是否恢复今天初始数据备份
                                    showRestoreConfirmDialog = true
                                } else {
                                    // 点击“回到今天”：恢复今天
                                    viewModel.returnToToday()
                                }
                            },
                        color = if (isToday) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        } else {
                            AccentPrimary.copy(alpha = 0.15f)
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (isToday) "今天" else "回到今天",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isToday) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                AccentPrimary
                            }
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // 右上角：回收站标识
                    // 不显示删除的笔记数量，点击显示回收站列表
                    IconButton(
                        onClick = { showTrashDialog = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "回收站",
                            tint = SlateMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = LineBorder.copy(alpha = 0.6f))
            }
        }
    ) { innerPadding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .clickable(enabled = editingNoteId != null) {
                    viewModel.setEditingNoteId(null)
                    focusManager.clearFocus()
                }
        ) {
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
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(displayNotes, key = { _, note -> note.id }) { index, note ->
                        val isEditing = (editingNoteId == note.id)

                        BulletNoteRow(
                            modifier = Modifier
                                // 正在拖动的条目不参与 LazyColumn 的位置动画，避免双重动画造成反向跳动。
                                .then(if (draggingNoteId == note.id) Modifier else Modifier.animateItem())
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
                            onBulletClick = { viewModel.cycleBulletType(note) },
                            onBulletLongClick = {
                                // 长按标识符弹出日期选择对话框
                                noteToChangeDate = note
                            },
                            onContentClick = {
                                if (editingNoteId == note.id) {
                                    viewModel.setEditingNoteId(null)
                                    focusManager.clearFocus()
                                } else {
                                    viewModel.setEditingNoteId(note.id)
                                }
                            },
                            onDragStart = {
                                val info = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == note.id }
                                if (info != null) {
                                    dragAnchorTop = info.offset.toFloat()
                                    draggingOffset = 0f
                                    dragStartIndex = notes.indexOfFirst { it.id == note.id }
                                    draggingNoteId = note.id
                                }
                            },
                            onDragDelta = { delta ->
                                if (draggingNoteId == note.id) {
                                    // 不在交换位置时重置或减去拖动距离。
                                    draggingOffset += delta
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
                            onDragFinish = {
                                if (draggingNoteId == note.id) {
                                    val endIndex = displayNotes.indexOfFirst { it.id == note.id }
                                    if (dragStartIndex >= 0 && endIndex >= 0 && dragStartIndex != endIndex) {
                                        viewModel.reorderNotes(dragStartIndex, endIndex)
                                    }
                                }
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
                                viewModel.insertNoteAfter(note, "")
                            },
                            onDelete = {
                                viewModel.moveToTrash(note.id)
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
            }
        )
    }

    // 点击“今天”弹出的恢复确认对话框
    // 需求：“点击今日标签时弹出对话框，询问用户是否恢复今天初始数据备份，如果选是，则自动读取 Download 文件夹中的备份文件恢复数据。”
    if (showRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmDialog = false },
            title = {
                Text(text = "恢复数据备份", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(text = "是否恢复今天初始数据备份？\n\n选择“是”将从系统的 Download 文件夹中读取《飞记每日自动备份.json》恢复数据。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirmDialog = false
                        viewModel.restoreTodayBackup { _, _ -> }
                    }
                ) {
                    Text(text = "是", fontWeight = FontWeight.Bold, color = AccentPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmDialog = false }) {
                    Text(text = "取消")
                }
            }
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
    onDelete: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragFinish: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    // 只在进入编辑状态时初始化光标；输入过程中保持 TextFieldValue 的选区。
    var editValue by remember(note.id, isEditing) {
        mutableStateOf(TextFieldValue(note.content, selection = TextRange(note.content.length)))
    }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            focusRequester.requestFocus()
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isEditing) MaterialTheme.colorScheme.surface else Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = if (isEditing) Alignment.Top else Alignment.CenterVertically
    ) {
        // 标识符区域：方框 (SQUARE) / 对勾 (CHECKED) / 实心圆点 (BULLET_DOT)
        Box(
            modifier = Modifier
                .padding(top = if (isEditing) 4.dp else 0.dp)
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
                    // 对勾
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(AccentPrimary, RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "已完成",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
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
                .then(
                    if (!isEditing) Modifier.pointerInput(note.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDrag = { change, amount ->
                                change.consume()
                                onDragDelta(amount.y)
                            },
                            onDragEnd = onDragFinish,
                            onDragCancel = onDragFinish
                        )
                    } else Modifier
                )
                .clickable(enabled = !isEditing) { onContentClick() }
        ) {
            if (!isEditing) {
                // 非编辑状态：当内容超过一行时，只显示一行内容 (maxLines = 1, TextOverflow.Ellipsis)
                Text(
                    text = if (note.content.isBlank()) "空笔记" else note.content,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        textDecoration = if (note.bulletType == BulletType.CHECKED) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (note.bulletType == BulletType.CHECKED) SlateMuted else MaterialTheme.colorScheme.onSurface
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

        // 编辑状态下的右侧辅助操作（完成、移入回收站）
        if (isEditing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "删除",
                        tint = AccentDanger,
                        modifier = Modifier.size(18.dp)
                    )
                }

            }
        }
    }
}
