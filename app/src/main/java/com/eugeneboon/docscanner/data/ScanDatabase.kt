package com.eugeneboon.docscanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ScanDocument::class], version = 2, exportSchema = false)
abstract class ScanDatabase : RoomDatabase() {

    abstract fun scanDao(): ScanDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scans ADD COLUMN ocrText TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var instance: ScanDatabase? = null

        fun get(context: Context): ScanDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ScanDatabase::class.java,
                    "scans.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
