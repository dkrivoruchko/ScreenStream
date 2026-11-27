package info.dvkr.screenstream.tile

import android.annotation.SuppressLint
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
import androidx.core.content.ContextCompat
import info.dvkr.screenstream.R
import info.dvkr.screenstream.SingleActivity
import io.screenstream.core.StreamingModuleManager
import io.screenstream.foundation.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.core.parameter.parametersOf

public class TileActionService : TileService() {

    internal companion object {
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        internal fun requestAddTile(context: Context, log: Logger, onResult: (Int) -> Unit) {
            val statusBarManager = context.getSystemService(StatusBarManager::class.java)
            statusBarManager.requestAddTileService(
                ComponentName(context, TileActionService::class.java),
                context.getString(R.string.app_name),
                Icon.createWithResource(context, R.drawable.ic_tile_24dp),
                context.mainExecutor,
            ) {
                log.d("TileActionService", "showAddTileRequest: $it")
                onResult(it)
            }
        }
    }

    private val log: Logger by inject { parametersOf(this) }
    private var streamingModuleManager: StreamingModuleManager? = null
    private var coroutineScope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = runCatching { super.onBind(intent) }.getOrNull()

    override fun onStartListening() {
        super.onStartListening()

        val manager = runCatching { get<StreamingModuleManager>() }.getOrNull() ?: return
        streamingModuleManager = manager
        manager.state
            .map { state ->
                when (state) {
                    is StreamingModuleManager.State.Running -> state.status.isStreaming
                    is StreamingModuleManager.State.Unresponsive -> state.lastStatus.isStreaming
                    else -> null
                }
            }
            .distinctUntilChanged()
            .onEach { isStreaming ->
                qsTile?.apply {
                    val tileLabel = if (isStreaming == null) {
                        getString(R.string.app_name)
                    } else {
                        val labelRes = if (isStreaming) R.string.app_tile_stop else R.string.app_tile_open
                        ContextCompat.getString(this@TileActionService, labelRes)
                    }
                    icon = Icon.createWithResource(this@TileActionService, R.drawable.ic_tile_24dp)
                    state = if (isStreaming == null) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
                    label = tileLabel
                    contentDescription = tileLabel
                    updateTile()
                }
            }
            .catch { log.i("TileActionService", "onStartListening: ${it.message}", it) }
            .launchIn(CoroutineScope(Job() + Dispatchers.Main.immediate).also { coroutineScope = it })
    }

    override fun onStopListening() {
        super.onStopListening()
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
        val status = when (val state = streamingModuleManager?.state?.value) {
            is StreamingModuleManager.State.Running -> state.status
            is StreamingModuleManager.State.Unresponsive -> state.lastStatus
            else -> null
        }
        val attempt = status?.captureAttempt
        if (status?.isStreaming == true && attempt != null) streamingModuleManager?.requestStreamStop(attempt)
        else startSingleActivity()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startSingleActivity() {
        val intent = SingleActivity.createLaunchIntent(applicationContext).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
