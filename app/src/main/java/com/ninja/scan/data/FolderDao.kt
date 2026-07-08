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

    @Query("SELECT name FROM folders ORDER BY name")
    suspend fun getAll(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: Folder)

    @Query("UPDATE scans SET folder = :newName WHERE folder = :oldName")
    suspend fun reassignScans(oldName: String, newName: String?)

    @Query("DELETE FROM folders WHERE name = :name")
    suspend fun deleteRow(name: String)

    /** Renaming onto an existing folder merges the two (scans move over). */
    @Transaction
    suspend fun rename(oldName: String, newName: String) {
        insert(Folder(newName))
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
