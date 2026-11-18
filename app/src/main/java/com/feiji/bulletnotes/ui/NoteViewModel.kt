package com.feiji.bulletnotes.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.feiji.bulletnotes.data.AppDatabase
import com.feiji.bulletnotes.data.BackupManager
import com.feiji.bulletnotes.data.BulletType
import com.feiji.bulletnotes.data.NoteEntity
import com.feiji.bulletnotes.data.WebDavBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // 搜索结果：仅包含未删除、符合指定标识符且正文匹配关键词的笔记。
    private val _searchResults = MutableStateFlow<List<NoteEntity>>(emptyList())
    val searchResults: StateFlow<List<NoteEntity>> = _searchResults.asStateFlow()
    @Volatile private var activeSearchKeywords: List<String> = emptyList()
    @Volatile private var activeSearchBulletType: BulletType? = null

    /** 按正文检索全部有效笔记；空格分隔多个词，要求每个词都出现在正文中，并可按标识符类型限定搜索范围。 */
    fun searchNotes(keyword: String, bulletType: BulletType? = null) {
        val queryWords = keyword.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        activeSearchKeywords = queryWords
        activeSearchBulletType = bulletType
        if (queryWords.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val allNotes = noteDao.getAllNotesForBackup()
            if (activeSearchKeywords == queryWords && activeSearchBulletType == bulletType) {
                _searchResults.value = allNotes
                    .asSequence()
                    .filter { note ->
                        !note.isDeleted &&
                            (bulletType == null || note.bulletType == bulletType) &&
                            queryWords.all { word ->
                                note.content.contains(word, ignoreCase = true)
                            }
                    }
                    .sortedWith(compareByDescending<NoteEntity> { it.dateString }.thenBy { it.orderIndex })
                    .toList()
            }
        }
    }

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
    private val webDavMutex = Mutex()
    private var lastSnapshot: String? = null
    private var backupLoop: Job? = null
    private var webDavLoop: Job? = null
    // 保留最近一次正文写入任务，并串成队列，确保退出编辑时能等待全部输入落库。
    @Volatile private var latestContentWriteJob: Job? = null

    data class PendingEncryptedRestore(
        val file: BackupManager.BackupFile,
        val fromTodayPrompt: Boolean,
        val wrongPassword: Boolean = false
    )

    private val _pendingEncryptedRestore = MutableStateFlow<PendingEncryptedRestore?>(null)
    val pendingEncryptedRestore: StateFlow<PendingEncryptedRestore?> = _pendingEncryptedRestore.asStateFlow()

    // WebDAV 配置与 21:00 后未完成备份的感叹号提示状态
    private val _webDavConfig = MutableStateFlow(WebDavBackupManager.getConfig(application))
    val webDavConfig: StateFlow<WebDavBackupManager.WebDavConfig> = _webDavConfig.asStateFlow()

    private val _showWebDavAlert = MutableStateFlow(WebDavBackupManager.shouldShowAlert(application))
    val showWebDavAlert: StateFlow<Boolean> = _showWebDavAlert.asStateFlow()

    fun setWebDavEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        _webDavConfig.value = WebDavBackupManager.setEnabled(app, enabled)
        _showWebDavAlert.value = WebDavBackupManager.shouldShowAlert(app)
        if (enabled) {
            triggerSilentWebDavBackup()
        }
    }

    fun saveWebDavSettings(
        serverUrl: String,
        remoteFolder: String,
        username: String,
        password: String,
        encryptionPassword: String = ""
    ) {
        val app = getApplication<Application>()
        _webDavConfig.value = WebDavBackupManager.saveSettings(
            context = app,
            serverUrl = serverUrl,
            remoteFolder = remoteFolder,
            username = username,
            password = password,
            encryptionPassword = encryptionPassword
        )
        _showWebDavAlert.value = WebDavBackupManager.shouldShowAlert(app)
        if (_webDavConfig.value.enabled) {
            triggerSilentWebDavBackup()
        }
    }

    /** 静默尝试执行今日的 WebDAV（昨天数据）备份，全程无任何提示打扰。 */
    fun triggerSilentWebDavBackup() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            webDavMutex.withLock {
                WebDavBackupManager.backupYesterdayIfNeeded(app, noteDao)
                _showWebDavAlert.value = WebDavBackupManager.shouldShowAlert(app)
            }
        }
    }

    private fun startWebDavLoop() {
        if (webDavLoop?.isActive == true) return
        webDavLoop = viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            var minutesElapsed = 0
            // 启动时先静默检查并尝试备份一次。
            webDavMutex.withLock {
                WebDavBackupManager.backupYesterdayIfNeeded(app, noteDao)
                _showWebDavAlert.value = WebDavBackupManager.shouldShowAlert(app)
            }
            while (isActive) {
                delay(60_000L)
                minutesElapsed++
                val config = WebDavBackupManager.getConfig(app)
                val completed = WebDavBackupManager.isTodayBackupCompleted(app)
                // 开启但今日尚未成功时，每 15 分钟静默重试；21:00 后每 5 分钟静默重试。
                val retryIntervalMinutes = if (WebDavBackupManager.shouldShowAlert(app)) 5 else 15
                if (config.enabled && !completed && minutesElapsed % retryIntervalMinutes == 0) {
                    webDavMutex.withLock {
                        WebDavBackupManager.backupYesterdayIfNeeded(app, noteDao)
                    }
                }
                _showWebDavAlert.value = WebDavBackupManager.shouldShowAlert(app)
            }
        }
    }

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

    /** 用户确认恢复首次选择文件夹时发现的当天备份；若为加密文件则优先尝试已保存加密密码，否则弹出密码框。 */
    fun restoreTodayBackupFromPrompt() {
        val backup = _todayBackupPrompt.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            var shouldStartLoop = false
            backupMutex.withLock {
                val app = getApplication<Application>()
                val savedPassword = WebDavBackupManager.getConfig(app).encryptionPassword.takeIf { it.isNotEmpty() }
                when (val result = BackupManager.restoreBackup(app, noteDao, backup.uri, savedPassword)) {
                    is BackupManager.RestoreResult.Success -> {
                        _userMessage.value = result.message
                        lastSnapshot = BackupManager.snapshotJson(noteDao.getAllNotesForBackup())
                        _todayBackupPrompt.value = null
                        shouldStartLoop = true
                    }

                    is BackupManager.RestoreResult.PasswordRequired -> {
                        _todayBackupPrompt.value = null
                        _pendingEncryptedRestore.value = PendingEncryptedRestore(
                            file = backup,
                            fromTodayPrompt = true,
                            wrongPassword = false
                        )
                    }

                    is BackupManager.RestoreResult.Failure -> {
                        _userMessage.value = result.message
                    }
                }
            }
            if (shouldStartLoop) startBackupLoop()
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

    fun restoreSelectedBackup(file: BackupManager.BackupFile) {
        viewModelScope.launch(Dispatchers.IO) {
            backupMutex.withLock {
                val app = getApplication<Application>()
                val savedPassword = WebDavBackupManager.getConfig(app).encryptionPassword.takeIf { it.isNotEmpty() }
                when (val result = BackupManager.restoreBackup(app, noteDao, file.uri, savedPassword)) {
                    is BackupManager.RestoreResult.Success -> {
                        _userMessage.value = result.message
                        lastSnapshot = BackupManager.snapshotJson(noteDao.getAllNotesForBackup())
                    }

                    is BackupManager.RestoreResult.PasswordRequired -> {
                        _pendingEncryptedRestore.value = PendingEncryptedRestore(
                            file = file,
                            fromTodayPrompt = false,
                            wrongPassword = false
                        )
                    }

                    is BackupManager.RestoreResult.Failure -> {
                        _userMessage.value = result.message
                    }
                }
            }
        }
    }

    /** 用户在解密弹窗中输入密码后执行解密恢复。 */
    fun confirmEncryptedRestore(password: String) {
        val pending = _pendingEncryptedRestore.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            var shouldStartLoop = false
            backupMutex.withLock {
                val app = getApplication<Application>()
                when (val result = BackupManager.restoreBackup(app, noteDao, pending.file.uri, password)) {
                    is BackupManager.RestoreResult.Success -> {
                        _pendingEncryptedRestore.value = null
                        _userMessage.value = result.message
                        lastSnapshot = BackupManager.snapshotJson(noteDao.getAllNotesForBackup())
                        shouldStartLoop = pending.fromTodayPrompt
                    }

                    is BackupManager.RestoreResult.PasswordRequired -> {
                        _pendingEncryptedRestore.value = pending.copy(wrongPassword = true)
                    }

                    is BackupManager.RestoreResult.Failure -> {
                        _pendingEncryptedRestore.value = null
                        _userMessage.value = result.message
                        if (pending.fromTodayPrompt) {
                            _todayBackupPrompt.value = pending.file
                        }
                    }
                }
            }
            if (shouldStartLoop) startBackupLoop()
        }
    }

    fun cancelEncryptedRestore() {
        val pending = _pendingEncryptedRestore.value ?: return
        _pendingEncryptedRestore.value = null
        if (pending.fromTodayPrompt) {
            _todayBackupPrompt.value = pending.file
        }
    }

    /**
     * 在正文写入锁和备份锁都已持有时调用。
     * 只有完整数据快照发生变化才写备份；写入失败时不更新比较基线，留待下次重试。
     */
    private suspend fun backupIfChangedLocked(app: Application) {
        if (!BackupManager.hasBackupFolder(app)) return
        val current = BackupManager.snapshotJson(noteDao.getAllNotesForBackup())
        if (current != lastSnapshot) {
            if (BackupManager.exportBackup(app, noteDao)) {
                lastSnapshot = current
            } else {
                _userMessage.value = "自动备份失败，请检查备份文件夹权限"
            }
        }
    }

    /** 立即检查并备份；同时等待正在进行的正文写入，避免导出旧内容。 */
    private suspend fun backupIfChanged(app: Application) {
        contentWriteMutex.withLock {
            backupMutex.withLock {
                backupIfChangedLocked(app)
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
            // 启动时记录当前数据作为比较基线；退出编辑时立即备份，另每 5 分钟兜底检查。
            contentWriteMutex.withLock {
                backupMutex.withLock {
                    lastSnapshot = BackupManager.snapshotJson(noteDao.getAllNotesForBackup())
                }
            }
            while (isActive) {
                delay(5 * 60_000L)
                backupIfChanged(app)
            }
        }
    }

    init {
        if (BackupManager.hasBackupFolder(application)) startBackupLoop()
        else _needsBackupSetup.value = true
        startWebDavLoop()
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    /**
     * 选择日期：更新当前日期，同步更新今天/回到今天状态
     */
    fun selectDate(newDate: LocalDate) {
        val now = LocalDate.now()
        _today.value = now
        _selectedDate.value = newDate
        _isToday.value = (newDate == now)
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
     * 跳转到最早一条以五角星做标识符的有效笔记所在日期
     */
    fun jumpToEarliestStarNoteDate() {
        viewModelScope.launch(Dispatchers.IO) {
            val earliestDate = noteDao.getAllActiveNotes()
                .asSequence()
                .filter { it.bulletType == BulletType.STAR }
                .mapNotNull { runCatching { LocalDate.parse(it.dateString) }.getOrNull() }
                .minOrNull()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (earliestDate != null) {
                    selectDate(earliestDate)
                } else {
                    _userMessage.value = "暂无带五角星标记的笔记"
                }
            }
        }
    }

    /**
     * 设置编辑中的笔记 ID
     */
    fun setEditingNoteId(id: Long?) {
        val previousId = _editingNoteId.value
        if (previousId == id) return
        _editingNoteId.value = id
        if (previousId != null) {
            // 退出编辑时先等待最后一次正文写入，再清理空笔记、自动调整符号，最后立即备份。
            val pendingContentWrite = latestContentWriteJob
            viewModelScope.launch(Dispatchers.IO) {
                pendingContentWrite?.join()
                val app = getApplication<Application>()
                contentWriteMutex.withLock {
                    val previous = noteDao.getNoteById(previousId)
                    if (previous != null && !previous.isDeleted) {
                        if (previous.content.isBlank()) {
                            noteDao.deletePermanently(previousId)
                        } else {
                            val hasLineBreak = previous.content.contains('\n')
                            val nextBulletType = when {
                                hasLineBreak && previous.bulletType in setOf(
                                    BulletType.SQUARE,
                                    BulletType.CHECKED,
                                    BulletType.BULLET_DOT
                                ) -> BulletType.STAR

                                !hasLineBreak && previous.bulletType == BulletType.STAR ->
                                    BulletType.BULLET_DOT

                                else -> previous.bulletType
                            }

                            if (nextBulletType != previous.bulletType) {
                                noteDao.updateNote(
                                    previous.copy(
                                        bulletType = nextBulletType,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                    // 保持正文写入锁直到备份导出完成，避免备份到编辑过程中的半成品。
                    backupMutex.withLock {
                        backupIfChangedLocked(app)
                    }
                }
            }
        }
    }

    /**
     * 点击标识符：在 方框 (SQUARE) -> 对勾 (CHECKED) -> 实心圆点 (BULLET_DOT) 之间轮流切换
     */
    fun cycleBulletType(note: NoteEntity) {
        // 五角星由笔记内容自动判定生成，点击时保持不变。
        if (note.bulletType == BulletType.STAR) return

        viewModelScope.launch(Dispatchers.IO) {
            val nextType = if (note.bulletType == BulletType.BULLET_DOT) BulletType.SQUARE else BulletType.BULLET_DOT
            val updated = note.copy(bulletType = nextType, updatedAt = System.currentTimeMillis())
            noteDao.updateNote(updated)
            _searchResults.value = _searchResults.value.map { if (it.id == updated.id) updated else it }
        }
    }

    /**
     * 长按标识符修改笔记日期：
     * 若所选日期与当前展示日期不同，该笔记将自动从当前列表视图中移除（因为仅显示匹配当前日期的笔记）
     */
    fun changeNoteDate(note: NoteEntity, newDate: LocalDate) {
        viewModelScope.launch(Dispatchers.IO) {
            val newDateStr = newDate.format(dateFormatter)
            val updated = note.copy(dateString = newDateStr, updatedAt = System.currentTimeMillis())
            noteDao.updateNote(updated)
            _searchResults.value = _searchResults.value
                .map { if (it.id == updated.id) updated else it }
                .sortedWith(compareByDescending<NoteEntity> { it.dateString }.thenBy { it.orderIndex })
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
        // 将每次输入写入串成任务队列；退出编辑时等待队列末尾即可确认正文已全部落库。
        val previousWrite = latestContentWriteJob
        latestContentWriteJob = viewModelScope.launch(Dispatchers.IO) {
            previousWrite?.join()
            contentWriteMutex.withLock {
                noteDao.updateNoteContent(id, newContent, System.currentTimeMillis())
                _searchResults.value = _searchResults.value.map {
                    if (it.id == id) it.copy(content = newContent, updatedAt = System.currentTimeMillis()) else it
                }.filter { note ->
                    note.content.isNotBlank() && activeSearchKeywords.all { word ->
                        note.content.contains(word, ignoreCase = true)
                    }
                }
            }
        }
    }

    /**
     * 在某条笔记后按回车，新增下一条笔记，附加当前选中的日期属性
     */
    fun insertNoteAfter(referenceNote: NoteEntity?, initialContent: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val currentDateStr = referenceNote?.dateString
                ?: _selectedDate.value.format(dateFormatter)
            val currentList = if (referenceNote != null && referenceNote.dateString != _selectedDate.value.format(dateFormatter)) {
                noteDao.getNotesByDate(currentDateStr).first()
            } else {
                notes.value
            }

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
     * 删除笔记放入回收站 (软删除)，并静默限制回收站最多保留 30 条笔记（超出则自动永久删除最早的笔记）。
     */
    fun moveToTrash(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            noteDao.softDeleteNote(id)
            val deletedNotes = noteDao.getDeletedNotes().first()
            if (deletedNotes.size > 30) {
                val overflowCount = deletedNotes.size - 30
                deletedNotes
                    .sortedWith(
                        compareBy<NoteEntity> { it.deletedAt ?: it.updatedAt }
                            .thenBy { it.updatedAt }
                            .thenBy { it.id }
                    )
                    .take(overflowCount)
                    .forEach { oldNote ->
                        noteDao.deletePermanently(oldNote.id)
                    }
            }
            _searchResults.value = _searchResults.value.filterNot { it.id == id }
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

}
