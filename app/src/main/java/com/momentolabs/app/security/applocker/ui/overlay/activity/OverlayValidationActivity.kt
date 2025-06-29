// === 第三步：完全重寫 OverlayValidationActivity.kt ===
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
        
        // 全局session管理
        private val activeSessions = mutableMapOf<String, SessionInfo>()
        private var globalActivityCount = 0
        
        private data class SessionInfo(
            val packageName: String,
            val createdTime: Long,
            val activityInstance: String
        )
        
        private const val SESSION_TIMEOUT = 5000L // 5秒session超時
        private const val MAX_SESSIONS = 1 // 最多只允許1個session
        
        @Synchronized
        fun newIntent(context: Context, lockedAppPackageName: String): Intent {
            val currentTime = System.currentTimeMillis()
            val instanceId = "${lockedAppPackageName}_${currentTime}_${System.identityHashCode(context)}"
            
            Log.e(TAG, "=== newIntent called ===")
            Log.e(TAG, "Package: $lockedAppPackageName")
            Log.e(TAG, "Current sessions: ${activeSessions.size}")
            Log.e(TAG, "Global activity count: $globalActivityCount")
            
            // 清理過期session
            cleanupExpiredSessions()
            
            // 檢查是否已有相同包名的活躍session
            val existingSession = activeSessions[lockedAppPackageName]
            if (existingSession != null) {
                val sessionAge = currentTime - existingSession.createdTime
                if (sessionAge < SESSION_TIMEOUT) {
                    Log.e(TAG, "Active session exists for $lockedAppPackageName (age: ${sessionAge}ms), returning empty intent")
                    return Intent() // 返回空Intent
                } else {
                    Log.e(TAG, "Session expired for $lockedAppPackageName, removing")
                    activeSessions.remove(lockedAppPackageName)
                }
            }
            
            // 檢查總session數量
            if (activeSessions.size >= MAX_SESSIONS) {
                Log.e(TAG, "Too many sessions (${activeSessions.size}), clearing all")
                activeSessions.clear()
            }
            
            // 檢查全局activity數量
            if (globalActivityCount > 0) {
                Log.e(TAG, "Activity already running (count: $globalActivityCount), returning empty intent")
                return Intent()
            }
            
            // 創建新session
            activeSessions[lockedAppPackageName] = SessionInfo(
                packageName = lockedAppPackageName,
                createdTime = currentTime,
                activityInstance = instanceId
            )
            
            Log.e(TAG, "Creating new session: $instanceId")
            Log.e(TAG, "Total active sessions: ${activeSessions.size}")
            
            return Intent(context, OverlayValidationActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, lockedAppPackageName)
                putExtra("INSTANCE_ID", instanceId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_HISTORY
            }
        }
        
        @Synchronized
        fun clearSession(packageName: String) {
            Log.e(TAG, "Clearing session for: $packageName")
            activeSessions.remove(packageName)
        }
        
        @Synchronized
        fun clearAllSessions() {
            Log.e(TAG, "Clearing all sessions (${activeSessions.size})")
            activeSessions.clear()
            globalActivityCount = 0
        }
        
        private fun cleanupExpiredSessions() {
            val currentTime = System.currentTimeMillis()
            val expiredSessions = activeSessions.filter { (_, session) ->
                currentTime - session.createdTime > SESSION_TIMEOUT
            }
            
            expiredSessions.forEach { (packageName, _) ->
                Log.e(TAG, "Removing expired session: $packageName")
                activeSessions.remove(packageName)
            }
        }
        
        @Synchronized
        private fun incrementActivityCount() {
            globalActivityCount++
            Log.e(TAG, "Activity count incremented to: $globalActivityCount")
        }
        
        @Synchronized
        private fun decrementActivityCount() {
            globalActivityCount = maxOf(0, globalActivityCount - 1)
            Log.e(TAG, "Activity count decremented to: $globalActivityCount")
        }
    }

    private var lockedAppPackageName: String? = null
    private var instanceId: String? = null
    private var hasValidatedSuccessfully = false
    private var isActivityValid = false
    private var startTime = 0L
    
    // 自動消失的Handler
    private val autoFinishHandler = android.os.Handler()
    private val autoFinishRunnable = Runnable {
        Log.e(TAG, "Auto finishing activity due to timeout")
        finishWithCleanup()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startTime = System.currentTimeMillis()
        
        Log.e(TAG, "=== OverlayValidationActivity onCreate ===")
        incrementActivityCount()
        
        // 獲取Intent數據
        lockedAppPackageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME)
        instanceId = intent?.getStringExtra("INSTANCE_ID")
        
        Log.e(TAG, "Package: $lockedAppPackageName")
        Log.e(TAG, "Instance ID: $instanceId")
        
        // 驗證Intent有效性
        if (lockedAppPackageName.isNullOrEmpty() || instanceId.isNullOrEmpty()) {
            Log.e(TAG, "Invalid intent data, finishing immediately")
            finishWithCleanup()
            return
        }
        
        // 驗證session有效性
        val session = activeSessions[lockedAppPackageName]
        if (session == null || session.activityInstance != instanceId) {
            Log.e(TAG, "Invalid or expired session, finishing immediately")
            finishWithCleanup()
            return
        }
        
        isActivityValid = true
        
        // 設置Activity屬性
        try {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting activity flags: ${e.message}")
        }
        
        // 設置自動消失定時器（15秒）
        autoFinishHandler.postDelayed(autoFinishRunnable, 15000)
        
        Log.e(TAG, "Activity created successfully")
        
        // 在這裡初始化你的UI
        // setContentView(R.layout.activity_overlay_validation)
        // initializeViews()
        
        // 模擬驗證流程
        simulateValidation()
    }

    override fun onStart() {
        super.onStart()
        Log.e(TAG, "onStart()")
    }

    override fun onResume() {
        super.onResume()
        Log.e(TAG, "onResume()")
        
        if (!isActivityValid) {
            Log.e(TAG, "Activity invalid in onResume, finishing")
            finishWithCleanup()
            return
        }
        
        // 檢查session是否仍然有效
        val session = activeSessions[lockedAppPackageName]
        if (session == null || session.activityInstance != instanceId) {
            Log.e(TAG, "Session invalidated in onResume, finishing")
            finishWithCleanup()
            return
        }
    }

    override fun onPause() {
        super.onPause()
        Log.e(TAG, "onPause() - hasValidated: $hasValidatedSuccessfully")
        
        if (!hasValidatedSuccessfully && isActivityValid) {
            Log.e(TAG, "Activity paused without validation - user might be trying to bypass")
            // 這裡可以添加處理邏輯，比如記錄嘗試次數
        }
    }

    override fun onStop() {
        super.onStop()
        Log.e(TAG, "onStop()")
    }

    override fun onDestroy() {
        super.onDestroy()
        val lifetime = System.currentTimeMillis() - startTime
        Log.e(TAG, "=== onDestroy() - lifetime: ${lifetime}ms ===")
        
        // 清理定時器
        autoFinishHandler.removeCallbacks(autoFinishRunnable)
        
        // 清理session
        lockedAppPackageName?.let { packageName ->
            clearSession(packageName)
        }
        
        decrementActivityCount()
        
        Log.e(TAG, "Activity destroyed")
    }

    override fun onBackPressed() {
        Log.e(TAG, "Back pressed - preventing bypass")
        // 不調用super.onBackPressed()來防止用戶繞過驗證
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        Log.e(TAG, "User leave hint")
        
        if (!hasValidatedSuccessfully) {
            Log.e(TAG, "User attempted to leave without validation")
            // 用戶試圖離開應用而沒有完成驗證
            // 這裡可以記錄安全事件或採取其他措施
        }
    }

    // 模擬驗證流程（你需要根據實際情況修改）
    private fun simulateValidation() {
        Log.e(TAG, "Starting validation simulation")
        
        // 模擬指紋驗證或圖案驗證
        // 這裡你需要調用實際的驗證邏輯
        
        // 模擬3秒後自動成功（僅用於測試）
        autoFinishHandler.postDelayed({
            if (isActivityValid && !hasValidatedSuccessfully) {
                Log.e(TAG, "Simulated validation success")
                onValidationSuccessful()
            }
        }, 3000)
    }

    // 驗證成功時調用
    private fun onValidationSuccessful() {
        Log.e(TAG, "=== Validation successful ===")
        hasValidatedSuccessfully = true
        
        // 取消自動完成定時器
        autoFinishHandler.removeCallbacks(autoFinishRunnable)
        
        finishWithCleanup()
    }

    // 驗證失敗時調用
    private fun onValidationFailed() {
        Log.e(TAG, "Validation failed")
        // 重置驗證狀態，讓用戶重新嘗試
        // 不要finish()，讓用戶重新驗證
    }

    private fun finishWithCleanup() {
        Log.e(TAG, "Finishing with cleanup")
        
        isActivityValid = false
        
        // 清理定時器
        autoFinishHandler.removeCallbacks(autoFinishRunnable)
        
        // 清理session
        lockedAppPackageName?.let { packageName ->
            clearSession(packageName)
        }
        
        finish()
    }

    // 供外部調用的驗證成功方法
    fun notifyFingerprintSuccess() {
        Log.e(TAG, "Fingerprint validation successful")
        onValidationSuccessful()
    }

    // 供外部調用的驗證失敗方法
    fun notifyFingerprintFailure() {
        Log.e(TAG, "Fingerprint validation failed")
        onValidationFailed()
    }

    // 供外部調用的圖案驗證成功方法
    fun notifyPatternSuccess() {
        Log.e(TAG, "Pattern validation successful")
        onValidationSuccessful()
    }

    // 供外部調用的圖案驗證失敗方法
    fun notifyPatternFailure() {
        Log.e(TAG, "Pattern validation failed")
        onValidationFailed()
    }
}