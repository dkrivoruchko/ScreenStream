package info.dvkr.screenstream.activity

import android.os.Bundle
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog

public abstract class BaseActivity(@LayoutRes contentLayoutId: Int) : AppCompatActivity(contentLayoutId) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setApplicationLocales(AppCompatDelegate.getApplicationLocales())
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