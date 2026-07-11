package com.ninja.scan.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ScanDocument::class, BusinessCard::class, Folder::class,
        Tag::class, CardTagCrossRef::class,
    ],
    version = 13,
    exportSchema = false,
)
abstract class ScanDatabase : RoomDatabase() {

    abstract fun scanDao(): ScanDao

    abstract fun cardDao(): BusinessCardDao

    abstract fun folderDao(): FolderDao

    abstract fun tagDao(): TagDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scans ADD COLUMN ocrText TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scans ADD COLUMN driveFileId TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scans ADD COLUMN folder TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scans ADD COLUMN watermark TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `business_cards` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `company` TEXT NOT NULL, " +
                        "`jobTitle` TEXT NOT NULL, `phone` TEXT NOT NULL, " +
                        "`email` TEXT NOT NULL, `website` TEXT NOT NULL, " +
                        "`address` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`thumbnailPath` TEXT)"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scans ADD COLUMN originalsDir TEXT")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE business_cards ADD COLUMN notes TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE business_cards ADD COLUMN tags TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folders` " +
                        "(`name` TEXT NOT NULL, PRIMARY KEY(`name`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO folders(name) " +
                        "SELECT DISTINCT folder FROM scans WHERE folder IS NOT NULL"
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE business_cards ADD COLUMN photoDriveFileId TEXT")
            }
        }

        // Same hex values as res/values/colors.xml's tag_* palette — a
        // migration has no access to Android resources, so the two lists
        // are kept in sync by hand.
        private val DEFAULT_TAG_PALETTE = listOf(
            "#EF5350", "#FFA726", "#FFCA28", "#66BB6A", "#26A69A",
            "#42A5F5", "#29B6F6", "#AB47BC", "#7E57C2", "#EC407A",
        )

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No DEFAULT clause on `description`: the Tag entity declares
                // no @ColumnInfo default, so Room's post-migration schema
                // check expects none — a SQL-level default here would fail
                // validation and crash the app on every subsequent launch.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                        "`color` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `card_tag_cross_ref` (" +
                        "`cardId` INTEGER NOT NULL, `tagId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`cardId`, `tagId`))"
                )

                // Split each card's old comma-separated tags string into real
                // Tag rows (deduped by lower-cased title, first-seen casing
                // wins) plus cross-ref links, so existing tag data survives
                // the move to a relational model.
                val titleToTagId = HashMap<String, Long>()
                var paletteIndex = 0
                val pendingLinks = mutableListOf<Pair<Long, String>>()

                val cardCursor = db.query("SELECT id, tags FROM business_cards")
                cardCursor.use { cursor ->
                    val idIndex = cursor.getColumnIndex("id")
                    val tagsIndex = cursor.getColumnIndex("tags")
                    while (cursor.moveToNext()) {
                        val cardId = cursor.getLong(idIndex)
                        val rawTags = cursor.getString(tagsIndex) ?: ""
                        for (piece in rawTags.split(",")) {
                            val title = piece.trim()
                            if (title.isEmpty()) continue
                            val key = title.lowercase(java.util.Locale.US)
                            if (key !in titleToTagId) {
                                val color = DEFAULT_TAG_PALETTE[paletteIndex % DEFAULT_TAG_PALETTE.size]
                                paletteIndex++
                                db.execSQL(
                                    "INSERT INTO tags (title, description, color) VALUES (?, '', ?)",
                                    arrayOf(title, color)
                                )
                                db.query("SELECT last_insert_rowid()").use { idCursor ->
                                    idCursor.moveToFirst()
                                    titleToTagId[key] = idCursor.getLong(0)
                                }
                            }
                            pendingLinks.add(cardId to key)
                        }
                    }
                }

                for ((cardId, key) in pendingLinks) {
                    val tagId = titleToTagId.getValue(key)
                    db.execSQL(
                        "INSERT OR IGNORE INTO card_tag_cross_ref (cardId, tagId) VALUES (?, ?)",
                        arrayOf(cardId, tagId)
                    )
                }

                // Rebuild business_cards without the now-migrated `tags`
                // column: the BusinessCard entity no longer declares it, and
                // leaving the physical column behind makes Room's startup
                // schema check see an unexpected extra column on every
                // upgrading install — which crashes the app before any UI
                // ever renders. A fresh install never hits this (no
                // migration runs), which is why it slipped through the
                // first time.
                db.execSQL(
                    "CREATE TABLE `business_cards_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `company` TEXT NOT NULL, " +
                        "`jobTitle` TEXT NOT NULL, `phone` TEXT NOT NULL, " +
                        "`email` TEXT NOT NULL, `website` TEXT NOT NULL, " +
                        "`address` TEXT NOT NULL, `notes` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `thumbnailPath` TEXT, " +
                        "`photoDriveFileId` TEXT)"
                )
                db.execSQL(
                    "INSERT INTO business_cards_new (" +
                        "id, name, company, jobTitle, phone, email, website, " +
                        "address, notes, createdAt, thumbnailPath, photoDriveFileId) " +
                        "SELECT id, name, company, jobTitle, phone, email, website, " +
                        "address, notes, createdAt, thumbnailPath, photoDriveFileId " +
                        "FROM business_cards"
                )
                db.execSQL("DROP TABLE business_cards")
                db.execSQL("ALTER TABLE business_cards_new RENAME TO business_cards")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN color TEXT NOT NULL DEFAULT ''")
                var index = 0
                db.query("SELECT name FROM folders ORDER BY name").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIndex)
                        db.execSQL(
                            "UPDATE folders SET color = ? WHERE name = ?",
                            arrayOf(FolderColors.next(index), name)
                        )
                        index++
                    }
                }
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scans ADD COLUMN isIdCard INTEGER NOT NULL DEFAULT 0")
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
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                        MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                        MIGRATION_12_13,
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
