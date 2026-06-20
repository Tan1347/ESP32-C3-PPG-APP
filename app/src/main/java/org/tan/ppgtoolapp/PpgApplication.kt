package org.tan.ppgtoolapp

import android.app.Application
import android.util.Log
import org.tan.ppgtoolapp.data.network.GitHubHostsHelper
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class PpgApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 后台预获取 GitHub 优选 IP，加速后续更新检查和下载
        applicationScope.launch {
            try {
                Log.i("PpgApplication", "开始预获取 GitHub hosts...")
                val hosts = GitHubHostsHelper.fetchRemoteHosts(this@PpgApplication)
                Log.i("PpgApplication", "GitHub hosts 获取完成: ${hosts.size} 条记录")
            } catch (e: Exception) {
                Log.w("PpgApplication", "GitHub hosts 预获取失败: ${e.message}")
            }
        }
    }
}
