package com.ninja.scan.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Query("SELECT name FROM folders ORDER BY name")
    fun observeAll(): Flow<List<String>>

    @Query("SELECT * FROM folders ORDER BY name")
    fun observeAllDetailed(): Flow<List<Folder>>

    @Query("SELECT name FROM folders ORDER BY name")
    suspend fun getAll(): List<String>

    @Query("SELECT * FROM folders ORDER BY name")
    suspend fun getAllDetailed(): List<Folder>

    @Query("SELECT COUNT(*) FROM folders")
    suspend fun count(): Int

    @Query("SELECT color FROM folders WHERE name = :name")
    suspend fun colorOf(name: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: Folder)

    @Query("UPDATE scans SET folder = :newName WHERE folder = :oldName")
    suspend fun reassignScans(oldName: String, newName: String?)

    @Query("DELETE FROM folders WHERE name = :name")
    suspend fun deleteRow(name: String)

    /**
     * Inserts a folder row for [name] if one doesn't already exist yet,
     * assigning it the next color in the round-robin palette. A no-op for
     * a name that's already a real folder, so its existing color sticks.
     */
    @Transaction
    suspend fun insertNamed(name: String) {
        if (name in getAll()) return
        insert(Folder(name, FolderColors.next(count())))
    }

    /** Renaming onto an existing folder merges the two (scans move over). */
    @Transaction
    suspend fun rename(oldName: String, newName: String) {
        val color = colorOf(oldName) ?: FolderColors.next(count())
        insert(Folder(newName, color))
        reassignScans(oldName, newName)
        deleteRow(oldName)
    }

    /** Deleting a folder unfiles its scans; the scans themselves are kept. */
    @Transaction
    suspend fun delete(name: String) {
        reassignScans(name, null)
        deleteRow(name)
    }
}
