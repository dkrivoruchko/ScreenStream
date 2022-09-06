package info.dvkr.screenstream.service

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.service.helper.IntentAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

@TargetApi(Build.VERSION_CODES.N)
class TileActionService : TileService() {

    internal companion object {

        @SuppressLint("WrongConstant")
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        internal fun askToAddTile(context: Context) {
            XLog.d(getLog("askToAddTile"))
            (context.getSystemService(Context.STATUS_BAR_SERVICE) as StatusBarManager).requestAddTileService(
                ComponentName(context, TileActionService::class.java),
                context.getString(R.string.app_name),
                Icon.createWithResource(context, R.drawable.ic_tile_default_24dp),
                {}, {}
            )
        }
    }

    private var coroutineScope: CoroutineScope? = null
    private var serviceConnection: ServiceConnection? = null
    private var isBound: Boolean = false
    private var isStreaming: Boolean = false

    override fun onStartListening() {
        super.onStartListening()
        XLog.d(getLog("onStartListening", " isRunning:${ForegroundService.isRunning}, isBound:$isBound"))

        if (ForegroundService.isRunning && isBound.not()) {
            serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                    XLog.d(this@TileActionService.getLog("onServiceConnected"))

                    try {
                        val foregroundServiceBinder = binder as ForegroundService.ForegroundServiceBinder

                        coroutineScope?.cancel()
                        coroutineScope = CoroutineScope(Job() + Dispatchers.Main.immediate)
                        coroutineScope!!.launch {
                            foregroundServiceBinder.serviceMessageFlow
                                .onEach { serviceMessage ->
                                    XLog.d(this@TileActionService.getLog("onServiceMessage", "$serviceMessage"))
                                    when (serviceMessage) {
                                        is ServiceMessage.ServiceState -> {
                                            isStreaming = serviceMessage.isStreaming; updateTile()
                                        }
                                        is ServiceMessage.FinishActivity -> {
                                            isStreaming = false; updateTile()
                                        }
                                        else -> Unit
                                    }
                                }
                                .catch { cause ->
                                    XLog.e(this@TileActionService.getLog("onServiceConnected.serviceMessageFlow: $cause"))
                                    XLog.e(this@TileActionService.getLog("onServiceConnected.serviceMessageFlow"), cause)
                                }
                                .collect()
                        }
                    } catch (cause: RemoteException) {
                        XLog.e(this@TileActionService.getLog("onServiceConnected", "Failed to bind"), cause)
                        return
                    }
                    isBound = true
                    IntentAction.GetServiceState.sendToAppService(this@TileActionService)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    XLog.w(this@TileActionService.getLog("onServiceDisconnected"))
                    coroutineScope?.cancel()
                    coroutineScope = null
                    isBound = false
                }
            }

            bindService(ForegroundService.getForegroundServiceIntent(this), serviceConnection!!, Context.BIND_AUTO_CREATE)
        } else {
            isStreaming = false
            updateTile()
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        XLog.d(getLog("onStopListening", "Invoked"))
        if (isBound) {
            coroutineScope?.cancel()
            coroutineScope = null
            serviceConnection?.let { unbindService(it) }
            serviceConnection = null
            isBound = false
        }
        isStreaming = false
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        XLog.d(getLog("onClick", "isRunning:${ForegroundService.isRunning}, isStreaming: $isStreaming"))
        if (isStreaming)
            IntentAction.StopStream.sendToAppService(applicationContext)
        else
            startActivityAndCollapse(
                IntentAction.StartStream.toAppActivityIntent(applicationContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
    }

    private fun updateTile() {
        XLog.d(getLog("updateTile", "isRunning:${ForegroundService.isRunning}, isStreaming: $isStreaming"))
        if (ForegroundService.isRunning.not()) {
            qsTile?.apply {
                icon = Icon.createWithResource(this@TileActionService, R.drawable.ic_tile_default_24dp)
                label = getString(R.string.app_name)
                contentDescription = getString(R.string.app_name)
                state = Tile.STATE_INACTIVE
                runCatching { updateTile() }
            }
        } else if (isStreaming.not()) {
            qsTile?.apply {
                icon = Icon.createWithResource(this@TileActionService, R.drawable.ic_tile_start_24dp)
                label = getString(R.string.notification_start)
                contentDescription = getString(R.string.notification_start)
                state = Tile.STATE_ACTIVE
                runCatching { updateTile() }
            }
        } else {
            qsTile?.apply {
                icon = Icon.createWithResource(this@TileActionService, R.drawable.ic_tile_stop_24dp)
                label = getString(R.string.notification_stop)
                contentDescription = getString(R.string.notification_stop)
                state = Tile.STATE_ACTIVE
                runCatching { updateTile() }
            }
        }
    }
}