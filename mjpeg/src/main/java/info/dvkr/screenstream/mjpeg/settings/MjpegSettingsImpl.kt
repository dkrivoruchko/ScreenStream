package info.dvkr.screenstream.mjpeg.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import java.io.IOException

@Single
internal class MjpegSettingsImpl(
    context: Context
) : MjpegSettings {

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { ex -> XLog.e(ex); emptyPreferences() },
        produceFile = { context.preferencesDataStoreFile("MJPEG_settings") } // Sync name with backup config
    )

    override val data: StateFlow<MjpegSettings.Data> = dataStore.data
        .map { preferences -> preferences.toMjpegSettings() }
        .catch { cause ->
            XLog.e(this@MjpegSettingsImpl.getLog("getCatching"), cause)
            if (cause is IOException) emit(MjpegSettings.Data()) else throw cause
        }
        .stateIn(
            CoroutineScope(Dispatchers.IO),
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000),
            MjpegSettings.Data()
        )

    override suspend fun updateData(transform: MjpegSettings.Data.() -> MjpegSettings.Data) = withContext(NonCancellable + Dispatchers.IO) {
        dataStore.edit { preferences ->
            val newSettings = transform.invoke(preferences.toMjpegSettings())

            preferences.apply {
                clear()

                if (newSettings.keepAwake != MjpegSettings.Default.KEEP_AWAKE)
                    set(MjpegSettings.Key.KEEP_AWAKE, newSettings.keepAwake)

                if (newSettings.stopOnSleep != MjpegSettings.Default.STOP_ON_SLEEP)
                    set(MjpegSettings.Key.STOP_ON_SLEEP, newSettings.stopOnSleep)

                if (newSettings.stopOnConfigurationChange != MjpegSettings.Default.STOP_ON_CONFIGURATION_CHANGE)
                    set(MjpegSettings.Key.STOP_ON_CONFIGURATION_CHANGE, newSettings.stopOnConfigurationChange)

                if (newSettings.notifySlowConnections != MjpegSettings.Default.NOTIFY_SLOW_CONNECTIONS)
                    set(MjpegSettings.Key.NOTIFY_SLOW_CONNECTIONS, newSettings.notifySlowConnections)

                if (newSettings.htmlEnableButtons != MjpegSettings.Default.HTML_ENABLE_BUTTONS)
                    set(MjpegSettings.Key.HTML_ENABLE_BUTTONS, newSettings.htmlEnableButtons)

                if (newSettings.htmlShowPressStart != MjpegSettings.Default.HTML_SHOW_PRESS_START)
                    set(MjpegSettings.Key.HTML_SHOW_PRESS_START, newSettings.htmlShowPressStart)

                if (newSettings.htmlBackColor != MjpegSettings.Default.HTML_BACK_COLOR)
                    set(MjpegSettings.Key.HTML_BACK_COLOR, newSettings.htmlBackColor)

                if (newSettings.htmlFitWindow != MjpegSettings.Default.HTML_FIT_WINDOW)
                    set(MjpegSettings.Key.HTML_FIT_WINDOW, newSettings.htmlFitWindow)


                if (newSettings.vrMode != MjpegSettings.Default.VR_MODE_DISABLE)
                    set(MjpegSettings.Key.VR_MODE, newSettings.vrMode)

                if (newSettings.imageCrop != MjpegSettings.Default.IMAGE_CROP)
                    set(MjpegSettings.Key.IMAGE_CROP, newSettings.imageCrop)

                if (newSettings.imageCropTop != MjpegSettings.Default.IMAGE_CROP_TOP)
                    set(MjpegSettings.Key.IMAGE_CROP_TOP, newSettings.imageCropTop)

                if (newSettings.imageCropBottom != MjpegSettings.Default.IMAGE_CROP_BOTTOM)
                    set(MjpegSettings.Key.IMAGE_CROP_BOTTOM, newSettings.imageCropBottom)

                if (newSettings.imageCropLeft != MjpegSettings.Default.IMAGE_CROP_LEFT)
                    set(MjpegSettings.Key.IMAGE_CROP_LEFT, newSettings.imageCropLeft)

                if (newSettings.imageCropRight != MjpegSettings.Default.IMAGE_CROP_RIGHT)
                    set(MjpegSettings.Key.IMAGE_CROP_RIGHT, newSettings.imageCropRight)

                if (newSettings.imageGrayscale != MjpegSettings.Default.IMAGE_GRAYSCALE)
                    set(MjpegSettings.Key.IMAGE_GRAYSCALE, newSettings.imageGrayscale)

                if (newSettings.jpegQuality != MjpegSettings.Default.JPEG_QUALITY)
                    set(MjpegSettings.Key.JPEG_QUALITY, newSettings.jpegQuality)

                if (newSettings.resizeFactor != MjpegSettings.Default.RESIZE_FACTOR)
                    set(MjpegSettings.Key.RESIZE_FACTOR, newSettings.resizeFactor)

                if (newSettings.rotation != MjpegSettings.Default.ROTATION)
                    set(MjpegSettings.Key.ROTATION, newSettings.rotation)

                if (newSettings.maxFPS != MjpegSettings.Default.MAX_FPS)
                    set(MjpegSettings.Key.MAX_FPS, newSettings.maxFPS)


                if (newSettings.enablePin != MjpegSettings.Default.ENABLE_PIN)
                    set(MjpegSettings.Key.ENABLE_PIN, newSettings.enablePin)

                if (newSettings.hidePinOnStart != MjpegSettings.Default.HIDE_PIN_ON_START)
                    set(MjpegSettings.Key.HIDE_PIN_ON_START, newSettings.hidePinOnStart)

                if (newSettings.newPinOnAppStart != MjpegSettings.Default.NEW_PIN_ON_APP_START)
                    set(MjpegSettings.Key.NEW_PIN_ON_APP_START, newSettings.newPinOnAppStart)

                if (newSettings.autoChangePin != MjpegSettings.Default.AUTO_CHANGE_PIN)
                    set(MjpegSettings.Key.AUTO_CHANGE_PIN, newSettings.autoChangePin)

                if (newSettings.pin != MjpegSettings.Default.PIN)
                    set(MjpegSettings.Key.PIN, newSettings.pin)

                if (newSettings.blockAddress != MjpegSettings.Default.BLOCK_ADDRESS)
                    set(MjpegSettings.Key.BLOCK_ADDRESS, newSettings.blockAddress)


                if (newSettings.useWiFiOnly != MjpegSettings.Default.USE_WIFI_ONLY)
                    set(MjpegSettings.Key.USE_WIFI_ONLY, newSettings.useWiFiOnly)

                if (newSettings.enableIPv6 != MjpegSettings.Default.ENABLE_IPV6)
                    set(MjpegSettings.Key.ENABLE_IPV6, newSettings.enableIPv6)

                if (newSettings.enableLocalHost != MjpegSettings.Default.ENABLE_LOCAL_HOST)
                    set(MjpegSettings.Key.ENABLE_LOCAL_HOST, newSettings.enableLocalHost)

                if (newSettings.localHostOnly != MjpegSettings.Default.LOCAL_HOST_ONLY)
                    set(MjpegSettings.Key.LOCAL_HOST_ONLY, newSettings.localHostOnly)

                if (newSettings.serverPort != MjpegSettings.Default.SERVER_PORT)
                    set(MjpegSettings.Key.SERVER_PORT, newSettings.serverPort)
            }
        }
        Unit
    }

    private fun Preferences.toMjpegSettings(): MjpegSettings.Data = MjpegSettings.Data(
        keepAwake = this[MjpegSettings.Key.KEEP_AWAKE] ?: MjpegSettings.Default.KEEP_AWAKE,
        stopOnSleep = this[MjpegSettings.Key.STOP_ON_SLEEP] ?: MjpegSettings.Default.STOP_ON_SLEEP,
        stopOnConfigurationChange = this[MjpegSettings.Key.STOP_ON_CONFIGURATION_CHANGE] ?: MjpegSettings.Default.STOP_ON_CONFIGURATION_CHANGE,
        notifySlowConnections = this[MjpegSettings.Key.NOTIFY_SLOW_CONNECTIONS] ?: MjpegSettings.Default.NOTIFY_SLOW_CONNECTIONS,
        htmlEnableButtons = this[MjpegSettings.Key.HTML_ENABLE_BUTTONS] ?: MjpegSettings.Default.HTML_ENABLE_BUTTONS,
        htmlShowPressStart = this[MjpegSettings.Key.HTML_SHOW_PRESS_START] ?: MjpegSettings.Default.HTML_SHOW_PRESS_START,
        htmlBackColor = this[MjpegSettings.Key.HTML_BACK_COLOR] ?: MjpegSettings.Default.HTML_BACK_COLOR,
        htmlFitWindow = this[MjpegSettings.Key.HTML_FIT_WINDOW] ?: MjpegSettings.Default.HTML_FIT_WINDOW,

        vrMode = this[MjpegSettings.Key.VR_MODE] ?: MjpegSettings.Default.VR_MODE_DISABLE,
        imageCrop = this[MjpegSettings.Key.IMAGE_CROP] ?: MjpegSettings.Default.IMAGE_CROP,
        imageCropTop = this[MjpegSettings.Key.IMAGE_CROP_TOP] ?: MjpegSettings.Default.IMAGE_CROP_TOP,
        imageCropBottom = this[MjpegSettings.Key.IMAGE_CROP_BOTTOM] ?: MjpegSettings.Default.IMAGE_CROP_BOTTOM,
        imageCropLeft = this[MjpegSettings.Key.IMAGE_CROP_LEFT] ?: MjpegSettings.Default.IMAGE_CROP_LEFT,
        imageCropRight = this[MjpegSettings.Key.IMAGE_CROP_RIGHT] ?: MjpegSettings.Default.IMAGE_CROP_RIGHT,
        imageGrayscale = this[MjpegSettings.Key.IMAGE_GRAYSCALE] ?: MjpegSettings.Default.IMAGE_GRAYSCALE,
        jpegQuality = this[MjpegSettings.Key.JPEG_QUALITY] ?: MjpegSettings.Default.JPEG_QUALITY,
        resizeFactor = this[MjpegSettings.Key.RESIZE_FACTOR] ?: MjpegSettings.Default.RESIZE_FACTOR,
        rotation = this[MjpegSettings.Key.ROTATION] ?: MjpegSettings.Default.ROTATION,
        maxFPS = this[MjpegSettings.Key.MAX_FPS] ?: MjpegSettings.Default.MAX_FPS,

        enablePin = this[MjpegSettings.Key.ENABLE_PIN] ?: MjpegSettings.Default.ENABLE_PIN,
        hidePinOnStart = this[MjpegSettings.Key.HIDE_PIN_ON_START] ?: MjpegSettings.Default.HIDE_PIN_ON_START,
        newPinOnAppStart = this[MjpegSettings.Key.NEW_PIN_ON_APP_START] ?: MjpegSettings.Default.NEW_PIN_ON_APP_START,
        autoChangePin = this[MjpegSettings.Key.AUTO_CHANGE_PIN] ?: MjpegSettings.Default.AUTO_CHANGE_PIN,
        pin = this[MjpegSettings.Key.PIN] ?: MjpegSettings.Default.PIN,
        blockAddress = this[MjpegSettings.Key.BLOCK_ADDRESS] ?: MjpegSettings.Default.BLOCK_ADDRESS,

        useWiFiOnly = this[MjpegSettings.Key.USE_WIFI_ONLY] ?: MjpegSettings.Default.USE_WIFI_ONLY,
        enableIPv6 = this[MjpegSettings.Key.ENABLE_IPV6] ?: MjpegSettings.Default.ENABLE_IPV6,
        enableLocalHost = this[MjpegSettings.Key.ENABLE_LOCAL_HOST] ?: MjpegSettings.Default.ENABLE_LOCAL_HOST,
        localHostOnly = this[MjpegSettings.Key.LOCAL_HOST_ONLY] ?: MjpegSettings.Default.LOCAL_HOST_ONLY,
        serverPort = this[MjpegSettings.Key.SERVER_PORT] ?: MjpegSettings.Default.SERVER_PORT,
    )
}