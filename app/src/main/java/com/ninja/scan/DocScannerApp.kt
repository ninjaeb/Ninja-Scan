package com.ninja.scan

import android.app.Application
import com.ninja.scan.data.ScanDatabase
import com.ninja.scan.data.ScanRepository
import com.ninja.scan.drive.DriveBackup

class DocScannerApp : Application() {

    val repository: ScanRepository by lazy {
        val database = ScanDatabase.get(this)
        ScanRepository(this, database.scanDao(), database.cardDao(), database.folderDao())
    }

    override fun onCreate() {
        super.onCreate()
        // WorkManager's own schedule isn't restored by Android's Auto Backup,
        // while this SharedPreferences flag can be — re-arming here closes
        // that gap and is a cheap no-op when already scheduled.
        if (DriveBackup.isEnabled(this)) DriveBackup.enqueuePeriodic(this)
    }
}
