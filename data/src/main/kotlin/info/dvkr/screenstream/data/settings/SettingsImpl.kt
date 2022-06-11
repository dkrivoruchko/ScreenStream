package info.dvkr.screenstream.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.other.getLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

class SettingsImpl(private val dataStore: DataStore<Preferences>): Settings {

    override val nightModeFlow: Flow<Int> = getCatching(Settings.Key.NIGHT_MODE, Settings.Default.NIGHT_MODE)
    override suspend fun setNightMode(value: Int) = setValue(Settings.Key.NIGHT_MODE, value)

    override val keepAwakeFlow: Flow<Boolean> = getCatching(Settings.Key.KEEP_AWAKE, Settings.Default.KEEP_AWAKE)
    override suspend fun setKeepAwake(value: Boolean) = setValue(Settings.Key.KEEP_AWAKE, value)

    override val stopOnSleepFlow: Flow<Boolean> = getCatching(Settings.Key.STOP_ON_SLEEP, Settings.Default.STOP_ON_SLEEP)
    override suspend fun setStopOnSleep(value: Boolean) = setValue(Settings.Key.STOP_ON_SLEEP, value)

    override val startOnBootFlow: Flow<Boolean> = getCatching(Settings.Key.START_ON_BOOT, Settings.Default.START_ON_BOOT)
    override suspend fun setStartOnBoot(value: Boolean) = setValue(Settings.Key.START_ON_BOOT, value)

    override val autoStartStopFlow: Flow<Boolean> = getCatching(Settings.Key.AUTO_START_STOP, Settings.Default.AUTO_START_STOP)
    override suspend fun setAutoStartStop(value: Boolean) = setValue(Settings.Key.AUTO_START_STOP, value)

    override val notifySlowConnectionsFlow: Flow<Boolean> = getCatching(Settings.Key.NOTIFY_SLOW_CONNECTIONS, Settings.Default.NOTIFY_SLOW_CONNECTIONS)
    override suspend fun setNotifySlowConnections(value: Boolean) = setValue(Settings.Key.NOTIFY_SLOW_CONNECTIONS, value)

    override val htmlEnableButtonsFlow: Flow<Boolean> = getCatching(Settings.Key.HTML_ENABLE_BUTTONS, Settings.Default.HTML_ENABLE_BUTTONS)
    override suspend fun setHtmlEnableButtons(value: Boolean) = setValue(Settings.Key.HTML_ENABLE_BUTTONS, value)

    override val htmlShowPressStartFlow: Flow<Boolean> = getCatching(Settings.Key.HTML_SHOW_PRESS_START, Settings.Default.HTML_SHOW_PRESS_START)
    override suspend fun setHtmlShowPressStart(value: Boolean) = setValue(Settings.Key.HTML_SHOW_PRESS_START, value)

    override val htmlBackColorFlow: Flow<Int> = getCatching(Settings.Key.HTML_BACK_COLOR, Settings.Default.HTML_BACK_COLOR)
    override suspend fun setHtmlBackColor(value: Int) = setValue(Settings.Key.HTML_BACK_COLOR, value)


    override val vrModeFlow: Flow<Int> = getCatching(Settings.Key.VR_MODE, Settings.Default.VR_MODE_DISABLE)
    override suspend fun setVrMode(value: Int) = setValue(Settings.Key.VR_MODE, value)

    override val imageCropFlow: Flow<Boolean> = getCatching(Settings.Key.IMAGE_CROP, Settings.Default.IMAGE_CROP)
    override suspend fun setImageCrop(value: Boolean) = setValue(Settings.Key.IMAGE_CROP, value)

    override val imageCropTopFlow: Flow<Int> = getCatching(Settings.Key.IMAGE_CROP_TOP, Settings.Default.IMAGE_CROP_TOP)
    override suspend fun setImageCropTop(value: Int) = setValue(Settings.Key.IMAGE_CROP_TOP, value)

    override val imageCropBottomFlow: Flow<Int> = getCatching(Settings.Key.IMAGE_CROP_BOTTOM, Settings.Default.IMAGE_CROP_BOTTOM)
    override suspend fun setImageCropBottom(value: Int) = setValue(Settings.Key.IMAGE_CROP_BOTTOM, value)

    override val imageCropLeftFlow: Flow<Int> = getCatching(Settings.Key.IMAGE_CROP_LEFT, Settings.Default.IMAGE_CROP_LEFT)
    override suspend fun setImageCropLeft(value: Int) = setValue(Settings.Key.IMAGE_CROP_LEFT, value)

    override val imageCropRightFlow: Flow<Int> = getCatching(Settings.Key.IMAGE_CROP_RIGHT, Settings.Default.IMAGE_CROP_RIGHT)
    override suspend fun setImageCropRight(value: Int) = setValue(Settings.Key.IMAGE_CROP_RIGHT, value)

    override val imageGrayscaleFlow: Flow<Boolean> = getCatching(Settings.Key.IMAGE_GRAYSCALE, Settings.Default.IMAGE_GRAYSCALE)
    override suspend fun setImageGrayscale(value: Boolean) = setValue(Settings.Key.IMAGE_GRAYSCALE, value)

    override val jpegQualityFlow: Flow<Int> = getCatching(Settings.Key.JPEG_QUALITY, Settings.Default.JPEG_QUALITY)
    override suspend fun setJpegQuality(value: Int) = setValue(Settings.Key.JPEG_QUALITY, value)

    override val resizeFactorFlow: Flow<Int> = getCatching(Settings.Key.RESIZE_FACTOR, Settings.Default.RESIZE_FACTOR)
    override suspend fun setResizeFactor(value: Int) = setValue(Settings.Key.RESIZE_FACTOR, value)

    override val rotationFlow: Flow<Int> = getCatching(Settings.Key.ROTATION, Settings.Default.ROTATION)
    override suspend fun setRotation(value: Int) = setValue(Settings.Key.ROTATION, value)

    override val maxFPSFlow: Flow<Int> = getCatching(Settings.Key.MAX_FPS, Settings.Default.MAX_FPS)
    override suspend fun setMaxFPS(value: Int) = setValue(Settings.Key.MAX_FPS, value)


    override val enablePinFlow: Flow<Boolean> = getCatching(Settings.Key.ENABLE_PIN, Settings.Default.ENABLE_PIN)
    override suspend fun setEnablePin(value: Boolean) = setValue(Settings.Key.ENABLE_PIN, value)

    override val hidePinOnStartFlow: Flow<Boolean> = getCatching(Settings.Key.HIDE_PIN_ON_START, Settings.Default.HIDE_PIN_ON_START)
    override suspend fun setHidePinOnStart(value: Boolean) = setValue(Settings.Key.HIDE_PIN_ON_START, value)

    override val newPinOnAppStartFlow: Flow<Boolean> = getCatching(Settings.Key.NEW_PIN_ON_APP_START, Settings.Default.NEW_PIN_ON_APP_START)
    override suspend fun setNewPinOnAppStart(value: Boolean) = setValue(Settings.Key.NEW_PIN_ON_APP_START, value)

    override val autoChangePinFlow: Flow<Boolean> = getCatching(Settings.Key.AUTO_CHANGE_PIN, Settings.Default.AUTO_CHANGE_PIN)
    override suspend fun setAutoChangePin(value: Boolean) = setValue(Settings.Key.AUTO_CHANGE_PIN, value)

    override val pinFlow: Flow<String> = getCatching(Settings.Key.PIN, Settings.Default.PIN)
    override suspend fun setPin(value: String) = setValue(Settings.Key.PIN, value)

    override val blockAddressFlow: Flow<Boolean> = getCatching(Settings.Key.BLOCK_ADDRESS, Settings.Default.BLOCK_ADDRESS)
    override suspend fun setBlockAddress(value: Boolean) = setValue(Settings.Key.BLOCK_ADDRESS, value)


    override val useWiFiOnlyFlow: Flow<Boolean> = getCatching(Settings.Key.USE_WIFI_ONLY, Settings.Default.USE_WIFI_ONLY)
    override suspend fun setUseWiFiOnly(value: Boolean) = setValue(Settings.Key.USE_WIFI_ONLY, value)

    override val enableIPv6Flow: Flow<Boolean> = getCatching(Settings.Key.ENABLE_IPV6, Settings.Default.ENABLE_IPV6)
    override suspend fun setEnableIPv6(value: Boolean) = setValue(Settings.Key.ENABLE_IPV6, value)

    override val enableLocalHostFlow: Flow<Boolean> = getCatching(Settings.Key.ENABLE_LOCAL_HOST, Settings.Default.ENABLE_LOCAL_HOST)
    override suspend fun setEnableLocalHost(value: Boolean) = setValue(Settings.Key.ENABLE_LOCAL_HOST, value)

    override val localHostOnlyFlow: Flow<Boolean> = getCatching(Settings.Key.LOCAL_HOST_ONLY, Settings.Default.LOCAL_HOST_ONLY)
    override suspend fun setLocalHostOnly(value: Boolean) = setValue(Settings.Key.LOCAL_HOST_ONLY, value)

    override val serverPortFlow: Flow<Int> = getCatching(Settings.Key.SERVER_PORT, Settings.Default.SERVER_PORT)
    override suspend fun setServerPort(value: Int) = setValue(Settings.Key.SERVER_PORT, value)


    override val loggingVisibleFlow: Flow<Boolean> = getCatching(Settings.Key.LOGGING_VISIBLE, Settings.Default.LOGGING_VISIBLE)
    override suspend fun setLoggingVisible(value: Boolean) = setValue(Settings.Key.LOGGING_VISIBLE, value)

    override val lastUpdateRequestMillisFlow: Flow<Long> = getCatching(Settings.Key.LAST_UPDATE_REQUEST_MILLIS, Settings.Default.LAST_IAU_REQUEST_TIMESTAMP)
    override suspend fun setLastUpdateRequestMillis(value: Long) = setValue(Settings.Key.LAST_UPDATE_REQUEST_MILLIS, value)

    private fun <T> getCatching(key: Preferences.Key<T>, default: T): Flow<T> = dataStore.data.catch { cause ->
        if (cause is IOException) {
            XLog.e(this@SettingsImpl.getLog("getCatching [${key.name}]"), cause)
            emit(emptyPreferences())
        } else {
            XLog.e(this@SettingsImpl.getLog("getCatching [${key.name}]"), cause)
            throw cause
        }
    }.map { it[key] ?: default }

    private suspend fun <T> setValue(key: Preferences.Key<T>, value: T) {
        try {
            dataStore.edit { it[key] = value }
        } catch (cause: IOException) {
            XLog.e(this@SettingsImpl.getLog("setValue [${key.name}]"), cause)
        }
    }
}