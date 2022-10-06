package info.dvkr.screenstream.mjpeg.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

class MjpegSettingsImpl(private val dataStore: DataStore<Preferences>): MjpegSettings {

    override val notifySlowConnectionsFlow: Flow<Boolean> = getCatching(MjpegSettings.Key.NOTIFY_SLOW_CONNECTIONS, MjpegSettings.Default.NOTIFY_SLOW_CONNECTIONS)
    override suspend fun setNotifySlowConnections(value: Boolean) = setValue(MjpegSettings.Key.NOTIFY_SLOW_CONNECTIONS, value)

    override val htmlEnableButtonsFlow: Flow<Boolean> = getCatching(MjpegSettings.Key.HTML_ENABLE_BUTTONS, MjpegSettings.Default.HTML_ENABLE_BUTTONS)
    override suspend fun setHtmlEnableButtons(value: Boolean) = setValue(MjpegSettings.Key.HTML_ENABLE_BUTTONS, value)

    override val htmlShowPressStartFlow: Flow<Boolean> = getCatching(MjpegSettings.Key.HTML_SHOW_PRESS_START, MjpegSettings.Default.HTML_SHOW_PRESS_START)
    override suspend fun setHtmlShowPressStart(value: Boolean) = setValue(MjpegSettings.Key.HTML_SHOW_PRESS_START, value)

    override val htmlBackColorFlow: Flow<Int> = getCatching(MjpegSettings.Key.HTML_BACK_COLOR, MjpegSettings.Default.HTML_BACK_COLOR)
    override suspend fun setHtmlBackColor(value: Int) = setValue(MjpegSettings.Key.HTML_BACK_COLOR, value)


    override val vrModeFlow: Flow<Int> = getCatching(MjpegSettings.Key.VR_MODE, MjpegSettings.Default.VR_MODE_DISABLE)
    override suspend fun setVrMode(value: Int) = setValue(MjpegSettings.Key.VR_MODE, value)

    override val imageCropFlow: Flow<Boolean> = getCatching(MjpegSettings.Key.IMAGE_CROP, MjpegSettings.Default.IMAGE_CROP)
    override suspend fun setImageCrop(value: Boolean) = setValue(MjpegSettings.Key.IMAGE_CROP, value)

    override val imageCropTopFlow: Flow<Int> = getCatching(MjpegSettings.Key.IMAGE_CROP_TOP, MjpegSettings.Default.IMAGE_CROP_TOP)
    override suspend fun setImageCropTop(value: Int) = setValue(MjpegSettings.Key.IMAGE_CROP_TOP, value)

    override val imageCropBottomFlow: Flow<Int> = getCatching(MjpegSettings.Key.IMAGE_CROP_BOTTOM, MjpegSettings.Default.IMAGE_CROP_BOTTOM)
    override suspend fun setImageCropBottom(value: Int) = setValue(MjpegSettings.Key.IMAGE_CROP_BOTTOM, value)

    override val imageCropLeftFlow: Flow<Int> = getCatching(MjpegSettings.Key.IMAGE_CROP_LEFT, MjpegSettings.Default.IMAGE_CROP_LEFT)
    override suspend fun setImageCropLeft(value: Int) = setValue(MjpegSettings.Key.IMAGE_CROP_LEFT, value)

    override val imageCropRightFlow: Flow<Int> = getCatching(MjpegSettings.Key.IMAGE_CROP_RIGHT, MjpegSettings.Default.IMAGE_CROP_RIGHT)
    override suspend fun setImageCropRight(value: Int) = setValue(MjpegSettings.Key.IMAGE_CROP_RIGHT, value)

    override val imageGrayscaleFlow: Flow<Boolean> = getCatching(MjpegSettings.Key.IMAGE_GRAYSCALE, MjpegSettings.Default.IMAGE_GRAYSCALE)
    override suspend fun setImageGrayscale(value: Boolean) = setValue(MjpegSettings.Key.IMAGE_GRAYSCALE, value)

    override val jpegQualityFlow: Flow<Int> = getCatching(MjpegSettings.Key.JPEG_QUALITY, MjpegSettings.Default.JPEG_QUALITY)
    override suspend fun setJpegQuality(value: Int) = setValue(MjpegSettings.Key.JPEG_QUALITY, value)

    override val resizeFactorFlow: Flow<Int> = getCatching(MjpegSettings.Key.RESIZE_FACTOR, MjpegSettings.Default.RESIZE_FACTOR)
    override suspend fun setResizeFactor(value: Int) = setValue(MjpegSettings.Key.RESIZE_FACTOR, value)

    override val rotationFlow: Flow<Int> = getCatching(MjpegSettings.Key.ROTATION, MjpegSettings.Default.ROTATION)
    override suspend fun setRotation(value: Int) = setValue(MjpegSettings.Key.ROTATION, value)

    override val maxFPSFlow: Flow<Int> = getCatching(MjpegSettings.Key.MAX_FPS, MjpegSettings.Default.MAX_FPS)
    override suspend fun setMaxFPS(value: Int) = setValue(MjpegSettings.Key.MAX_FPS, value)


    override val enablePinFlow: Flow<Boolean> = getCatching(MjpegSettings.Key.ENABLE_PIN, MjpegSettings.Default.ENABLE_PIN)
    override suspend fun setEnablePin(value: Boolean) = setValue(MjpegSettings.Key.ENABLE_PIN, value)

    override val hidePinOnStartFlow: Flow<Boolean> = getCatching(MjpegSettings.Key.HIDE_PIN_ON_START, MjpegSettings.Default.HIDE_PIN_ON_START)
    override suspend fun setHidePinOnStart(value: Boolean) = setValue(MjpegSettings.Key.HIDE_PIN_ON_START, value)

    override val newPinOnAppStartFlow: Flow<Boolean> = getCatching(MjpegSettings.Key.NEW_PIN_ON_APP_START, MjpegSettings.Default.NEW_PIN_ON_APP_START)
    override suspend fun setNewPinOnAppStart(value: Boolean) = setValue(MjpegSettings.Key.NEW_PIN_ON_APP_START, value)

    override val autoChangePinFlow: Flow<Boolean> = getCatching(MjpegSettings.Key.AUTO_CHANGE_PIN, MjpegSettings.Default.AUTO_CHANGE_PIN)
    override suspend fun setAutoChangePin(value: Boolean) = setValue(MjpegSettings.Key.AUTO_CHANGE_PIN, value)

    override val pinFlow: Flow<String> = getCatching(MjpegSettings.Key.PIN, MjpegSettings.Default.PIN)
    override suspend fun setPin(value: String) = setValue(MjpegSettings.Key.PIN, value)

    override val blockAddressFlow: Flow<Boolean> = getCatching(MjpegSettings.Key.BLOCK_ADDRESS, MjpegSettings.Default.BLOCK_ADDRESS)
    override suspend fun setBlockAddress(value: Boolean) = setValue(MjpegSettings.Key.BLOCK_ADDRESS, value)


    override val useWiFiOnlyFlow: Flow<Boolean> = getCatching(MjpegSettings.Key.USE_WIFI_ONLY, MjpegSettings.Default.USE_WIFI_ONLY)
    override suspend fun setUseWiFiOnly(value: Boolean) = setValue(MjpegSettings.Key.USE_WIFI_ONLY, value)

    override val enableIPv6Flow: Flow<Boolean> = getCatching(MjpegSettings.Key.ENABLE_IPV6, MjpegSettings.Default.ENABLE_IPV6)
    override suspend fun setEnableIPv6(value: Boolean) = setValue(MjpegSettings.Key.ENABLE_IPV6, value)

    override val enableLocalHostFlow: Flow<Boolean> = getCatching(MjpegSettings.Key.ENABLE_LOCAL_HOST, MjpegSettings.Default.ENABLE_LOCAL_HOST)
    override suspend fun setEnableLocalHost(value: Boolean) = setValue(MjpegSettings.Key.ENABLE_LOCAL_HOST, value)

    override val localHostOnlyFlow: Flow<Boolean> = getCatching(MjpegSettings.Key.LOCAL_HOST_ONLY, MjpegSettings.Default.LOCAL_HOST_ONLY)
    override suspend fun setLocalHostOnly(value: Boolean) = setValue(MjpegSettings.Key.LOCAL_HOST_ONLY, value)

    override val serverPortFlow: Flow<Int> = getCatching(MjpegSettings.Key.SERVER_PORT, MjpegSettings.Default.SERVER_PORT)
    override suspend fun setServerPort(value: Int) = setValue(MjpegSettings.Key.SERVER_PORT, value)

    private fun <T> getCatching(key: Preferences.Key<T>, default: T): Flow<T> = dataStore.data.catch { cause ->
        if (cause is IOException) {
            XLog.e(this@MjpegSettingsImpl.getLog("getCatching [${key.name}]"), cause)
            emit(emptyPreferences())
        } else {
            XLog.e(this@MjpegSettingsImpl.getLog("getCatching [${key.name}]"), cause)
            throw cause
        }
    }.map { it[key] ?: default }

    private suspend fun <T> setValue(key: Preferences.Key<T>, value: T) {
        try {
            dataStore.edit { it[key] = value }
        } catch (cause: IOException) {
            XLog.e(this@MjpegSettingsImpl.getLog("setValue [${key.name}]"), cause)
        }
    }
}