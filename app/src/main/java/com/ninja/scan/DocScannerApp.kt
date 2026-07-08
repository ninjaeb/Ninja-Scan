package com.ninja.scan

import android.app.Application
import com.ninja.scan.data.ScanDatabase
import com.ninja.scan.data.ScanRepository

class DocScannerApp : Application() {

    val repository: ScanRepository by lazy {
        val database = ScanDatabase.get(this)
        ScanRepository(this, database.scanDao(), database.cardDao(), database.folderDao())
    }
}
