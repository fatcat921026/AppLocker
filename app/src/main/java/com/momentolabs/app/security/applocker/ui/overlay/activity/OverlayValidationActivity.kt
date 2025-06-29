// 在 OverlayValidationActivity 中添加以下修改，防止重複觸發

class OverlayValidationActivity : AppCompatActivity() {
    
    companion object {
        private var isActivityShowing = false
        private var lastPackageName: String? = null
        private var lastShowTime = 0L
        private const val MIN_SHOW_INTERVAL = 1000L // 1秒內不重複顯示同一應用的鎖

        fun newIntent(context: Context, lockedAppPackageName: String): Intent {
            val currentTime = System.currentTimeMillis()
            
            // 如果同一個應用在短時間內重複觸發，返回空 Intent
            if (isActivityShowing && 
                lastPackageName == lockedAppPackageName && 
                currentTime - lastShowTime < MIN_SHOW_INTERVAL) {
                return Intent() // 返回空 Intent，不啟動
            }
            
            lastPackageName = lockedAppPackageName
            lastShowTime = currentTime
            
            return Intent(context, OverlayValidationActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, lockedAppPackageName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isActivityShowing = true
        
        // 如果是空 Intent，直接結束
        if (intent == null || intent.getStringExtra(EXTRA_PACKAGE_NAME).isNullOrEmpty()) {
            finish()
            return
        }
        
        // 其他初始化代碼...
    }

    override fun onDestroy() {
        super.onDestroy()
        isActivityShowing = false
    }

    override fun onBackPressed() {
        // 防止用戶按返回鍵繞過鎖定
        // 不調用 super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        // 當 Activity 失去焦點時，檢查是否需要重新鎖定
        if (!isFinishing) {
            // 可以在這裡添加邏輯來處理應用切換
        }
    }
}