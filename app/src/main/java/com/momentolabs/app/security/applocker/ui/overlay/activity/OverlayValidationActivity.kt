// 2. 修改或創建 OverlayValidationActivity.kt
package com.momentolabs.app.security.applocker.ui.overlay.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

class OverlayValidationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OverlayValidation"
        private const val EXTRA_PACKAGE_NAME = "EXTRA_PACKAGE_NAME"
        
        // 防重複觸發的靜態變數
        private var isActivityActive = false
        private var lastPackageName: String? = null
        private var lastShowTime = 0L
        private val MIN_SHOW_INTERVAL = 2000L // 2秒內不重複顯示
        private val activeSessions = mutableSetOf<String>()

        fun newIntent(context: Context, lockedAppPackageName: String): Intent {
            val currentTime = System.currentTimeMillis()
            val sessionKey = "$lockedAppPackageName-${currentTime / MIN_SHOW_INTERVAL}" // 基於時間片的session key
            
            Log.d(TAG, "Creating intent for: $lockedAppPackageName")
            Log.d(TAG, "Current state - active: $isActivityActive, last: $lastPackageName, sessions: ${activeSessions.size}")
            
            // 檢查是否已有相同應用的活躍session
            if (activeSessions.any { it.startsWith("$lockedAppPackageName-") }) {
                Log.d(TAG, "Active session exists for $lockedAppPackageName, skipping")
                return Intent() // 返回空Intent
            }
            
            // 檢查是否在短時間內重複觸發
            if (isActivityActive && 
                lastPackageName == lockedAppPackageName && 
                currentTime - lastShowTime < MIN_SHOW_INTERVAL) {
                Log.d(TAG, "Too frequent trigger for $lockedAppPackageName, skipping")
                return Intent()
            }
            
            // 清理過期的sessions
            val currentTimeSlot = currentTime / MIN_SHOW_INTERVAL
            activeSessions.removeAll { session ->
                val sessionTime = session.substringAfter("-").toLongOrNull() ?: 0
                currentTimeSlot - sessionTime > 3 // 保留最近3個時間片
            }
            
            // 添加新session
            activeSessions.add(sessionKey)
            lastPackageName = lockedAppPackageName
            lastShowTime = currentTime
            
            Log.d(TAG, "Creating valid intent for: $lockedAppPackageName")
            return Intent(context, OverlayValidationActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, lockedAppPackageName)
                putExtra("SESSION_KEY", sessionKey)
            }
        }
        
        fun clearSession(packageName: String) {
            activeSessions.removeAll { it.startsWith("$packageName-") }
            if (lastPackageName == packageName) {
                lastPackageName = null
            }
            Log.d(TAG, "Cleared session for: $packageName")
        }
    }

    private var lockedAppPackageName: String? = null
    private var sessionKey: String? = null
    private var hasValidatedSuccessfully = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "OverlayValidationActivity onCreate")
        
        // 檢查Intent是否有效
        lockedAppPackageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME)
        sessionKey = intent?.getStringExtra("SESSION_KEY")
        
        if (lockedAppPackageName.isNullOrEmpty() || sessionKey.isNullOrEmpty()) {
            Log.d(TAG, "Invalid intent, finishing activity")
            finish()
            return
        }
        
        // 檢查session是否仍然有效
        if (!activeSessions.contains(sessionKey)) {
            Log.d(TAG, "Session expired: $sessionKey, finishing activity")
            finish()
            return
        }
        
        isActivityActive = true
        Log.d(TAG, "Starting validation for: $lockedAppPackageName")
        
        // 設置Activity屬性防止被系統回收
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        
        // 在這裡初始化你的UI和驗證邏輯
        // setContentView(R.layout.activity_overlay_validation)
        // initializeViews()
        // startValidation()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "OverlayValidationActivity onResume")
        
        // 確保Activity在最前面
        if (!hasValidatedSuccessfully) {
            // 重新檢查是否需要顯示鎖屏
            checkIfStillNeedsLock()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "OverlayValidationActivity onPause")
        
        // 如果還沒驗證成功就暫停了，可能是用戶切換了應用
        if (!hasValidatedSuccessfully) {
            // 這裡可以添加邏輯來決定是否需要重新顯示鎖屏
            handleUnauthorizedPause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OverlayValidationActivity onDestroy")
        
        isActivityActive = false
        
        // 清理對應的session
        sessionKey?.let { key ->
            activeSessions.remove(key)
        }
        
        // 如果沒有成功驗證就被銷毀，需要清理狀態
        if (!hasValidatedSuccessfully) {
            lockedAppPackageName?.let { packageName ->
                clearSession(packageName)
            }
        }
    }

    override fun onBackPressed() {
        // 防止用戶通過返回鍵繞過鎖定
        Log.d(TAG, "Back pressed - ignoring")
        // 不調用 super.onBackPressed()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        Log.d(TAG, "User leave hint")
        
        // 用戶試圖離開應用，如果還沒驗證成功，需要重新顯示鎖屏
        if (!hasValidatedSuccessfully) {
            handleUnauthorizedLeave()
        }
    }

    // 驗證成功時調用
    private fun onValidationSuccessful() {
        Log.d(TAG, "Validation successful for: $lockedAppPackageName")
        hasValidatedSuccessfully = true
        
        // 清理session
        lockedAppPackageName?.let { packageName ->
            clearSession(packageName)
        }
        
        finish()
    }

    // 驗證失敗時調用
    private fun onValidationFailed() {
        Log.d(TAG, "Validation failed for: $lockedAppPackageName")
        // 可以在這裡添加錯誤提示邏輯
        // 不要finish()，讓用戶重新嘗試
    }

    private fun checkIfStillNeedsLock() {
        // 檢查當前前台應用是否還是需要鎖定的應用
        // 如果不是，可以直接結束Activity
        lockedAppPackageName?.let { packageName ->
            // 這裡可以添加邏輯來檢查當前前台應用
            // 如果當前前台應用不是需要鎖定的應用，就結束Activity
        }
    }

    private fun handleUnauthorizedPause() {
        Log.d(TAG, "Handling unauthorized pause")
        // 用戶可能試圖切換應用，這裡可以添加相應的處理邏輯
        // 例如：記錄嘗試次數、發送通知等
    }

    private fun handleUnauthorizedLeave() {
        Log.d(TAG, "Handling unauthorized leave")
        // 用戶試圖離開應用而沒有完成驗證
        // 可以在這裡添加額外的安全措施
    }

    // 模擬指紋驗證成功的回調
    private fun onFingerprintValidationSuccess() {
        onValidationSuccessful()
    }

    // 模擬圖案驗證成功的回調
    private fun onPatternValidationSuccess() {
        onValidationSuccessful()
    }
}