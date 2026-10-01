package com.heizhu.weiqi

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.heizhu.weiqi.ui.AppRoot
import com.heizhu.weiqi.ui.theme.WeiqiTvTheme
import com.heizhu.weiqi.vm.GameViewModel

/**
 * 唯一的 Activity。
 *
 * 全应用单 Activity + Compose 内部导航：电视应用没有多任务栈的需求，
 * 单 Activity 能让返回键的处理完全可控（我们用它做悔棋，而不是退出页面）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 下棋时不能因为「长时间没操作」而息屏 —— 孩子思考一步可能要很久
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            WeiqiTvTheme {
                val vm: GameViewModel = viewModel()
                AppRoot(vm)
            }
        }
    }
}
