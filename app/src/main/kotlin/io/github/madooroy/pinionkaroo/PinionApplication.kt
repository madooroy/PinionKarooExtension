package io.github.madooroy.pinionkaroo

import android.app.Application
import timber.log.Timber

class PinionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Everything is logged under the single tag "Pinion": adb logcat -s Pinion
        Timber.plant(object : Timber.DebugTree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                super.log(priority, "Pinion", message, t)
            }
        })
        // And to a file per day on the Karoo, for looking at a ride afterwards (see FileLogTree)
        Timber.plant(FileLogTree(this))
        Timber.i("Process started (${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()})")
    }
}
