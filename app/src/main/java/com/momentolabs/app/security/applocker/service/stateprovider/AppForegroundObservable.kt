// === 針對性修復 - 重點解決包名檢測問題 ===

// 1. 首先修復 AppForegroundObservable.kt
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
import android.util.Log

class AppForegroundObservable @Inject constructor(val context: Context) {

    private var foregroundFlowable: Flowable<String>? = null
    private var lastReportedPackage: String = ""
    private var lastReportTime = 0L
    private val MIN_REPORT_INTERVAL = 3000L // 3秒間隔，防止重複

    companion object {
        private const val TAG = "AppForegroundObs"
        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.miui.home",
            "com.huawei.android.launcher"
        )
    }

    fun get(): Flowable<String> {
        foregroundFlowable = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP -> getForegroundObservableHigherLollipop()
            else -> getForegroundObservableLowerLollipop()
        }

        return foregroundFlowable!!
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun getForegroundObservableHigherLollipop(): Flowable<String> {
        return Flowable.interval(2000, TimeUnit.MILLISECONDS) // 改為2秒檢測一次
            .filter { PermissionChecker.checkUsageAccessPermission(context) }
            .map { getForegroundPackageModern() }
            .filter { packageName ->
                // 過濾系統包名和空值
                packageName.isNotEmpty() && 
                !IGNORED_PACKAGES.contains(packageName) &&
                !packageName.contains("systemui") &&
                !packageName.contains("launcher")
            }
            .filter { packageName -> shouldReportPackage(packageName) }
            .distinctUntilChanged()
    }

    private fun getForegroundObservableLowerLollipop(): Flowable<String> {
        return Flowable.interval(2000, TimeUnit.MILLISECONDS)
            .map { getForegroundPackageLegacy() }
            .filter { packageName ->
                packageName.isNotEmpty() && 
                !IGNORED_PACKAGES.contains(packageName)
            }
            .filter { packageName -> shouldReportPackage(packageName) }
            .distinctUntilChanged()
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun getForegroundPackageModern(): String {
        return try {
            val usageStatsManager = context.getSystemService(Service.USAGE_STATS_SERVICE) as UsageStatsManager
            val currentTime = System.currentTimeMillis()

            // 只查詢最近10秒的事件
            val usageEvents = usageStatsManager.queryEvents(currentTime - 10000, currentTime)
            var latestEvent: UsageEvents.Event? = null
            val event = UsageEvents.Event()

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                
                // 只關注 MOVE_TO_FOREGROUND 事件
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    if (latestEvent == null || event.timeStamp > latestEvent.timeStamp) {
                        latestEvent = UsageEvents.Event().apply {
                            packageName = event.packageName
                            className = event.className
                            eventType = event.eventType
                            timeStamp = event.timeStamp
                        }
                    }
                }
            }

            val packageName = latestEvent?.packageName ?: ""
            Log.d(TAG, "Modern detection: $packageName")
            packageName

        } catch (e: Exception) {
            Log.e(TAG, "Error in modern detection", e)
            ""
        }
    }

    private fun getForegroundPackageLegacy(): String {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningTasks = activityManager.getRunningTasks(1)
            
            if (runningTasks.isNotEmpty()) {
                val topActivity = runningTasks[0].topActivity
                val packageName = topActivity?.packageName ?: ""
                Log.d(TAG, "Legacy detection: $packageName")
                packageName
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in legacy detection", e)
            ""
        }
    }

    private fun shouldReportPackage(packageName: String): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // 如果是同一個包名且在最小間隔內，不報告
        if (packageName == lastReportedPackage && 
            currentTime - lastReportTime < MIN_REPORT_INTERVAL) {
            Log.d(TAG, "Filtering duplicate package: $packageName")
            return false
        }

        // 更新記錄
        lastReportedPackage = packageName
        lastReportTime = currentTime
        
        Log.d(TAG, "Reporting package: $packageName")
        return true
    }
}

// 2. 修復 AppLockerService.kt 中的關鍵方法
// 在 AppLockerService 類中添加或修改以下方法：

private fun onAppForeground(foregroundAppPackage: String) {
    val currentTime = System.currentTimeMillis()
    
    Log.e("AppLockerService", "=== APP FOREGROUND EVENT ===")
    Log.e("AppLockerService", "Package: $foregroundAppPackage")
    Log.e("AppLockerService", "Own package: ${applicationContext.packageName}")
    Log.e("AppLockerService", "Last package: $lastForegroundAppPackage")
    Log.e("AppLockerService", "Is processing: $isProcessingAppChange")
    Log.e("AppLockerService", "Current locked: $isAppCurrentlyLocked")
    Log.e("AppLockerService", "Current locked app: $currentLockedApp")
    
    // 防止重複處理
    if (isProcessingAppChange) {
        Log.e("AppLockerService", "Already processing, ignoring")
        return
    }
    
    // 檢查是否是自己的應用或驗證Activity
    if (foregroundAppPackage == applicationContext.packageName) {
        Log.e("AppLockerService", "Own app detected, ignoring")
        // 如果切換到自己的應用，重置鎖定狀態
        if (isAppCurrentlyLocked) {
            Log.e("AppLockerService", "Resetting lock state for own app")
            resetLockState()
        }
        lastForegroundAppPackage = foregroundAppPackage
        return
    }
    
    // 檢查是否是系統應用
    if (isSystemApp(foregroundAppPackage)) {
        Log.e("AppLockerService", "System app detected, ignoring: $foregroundAppPackage")
        lastForegroundAppPackage = foregroundAppPackage
        return
    }
    
    // 防止過於頻繁的觸發
    if (foregroundAppPackage == lastForegroundAppPackage && 
        currentTime - lastProcessTime < MIN_PROCESS_INTERVAL) {
        Log.e("AppLockerService", "Too frequent for same app, ignoring")
        return
    }

    isProcessingAppChange = true
    lastProcessTime = currentTime

    try {
        // 檢查是否從鎖定應用切換到其他應用
        if (isAppCurrentlyLocked && currentLockedApp != foregroundAppPackage) {
            Log.e("AppLockerService", "Switching from locked app, resetting state")
            hideOverlay()
            resetLockState()
        }

        // 檢查新應用是否需要鎖定
        if (shouldLockApp(foregroundAppPackage)) {
            // 防止重複鎖定同一應用
            if (isAppCurrentlyLocked && currentLockedApp == foregroundAppPackage) {
                Log.e("AppLockerService", "App already locked, ignoring")
                return
            }

            Log.e("AppLockerService", "Showing lock for: $foregroundAppPackage")
            showAppLock(foregroundAppPackage)
        } else {
            // 應用不需要鎖定，確保清理狀態
            if (isAppCurrentlyLocked) {
                Log.e("AppLockerService", "App doesn't need lock, resetting state")
                hideOverlay()
                resetLockState()
            }
        }

    } catch (e: Exception) {
        Log.e("AppLockerService", "Error processing app foreground", e)
        resetLockState()
    } finally {
        lastForegroundAppPackage = foregroundAppPackage
        isProcessingAppChange = false
        Log.e("AppLockerService", "=== END APP FOREGROUND EVENT ===")
    }
}

// 添加系統應用檢測方法
private fun isSystemApp(packageName: String): Boolean {
    val systemApps = setOf(
        "android",
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher3",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.samsung.android.launcher",
        "com.google.android.launcher"
    )
    
    return systemApps.contains(packageName) || 
           packageName.startsWith("com.android.") ||
           packageName.contains("launcher") ||
           packageName.contains("systemui")
}

// 修改 shouldLockApp 方法
private fun shouldLockApp(packageName: String): Boolean {
    val needsLock = lockedAppPackageSet.contains(packageName)
    Log.e("AppLockerService", "Should lock $packageName: $needsLock")
    Log.e("AppLockerService", "Locked apps: $lockedAppPackageSet")
    return needsLock
}

// 修改 showAppLock 方法
private fun showAppLock(foregroundAppPackage: String) {
    Log.e("AppLockerService", "=== SHOWING APP LOCK ===")
    Log.e("AppLockerService", "Target app: $foregroundAppPackage")
    Log.e("AppLockerService", "Fingerprint enabled: ${appLockerPreferences.getFingerPrintEnabled()}")
    Log.e("AppLockerService", "Overlay permission: ${PermissionChecker.checkOverlayPermission(applicationContext)}")
    
    currentLockedApp = foregroundAppPackage
    isAppCurrentlyLocked = true

    if (appLockerPreferences.getFingerPrintEnabled() || 
        PermissionChecker.checkOverlayPermission(applicationContext).not()) {
        
        Log.e("AppLockerService", "Starting fingerprint activity")
        
        val intent = OverlayValidationActivity.newIntent(applicationContext, foregroundAppPackage)
        
        // 檢查 Intent 是否有效
        if (intent.component != null && intent.hasExtra("EXTRA_PACKAGE_NAME")) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            try {
                startActivity(intent)
                Log.e("AppLockerService", "Fingerprint activity started successfully")
            } catch (e: Exception) {
                Log.e("AppLockerService", "Error starting fingerprint activity", e)
                resetLockState()
            }
        } else {
            Log.e("AppLockerService", "Invalid intent for fingerprint activity")
            resetLockState()
        }
    } else {
        Log.e("AppLockerService", "Showing overlay")
        showOverlay(foregroundAppPackage)
    }
}

// 3. 修復 OverlayValidationActivity.kt 的 newIntent 方法
companion object {
    private const val TAG = "OverlayValidation"
    private const val EXTRA_PACKAGE_NAME = "EXTRA_PACKAGE_NAME"
    
    // 簡化的 session 管理
    private var currentSession: String? = null
    private var sessionStartTime = 0L
    private val SESSION_TIMEOUT = 10000L // 10秒超時
    
    @Synchronized
    fun newIntent(context: Context, lockedAppPackageName: String): Intent {
        val currentTime = System.currentTimeMillis()
        
        Log.e(TAG, "=== newIntent called ===")
        Log.e(TAG, "Target package: $lockedAppPackageName")
        Log.e(TAG, "Current session: $currentSession")
        Log.e(TAG, "Session age: ${currentTime - sessionStartTime}ms")
        
        // 檢查是否有相同包名的活躍 session
        if (currentSession == lockedAppPackageName && 
            currentTime - sessionStartTime < SESSION_TIMEOUT) {
            Log.e(TAG, "Active session exists, returning empty intent")
            return Intent() // 返回空 Intent
        }
        
        // 創建新 session
        currentSession = lockedAppPackageName
        sessionStartTime = currentTime
        
        Log.e(TAG, "Creating new session for: $lockedAppPackageName")
        
        return Intent(context, OverlayValidationActivity::class.java).apply {
            putExtra(EXTRA_PACKAGE_NAME, lockedAppPackageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
    }
    
    @Synchronized
    fun clearSession(packageName: String) {
        Log.e(TAG, "Clearing session for: $packageName")
        if (currentSession == packageName) {
            currentSession = null
            sessionStartTime = 0L
        }
    }
    
    @Synchronized
    fun clearAllSessions() {
        Log.e(TAG, "Clearing all sessions")
        currentSession = null
        sessionStartTime = 0L
    }
}