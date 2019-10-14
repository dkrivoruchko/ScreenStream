package info.dvkr.screenstream.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.other.getLog
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

abstract class BaseActivity : AppCompatActivity(), CoroutineScope {

    private val supervisorJob = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() = supervisorJob + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, throwable ->
            XLog.e(getLog("onCoroutineException"), throwable)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        XLog.v(getLog("onCreate", "Invoked"))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        XLog.e(getLog("onActivityResult"), IllegalStateException("Unknown requestCode: $requestCode"))
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onStart() {
        super.onStart()
        XLog.v(getLog("onStart", "Invoked"))
    }

    override fun onResume() {
        super.onResume()
        XLog.v(getLog("onResume", "Invoked"))
    }

    override fun onPause() {
        XLog.v(getLog("onPause", "Invoked"))
        super.onPause()
    }

    override fun onStop() {
        XLog.v(getLog("onStop", "Invoked"))
        super.onStop()
    }

    override fun onDestroy() {
        XLog.v(getLog("onDestroy", "Invoked"))
        supervisorJob.cancelChildren()
        super.onDestroy()
    }
}