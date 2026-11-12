package com.gromozeka.updates

import android.app.Activity
import android.app.Application
import android.os.Bundle

object AndroidUpdateVisibility : Application.ActivityLifecycleCallbacks {
    private var registered = false
    private val started = mutableSetOf<Activity>()
    @Volatile var background = true
        private set

    fun register(application: Application) {
        if (registered) return
        registered = true
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) { started.add(activity); background = false }
    override fun onActivityStopped(activity: Activity) { started.remove(activity); background = started.isEmpty() && !activity.isChangingConfigurations }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) { started.remove(activity) }
}
