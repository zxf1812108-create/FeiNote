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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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

    // 日历中需要标记的日期：只统计未删除笔记，并随数据库变化自动更新。
    val noteDates: StateFlow<Set<LocalDate>> = noteDao.getDatesWithActiveNotes()
        .map { dates -> dates.mapNotNull { value -> runCatching { LocalDate.parse(value) }.getOrNull() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // 回收站笔记列表
    val trashNotes: StateFlow<List<NoteEntity>> = noteDao.getDeletedNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 提示信息 / Toast 弹窗
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    // 文件夹首次授权与备份列表
    private val _needsBackupSetup = MutableStateFlow(false)
    val needsBackupSetup: StateFlow<Boolean> = _needsBackupSetup.asStateFlow()
    private val _backupFiles = MutableStateFlow<List<BackupManager.BackupFile>>(emptyList())
    val backupFiles: StateFlow<List<BackupManager.BackupFile>> = _backupFiles.asStateFlow()
    private val _todayBackupPrompt = MutableStateFlow<BackupManager.BackupFile?>(null)
    val todayBackupPrompt: StateFlow<BackupManager.BackupFile?> = _todayBackupPrompt.asStateFlow()
    private val backupMutex = Mutex()
    private var lastSnapshot: String? = null
    private var backupLoop: Job? = null

    fun dismissBackupSetup() { _needsBackupSetup.value = false }

    fun onBackupFolderSelected(uri: Uri, flags: Int) {
        _needsBackupSetup.value = false
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                BackupManager.setBackupFolder(app, uri, flags)
                backupMutex.withLock {
                    BackupManager.cleanOldBackupsOncePerDay(app)
                    // 必须先检查当天备份，再决定是否写入；否则初始化备份会覆盖待恢复文件。
                    val todayBackup = BackupManager.findTodayBackup(app)
                    if (todayBackup != null) {
                        _todayBackupPrompt.value = todayBackup
                        _userMessage.value = "发现当天备份，请选择是否恢复"
                        return@withLock
                    }
                    val success = BackupManager.exportBackup(app, noteDao)
                    if (success) lastSnapshot = BackupManager.snapshotJson(noteDao.getAllNotesForBackup())
                    _userMessage.value = if (success) "备份文件夹已设置，今日备份已保存" else "文件夹已设置，但备份写入失败"
                }
                if (_todayBackupPrompt.value == null) startBackupLoop()
            } catch (e: Exception) {
                _userMessage.value = "备份文件夹授权失败：${e.localizedMessage ?: "未知错误"}"
                _needsBackupSetup.value = true
            }
        }
    }

    /** 用户确认恢复首次选择文件夹时发现的当天备份。 */
    fun restoreTodayBackupFromPrompt() {
        val backup = _todayBackupPrompt.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            backupMutex.withLock {
                val app = getApplication<Application>()
                val (ok, message) = BackupManager.restoreBackup(app, noteDao, backup.uri)
                _userMessage.value = message
                if (ok) {
                    lastSnapshot = BackupManager.snapshotJson(noteDao.getAllNotesForBackup())
                    _todayBackupPrompt.value = null
                }
            }
            if (_todayBackupPrompt.value == null) startBackupLoop()
        }
    }

    /** 用户选择暂不恢复：保留当前数据库，并以当前内容作为自动备份比较基线。 */
    fun skipTodayBackupRestore() {
        if (_todayBackupPrompt.value == null) return
        _todayBackupPrompt.value = null
        viewModelScope.launch(Dispatchers.IO) {
            backupMutex.withLock {
                lastSnapshot = BackupManager.snapshotJson(noteDao.getAllNotesForBackup())
            }
            startBackupLoop()
        }
    }

    fun loadBackupFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _backupFiles.value = BackupManager.listBackups(getApplication())
                if (_backupFiles.value.isEmpty()) _userMessage.value = "备份文件夹中没有可恢复的备份"
            } catch (e: Exception) {
                _backupFiles.value = emptyList()
                _userMessage.value = "读取备份列表失败：${e.localizedMessage ?: "未知错误"}"
            }
        }
    }

    fun restoreSelectedBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            backupMutex.withLock {
                val app = getApplication<Application>()
                val (ok, message) = BackupManager.restoreBackup(app, noteDao, uri)
                _userMessage.value = message
                if (ok) lastSnapshot = BackupManager.snapshotJson(noteDao.getAllNotesForBackup())
            }
        }
    }

    private fun startBackupLoop() {
        if (backupLoop?.isActive == true) return
        backupLoop = viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            if (!BackupManager.hasBackupFolder(app)) return@launch
            try { BackupManager.cleanOldBackupsOncePerDay(app) } catch (e: Exception) {
                _userMessage.value = "清理旧备份失败：${e.localizedMessage ?: "未知错误"}"
            }
            // 启动时记录当前数据；以后每分钟检查一次，有变化才覆盖当日文件。
            lastSnapshot = BackupManager.snapshotJson(noteDao.getAllNotesForBackup())
            while (isActive) {
                delay(60_000L)
                backupMutex.withLock {
                    val current = BackupManager.snapshotJson(noteDao.getAllNotesForBackup())
                    if (current != lastSnapshot) {
                        if (BackupManager.exportBackup(app, noteDao)) lastSnapshot = current
                        else _userMessage.value = "自动备份失败，请检查备份文件夹权限"
                    }
                }
            }
        }
    }

    init {
        if (BackupManager.hasBackupFolder(application)) startBackupLoop()
        else _needsBackupSetup.value = true
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
            val nextType = if (note.bulletType == BulletType.BULLET_DOT) BulletType.SQUARE else BulletType.BULLET_DOT
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
        viewModelScope.launch(Dispatchers.IO) {
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

}
