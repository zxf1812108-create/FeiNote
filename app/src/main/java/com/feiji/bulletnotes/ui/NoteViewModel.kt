package com.feiji.bulletnotes.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.feiji.bulletnotes.data.AppDatabase
import com.feiji.bulletnotes.data.BackupManager
import com.feiji.bulletnotes.data.BulletType
import com.feiji.bulletnotes.data.NoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class NoteViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val noteDao = db.noteDao()
    private val contentWriteMutex = Mutex()

    val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)
    val displayDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yy-MM-dd", Locale.CHINA)

    // 今天日期
    private val _today = MutableStateFlow(LocalDate.now())
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    // 当前选中的查看日期
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    // 当前日期对应的格式化字符串
    val selectedDateString: StateFlow<String> = MutableStateFlow(
        LocalDate.now().format(dateFormatter)
    )

    // 当前是否停留在今天
    private val _isToday = MutableStateFlow(true)
    val isToday: StateFlow<Boolean> = _isToday.asStateFlow()

    // 当前处于编辑状态的笔记 ID（null 表示无单项进入多行编辑）
    private val _editingNoteId = MutableStateFlow<Long?>(null)
    val editingNoteId: StateFlow<Long?> = _editingNoteId.asStateFlow()

    // 当前日期的有效笔记列表（根据当前日期流式响应）
    val notes: StateFlow<List<NoteEntity>> = _selectedDate
        .flatMapLatest { date ->
            val dateStr = date.format(dateFormatter)
            noteDao.getNotesByDate(dateStr)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 回收站笔记列表
    val trashNotes: StateFlow<List<NoteEntity>> = noteDao.getDeletedNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 提示信息 / Toast 弹窗
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    // 首次备份授权提示：每次应用进程最多提示一次，避免取消后反复弹窗。
    private val _needsBackupSetup = MutableStateFlow(false)
    val needsBackupSetup: StateFlow<Boolean> = _needsBackupSetup.asStateFlow()
    private var backupPromptShown = false

    fun dismissBackupSetup() { _needsBackupSetup.value = false }

    fun onBackupDocumentSelected(uri: Uri, flags: Int) {
        _needsBackupSetup.value = false
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                BackupManager.setBackupDocument(app, uri, flags)
                val success = BackupManager.checkAndPerformDailyBackup(app, noteDao)
                _userMessage.value = if (success) "今日备份已保存" else "备份未成功，请重新选择可写入的文件"
            } catch (e: Exception) {
                _userMessage.value = "备份文件授权失败：${e.localizedMessage ?: "未知错误"}"
            }
        }
    }

    init {
        // App 启动时自动检查并执行每日首次自动备份
        checkDailyBackup()
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    /**
     * 选择日期：更新当前日期，同步更新今天/回到今天状态
     */
    fun selectDate(newDate: LocalDate) {
        _selectedDate.value = newDate
        _isToday.value = (newDate == _today.value)
        setEditingNoteId(null)
    }

    /**
     * 回到今天：日期恢复为今天，标识恢复为“今天”
     */
    fun returnToToday() {
        val now = LocalDate.now()
        _today.value = now
        _selectedDate.value = now
        _isToday.value = true
        setEditingNoteId(null)
    }

    /**
     * 设置编辑中的笔记 ID
     */
    fun setEditingNoteId(id: Long?) {
        val previousId = _editingNoteId.value
        if (previousId == id) return
        _editingNoteId.value = id
        if (previousId != null) {
            // 与逐字保存共用互斥锁：先完成已经排队的文字写入，再检查空笔记。
            viewModelScope.launch {
                contentWriteMutex.withLock {
                    val previous = noteDao.getNoteById(previousId)
                    if (previous != null && !previous.isDeleted && previous.content.isBlank()) {
                        noteDao.deletePermanently(previousId)
                    }
                }
            }
        }
    }

    /**
     * 点击标识符：在 方框 (SQUARE) -> 对勾 (CHECKED) -> 实心圆点 (BULLET_DOT) 之间轮流切换
     */
    fun cycleBulletType(note: NoteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val nextType = note.bulletType.next()
            noteDao.updateNote(note.copy(bulletType = nextType, updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * 长按标识符修改笔记日期：
     * 若所选日期与当前展示日期不同，该笔记将自动从当前列表视图中移除（因为仅显示匹配当前日期的笔记）
     */
    fun changeNoteDate(note: NoteEntity, newDate: LocalDate) {
        viewModelScope.launch(Dispatchers.IO) {
            val newDateStr = newDate.format(dateFormatter)
            noteDao.updateNote(note.copy(dateString = newDateStr, updatedAt = System.currentTimeMillis()))
            if (newDate != _selectedDate.value) {
                _userMessage.value = "笔记已移动至 $newDateStr"
            }
        }
    }

    /**
     * 更新笔记内容
     */
    fun updateNoteContent(id: Long, newContent: String) {
        // 串行写入，避免快速输入时旧内容覆盖新内容；只更新正文，不覆盖排序等字段。
        viewModelScope.launch {
            contentWriteMutex.withLock {
                noteDao.updateNoteContent(id, newContent, System.currentTimeMillis())
            }
        }
    }

    /**
     * 在某条笔记后按回车，新增下一条笔记，附加当前选中的日期属性
     */
    fun insertNoteAfter(referenceNote: NoteEntity?, initialContent: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val currentDateStr = _selectedDate.value.format(dateFormatter)
            val currentList = notes.value

            val newOrder = if (referenceNote != null) {
                referenceNote.orderIndex + 1
            } else {
                (currentList.maxOfOrNull { it.orderIndex } ?: 0) + 1
            }

            // 更新后续项的 orderIndex
            val updatedList = currentList.map { item ->
                if (item.orderIndex >= newOrder) {
                    item.copy(orderIndex = item.orderIndex + 1)
                } else {
                    item
                }
            }
            if (updatedList.isNotEmpty()) {
                noteDao.updateNotes(updatedList)
            }

            val newNote = NoteEntity(
                content = initialContent,
                dateString = currentDateStr,
                bulletType = BulletType.SQUARE,
                orderIndex = newOrder
            )
            val newId = noteDao.insertNote(newNote)
            kotlinx.coroutines.withContext(Dispatchers.Main) { setEditingNoteId(newId) }
        }
    }

    /**
     * 拖拽重排序
     */
    fun reorderNotes(fromIndex: Int, toIndex: Int) {
        val currentList = notes.value.toMutableList()
        if (fromIndex !in currentList.indices || toIndex !in currentList.indices || fromIndex == toIndex) return

        val movedItem = currentList.removeAt(fromIndex)
        currentList.add(toIndex, movedItem)

        // 重新分配连续 orderIndex
        val updatedList = currentList.mapIndexed { index, note ->
            note.copy(orderIndex = index)
        }

        viewModelScope.launch(Dispatchers.IO) {
            noteDao.updateNotes(updatedList)
        }
    }

    /**
     * 删除笔记放入回收站 (软删除)
     */
    fun moveToTrash(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            noteDao.softDeleteNote(id)
            if (_editingNoteId.value == id) {
                _editingNoteId.value = null
            }
        }
    }

    /**
     * 从回收站恢复笔记
     */
    fun restoreFromTrash(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            noteDao.restoreNote(id)
        }
    }

    /**
     * 彻底删除单条笔记
     */
    fun permanentlyDelete(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            noteDao.deletePermanently(id)
        }
    }

    /**
     * 清空回收站全部删除
     */
    fun deleteTrashBeforeToday() {
        viewModelScope.launch(Dispatchers.IO) {
            val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val count = noteDao.deleteTrashOlderThan(todayStart - 1L)
            _userMessage.value = "已删除昨天及以前的 $count 条回收站笔记"
        }
    }

    /**
     * 检查并执行每日首次自动备份
     */
    private fun checkDailyBackup() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            if (!BackupManager.hasBackupDocument(app)) {
                if (!backupPromptShown) {
                    backupPromptShown = true
                    _needsBackupSetup.value = true
                }
            } else {
                val result = BackupManager.checkAndPerformDailyBackup(app, noteDao)
                if (!result && !BackupManager.hasBackupDocument(app) && !backupPromptShown) {
                    backupPromptShown = true
                    _needsBackupSetup.value = true
                }
            }
        }
    }

    /**
     * 恢复今天初始数据备份
     */
    fun restoreTodayBackup(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val (success, message) = BackupManager.restoreTodayBackup(app, noteDao)
            _userMessage.value = message
            kotlinx.coroutines.withContext(Dispatchers.Main) { onResult(success, message) }
        }
    }
}
