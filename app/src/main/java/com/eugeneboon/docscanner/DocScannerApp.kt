package com.eugeneboon.docscanner

import android.app.Application
import com.eugeneboon.docscanner.data.ScanDatabase
import com.eugeneboon.docscanner.data.ScanRepository

class DocScannerApp : Application() {

    val repository: ScanRepository by lazy {
        ScanRepository(this, ScanDatabase.get(this).scanDao())
    }
}
