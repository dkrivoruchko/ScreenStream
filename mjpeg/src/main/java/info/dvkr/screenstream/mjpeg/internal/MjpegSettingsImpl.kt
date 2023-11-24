package info.dvkr.screenstream.mjpeg.internal

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getCatching
import info.dvkr.screenstream.common.setValue
import info.dvkr.screenstream.mjpeg.MjpegKoinScope
import info.dvkr.screenstream.mjpeg.MjpegSettings
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Scope

@Scope(MjpegKoinScope::class)
internal class PreferenceDataStoreProvider(context: Context) {

    private companion object {
        @Volatile
        private var instance: DataStore<Preferences>? = null

        private fun getInstance(context: Context): DataStore<Preferences> = instance ?: synchronized(this) {
            instance ?: PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { ex -> XLog.e(ex); emptyPreferences() },
                produceFile = { context.preferencesDataStoreFile("MJPEG_settings") }
            ).also { instance = it }
        }
    }

    internal val dataStore: DataStore<Preferences> = getInstance(context)
}

@Scope(MjpegKoinScope::class)
internal class MjpegSettingsImpl(preferenceDataStoreProvider: PreferenceDataStoreProvider) : MjpegSettings {

    private val dataStore: DataStore<Preferences> = preferenceDataStoreProvider.dataStore

    override val keepAwakeFlow: Flow<Boolean> = dataStore.getCatching(MjpegSettings.Key.KEEP_AWAKE, MjpegSettings.Default.KEEP_AWAKE)
    override suspend fun setKeepAwake(value: Boolean): Unit = dataStore.setValue(MjpegSettings.Key.KEEP_AWAKE, value)

    override val stopOnSleepFlow: Flow<Boolean> = dataStore.getCatching(MjpegSettings.Key.STOP_ON_SLEEP, MjpegSettings.Default.STOP_ON_SLEEP)
    override suspend fun setStopOnSleep(value: Boolean): Unit = dataStore.setValue(MjpegSettings.Key.STOP_ON_SLEEP, value)

    override val notifySlowConnectionsFlow: Flow<Boolean> = dataStore.getCatching(MjpegSettings.Key.NOTIFY_SLOW_CONNECTIONS, MjpegSettings.Default.NOTIFY_SLOW_CONNECTIONS)
    override suspend fun setNotifySlowConnections(value: Boolean) = dataStore.setValue(MjpegSettings.Key.NOTIFY_SLOW_CONNECTIONS, value)

    override val htmlEnableButtonsFlow: Flow<Boolean> = dataStore.getCatching(MjpegSettings.Key.HTML_ENABLE_BUTTONS, MjpegSettings.Default.HTML_ENABLE_BUTTONS)
    override suspend fun setHtmlEnableButtons(value: Boolean) = dataStore.setValue(MjpegSettings.Key.HTML_ENABLE_BUTTONS, value)

    override val htmlShowPressStartFlow: Flow<Boolean> = dataStore.getCatching(MjpegSettings.Key.HTML_SHOW_PRESS_START, MjpegSettings.Default.HTML_SHOW_PRESS_START)
    override suspend fun setHtmlShowPressStart(value: Boolean) = dataStore.setValue(MjpegSettings.Key.HTML_SHOW_PRESS_START, value)

    override val htmlBackColorFlow: Flow<Int> = dataStore.getCatching(MjpegSettings.Key.HTML_BACK_COLOR, MjpegSettings.Default.HTML_BACK_COLOR)
    override suspend fun setHtmlBackColor(value: Int) = dataStore.setValue(MjpegSettings.Key.HTML_BACK_COLOR, value)


    override val vrModeFlow: Flow<Int> = dataStore.getCatching(MjpegSettings.Key.VR_MODE, MjpegSettings.Default.VR_MODE_DISABLE)
    override suspend fun setVrMode(value: Int) = dataStore.setValue(MjpegSettings.Key.VR_MODE, value)

    override val imageCropFlow: Flow<Boolean> = dataStore.getCatching(MjpegSettings.Key.IMAGE_CROP, MjpegSettings.Default.IMAGE_CROP)
    override suspend fun setImageCrop(value: Boolean) = dataStore.setValue(MjpegSettings.Key.IMAGE_CROP, value)

    override val imageCropTopFlow: Flow<Int> = dataStore.getCatching(MjpegSettings.Key.IMAGE_CROP_TOP, MjpegSettings.Default.IMAGE_CROP_TOP)
    override suspend fun setImageCropTop(value: Int) = dataStore.setValue(MjpegSettings.Key.IMAGE_CROP_TOP, value)

    override val imageCropBottomFlow: Flow<Int> = dataStore.getCatching(MjpegSettings.Key.IMAGE_CROP_BOTTOM, MjpegSettings.Default.IMAGE_CROP_BOTTOM)
    override suspend fun setImageCropBottom(value: Int) = dataStore.setValue(MjpegSettings.Key.IMAGE_CROP_BOTTOM, value)

    override val imageCropLeftFlow: Flow<Int> = dataStore.getCatching(MjpegSettings.Key.IMAGE_CROP_LEFT, MjpegSettings.Default.IMAGE_CROP_LEFT)
    override suspend fun setImageCropLeft(value: Int) = dataStore.setValue(MjpegSettings.Key.IMAGE_CROP_LEFT, value)

    override val imageCropRightFlow: Flow<Int> = dataStore.getCatching(MjpegSettings.Key.IMAGE_CROP_RIGHT, MjpegSettings.Default.IMAGE_CROP_RIGHT)
    override suspend fun setImageCropRight(value: Int) = dataStore.setValue(MjpegSettings.Key.IMAGE_CROP_RIGHT, value)

    override val imageGrayscaleFlow: Flow<Boolean> = dataStore.getCatching(MjpegSettings.Key.IMAGE_GRAYSCALE, MjpegSettings.Default.IMAGE_GRAYSCALE)
    override suspend fun setImageGrayscale(value: Boolean) = dataStore.setValue(MjpegSettings.Key.IMAGE_GRAYSCALE, value)

    override val jpegQualityFlow: Flow<Int> = dataStore.getCatching(MjpegSettings.Key.JPEG_QUALITY, MjpegSettings.Default.JPEG_QUALITY)
    override suspend fun setJpegQuality(value: Int) = dataStore.setValue(MjpegSettings.Key.JPEG_QUALITY, value)

    override val resizeFactorFlow: Flow<Int> = dataStore.getCatching(MjpegSettings.Key.RESIZE_FACTOR, MjpegSettings.Default.RESIZE_FACTOR)
    override suspend fun setResizeFactor(value: Int) = dataStore.setValue(MjpegSettings.Key.RESIZE_FACTOR, value)

    override val rotationFlow: Flow<Int> = dataStore.getCatching(MjpegSettings.Key.ROTATION, MjpegSettings.Default.ROTATION)
    override suspend fun setRotation(value: Int) = dataStore.setValue(MjpegSettings.Key.ROTATION, value)

    override val maxFPSFlow: Flow<Int> = dataStore.getCatching(MjpegSettings.Key.MAX_FPS, MjpegSettings.Default.MAX_FPS)
    override suspend fun setMaxFPS(value: Int) = dataStore.setValue(MjpegSettings.Key.MAX_FPS, value)


    override val enablePinFlow: Flow<Boolean> = dataStore.getCatching(MjpegSettings.Key.ENABLE_PIN, MjpegSettings.Default.ENABLE_PIN)
    override suspend fun setEnablePin(value: Boolean) = dataStore.setValue(MjpegSettings.Key.ENABLE_PIN, value)

    override val hidePinOnStartFlow: Flow<Boolean> = dataStore.getCatching(MjpegSettings.Key.HIDE_PIN_ON_START, MjpegSettings.Default.HIDE_PIN_ON_START)
    override suspend fun setHidePinOnStart(value: Boolean) = dataStore.setValue(MjpegSettings.Key.HIDE_PIN_ON_START, value)

    override val newPinOnAppStartFlow: Flow<Boolean> = dataStore.getCatching(MjpegSettings.Key.NEW_PIN_ON_APP_START, MjpegSettings.Default.NEW_PIN_ON_APP_START)
    override suspend fun setNewPinOnAppStart(value: Boolean) = dataStore.setValue(MjpegSettings.Key.NEW_PIN_ON_APP_START, value)

    override val autoChangePinFlow: Flow<Boolean> = dataStore.getCatching(MjpegSettings.Key.AUTO_CHANGE_PIN, MjpegSettings.Default.AUTO_CHANGE_PIN)
    override suspend fun setAutoChangePin(value: Boolean) = dataStore.setValue(MjpegSettings.Key.AUTO_CHANGE_PIN, value)

    override val pinFlow: Flow<String> = dataStore.getCatching(MjpegSettings.Key.PIN, MjpegSettings.Default.PIN)
    override suspend fun setPin(value: String) = dataStore.setValue(MjpegSettings.Key.PIN, value)

    override val blockAddressFlow: Flow<Boolean> = dataStore.getCatching(MjpegSettings.Key.BLOCK_ADDRESS, MjpegSettings.Default.BLOCK_ADDRESS)
    override suspend fun setBlockAddress(value: Boolean) = dataStore.setValue(MjpegSettings.Key.BLOCK_ADDRESS, value)


    override val useWiFiOnlyFlow: Flow<Boolean> = dataStore.getCatching(MjpegSettings.Key.USE_WIFI_ONLY, MjpegSettings.Default.USE_WIFI_ONLY)
    override suspend fun setUseWiFiOnly(value: Boolean) = dataStore.setValue(MjpegSettings.Key.USE_WIFI_ONLY, value)

    override val enableIPv6Flow: Flow<Boolean> = dataStore.getCatching(MjpegSettings.Key.ENABLE_IPV6, MjpegSettings.Default.ENABLE_IPV6)
    override suspend fun setEnableIPv6(value: Boolean) = dataStore.setValue(MjpegSettings.Key.ENABLE_IPV6, value)

    override val enableLocalHostFlow: Flow<Boolean> = dataStore.getCatching(MjpegSettings.Key.ENABLE_LOCAL_HOST, MjpegSettings.Default.ENABLE_LOCAL_HOST)
    override suspend fun setEnableLocalHost(value: Boolean) = dataStore.setValue(MjpegSettings.Key.ENABLE_LOCAL_HOST, value)

    override val localHostOnlyFlow: Flow<Boolean> = dataStore.getCatching(MjpegSettings.Key.LOCAL_HOST_ONLY, MjpegSettings.Default.LOCAL_HOST_ONLY)
    override suspend fun setLocalHostOnly(value: Boolean) = dataStore.setValue(MjpegSettings.Key.LOCAL_HOST_ONLY, value)

    override val serverPortFlow: Flow<Int> = dataStore.getCatching(MjpegSettings.Key.SERVER_PORT, MjpegSettings.Default.SERVER_PORT)
    override suspend fun setServerPort(value: Int) = dataStore.setValue(MjpegSettings.Key.SERVER_PORT, value)
}