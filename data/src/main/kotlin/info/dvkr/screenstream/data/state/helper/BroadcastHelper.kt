package info.dvkr.screenstream.data.state.helper

import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.other.getLog
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

internal sealed class BroadcastHelper(context: Context, private val onError: (AppError) -> Unit) : CoroutineScope {

    companion object {
        fun getInstance(context: Context, onError: (AppError) -> Unit): BroadcastHelper {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                NougatBroadcastHelper(context, onError)
            else
                LegacyBroadcastHelper(context, onError)
        }
    }

    protected val applicationContext: Context = context.applicationContext
    private val supervisorJob = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() = supervisorJob + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, throwable ->
            XLog.e(getLog("onCoroutineException"), throwable)
            onError(FatalError.CoroutineException)
        }

    protected abstract val intentFilter: IntentFilter
    protected abstract val broadcastReceiver: BroadcastReceiver
    private lateinit var onScreenOff: () -> Unit
    private lateinit var onConnectionChanged: () -> Unit

    fun startListening(onScreenOff: () -> Unit, onConnectionChanged: () -> Unit) {
        this.onScreenOff = onScreenOff
        this.onConnectionChanged = onConnectionChanged
        applicationContext.registerReceiver(broadcastReceiver, intentFilter)
    }

    fun stopListening() {
        applicationContext.unregisterReceiver(broadcastReceiver)
        coroutineContext.cancelChildren()
    }

    protected fun onScreenIntentAction() {
        if (::onScreenOff.isInitialized) onScreenOff.invoke()
    }

    private var isConnectionEventScheduled: Boolean = false
    private var isFirstConnectionEvent: Boolean = true

    protected fun onConnectivityIntentAction() {
        isConnectionEventScheduled.not() || return
        isConnectionEventScheduled = true
        launch {
            delay(1000)
            if (isActive) {
                isConnectionEventScheduled = false
                if (isFirstConnectionEvent) isFirstConnectionEvent = false
                else if (::onConnectionChanged.isInitialized) onConnectionChanged.invoke()
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    private class NougatBroadcastHelper(context: Context, onError: (AppError) -> Unit) :
        BroadcastHelper(context, onError) {

        override val intentFilter: IntentFilter by lazy {
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            }
        }

        override val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                XLog.d(this@NougatBroadcastHelper.getLog("onReceive", "Action: ${intent?.action}"))

                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenIntentAction()
                    "android.net.wifi.WIFI_AP_STATE_CHANGED" -> onConnectivityIntentAction()
                }
            }
        }
    }

    @Suppress("Deprecation")
    private class LegacyBroadcastHelper(context: Context, onError: (AppError) -> Unit) :
        BroadcastHelper(context, onError) {

        override val intentFilter: IntentFilter by lazy {
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            }
        }

        override val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                XLog.d(this@LegacyBroadcastHelper.getLog("onReceive", "Action: ${intent?.action}"))

                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenIntentAction()

                    WifiManager.WIFI_STATE_CHANGED_ACTION,
                    ConnectivityManager.CONNECTIVITY_ACTION,
                    "android.net.wifi.WIFI_AP_STATE_CHANGED" -> onConnectivityIntentAction()
                }
            }
        }
    }
}