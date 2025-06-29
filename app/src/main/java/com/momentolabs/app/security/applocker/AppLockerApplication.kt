package com.momentolabs.app.security.applocker

import android.content.Context
import androidx.multidex.MultiDex
// import com.bugsnag.android.Bugsnag  // 移除 - 錯誤回報
// import com.facebook.FacebookSdk     // 移除 - Facebook SDK
// import com.facebook.soloader.SoLoader  // 移除 - Facebook SoLoader
import com.facebook.stetho.Stetho
// import com.google.android.gms.ads.MobileAds  // 移除 - Google 廣告
import com.momentolabs.app.security.applocker.di.component.DaggerAppComponent
import com.momentolabs.app.security.applocker.service.ServiceStarter
import com.momentolabs.app.security.applocker.service.worker.WorkerStarter
import com.raqun.beaverlib.Beaver
import dagger.android.AndroidInjector
import dagger.android.DaggerApplication

class AppLockerApplication : DaggerApplication() {

    override fun applicationInjector(): AndroidInjector<out DaggerApplication> =
        DaggerAppComponent.builder().create(this)

    override fun onCreate() {
        super.onCreate()
        
        // 移除廣告初始化
        // MobileAds.initialize(this, getString(R.string.mobile_ad_id))
        
        // 保留 Stetho (開發者工具，可選擇移除)
        if (BuildConfig.DEBUG) {
            Stetho.initializeWithDefaults(this)
        }
        
        // 移除錯誤回報
        // Bugsnag.init(this)
        
        // 保留日誌工具
        Beaver.build(this)
        
        // 保留核心服務
        ServiceStarter.startService(this)
        
        // 移除 Facebook SoLoader
        // SoLoader.init(this, false)
        
        // 保留工作排程器
        WorkerStarter.startServiceCheckerWorker()
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }
}