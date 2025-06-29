package com.momentolabs.app.security.applocker.service.stateprovider

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import com.momentolabs.app.security.applocker.ui.permissions.PermissionChecker
import io.reactivex.Flowable
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import android.app.ActivityManager
import androidx.annotation.RequiresApi
import com.momentolabs.app.security.applocker.ui.overlay.activity.OverlayValidationActivity


class AppForegroundObservable @Inject constructor(val context: Context) {

    private var foregroundFlowable: Flowable<String>? = null

    fun get(): Flowable<String> {
        foregroundFlowable = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP -> getForegroundObservableHigherLollipop()
            else -> getForegroundObservableLowerLollipop()
        }

        return foregroundFlowable!!
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun getForegroundObservableHigherLollipop(): Flowable<String> {
        return Flowable.interval(500, TimeUnit.MILLISECONDS) // 增加間隔時間，減少頻繁觸發
            .filter { PermissionChecker.checkUsageAccessPermission(context) }
            .map {
                var latestUsageEvent: UsageEvents.Event? = null

                val mUsageStatsManager = context.getSystemService(Service.USAGE_STATS_SERVICE) as UsageStatsManager
                val time = System.currentTimeMillis()

                // 只查詢最近10秒的事件，減少查詢範圍
                val usageEvents = mUsageStatsManager.queryEvents(time - 10000, time)
                val event = UsageEvents.Event()
                
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    // 只關注 MOVE_TO_FOREGROUND 事件
                    if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        // 記錄最新的前台事件
                        if (latestUsageEvent == null || event.timeStamp > latestUsageEvent.timeStamp) {
                            latestUsageEvent = UsageEvents.Event().apply {
                                packageName = event.packageName
                                className = event.className
                                eventType = event.eventType
                                timeStamp = event.timeStamp
                            }
                        }
                    }
                }
                
                UsageEventWrapper(latestUsageEvent)
            }
            .filter { it.usageEvent != null }
            .map { it.usageEvent }
            .filter { it.className != null }
            .filter { 
                // 過濾掉我們自己的 OverlayValidationActivity
                it.className.contains(OverlayValidationActivity::class.java.simpleName).not() 
            }
            .map { it.packageName }
            .distinctUntilChanged() // 只有當包名真正改變時才發出
            .debounce(200, TimeUnit.MILLISECONDS) // 防抖處理，200ms內的重複事件會被過濾
    }

    private fun getForegroundObservableLowerLollipop(): Flowable<String> {
        return Flowable.interval(500, TimeUnit.MILLISECONDS) // 增加間隔時間
            .map {
                val mActivityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val runningTasks = mActivityManager.getRunningTasks(1)
                if (runningTasks.isNotEmpty()) {
                    runningTasks[0].topActivity
                } else {
                    null
                }
            }
            .filter { it != null }
            .filter { 
                it!!.className.contains(OverlayValidationActivity::class.java.simpleName).not() 
            }
            .map { it!!.packageName }
            .distinctUntilChanged()
            .debounce(200, TimeUnit.MILLISECONDS) // 防抖處理
    }
}