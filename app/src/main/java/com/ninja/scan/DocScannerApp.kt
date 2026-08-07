package com.ninja.scan

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.ninja.scan.data.ScanDatabase
import com.ninja.scan.data.ScanRepository
import com.ninja.scan.drive.DriveBackup
import com.ninja.scan.security.AppLock
import com.ninja.scan.security.AppLockActivity
import com.ninja.scan.ui.theme.LocalePrefs

class DocScannerApp : Application(), Application.ActivityLifecycleCallbacks {

    val repository: ScanRepository by lazy {
        val database = ScanDatabase.get(this)
        ScanRepository(
            this, database.scanDao(), database.cardDao(), database.tagDao(), database.scanTagDao(),
        )
    }

    // Every Activity applies the same wrap in its own attachBaseContext (see
    // LocalePrefs.wrap), but this one matters too: applicationContext-derived
    // resources (e.g. from a WorkManager Worker) would otherwise stay on the
    // system language regardless of the in-app override.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocalePrefs.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // A WorkManager schedule can be lost independently of this flag
        // (app data cleared, OS quirks) without the flag itself changing —
        // re-arming here closes that gap and is a cheap no-op when already
        // scheduled. This is unrelated to Android's own Auto Backup: that's
        // deliberately excluded from covering this app's data at all (see
        // res/xml/backup_rules.xml) in favor of the encrypted Drive backup.
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
