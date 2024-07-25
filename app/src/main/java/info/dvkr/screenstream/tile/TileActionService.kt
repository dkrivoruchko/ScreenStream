package info.dvkr.screenstream.tile

import android.app.PendingIntent
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
import info.dvkr.screenstream.SingleActivity
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.module.StreamingModuleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject

@RequiresApi(Build.VERSION_CODES.N)
public class TileActionService : TileService() {

    internal companion object {
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        internal fun showAddTileRequest(context: Context) {
            val statusBarManager = context.getSystemService(StatusBarManager::class.java)
            statusBarManager.requestAddTileService(
                ComponentName(context, TileActionService::class.java),
                context.getString(R.string.app_name),
                Icon.createWithResource(context, R.drawable.ic_tile_24dp),
                { it?.run() }
            ) {
                XLog.d(getLog("TileActionService", "showAddTileRequest: $it"))
            }
        }
    }

    private val streamingModulesManager: StreamingModuleManager by inject()
    private var coroutineScope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = runCatching { super.onBind(intent) }.getOrNull()

    override fun onTileAdded() {
        super.onTileAdded()
        XLog.d(getLog("TileActionService", "onTileAdded"))
    }

    override fun onStartListening() {
        super.onStartListening()
        XLog.d(getLog("TileActionService", "onStartListening"))

        @OptIn(ExperimentalCoroutinesApi::class)
        streamingModulesManager.activeModuleStateFlow
            .flatMapConcat { activeModule -> activeModule?.isStreaming?.map<Boolean, Boolean?> { it } ?: flow { emit(null) } }
            .distinctUntilChanged()
            .map { isStreaming ->
                XLog.e(getLog("TileActionService", "onStartListening.isStreaming: $isStreaming"))
                qsTile?.icon = Icon.createWithResource(this, R.drawable.ic_tile_24dp)
                qsTile?.state = if (isStreaming == null) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
                when {
                    isStreaming == null -> {
                        qsTile?.label = getString(R.string.app_name)
                        qsTile?.contentDescription = getString(R.string.app_name)
                    }

                    isStreaming -> {
                        qsTile?.label = getString(R.string.app_tile_stop)
                        qsTile?.contentDescription = getString(R.string.app_tile_stop)
                    }

                    else -> {
                        qsTile?.label = getString(R.string.app_tile_start)
                        qsTile?.contentDescription = getString(R.string.app_tile_start)
                    }
                }
                qsTile?.updateTile()
            }
            .catch { XLog.i(getLog("TileActionService", "onStartListening: ${it.message}"), it) }
            .launchIn(CoroutineScope(Job() + Dispatchers.Main.immediate).also { coroutineScope = it })
    }

    override fun onStopListening() {
        super.onStopListening()
        XLog.d(getLog("TileActionService", "onStopListening"))
        coroutineScope?.cancel()
        coroutineScope = null

        qsTile?.apply {
            icon = Icon.createWithResource(this@TileActionService, R.drawable.ic_tile_24dp)
            state = Tile.STATE_INACTIVE
            label = getString(R.string.app_name)
            contentDescription = getString(R.string.app_name)
            runCatching { updateTile() }
        }
    }

    override fun onClick() {
        super.onClick()
        XLog.d(getLog("TileActionService", "onClick"))

        val activeModule = runBlocking { streamingModulesManager.activeModuleStateFlow.first() }
        if (activeModule == null) {
            startSingleActivity()
            return
        }

        val isStreaming = runBlocking { activeModule.isStreaming.first() }
        if (isStreaming) {
            activeModule.stopStream("TileActionService.onClick")
        } else {
            startSingleActivity()
        }
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        XLog.d(getLog("TileActionService", "onTileRemoved"))
    }

    @Suppress("DEPRECATION")
    private fun startSingleActivity() {
        val intent = SingleActivity.getIntent(applicationContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}