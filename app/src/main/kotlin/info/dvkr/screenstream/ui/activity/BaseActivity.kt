package info.dvkr.screenstream.ui.activity

import android.os.Bundle
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog

abstract class BaseActivity(@LayoutRes contentLayoutId: Int) : AppCompatActivity(contentLayoutId) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        XLog.d(getLog("onCreate"))
    }

    override fun onStart() {
        super.onStart()
        XLog.d(getLog("onStart"))
    }

    override fun onResume() {
        super.onResume()
        XLog.d(getLog("onResume"))
    }

    override fun onPause() {
        XLog.d(getLog("onPause"))
        super.onPause()
    }

    override fun onStop() {
        XLog.d(getLog("onStop"))
        super.onStop()
    }

    override fun onDestroy() {
        XLog.d(getLog("onDestroy"))
        super.onDestroy()
    }
}