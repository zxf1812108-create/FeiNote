package com.feiji.bulletnotes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes WHERE dateString = :date AND isDeleted = 0 ORDER BY orderIndex ASC, id ASC")
    fun getNotesByDate(date: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE isDeleted = 1 ORDER BY updatedAt DESC")
    fun getDeletedNotes(): Flow<List<NoteEntity>>

    @Query("SELECT DISTINCT dateString FROM notes WHERE isDeleted = 0")
    fun getDatesWithActiveNotes(): Flow<List<String>>

    @Query(
        "SELECT * FROM notes WHERE isDeleted = 0 AND (content LIKE '%@%@%' OR content LIKE '%#%#%') " +
            "ORDER BY dateString DESC, orderIndex ASC, updatedAt DESC"
    )
    fun getKeywordCandidateNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getNoteById(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE isDeleted = 0")
    suspend fun getAllActiveNotes(): List<NoteEntity>

    @Query("SELECT * FROM notes")
    suspend fun getAllNotesForBackup(): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotes(notes: List<NoteEntity>): List<Long>

    @Update
    suspend fun updateNote(note: NoteEntity)

    @Query("UPDATE notes SET content = :content, updatedAt = :updatedAt WHERE id = :id AND isDeleted = 0")
    suspend fun updateNoteContent(id: Long, content: String, updatedAt: Long)

    @Update
    suspend fun updateNotes(notes: List<NoteEntity>)

    @Query("UPDATE notes SET isDeleted = 1, deletedAt = :updatedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDeleteNote(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET isDeleted = 0, deletedAt = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun restoreNote(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deletePermanently(id: Long)

    @Query("DELETE FROM notes WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt <= :cutoff")
    suspend fun deleteTrashOlderThan(cutoff: Long): Int

    @Query("DELETE FROM notes")
    suspend fun deleteAll()

    @Query("SELECT * FROM deleted_sync_tombstones")
    suspend fun getAllTombstones(): List<DeletedNoteTombstone>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: DeletedNoteTombstone)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstones(tombstones: List<DeletedNoteTombstone>)

    @Query("DELETE FROM deleted_sync_tombstones WHERE deletedAt < :cutoff")
    suspend fun pruneOldTombstones(cutoff: Long)

    @Query("DELETE FROM deleted_sync_tombstones")
    suspend fun deleteAllTombstones()

    @Transaction
    suspend fun deletePermanentlyWithTombstone(
        id: Long,
        deletedAt: Long = System.currentTimeMillis()
    ) {
        val existing = getNoteById(id)
        if (existing != null && existing.syncId.isNotBlank()) {
            val effectiveDeletedAt = maxOf(deletedAt, existing.updatedAt + 1L)
            insertTombstone(DeletedNoteTombstone(syncId = existing.syncId, deletedAt = effectiveDeletedAt))
        }
        deletePermanently(id)
    }

    @Transaction
    suspend fun applySyncMerge(
        toInsert: List<NoteEntity>,
        toUpdate: List<NoteEntity>,
        toDeleteIds: List<Long>,
        mergedTombstones: List<DeletedNoteTombstone>,
        tombstoneCutoff: Long
    ) {
        for (id in toDeleteIds) {
            deletePermanently(id)
        }
        if (toUpdate.isNotEmpty()) {
            updateNotes(toUpdate)
        }
        if (toInsert.isNotEmpty()) {
            insertNotes(toInsert)
        }
        if (mergedTombstones.isNotEmpty()) {
            insertTombstones(mergedTombstones)
        }
        pruneOldTombstones(tombstoneCutoff)
    }

    @Transaction
    suspend fun restoreAllFromBackup(notes: List<NoteEntity>) {
        deleteAll()
        deleteAllTombstones()
        insertNotes(notes)
    }
}
