package com.eugeneboon.docscanner

import android.app.Application
import com.eugeneboon.docscanner.data.ScanDatabase
import com.eugeneboon.docscanner.data.ScanRepository

class DocScannerApp : Application() {

    val repository: ScanRepository by lazy {
        val database = ScanDatabase.get(this)
        ScanRepository(this, database.scanDao(), database.cardDao())
    }
}
