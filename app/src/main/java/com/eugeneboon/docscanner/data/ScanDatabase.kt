package com.eugeneboon.docscanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ScanDocument::class], version = 1, exportSchema = false)
abstract class ScanDatabase : RoomDatabase() {

    abstract fun scanDao(): ScanDao

    companion object {
        @Volatile
        private var instance: ScanDatabase? = null

        fun get(context: Context): ScanDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ScanDatabase::class.java,
                    "scans.db"
                ).build().also { instance = it }
            }
    }
}
