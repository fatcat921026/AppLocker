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
    
    // 新增：防止重複觸發的狀態控制
    private var isProcessingAppChange = false
    private var lastProcessedApp: String? = null
    private var lastProcessTime = 0L
    private val MIN_PROCESS_INTERVAL = 1000L // 1秒內不重複處理同一應用

    // 新增：應用鎖定狀態追蹤
    private var currentLockedApp: String? = null
    private var isAppCurrentlyLocked = false

    private var screenOnOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    // 螢幕開啟時重置狀態
                    resetLockState()
                    observeForegroundApplication()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // 螢幕關閉時重置狀態並停止監控
                    resetLockState()
                    stopForegroundApplicationObserver()
                }
            }
        }
    }

    private var installUninstallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
        }
    }

    init {
        SystemPackages.getSystemPackages().forEach { lockedAppPackageSet.add(it) }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()

        initializeAppLockerNotification()
        initializeOverlayView()
        registerScreenReceiver()
        registerInstallUninstallReceiver()
        observeLockedApps()
        observeOverlayView()
        observeForegroundApplication()
        observePermissionChecker()
    }

    override fun onDestroy() {
        ServiceStarter.startService(applicationContext)
        unregisterScreenReceiver()
        unregisterInstallUninstallReceiver()
        if (allDisposables.isDisposed.not()) {
            allDisposables.dispose()
        }
        super.onDestroy()
    }

    private fun registerInstallUninstallReceiver() {
        var installUninstallFilter = IntentFilter()
            .apply {
                addAction(Intent.ACTION_PACKAGE_INSTALL)
                addDataScheme("package")
            }

        registerReceiver(installUninstallReceiver, installUninstallFilter)
    }

    private fun unregisterInstallUninstallReceiver() {
        unregisterReceiver(installUninstallReceiver)
    }

    private fun registerScreenReceiver() {
        val screenFilter = IntentFilter()
        screenFilter.addAction(Intent.ACTION_SCREEN_ON)
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOnOffReceiver, screenFilter)
    }

    private fun unregisterScreenReceiver() {
        unregisterReceiver(screenOnOffReceiver)
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
                },
                { error -> 
                    Log.e("AppLockerService", "Error observing locked apps", error)
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
        overlayView = PatternOverlayView(
            applicationContext
        ).apply {
            observePattern(this@AppLockerService::onDrawPattern)
        }
    }

    private fun observeForegroundApplication() {
        if (foregroundAppDisposable != null && foregroundAppDisposable?.isDisposed?.not() == true) {
            return
        }

        foregroundAppDisposable = appForegroundObservable
            .get()
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { foregroundAppPackage -> onAppForeground(foregroundAppPackage) },
                { error -> 
                    Log.e("AppLockerService", "Error observing foreground app", error)
                })
        allDisposables.add(foregroundAppDisposable!!)
    }

    private fun stopForegroundApplicationObserver() {
        if (foregroundAppDisposable != null && foregroundAppDisposable?.isDisposed?.not() == true) {
            foregroundAppDisposable?.dispose()
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

    private fun onAppForeground(foregroundAppPackage: String) {
        // 防止重複處理和過於頻繁的觸發
        val currentTime = System.currentTimeMillis()
        if (isProcessingAppChange || 
            (lastProcessedApp == foregroundAppPackage && 
             currentTime - lastProcessTime < MIN_PROCESS_INTERVAL)) {
            return
        }

        isProcessingAppChange = true
        lastProcessedApp = foregroundAppPackage
        lastProcessTime = currentTime

        try {
            Log.d("AppLockerService", "App foreground: $foregroundAppPackage, last: $lastForegroundAppPackage")

            // 如果當前應用是我們自己的應用，不需要處理
            if (foregroundAppPackage == applicationContext.packageName) {
                lastForegroundAppPackage = foregroundAppPackage
                return
            }

            // 如果從鎖定狀態切換到其他應用，先隱藏覆蓋層
            if (isAppCurrentlyLocked && currentLockedApp != foregroundAppPackage) {
                hideOverlay()
                resetLockState()
            }

            // 檢查新的前台應用是否需要鎖定
            if (lockedAppPackageSet.contains(foregroundAppPackage)) {
                // 如果已經在鎖定同一個應用，不重複觸發
                if (isAppCurrentlyLocked && currentLockedApp == foregroundAppPackage) {
                    return
                }

                showAppLock(foregroundAppPackage)
            } else {
                // 當前應用不需要鎖定，重置狀態
                if (isAppCurrentlyLocked) {
                    hideOverlay()
                    resetLockState()
                }
            }

            lastForegroundAppPackage = foregroundAppPackage
        } finally {
            isProcessingAppChange = false
        }
    }

    private fun showAppLock(foregroundAppPackage: String) {
        currentLockedApp = foregroundAppPackage
        isAppCurrentlyLocked = true

        if (appLockerPreferences.getFingerPrintEnabled() || 
            PermissionChecker.checkOverlayPermission(applicationContext).not()) {
            
            val intent = OverlayValidationActivity.newIntent(applicationContext, foregroundAppPackage)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        } else {
            showOverlay(foregroundAppPackage)
        }
    }

    private fun resetLockState() {
        isAppCurrentlyLocked = false
        currentLockedApp = null
        if (isOverlayShowing) {
            hideOverlay()
        }
    }

    private fun onDrawPattern(pattern: List<PatternLockView.Dot>) {
        validatedPatternObservable.onNext(pattern.convertToPatternDot())
    }

    private fun onPatternValidated(isDrawedPatternCorrect: Boolean) {
        if (isDrawedPatternCorrect) {
            overlayView.notifyDrawnCorrect()
            hideOverlay()
            resetLockState()
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

    private fun showOverlay(lockedAppPackageName: String) {
        if (isOverlayShowing.not()) {
            isOverlayShowing = true
            overlayView.setHiddenDrawingMode(appLockerPreferences.getHiddenDrawingMode())
            overlayView.setAppPackageName(lockedAppPackageName)
            windowManager.addView(overlayView, overlayParams)
        }
    }

    private fun hideOverlay() {
        if (isOverlayShowing) {
            isOverlayShowing = false
            try {
                windowManager.removeViewImmediate(overlayView)
            } catch (e: Exception) {
                Log.e("AppLockerService", "Error removing overlay", e)
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID_APPLOCKER_SERVICE = 1
        private const val NOTIFICATION_ID_APPLOCKER_PERMISSION_NEED = 2
    }
}