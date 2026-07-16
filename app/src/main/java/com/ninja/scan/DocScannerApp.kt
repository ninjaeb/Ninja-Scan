package com.ninja.scan

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.ninja.scan.data.ScanDatabase
import com.ninja.scan.data.ScanRepository
import com.ninja.scan.drive.DriveBackup
import com.ninja.scan.security.AppLock
import com.ninja.scan.security.AppLockActivity

class DocScannerApp : Application(), Application.ActivityLifecycleCallbacks {

    val repository: ScanRepository by lazy {
        val database = ScanDatabase.get(this)
        ScanRepository(
            this, database.scanDao(), database.cardDao(), database.folderDao(), database.tagDao(),
        )
    }

    override fun onCreate() {
        super.onCreate()
        // WorkManager's own schedule isn't restored by Android's Auto Backup,
        // while this SharedPreferences flag can be — re-arming here closes
        // that gap and is a cheap no-op when already scheduled.
        if (DriveBackup.isEnabled(this)) DriveBackup.enqueuePeriodic(this)

        registerActivityLifecycleCallbacks(this)
    }

    // Fires for every Activity start in the app, not just the first — but
    // AppLock.unlockedThisProcess makes every check after the very first
    // one a no-op, so this only ever prompts once per process lifetime, no
    // matter how many times the app is backgrounded/foregrounded or which
    // screen (Documents/Cards/About) happens to be the one that starts.
    override fun onActivityStarted(activity: Activity) {
        if (activity !is AppLockActivity && !AppLock.lockActivityShowing &&
            !AppLock.unlockedThisProcess && AppLock.isEnabled(this)
        ) {
            AppLock.lockActivityShowing = true
            activity.startActivity(AppLockActivity.intent(activity))
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
