package info.dvkr.screenstream.service

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.service.helper.IntentAction
import kotlinx.coroutines.*

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
    private var isStreaming: Boolean = false

    override fun onBind(intent: Intent?): IBinder? {
        return runCatching { super.onBind(intent) }.getOrNull()
    }

    override fun onStartListening() {
        super.onStartListening()
        XLog.d(getLog("onStartListening", "isRunning:${ForegroundService.isRunning}"))

        if (ForegroundService.isRunning) {
            coroutineScope?.cancel()

            coroutineScope = CoroutineScope(Job() + Dispatchers.Main.immediate).apply {
                launch {
                    ForegroundService.serviceMessageFlow.collect { serviceMessage ->
                        XLog.d(this@TileActionService.getLog("onServiceMessage", "$serviceMessage"))
                        when (serviceMessage) {
                            is ServiceMessage.ServiceState -> {
                                isStreaming = serviceMessage.isStreaming
                                updateTile()
                            }
                            is ServiceMessage.FinishActivity -> {
                                isStreaming = false
                                updateTile()
                            }
                            else -> Unit
                        }
                    }
                }
            }

            IntentAction.GetServiceState.sendToAppService(this@TileActionService)
        } else {
            isStreaming = false
            updateTile()
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        XLog.d(getLog("onStopListening"))
        coroutineScope?.cancel()
        coroutineScope = null
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