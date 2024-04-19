package info.dvkr.screenstream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatDelegate
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public abstract class AppUpdateActivity : ComponentActivity() {

    protected val updateFlow: StateFlow<((Boolean) -> Unit)?> = MutableStateFlow(null).asStateFlow()

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