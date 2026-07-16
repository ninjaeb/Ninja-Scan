package com.ninja.scan

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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

    /** The most recently started Activity — used as a launch context for the lock screen. */
    private var frontActivity: Activity? = null

    override fun onCreate() {
        super.onCreate()
        // WorkManager's own schedule isn't restored by Android's Auto Backup,
        // while this SharedPreferences flag can be — re-arming here closes
        // that gap and is a cheap no-op when already scheduled.
        if (DriveBackup.isEnabled(this)) DriveBackup.enqueuePeriodic(this)

        registerActivityLifecycleCallbacks(this)
        // ProcessLifecycleOwner reports whole-app foreground/background
        // transitions rather than per-Activity ones, so switching between
        // Documents/Cards/About — or rotating the screen — never re-triggers
        // the lock; only actually backgrounding and reopening the app does.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val activity = frontActivity
                if (activity != null && activity !is AppLockActivity &&
                    !AppLock.lockActivityShowing && !AppLock.unlockedThisSession &&
                    AppLock.isEnabled(this@DocScannerApp)
                ) {
                    AppLock.lockActivityShowing = true
                    activity.startActivity(AppLockActivity.intent(activity))
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                AppLock.unlockedThisSession = false
            }
        })
    }

    override fun onActivityStarted(activity: Activity) {
        frontActivity = activity
    }

    override fun onActivityStopped(activity: Activity) {
        if (frontActivity === activity) frontActivity = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
