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

    @Inject lateinit var serviceNotificationManager: ServiceNotificationManager
    @Inject lateinit var appForegroundObservable: AppForegroundObservable
    @Inject lateinit var permissionCheckerObservable: PermissionCheckerObservable
    @Inject lateinit var lockedAppsDao: LockedAppsDao
    @Inject lateinit var patternDao: PatternDao
    @Inject lateinit var appLockerPreferences: AppLockerPreferences

    private val validatedPatternObservable = PublishSubject.create<List<PatternDot>>()
    private val allDisposables: CompositeDisposable = CompositeDisposable()
    private var foregroundAppDisposable: Disposable? = null
    private val lockedAppPackageSet: HashSet<String> = HashSet()

    private lateinit var windowManager: WindowManager
    private lateinit var overlayParams: WindowManager.LayoutParams
    private lateinit var overlayView: PatternOverlayView
    private var isOverlayShowing = false

    // === 核心狀態管理 - 完全重新設計 ===
    private data class LockState(
        val isActive: Boolean = false,
        val targetApp: String? = null,
        val sessionId: String? = null,
        val createdTime: Long = 0L,
        val lockType: LockType = LockType.NONE
    )
    
    private enum class LockType { NONE, OVERLAY, FINGERPRINT }
    
    private var currentLockState = LockState()
    private var isProcessingLock = false
    private var lastProcessedApp: String? = null
    private var lastProcessTime = 0L
    
    // 嚴格的防重複機制
    private val LOCK_SESSION_TIMEOUT = 5000L // 5秒後session過期
    private val MIN_LOCK_INTERVAL = 3000L // 同一應用最少3秒間隔
    private val MAX_CONCURRENT_LOCKS = 1 // 最多只能有1個鎖定session
    
    private var screenOnOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    AppLockDiagnostic.log("SCREEN_ON - Resetting all states")
                    resetAllLockStates()
                    android.os.Handler().postDelayed({
                        if (isServiceRunning()) {
                            startMonitoring()
                        }
                    }, 2000) // 延遲2秒確保系統穩定
                }
                Intent.ACTION_SCREEN_OFF -> {
                    AppLockDiagnostic.log("SCREEN_OFF - Stopping monitoring")
                    stopMonitoring()
                    resetAllLockStates()
                }
            }
        }
    }

    private var installUninstallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {}
    }

    init {
        SystemPackages.getSystemPackages().forEach { lockedAppPackageSet.add(it) }
        AppLockDiagnostic.log("AppLockerService initialized")
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        AppLockDiagnostic.log("AppLockerService onCreate()")
        
        initializeAppLockerNotification()
        initializeOverlayView()
        registerReceivers()
        observeLockedApps()
        observeOverlayView()
        observePermissionChecker()
        
        // 延遲啟動監控，確保服務完全初始化
        android.os.Handler().postDelayed({
            startMonitoring()
        }, 1000)
    }

    override fun onDestroy() {
        AppLockDiagnostic.log("AppLockerService onDestroy()")
        AppLockDiagnostic.dumpLogs()
        
        stopMonitoring()
        resetAllLockStates()
        unregisterReceivers()
        
        if (!allDisposables.isDisposed) {
            allDisposables.dispose()
        }
        
        // 重啟服務
        ServiceStarter.startService(applicationContext)
        super.onDestroy()
    }

    private fun registerReceivers() {
        try {
            registerReceiver(screenOnOffReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
            
            registerReceiver(installUninstallReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_INSTALL)
                addDataScheme("package")
            })
        } catch (e: Exception) {
            AppLockDiagnostic.log("Error registering receivers: ${e.message}")
        }
    }

    private fun unregisterReceivers() {
        try {
            unregisterReceiver(screenOnOffReceiver)
            unregisterReceiver(installUninstallReceiver)
        } catch (e: Exception) {
            AppLockDiagnostic.log("Error unregistering receivers: ${e.message}")
        }
    }

    private fun startMonitoring() {
        if (foregroundAppDisposable?.isDisposed != false) {
            AppLockDiagnostic.log("Starting app monitoring")
            
            foregroundAppDisposable = appForegroundObservable
                .get()
                .debounce(1000, TimeUnit.MILLISECONDS) // 1秒防抖
                .distinctUntilChanged()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { packageName -> handleAppForeground(packageName) },
                    { error -> 
                        AppLockDiagnostic.log("Error in app monitoring: ${error.message}")
                    }
                )
            allDisposables.add(foregroundAppDisposable!!)
        }
    }

    private fun stopMonitoring() {
        AppLockDiagnostic.log("Stopping app monitoring")
        foregroundAppDisposable?.let {
            if (!it.isDisposed) {
                it.dispose()
            }
        }
        foregroundAppDisposable = null
    }

    private fun handleAppForeground(packageName: String) {
        val currentTime = System.currentTimeMillis()
        
        AppLockDiagnostic.log("App foreground detected: $packageName")
        AppLockDiagnostic.log("Current lock state: ${currentLockState}")
        AppLockDiagnostic.log("Processing lock: $isProcessingLock")
        
        // 防止重複處理
        if (isProcessingLock) {
            AppLockDiagnostic.log("Already processing lock, ignoring")
            return
        }
        
        // 檢查是否是自己的應用
        if (packageName == applicationContext.packageName) {
            AppLockDiagnostic.log("Own app detected, ignoring")
            return
        }
        
        // 檢查處理間隔
        if (lastProcessedApp == packageName && 
            currentTime - lastProcessTime < MIN_LOCK_INTERVAL) {
            AppLockDiagnostic.log("Too frequent for same app, ignoring")
            return
        }
        
        isProcessingLock = true
        lastProcessedApp = packageName
        lastProcessTime = currentTime
        
        try {
            // 如果當前有鎖定session且不是同一個應用，清理舊session
            if (currentLockState.isActive && currentLockState.targetApp != packageName) {
                AppLockDiagnostic.log("Clearing old lock session for different app")
                clearCurrentLockSession()
            }
            
            // 檢查新應用是否需要鎖定
            if (shouldLockApp(packageName)) {
                // 檢查是否已經有活躍的鎖定session
                if (currentLockState.isActive && currentLockState.targetApp == packageName) {
                    val sessionAge = currentTime - currentLockState.createdTime
                    if (sessionAge < LOCK_SESSION_TIMEOUT) {
                        AppLockDiagnostic.log("Active session exists for same app, ignoring")
                        return
                    } else {
                        AppLockDiagnostic.log("Session expired, creating new one")
                        clearCurrentLockSession()
                    }
                }
                
                createLockSession(packageName)
            } else {
                // 應用不需要鎖定，清理任何現有session
                if (currentLockState.isActive) {
                    AppLockDiagnostic.log("App doesn't need lock, clearing session")
                    clearCurrentLockSession()
                }
            }
            
        } catch (e: Exception) {
            AppLockDiagnostic.log("Error handling app foreground: ${e.message}")
            clearCurrentLockSession()
        } finally {
            isProcessingLock = false
        }
    }

    private fun shouldLockApp(packageName: String): Boolean {
        val needsLock = lockedAppPackageSet.contains(packageName)
        AppLockDiagnostic.log("App $packageName needs lock: $needsLock")
        return needsLock
    }

    private fun createLockSession(packageName: String) {
        val sessionId = "${packageName}_${System.currentTimeMillis()}"
        val useFingerprint = appLockerPreferences.getFingerPrintEnabled()
        val hasOverlayPermission = PermissionChecker.checkOverlayPermission(applicationContext)
        
        val lockType = when {
            useFingerprint -> LockType.FINGERPRINT
            hasOverlayPermission -> LockType.OVERLAY
            else -> LockType.FINGERPRINT // fallback to fingerprint
        }
        
        AppLockDiagnostic.log("Creating lock session: $sessionId, type: $lockType")
        
        currentLockState = LockState(
            isActive = true,
            targetApp = packageName,
            sessionId = sessionId,
            createdTime = System.currentTimeMillis(),
            lockType = lockType
        )
        
        when (lockType) {
            LockType.FINGERPRINT -> showFingerprintLock(packageName)
            LockType.OVERLAY -> showOverlayLock(packageName)
            LockType.NONE -> {} // 不應該發生
        }
    }

    private fun showFingerprintLock(packageName: String) {
        AppLockDiagnostic.log("Showing fingerprint lock for: $packageName")
        
        try {
            val intent = OverlayValidationActivity.newIntent(applicationContext, packageName)
            if (intent.component != null) { // 檢查Intent是否有效
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
            } else {
                AppLockDiagnostic.log("Invalid intent for OverlayValidationActivity")
                clearCurrentLockSession()
            }
        } catch (e: Exception) {
            AppLockDiagnostic.log("Error starting fingerprint activity: ${e.message}")
            clearCurrentLockSession()
        }
    }

    private fun showOverlayLock(packageName: String) {
        AppLockDiagnostic.log("Showing overlay lock for: $packageName")
        
        if (!isOverlayShowing) {
            try {
                isOverlayShowing = true
                overlayView.setHiddenDrawingMode(appLockerPreferences.getHiddenDrawingMode())
                overlayView.setAppPackageName(packageName)
                windowManager.addView(overlayView, overlayParams)
            } catch (e: Exception) {
                AppLockDiagnostic.log("Error showing overlay: ${e.message}")
                isOverlayShowing = false
                clearCurrentLockSession()
            }
        }
    }

    private fun clearCurrentLockSession() {
        AppLockDiagnostic.log("Clearing lock session: ${currentLockState.sessionId}")
        
        // 隱藏覆蓋層
        hideOverlay()
        
        // 重置狀態
        currentLockState = LockState()
        
        // 通知OverlayValidationActivity清理session
        currentLockState.targetApp?.let { packageName ->
            OverlayValidationActivity.clearSession(packageName)
        }
    }

    private fun resetAllLockStates() {
        AppLockDiagnostic.log("Resetting all lock states")
        
        clearCurrentLockSession()
        isProcessingLock = false
        lastProcessedApp = null
        lastProcessTime = 0L
        
        // 清理OverlayValidationActivity的所有session
        OverlayValidationActivity.clearAllSessions()
    }

    private fun hideOverlay() {
        if (isOverlayShowing) {
            AppLockDiagnostic.log("Hiding overlay")
            isOverlayShowing = false
            try {
                windowManager.removeViewImmediate(overlayView)
            } catch (e: Exception) {
                AppLockDiagnostic.log("Error hiding overlay: ${e.message}")
            }
        }
    }

    private fun isServiceRunning(): Boolean {
        return !allDisposables.isDisposed
    }

    // === 其他方法保持不變 ===
    private fun observeLockedApps() {
        allDisposables += lockedAppsDao.getLockedApps()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { lockedAppList ->
                    lockedAppPackageSet.clear()
                    lockedAppList.forEach { lockedAppPackageSet.add(it.parsePackageName()) }
                    SystemPackages.getSystemPackages().forEach { lockedAppPackageSet.add(it) }
                    AppLockDiagnostic.log("Locked apps updated: ${lockedAppPackageSet.size}")
                },
                { error -> 
                    AppLockDiagnostic.log("Error observing locked apps: ${error.message}")
                }
            )
    }

    private fun observeOverlayView() {
        allDisposables += Flowable
            .combineLatest(
                patternDao.getPattern().map { it.patternMetadata.pattern },
                validatedPatternObservable.toFlowable(BackpressureStrategy.BUFFER),
                PatternValidatorFunction()
            )
            .subscribe { isValid -> 
                onPatternValidated(isValid)
            }
    }

    private fun initializeOverlayView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayParams = OverlayViewLayoutParams.get()
        overlayView = PatternOverlayView(applicationContext).apply {
            observePattern { pattern -> 
                validatedPatternObservable.onNext(pattern.convertToPatternDot())
            }
        }
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

    private fun onPatternValidated(isCorrect: Boolean) {
        AppLockDiagnostic.log("Pattern validation result: $isCorrect")
        
        if (isCorrect) {
            overlayView.notifyDrawnCorrect()
            clearCurrentLockSession()
        } else {
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

    companion object {
        private const val NOTIFICATION_ID_APPLOCKER_SERVICE = 1
        private const val NOTIFICATION_ID_APPLOCKER_PERMISSION_NEED = 2
    }
}