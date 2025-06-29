// 1. 修改 AppLockerService.kt - 完整版本
package com.momentolabs.app.security.applocker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import com.momentolabs.app.security.applocker.data.database.lockedapps.LockedAppsDao
import com.momentolabs.app.security.applocker.service.notification.ServiceNotificationManager
import com.momentolabs.app.security.applocker.service.stateprovider.AppForegroundObservable
import com.momentolabs.app.security.applocker.util.extensions.plusAssign
import dagger.android.DaggerService
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject
import android.view.WindowManager
import com.andrognito.patternlockview.PatternLockView
import com.momentolabs.app.security.applocker.data.AppLockerPreferences
import com.momentolabs.app.security.applocker.util.extensions.convertToPatternDot
import com.momentolabs.app.security.applocker.data.database.pattern.PatternDao
import com.momentolabs.app.security.applocker.data.database.pattern.PatternDot
import com.momentolabs.app.security.applocker.service.stateprovider.PermissionCheckerObservable
import com.momentolabs.app.security.applocker.ui.overlay.activity.OverlayValidationActivity
import com.momentolabs.app.security.applocker.ui.overlay.view.OverlayViewLayoutParams
import com.momentolabs.app.security.applocker.ui.overlay.view.PatternOverlayView
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.subjects.PublishSubject
import android.content.IntentFilter
import android.util.Log
import com.momentolabs.app.security.applocker.data.SystemPackages
import com.momentolabs.app.security.applocker.ui.permissions.PermissionChecker
import io.reactivex.disposables.Disposable
import java.util.concurrent.TimeUnit

class AppLockerService : DaggerService() {

    @Inject
    lateinit var serviceNotificationManager: ServiceNotificationManager

    @Inject
    lateinit var appForegroundObservable: AppForegroundObservable

    @Inject
    lateinit var permissionCheckerObservable: PermissionCheckerObservable

    @Inject
    lateinit var lockedAppsDao: LockedAppsDao

    @Inject
    lateinit var patternDao: PatternDao

    @Inject
    lateinit var appLockerPreferences: AppLockerPreferences

    private val validatedPatternObservable = PublishSubject.create<List<PatternDot>>()
    private val allDisposables: CompositeDisposable = CompositeDisposable()
    private var foregroundAppDisposable: Disposable? = null
    private val lockedAppPackageSet: HashSet<String> = HashSet()

    private lateinit var windowManager: WindowManager
    private lateinit var overlayParams: WindowManager.LayoutParams
    private lateinit var overlayView: PatternOverlayView

    private var isOverlayShowing = false
    private var lastForegroundAppPackage: String? = null
    
    // === 核心狀態管理 ===
    private var currentLockedApp: String? = null
    private var isAppCurrentlyLocked = false
    private var isProcessingAppChange = false
    private var lastProcessTime = 0L
    private val MIN_PROCESS_INTERVAL = 2000L // 增加到2秒

    // === 防重複觸發機制 ===
    private var lockSessionId: String? = null
    private var lastLockTime = 0L
    private val LOCK_COOLDOWN = 3000L // 3秒冷卻時間

    // === 應用狀態追蹤 ===
    companion object {
        private var isServiceActive = false
        private var lastActiveApp: String? = null
        private const val TAG = "AppLockerService"
    }

    private var screenOnOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen ON - Resetting lock state")
                    resetAllStates()
                    // 延遲啟動監控，等待系統穩定
                    android.os.Handler().postDelayed({
                        observeForegroundApplication()
                    }, 1000)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen OFF - Stopping monitoring")
                    resetAllStates()
                    stopForegroundApplicationObserver()
                }
            }
        }
    }

    private var installUninstallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 空實現
        }
    }

    init {
        SystemPackages.getSystemPackages().forEach { lockedAppPackageSet.add(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        isServiceActive = true
        
        initializeAppLockerNotification()
        initializeOverlayView()
        registerScreenReceiver()
        registerInstallUninstallReceiver()
        observeLockedApps()
        observeOverlayView()
        observeForegroundApplication()
        observePermissionChecker()
        
        Log.d(TAG, "AppLockerService created")
    }

    override fun onDestroy() {
        isServiceActive = false
        ServiceStarter.startService(applicationContext)
        unregisterScreenReceiver()
        unregisterInstallUninstallReceiver()
        resetAllStates()
        if (allDisposables.isDisposed.not()) {
            allDisposables.dispose()
        }
        Log.d(TAG, "AppLockerService destroyed")
        super.onDestroy()
    }

    private fun registerInstallUninstallReceiver() {
        val installUninstallFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_INSTALL)
            addDataScheme("package")
        }
        registerReceiver(installUninstallReceiver, installUninstallFilter)
    }

    private fun unregisterInstallUninstallReceiver() {
        try {
            unregisterReceiver(installUninstallReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering install receiver", e)
        }
    }

    private fun registerScreenReceiver() {
        val screenFilter = IntentFilter()
        screenFilter.addAction(Intent.ACTION_SCREEN_ON)
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOnOffReceiver, screenFilter)
    }

    private fun unregisterScreenReceiver() {
        try {
            unregisterReceiver(screenOnOffReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering screen receiver", e)
        }
    }

    private fun observeLockedApps() {
        allDisposables += lockedAppsDao.getLockedApps()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { lockedAppList ->
                    lockedAppPackageSet.clear()
                    lockedAppList.forEach { lockedAppPackageSet.add(it.parsePackageName()) }
                    SystemPackages.getSystemPackages().forEach { lockedAppPackageSet.add(it) }
                    Log.d(TAG, "Locked apps updated: ${lockedAppPackageSet.size}")
                },
                { error -> 
                    Log.e(TAG, "Error observing locked apps", error)
                })
    }

    private fun observeOverlayView() {
        allDisposables += Flowable
            .combineLatest(
                patternDao.getPattern().map { it.patternMetadata.pattern },
                validatedPatternObservable.toFlowable(BackpressureStrategy.BUFFER),
                PatternValidatorFunction()
            )
            .subscribe(this@AppLockerService::onPatternValidated)
    }

    private fun initializeOverlayView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayParams = OverlayViewLayoutParams.get()
        overlayView = PatternOverlayView(applicationContext).apply {
            observePattern(this@AppLockerService::onDrawPattern)
        }
    }

    private fun observeForegroundApplication() {
        if (foregroundAppDisposable != null && foregroundAppDisposable?.isDisposed?.not() == true) {
            return
        }

        foregroundAppDisposable = appForegroundObservable
            .get()
            .debounce(300, TimeUnit.MILLISECONDS) // 增加防抖時間
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { foregroundAppPackage -> 
                    if (isServiceActive) {
                        onAppForeground(foregroundAppPackage)
                    }
                },
                { error -> 
                    Log.e(TAG, "Error observing foreground app", error)
                })
        allDisposables.add(foregroundAppDisposable!!)
    }

    private fun stopForegroundApplicationObserver() {
        foregroundAppDisposable?.let {
            if (!it.isDisposed) {
                it.dispose()
            }
        }
        foregroundAppDisposable = null
    }

    private fun observePermissionChecker() {
        allDisposables += permissionCheckerObservable
            .get()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { isPermissionNeed ->
                if (isPermissionNeed) {
                    showPermissionNeedNotification()
                } else {
                    serviceNotificationManager.hidePermissionNotification()
                }
            }
    }

    private fun onAppForeground(foregroundAppPackage: String) {
        val currentTime = System.currentTimeMillis()
        
        // 防止過於頻繁的處理
        if (isProcessingAppChange || currentTime - lastProcessTime < MIN_PROCESS_INTERVAL) {
            Log.d(TAG, "Skipping app change - too frequent: $foregroundAppPackage")
            return
        }

        // 如果是同樣的應用，而且剛剛處理過，跳過
        if (lastActiveApp == foregroundAppPackage && currentTime - lastProcessTime < 5000) {
            Log.d(TAG, "Skipping same app: $foregroundAppPackage")
            return
        }

        isProcessingAppChange = true
        lastProcessTime = currentTime
        lastActiveApp = foregroundAppPackage

        try {
            Log.d(TAG, "Processing app foreground: $foregroundAppPackage")
            Log.d(TAG, "Current state - locked: $isAppCurrentlyLocked, currentApp: $currentLockedApp")

            // 如果是我們自己的應用，不處理
            if (foregroundAppPackage == applicationContext.packageName) {
                Log.d(TAG, "Own app in foreground, ignoring")
                return
            }

            // 檢查是否需要解鎖當前應用
            if (isAppCurrentlyLocked && currentLockedApp != foregroundAppPackage) {
                Log.d(TAG, "Switching from locked app, resetting state")
                hideOverlay()
                resetLockState()
            }

            // 檢查新應用是否需要鎖定
            if (shouldLockApp(foregroundAppPackage)) {
                // 防止重複鎖定同一應用
                if (isAppCurrentlyLocked && currentLockedApp == foregroundAppPackage) {
                    Log.d(TAG, "App already locked: $foregroundAppPackage")
                    return
                }

                // 檢查冷卻時間
                if (currentTime - lastLockTime < LOCK_COOLDOWN) {
                    Log.d(TAG, "In lock cooldown period")
                    return
                }

                showAppLock(foregroundAppPackage)
            } else {
                // 應用不需要鎖定，確保清理狀態
                if (isAppCurrentlyLocked) {
                    Log.d(TAG, "App doesn't need lock, resetting state")
                    hideOverlay()
                    resetLockState()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing app foreground", e)
            resetLockState()
        } finally {
            lastForegroundAppPackage = foregroundAppPackage
            isProcessingAppChange = false
        }
    }

    private fun shouldLockApp(packageName: String): Boolean {
        return lockedAppPackageSet.contains(packageName)
    }

    private fun showAppLock(foregroundAppPackage: String) {
        val currentTime = System.currentTimeMillis()
        val sessionId = "$foregroundAppPackage-$currentTime"
        
        Log.d(TAG, "Showing app lock for: $foregroundAppPackage")
        
        currentLockedApp = foregroundAppPackage
        isAppCurrentlyLocked = true
        lockSessionId = sessionId
        lastLockTime = currentTime

        if (appLockerPreferences.getFingerPrintEnabled() || 
            !PermissionChecker.checkOverlayPermission(applicationContext)) {
            
            val intent = OverlayValidationActivity.newIntent(applicationContext, foregroundAppPackage)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        } else {
            showOverlay(foregroundAppPackage)
        }
    }

    private fun resetLockState() {
        Log.d(TAG, "Resetting lock state")
        isAppCurrentlyLocked = false
        currentLockedApp = null
        lockSessionId = null
        if (isOverlayShowing) {
            hideOverlay()
        }
    }

    private fun resetAllStates() {
        Log.d(TAG, "Resetting all states")
        resetLockState()
        isProcessingAppChange = false
        lastActiveApp = null
        lastForegroundAppPackage = null
    }

    private fun onDrawPattern(pattern: List<PatternLockView.Dot>) {
        validatedPatternObservable.onNext(pattern.convertToPatternDot())
    }

    private fun onPatternValidated(isDrawedPatternCorrect: Boolean) {
        if (isDrawedPatternCorrect) {
            Log.d(TAG, "Pattern validated successfully")
            overlayView.notifyDrawnCorrect()
            hideOverlay()
            resetLockState()
        } else {
            Log.d(TAG, "Pattern validation failed")
            overlayView.notifyDrawnWrong()
        }
    }

    private fun initializeAppLockerNotification() {
        val notification = serviceNotificationManager.createNotification()
        NotificationManagerCompat.from(applicationContext)
            .notify(NOTIFICATION_ID_APPLOCKER_SERVICE, notification)
        startForeground(NOTIFICATION_ID_APPLOCKER_SERVICE, notification)
    }

    private fun showPermissionNeedNotification() {
        val notification = serviceNotificationManager.createPermissionNeedNotification()
        NotificationManagerCompat.from(applicationContext)
            .notify(NOTIFICATION_ID_APPLOCKER_PERMISSION_NEED, notification)
    }

    private fun showOverlay(lockedAppPackageName: String) {
        if (!isOverlayShowing) {
            Log.d(TAG, "Showing overlay for: $lockedAppPackageName")
            isOverlayShowing = true
            overlayView.setHiddenDrawingMode(appLockerPreferences.getHiddenDrawingMode())
            overlayView.setAppPackageName(lockedAppPackageName)
            windowManager.addView(overlayView, overlayParams)
        }
    }

    private fun hideOverlay() {
        if (isOverlayShowing) {
            Log.d(TAG, "Hiding overlay")
            isOverlayShowing = false
            try {
                windowManager.removeViewImmediate(overlayView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay", e)
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID_APPLOCKER_SERVICE = 1
        private const val NOTIFICATION_ID_APPLOCKER_PERMISSION_NEED = 2
    }
}