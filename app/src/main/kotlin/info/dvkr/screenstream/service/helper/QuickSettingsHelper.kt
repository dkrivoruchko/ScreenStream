package info.dvkr.screenstream.service.helper

import android.annotation.TargetApi
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

@TargetApi(Build.VERSION_CODES.N)
class QuickSettingsHelper : TileService() {
    private lateinit var mTile: Tile
    val STATE_INACTIVE = 1
    val STATE_ACTIVE = 2

    companion object {
        private var qsState = Tile.STATE_ACTIVE
    }

    fun QuickSettingsHelper() {
        mTile = qsTile
    }

    override fun onTileAdded() {
        super.onTileAdded()
        UpdateState()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
    }

    override fun onStartListening() {
        super.onStartListening()
        UpdateState()
    }

    override fun onStopListening() {
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (qsState == STATE_ACTIVE) IntentAction.StopStream.sendToAppService(
            this
        ) else this.startActivity(IntentAction.StartStream.toAppActivityIntent(this))
        // switch the state programatically
        mTile.state = if (qsState == STATE_ACTIVE) STATE_INACTIVE else STATE_ACTIVE
        // update the tile
        mTile.updateTile()
    }

    /*
     * set the state of the tile
     */
    fun setState(state: Int) {
        qsState = state
    }

    /**
     * Update the Tile State
     */
    private fun UpdateState() {
        mTile = qsTile
        mTile.state = qsState
        mTile.updateTile()
    }
}