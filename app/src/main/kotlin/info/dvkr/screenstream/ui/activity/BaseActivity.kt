package info.dvkr.screenstream.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.other.getLog

abstract class BaseActivity(@LayoutRes contentLayoutId: Int) : AppCompatActivity(contentLayoutId) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        XLog.d(getLog("onCreate", "Invoked"))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        XLog.e(getLog("onActivityResult"), IllegalStateException("Unknown requestCode: $requestCode"))
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onStart() {
        super.onStart()
        XLog.d(getLog("onStart", "Invoked"))
    }

    override fun onResume() {
        super.onResume()
        XLog.d(getLog("onResume", "Invoked"))
    }

    override fun onPause() {
        XLog.d(getLog("onPause", "Invoked"))
        super.onPause()
    }

    override fun onStop() {
        XLog.d(getLog("onStop", "Invoked"))
        super.onStop()
    }

    override fun onDestroy() {
        XLog.d(getLog("onDestroy", "Invoked"))
        super.onDestroy()
    }
}