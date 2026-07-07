package com.ninja.scan.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BusinessCardDao {

    @Query("SELECT * FROM business_cards ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BusinessCard>>

    @Insert
    suspend fun insert(card: BusinessCard): Long

    @Update
    suspend fun update(card: BusinessCard)

    @Delete
    suspend fun delete(card: BusinessCard)
}
