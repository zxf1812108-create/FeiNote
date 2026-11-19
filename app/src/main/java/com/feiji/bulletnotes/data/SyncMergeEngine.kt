package com.feiji.bulletnotes.data

import android.content.Context
import java.security.MessageDigest

/**
 * 统一多设备确定性合并内核（供坚果云 WebDAV 准实时同步、同 Wi-Fi 自动同步与每日备份防覆盖共用）：
 * - 按 syncId + updatedAt（LWW）合并笔记，并结合 90 天删除墓碑防止已彻底删除笔记复活；
 * - 兼容旧版备份恢复产生的同内容不同 syncId 重复笔记，按字典序确定性收敛；
 * - 确定性执行回收站 30 条上限，确保所有设备收敛到完全一致的状态摘要。
 */
object SyncMergeEngine {
    const val TRASH_MAX_COUNT = 30
    const val TOMBSTONE_RETENTION_MS = 90L * 24 * 60 * 60 * 1000

    data class SyncableSettings(
        val updatedAt: Long = 0L,
        val serverUrl: String = WebDavBackupManager.DEFAULT_SERVER_URL,
        val remoteFolder: String = WebDavBackupManager.DEFAULT_REMOTE_FOLDER,
        val username: String = "",
        val password: String = "",
        val wifiAutoSyncEnabled: Boolean = false
    )

    data class MergeResult(
        val notesChanged: Boolean = false,
        val settingsChanged: Boolean = false,
        val changedNoteCount: Int = 0
    )

    data class DailyBackupMergeResult(
        val mergedNotes: List<NoteEntity>,
        val remoteWasAlreadyUpToDate: Boolean
    )

    fun readLocalSyncableSettings(context: Context): SyncableSettings {
        val cfg = WebDavBackupManager.getConfig(context)
        return SyncableSettings(
            updatedAt = WebDavBackupManager.getSettingsUpdatedAt(context),
            serverUrl = cfg.serverUrl,
            remoteFolder = cfg.remoteFolder,
            username = cfg.username,
            password = cfg.password,
            wifiAutoSyncEnabled = cfg.wifiAutoSyncEnabled
        )
    }

    fun normalizeSyncId(note: NoteEntity): String {
        @Suppress("USELESS_ELVIS")
        val trimmed = (note.syncId ?: "").trim()
        if (trimmed.isNotEmpty()) return trimmed
        @Suppress("USELESS_ELVIS")
        val safeDate = (note.dateString ?: "").trim()
        @Suppress("USELESS_ELVIS")
        val safeContent = note.content ?: ""
        val digest = sha256Hex("$safeDate|${note.createdAt}|$safeContent".toByteArray(Charsets.UTF_8)).take(16)
        return "legacy-$digest"
    }

    fun sanitizeRemoteNotes(rawNotes: List<NoteEntity>): List<NoteEntity> {
        val result = ArrayList<NoteEntity>(rawNotes.size)
        for (raw in rawNotes) {
            @Suppress("USELESS_ELVIS")
            val dateString = (raw.dateString ?: "").trim()
            @Suppress("USELESS_ELVIS")
            val content = raw.content ?: ""
            if (dateString.isEmpty() || content.length > 200_000) continue
            @Suppress("USELESS_ELVIS")
            val bulletType = raw.bulletType ?: BulletType.SQUARE
            val normalizedSid = normalizeSyncId(raw.copy(dateString = dateString, content = content))
            result.add(
                raw.copy(
                    id = 0L,
                    syncId = normalizedSid,
                    content = content,
                    dateString = dateString,
                    bulletType = bulletType
                )
            )
        }
        return result
    }

    fun sanitizeRemoteTombstones(rawTombstones: List<DeletedNoteTombstone>): List<DeletedNoteTombstone> {
        val result = ArrayList<DeletedNoteTombstone>(rawTombstones.size)
        for (raw in rawTombstones) {
            @Suppress("USELESS_ELVIS")
            val syncId = (raw.syncId ?: "").trim()
            if (syncId.isEmpty() || raw.deletedAt <= 0L) continue
            result.add(DeletedNoteTombstone(syncId = syncId, deletedAt = raw.deletedAt))
        }
        return result
    }

    fun sanitizeRemoteSettings(settings: SyncableSettings?): SyncableSettings? {
        if (settings == null || settings.updatedAt <= 0L) return null
        @Suppress("USELESS_ELVIS")
        val serverUrl = settings.serverUrl ?: WebDavBackupManager.DEFAULT_SERVER_URL
        @Suppress("USELESS_ELVIS")
        val remoteFolder = settings.remoteFolder ?: WebDavBackupManager.DEFAULT_REMOTE_FOLDER
        @Suppress("USELESS_ELVIS")
        val username = settings.username ?: ""
        @Suppress("USELESS_ELVIS")
        val password = settings.password ?: ""
        if (serverUrl.length > 512 ||
            remoteFolder.length > 256 ||
            username.length > 256 ||
            password.length > 512
        ) {
            return null
        }
        return SyncableSettings(
            updatedAt = settings.updatedAt,
            serverUrl = serverUrl,
            remoteFolder = remoteFolder,
            username = username,
            password = password,
            wifiAutoSyncEnabled = settings.wifiAutoSyncEnabled
        )
    }

    /**
     * 将一个或多个远端设备的笔记、墓碑与可选设置确定性合并到本地 Room 数据库。
     * 调用方必须已持有数据库写锁（contentWriteMutex）并确认当前不处于编辑状态。
     */
    suspend fun mergeIntoLocalDb(
        context: Context,
        noteDao: NoteDao,
        remoteNoteLists: List<List<NoteEntity>>,
        remoteTombstoneLists: List<List<DeletedNoteTombstone>>,
        remoteSettingsList: List<SyncableSettings> = emptyList()
    ): MergeResult {
        val now = System.currentTimeMillis()
        val tombstoneCutoff = now - TOMBSTONE_RETENTION_MS

        val localNotes = noteDao.getAllNotesForBackup()
        val localTombstones = noteDao.getAllTombstones()

        // 1. 合并 90 天内有效的永久删除墓碑
        val localTombstoneMap = linkedMapOf<String, Long>()
        val mergedTombstoneMap = linkedMapOf<String, Long>()
        for (t in localTombstones) {
            if (t.syncId.isNotBlank() && t.deletedAt >= tombstoneCutoff) {
                val prev = localTombstoneMap[t.syncId]
                if (prev == null || t.deletedAt > prev) {
                    localTombstoneMap[t.syncId] = t.deletedAt
                    mergedTombstoneMap[t.syncId] = t.deletedAt
                }
            }
        }
        for (tombList in remoteTombstoneLists) {
            for (t in tombList) {
                if (t.syncId.isNotBlank() && t.deletedAt >= tombstoneCutoff) {
                    val prev = mergedTombstoneMap[t.syncId]
                    if (prev == null || t.deletedAt > prev) {
                        mergedTombstoneMap[t.syncId] = t.deletedAt
                    }
                }
            }
        }

        // 2. 整理本地笔记（按规范化 syncId 索引）
        val localBySyncId = linkedMapOf<String, NoteEntity>()
        val toDeleteIds = linkedSetOf<Long>()
        for (note in localNotes) {
            val sid = normalizeSyncId(note)
            val normalized = if (note.syncId == sid) note else note.copy(syncId = sid)
            val existing = localBySyncId[sid]
            if (existing == null) {
                localBySyncId[sid] = normalized
            } else {
                val winner = pickWinningNote(existing, normalized)
                val loser = if (winner === existing) normalized else existing
                toDeleteIds.add(loser.id)
                localBySyncId[sid] = winner
            }
        }

        // 3. 汇总全部远端笔记（同一 syncId 选胜出版本）
        val remoteBySyncId = linkedMapOf<String, NoteEntity>()
        for (noteList in remoteNoteLists) {
            for (note in noteList) {
                val sid = normalizeSyncId(note)
                val normalized = note.copy(id = 0L, syncId = sid)
                val existing = remoteBySyncId[sid]
                remoteBySyncId[sid] = if (existing == null) normalized else pickWinningNote(existing, normalized)
            }
        }

        // 4. 历史旧版备份去重：未命中 syncId 时按 (dateString, createdAt, content) 确定性收敛到更小 syncId
        val remappedRemoteBySyncId = linkedMapOf<String, NoteEntity>()
        val localByLegacyKey = mutableMapOf<String, MutableList<NoteEntity>>()
        for (local in localBySyncId.values) {
            if (local.createdAt > 0L) {
                localByLegacyKey.getOrPut(legacyDedupKey(local)) { mutableListOf() }.add(local)
            }
        }

        val legacyMatchedLocalSyncIds = mutableSetOf<String>()
        val localSyncIdRemaps = mutableMapOf<String, NoteEntity>()
        for ((remoteSid, remoteNote) in remoteBySyncId) {
            if (localBySyncId.containsKey(remoteSid) || remoteNote.createdAt <= 0L) {
                val prev = remappedRemoteBySyncId[remoteSid]
                remappedRemoteBySyncId[remoteSid] =
                    if (prev == null) remoteNote else pickWinningNote(prev, remoteNote)
                continue
            }
            val candidates = localByLegacyKey[legacyDedupKey(remoteNote)]
            val matchedLocal = candidates?.firstOrNull { cand ->
                !remoteBySyncId.containsKey(cand.syncId) && cand.syncId !in legacyMatchedLocalSyncIds
            }
            if (matchedLocal != null) {
                legacyMatchedLocalSyncIds.add(matchedLocal.syncId)
                val canonicalSid = minOf(matchedLocal.syncId, remoteSid)
                if (canonicalSid != matchedLocal.syncId) {
                    localSyncIdRemaps[matchedLocal.syncId] = matchedLocal.copy(syncId = canonicalSid)
                }
                val canonicalRemote = remoteNote.copy(syncId = canonicalSid)
                val prev = remappedRemoteBySyncId[canonicalSid]
                remappedRemoteBySyncId[canonicalSid] =
                    if (prev == null) canonicalRemote else pickWinningNote(prev, canonicalRemote)
            } else {
                val prev = remappedRemoteBySyncId[remoteSid]
                remappedRemoteBySyncId[remoteSid] =
                    if (prev == null) remoteNote else pickWinningNote(prev, remoteNote)
            }
        }

        if (localSyncIdRemaps.isNotEmpty()) {
            for ((oldSid, updatedLocal) in localSyncIdRemaps) {
                localBySyncId.remove(oldSid)
                localBySyncId[updatedLocal.syncId] = updatedLocal
            }
        }

        // 5. 逐 syncId 计算最终存活版本（LWW + 墓碑判定）
        val allSyncIds = linkedSetOf<String>().apply {
            addAll(localBySyncId.keys)
            addAll(remappedRemoteBySyncId.keys)
        }

        val resolvedBySyncId = linkedMapOf<String, NoteEntity>()
        for (sid in allSyncIds) {
            val local = localBySyncId[sid]
            val remote = remappedRemoteBySyncId[sid]
            val candidate = when {
                local != null && remote != null -> pickWinningNote(local, remote)
                local != null -> local
                else -> remote!!
            }
            val tombDeletedAt = mergedTombstoneMap[sid]
            if (tombDeletedAt != null && tombDeletedAt >= candidate.updatedAt) {
                if (local != null) {
                    toDeleteIds.add(local.id)
                }
            } else {
                resolvedBySyncId[sid] = candidate
            }
        }

        // 6. 确定性执行回收站 30 条上限（溢出项按确定性时间戳写入墓碑）
        val deletedNotes = resolvedBySyncId.values.filter { it.isDeleted }
        if (deletedNotes.size > TRASH_MAX_COUNT) {
            val overflow = deletedNotes
                .sortedWith(
                    compareBy<NoteEntity> { it.deletedAt ?: it.updatedAt }
                        .thenBy { it.updatedAt }
                        .thenBy { it.syncId }
                )
                .take(deletedNotes.size - TRASH_MAX_COUNT)
            for (oldNote in overflow) {
                resolvedBySyncId.remove(oldNote.syncId)
                localBySyncId[oldNote.syncId]?.let { toDeleteIds.add(it.id) }
                val deterministicDeletedAt =
                    maxOf(oldNote.updatedAt + 1L, (oldNote.deletedAt ?: oldNote.updatedAt) + 1L)
                val prevTomb = mergedTombstoneMap[oldNote.syncId]
                if (prevTomb == null || deterministicDeletedAt > prevTomb) {
                    mergedTombstoneMap[oldNote.syncId] = deterministicDeletedAt
                }
            }
        }

        // 7. 写入最小增量变更到 Room
        val originalLocalById = localNotes.associateBy { it.id }
        val toInsert = mutableListOf<NoteEntity>()
        val toUpdate = mutableListOf<NoteEntity>()

        for ((sid, resolved) in resolvedBySyncId) {
            val existingLocal = localBySyncId[sid]
            if (existingLocal == null) {
                toInsert.add(resolved.copy(id = 0L, syncId = sid))
            } else {
                val rawOriginal = originalLocalById[existingLocal.id]
                val mergedEntity = resolved.copy(id = existingLocal.id, syncId = sid)
                if (rawOriginal == null || hasSyncedNoteDiff(rawOriginal, mergedEntity)) {
                    toUpdate.add(mergedEntity)
                }
            }
        }

        val tombstonesToSave = mutableListOf<DeletedNoteTombstone>()
        for ((sid, deletedAt) in mergedTombstoneMap) {
            val localDeletedAt = localTombstoneMap[sid]
            if (localDeletedAt == null || deletedAt > localDeletedAt) {
                tombstonesToSave.add(DeletedNoteTombstone(syncId = sid, deletedAt = deletedAt))
            }
        }

        val hasPrunedTombstones = localTombstones.any { it.deletedAt < tombstoneCutoff }
        val hasDbWrites = toInsert.isNotEmpty() || toUpdate.isNotEmpty() ||
            toDeleteIds.isNotEmpty() || tombstonesToSave.isNotEmpty() || hasPrunedTombstones

        if (hasDbWrites) {
            noteDao.applySyncMerge(
                toInsert = toInsert,
                toUpdate = toUpdate,
                toDeleteIds = toDeleteIds.toList(),
                mergedTombstones = tombstonesToSave,
                tombstoneCutoff = tombstoneCutoff
            )
        }

        val changedNoteCount = toInsert.size + toUpdate.size + toDeleteIds.size
        val notesChanged = changedNoteCount > 0

        // 8. 合并设置（取所有远端设置中 updatedAt 最新的，按 LWW 决定是否应用）
        var settingsChanged = false
        val newestRemoteSettings = remoteSettingsList
            .mapNotNull { sanitizeRemoteSettings(it) }
            .maxByOrNull { it.updatedAt }
        if (newestRemoteSettings != null) {
            settingsChanged = WebDavBackupManager.applySyncedSettings(
                context = context,
                serverUrl = newestRemoteSettings.serverUrl,
                remoteFolder = newestRemoteSettings.remoteFolder,
                username = newestRemoteSettings.username,
                password = newestRemoteSettings.password,
                wifiAutoSyncEnabled = newestRemoteSettings.wifiAutoSyncEnabled,
                remoteUpdatedAt = newestRemoteSettings.updatedAt
            )
        }

        return MergeResult(
            notesChanged = notesChanged,
            settingsChanged = settingsChanged,
            changedNoteCount = changedNoteCount
        )
    }

    /**
     * 每日历史快照（飞记备份 yyMMdd.json）防覆盖纯内存合并：
     * - 仅比较云端已有昨日备份与本机待上传备份，不破坏云端已有较新数据；
     * - 若合并后与云端完全一致，remoteWasAlreadyUpToDate 为 true，可直接跳过上传、绝不降级覆盖。
     */
    fun mergeForDailyBackup(
        rawRemoteNotes: List<NoteEntity>,
        rawLocalNotes: List<NoteEntity>
    ): DailyBackupMergeResult {
        val remoteNotes = sanitizeRemoteNotes(rawRemoteNotes)
        val localNotes = sanitizeRemoteNotes(rawLocalNotes)

        val remoteBySyncId = linkedMapOf<String, NoteEntity>()
        for (note in remoteNotes) {
            val existing = remoteBySyncId[note.syncId]
            remoteBySyncId[note.syncId] = if (existing == null) note else pickWinningNote(existing, note)
        }

        val localBySyncId = linkedMapOf<String, NoteEntity>()
        for (note in localNotes) {
            val existing = localBySyncId[note.syncId]
            localBySyncId[note.syncId] = if (existing == null) note else pickWinningNote(existing, note)
        }

        val remoteByLegacyKey = mutableMapOf<String, MutableList<NoteEntity>>()
        for (r in remoteBySyncId.values) {
            if (r.createdAt > 0L) {
                remoteByLegacyKey.getOrPut(legacyDedupKey(r)) { mutableListOf() }.add(r)
            }
        }

        val matchedRemoteSids = mutableSetOf<String>()
        val mergedBySyncId = linkedMapOf<String, NoteEntity>().apply { putAll(remoteBySyncId) }

        for ((localSid, localNote) in localBySyncId) {
            val directRemote = mergedBySyncId[localSid]
            if (directRemote != null) {
                mergedBySyncId[localSid] = pickWinningNote(directRemote, localNote)
                continue
            }
            val legacyMatch = if (localNote.createdAt > 0L) {
                remoteByLegacyKey[legacyDedupKey(localNote)]?.firstOrNull { cand ->
                    !localBySyncId.containsKey(cand.syncId) && cand.syncId !in matchedRemoteSids
                }
            } else {
                null
            }
            if (legacyMatch != null) {
                matchedRemoteSids.add(legacyMatch.syncId)
                val canonicalSid = minOf(legacyMatch.syncId, localSid)
                mergedBySyncId.remove(legacyMatch.syncId)
                val winner = pickWinningNote(
                    legacyMatch.copy(syncId = canonicalSid),
                    localNote.copy(syncId = canonicalSid)
                )
                mergedBySyncId[canonicalSid] = winner
            } else {
                mergedBySyncId[localSid] = localNote
            }
        }

        // 确定性裁剪回收站超出 30 条的旧条目
        val deletedNotes = mergedBySyncId.values.filter { it.isDeleted }
        if (deletedNotes.size > TRASH_MAX_COUNT) {
            deletedNotes
                .sortedWith(
                    compareBy<NoteEntity> { it.deletedAt ?: it.updatedAt }
                        .thenBy { it.updatedAt }
                        .thenBy { it.syncId }
                )
                .take(deletedNotes.size - TRASH_MAX_COUNT)
                .forEach { mergedBySyncId.remove(it.syncId) }
        }

        val mergedList = mergedBySyncId.values
            .sortedWith(compareBy<NoteEntity> { it.dateString }.thenBy { it.orderIndex }.thenBy { it.syncId })
            .mapIndexed { index, note -> note.copy(id = (index + 1).toLong()) }

        val remoteCanonicalHash = computeNotesOnlyHash(remoteBySyncId.values.toList())
        val mergedCanonicalHash = computeNotesOnlyHash(mergedList)

        return DailyBackupMergeResult(
            mergedNotes = mergedList,
            remoteWasAlreadyUpToDate = (remoteCanonicalHash == mergedCanonicalHash)
        )
    }

    fun computeFullStateHash(
        notes: List<NoteEntity>,
        tombstones: List<DeletedNoteTombstone>,
        settings: SyncableSettings
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        appendNotesToDigest(md, notes)
        val sortedTombs = tombstones.sortedBy { it.syncId }
        for (t in sortedTombs) {
            md.update("T|${t.syncId}|${t.deletedAt}\n".toByteArray(Charsets.UTF_8))
        }
        val settingsLine = "S|${settings.updatedAt}|${settings.serverUrl}|${settings.remoteFolder}|" +
            "${settings.username}|${settings.password}|${settings.wifiAutoSyncEnabled}\n"
        md.update(settingsLine.toByteArray(Charsets.UTF_8))
        return bytesToHex(md.digest()).take(16)
    }

    private fun computeNotesOnlyHash(notes: List<NoteEntity>): String {
        val md = MessageDigest.getInstance("SHA-256")
        appendNotesToDigest(md, notes)
        return bytesToHex(md.digest())
    }

    private fun appendNotesToDigest(md: MessageDigest, notes: List<NoteEntity>) {
        val sortedNotes = notes.sortedBy { normalizeSyncId(it) }
        for (n in sortedNotes) {
            val line = buildString {
                append("N|")
                append(normalizeSyncId(n)).append('|')
                append(n.dateString).append('|')
                append(n.bulletType.name).append('|')
                append(n.orderIndex).append('|')
                append(n.isDeleted).append('|')
                append(n.createdAt).append('|')
                append(n.updatedAt).append('|')
                append(n.deletedAt ?: -1L).append('|')
                append(n.content.length).append(':').append(n.content)
                append('\n')
            }
            md.update(line.toByteArray(Charsets.UTF_8))
        }
    }

    private fun legacyDedupKey(note: NoteEntity): String =
        "${note.dateString}|${note.createdAt}|${note.content}"

    private fun pickWinningNote(a: NoteEntity, b: NoteEntity): NoteEntity {
        if (a.updatedAt != b.updatedAt) {
            return if (a.updatedAt > b.updatedAt) a else b
        }
        if (a.isDeleted != b.isDeleted) {
            return if (a.isDeleted) a else b
        }
        val aDel = a.deletedAt ?: -1L
        val bDel = b.deletedAt ?: -1L
        if (aDel != bDel) {
            return if (aDel > bDel) a else b
        }
        val contentCmp = a.content.compareTo(b.content)
        if (contentCmp != 0) return if (contentCmp > 0) a else b
        val dateCmp = a.dateString.compareTo(b.dateString)
        if (dateCmp != 0) return if (dateCmp > 0) a else b
        val typeCmp = a.bulletType.name.compareTo(b.bulletType.name)
        if (typeCmp != 0) return if (typeCmp > 0) a else b
        return if (a.orderIndex <= b.orderIndex) a else b
    }

    private fun hasSyncedNoteDiff(local: NoteEntity, merged: NoteEntity): Boolean {
        return local.syncId != merged.syncId ||
            local.content != merged.content ||
            local.dateString != merged.dateString ||
            local.bulletType != merged.bulletType ||
            local.orderIndex != merged.orderIndex ||
            local.isDeleted != merged.isDeleted ||
            local.createdAt != merged.createdAt ||
            local.updatedAt != merged.updatedAt ||
            local.deletedAt != merged.deletedAt
    }

    private fun sha256Hex(bytes: ByteArray): String =
        bytesToHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }
}
