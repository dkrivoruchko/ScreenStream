package info.dvkr.screenstream.service

import android.annotation.TargetApi
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
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

    private var coroutineScope: CoroutineScope? = null
    private var serviceConnection: ServiceConnection? = null
    private var isBound: Boolean = false
    private var isStreaming: Boolean = false

    override fun onStartListening() {
        super.onStartListening()
        XLog.d(getLog("onStartListening", " isRunning:${AppService.isRunning}, isBound:$isBound"))

        if (AppService.isRunning && isBound.not()) {
            serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                    XLog.d(this@TileActionService.getLog("onServiceConnected"))
                    coroutineScope?.cancel()
                    coroutineScope = CoroutineScope(Job() + Dispatchers.Main.immediate).apply {
                        launch(CoroutineName("TileActionService.ServiceMessageFlow")) {
                            (service as AppService.AppServiceBinder).getServiceMessageFlow()
                                .onEach { serviceMessage ->
                                    XLog.d(this@TileActionService.getLog("onServiceMessage", "$serviceMessage"))
                                    when (serviceMessage) {
                                        is ServiceMessage.ServiceState -> {
                                            isStreaming = serviceMessage.isStreaming; updateTile()
                                        }
                                        is ServiceMessage.FinishActivity -> {
                                            isStreaming = false; updateTile()
                                        }
                                    }
                                }
                                .catch { cause -> XLog.e(this@TileActionService.getLog("onServiceMessage"), cause) }
                                .collect()
                        }
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
            bindService(AppService.getAppServiceIntent(this), serviceConnection!!, Context.BIND_AUTO_CREATE)
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
            isBound = false
        }
        isStreaming = false
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        XLog.d(getLog("onClick", "isRunning:${AppService.isRunning}, isStreaming: $isStreaming"))
        if (isStreaming)
            IntentAction.StopStream.sendToAppService(applicationContext)
        else
            startActivityAndCollapse(
                IntentAction.StartStream.toAppActivityIntent(applicationContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
    }

    private fun updateTile() {
        XLog.d(getLog("updateTile", "isRunning:${AppService.isRunning}, isStreaming: $isStreaming"))
        if (AppService.isRunning.not()) {
            qsTile?.apply {
                icon = Icon.createWithResource(this@TileActionService, R.drawable.ic_tile_default_24dp)
                label = getString(R.string.app_name)
                contentDescription = getString(R.string.app_name)
                state = Tile.STATE_INACTIVE
                updateTile()
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
                updateTile()
            }
        }
    }
}