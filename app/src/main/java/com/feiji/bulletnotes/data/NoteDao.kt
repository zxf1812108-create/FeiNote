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

    @Transaction
    suspend fun restoreAllFromBackup(notes: List<NoteEntity>) {
        deleteAll()
        insertNotes(notes)
    }
}
