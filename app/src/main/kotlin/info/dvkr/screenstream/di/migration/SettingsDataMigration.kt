package info.dvkr.screenstream.di.migration

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.settings.AppSettings
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import java.io.File

class SettingsDataMigration(
    private val appContext: Context,
    private val binaryPreferences: com.ironz.binaryprefs.Preferences) : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val shouldMigrate = binaryPreferences.keys().isNullOrEmpty().not()
        XLog.i(getLog("shouldMigrate", "shouldMigrate: $shouldMigrate"))
        XLog.i(getLog("shouldMigrate", "Saved settings: ${binaryPreferences.keys().joinToString()}"))
        return shouldMigrate
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        XLog.i(getLog("migrate"))
        val currentMutablePrefs = currentData.toMutablePreferences()
        currentMutablePrefs.clear()

        SettingsOldImpl(binaryPreferences).let { settingsOld ->
            if (settingsOld.nightMode != AppSettings.Default.NIGHT_MODE)
                currentMutablePrefs[AppSettings.Key.NIGHT_MODE] = settingsOld.nightMode

            if (settingsOld.keepAwake != AppSettings.Default.KEEP_AWAKE)
                currentMutablePrefs[AppSettings.Key.KEEP_AWAKE] = settingsOld.keepAwake

            if (settingsOld.stopOnSleep != AppSettings.Default.STOP_ON_SLEEP)
                currentMutablePrefs[AppSettings.Key.STOP_ON_SLEEP] = settingsOld.stopOnSleep

            if (settingsOld.startOnBoot != AppSettings.Default.START_ON_BOOT)
                currentMutablePrefs[AppSettings.Key.START_ON_BOOT] = settingsOld.startOnBoot

            if (settingsOld.autoStartStop != AppSettings.Default.AUTO_START_STOP)
                currentMutablePrefs[AppSettings.Key.AUTO_START_STOP] = settingsOld.startOnBoot

            if (settingsOld.notifySlowConnections != MjpegSettings.Default.NOTIFY_SLOW_CONNECTIONS)
                currentMutablePrefs[MjpegSettings.Key.NOTIFY_SLOW_CONNECTIONS] = settingsOld.notifySlowConnections


            if (settingsOld.htmlEnableButtons != MjpegSettings.Default.HTML_ENABLE_BUTTONS)
                currentMutablePrefs[MjpegSettings.Key.HTML_ENABLE_BUTTONS] = settingsOld.htmlEnableButtons

            if (settingsOld.htmlShowPressStart != MjpegSettings.Default.HTML_SHOW_PRESS_START)
                currentMutablePrefs[MjpegSettings.Key.HTML_SHOW_PRESS_START] = settingsOld.htmlShowPressStart

            if (settingsOld.htmlBackColor != MjpegSettings.Default.HTML_BACK_COLOR)
                currentMutablePrefs[MjpegSettings.Key.HTML_BACK_COLOR] = settingsOld.htmlBackColor


            if (settingsOld.vrMode != MjpegSettings.Default.VR_MODE_DISABLE)
                currentMutablePrefs[MjpegSettings.Key.VR_MODE] = settingsOld.vrMode

            if (settingsOld.imageCrop != MjpegSettings.Default.IMAGE_CROP)
                currentMutablePrefs[MjpegSettings.Key.IMAGE_CROP] = settingsOld.imageCrop

            if (settingsOld.imageCropTop != MjpegSettings.Default.IMAGE_CROP_TOP)
                currentMutablePrefs[MjpegSettings.Key.IMAGE_CROP_TOP] = settingsOld.imageCropTop

            if (settingsOld.imageCropBottom != MjpegSettings.Default.IMAGE_CROP_BOTTOM)
                currentMutablePrefs[MjpegSettings.Key.IMAGE_CROP_BOTTOM] = settingsOld.imageCropBottom

            if (settingsOld.imageCropLeft != MjpegSettings.Default.IMAGE_CROP_LEFT)
                currentMutablePrefs[MjpegSettings.Key.IMAGE_CROP_LEFT] = settingsOld.imageCropLeft

            if (settingsOld.imageCropRight != MjpegSettings.Default.IMAGE_CROP_RIGHT)
                currentMutablePrefs[MjpegSettings.Key.IMAGE_CROP_RIGHT] = settingsOld.imageCropRight

            if (settingsOld.imageGrayscale != MjpegSettings.Default.IMAGE_GRAYSCALE)
                currentMutablePrefs[MjpegSettings.Key.IMAGE_GRAYSCALE] = settingsOld.imageGrayscale

            if (settingsOld.jpegQuality != MjpegSettings.Default.JPEG_QUALITY)
                currentMutablePrefs[MjpegSettings.Key.JPEG_QUALITY] = settingsOld.jpegQuality

            if (settingsOld.resizeFactor != MjpegSettings.Default.RESIZE_FACTOR)
                currentMutablePrefs[MjpegSettings.Key.RESIZE_FACTOR] = settingsOld.resizeFactor

            if (settingsOld.rotation != MjpegSettings.Default.ROTATION)
                currentMutablePrefs[MjpegSettings.Key.ROTATION] = settingsOld.rotation

            if (settingsOld.maxFPS != MjpegSettings.Default.MAX_FPS)
                currentMutablePrefs[MjpegSettings.Key.MAX_FPS] = settingsOld.maxFPS


            if (settingsOld.enablePin != MjpegSettings.Default.ENABLE_PIN)
                currentMutablePrefs[MjpegSettings.Key.ENABLE_PIN] = settingsOld.enablePin

            if (settingsOld.hidePinOnStart != MjpegSettings.Default.HIDE_PIN_ON_START)
                currentMutablePrefs[MjpegSettings.Key.HIDE_PIN_ON_START] = settingsOld.hidePinOnStart

            if (settingsOld.newPinOnAppStart != MjpegSettings.Default.NEW_PIN_ON_APP_START)
                currentMutablePrefs[MjpegSettings.Key.NEW_PIN_ON_APP_START] = settingsOld.newPinOnAppStart

            if (settingsOld.autoChangePin != MjpegSettings.Default.AUTO_CHANGE_PIN)
                currentMutablePrefs[MjpegSettings.Key.AUTO_CHANGE_PIN] = settingsOld.autoChangePin

            if (settingsOld.pin != MjpegSettings.Default.PIN)
                currentMutablePrefs[MjpegSettings.Key.PIN] = settingsOld.pin

            if (settingsOld.blockAddress != MjpegSettings.Default.BLOCK_ADDRESS)
                currentMutablePrefs[MjpegSettings.Key.BLOCK_ADDRESS] = settingsOld.blockAddress


            if (settingsOld.useWiFiOnly != MjpegSettings.Default.USE_WIFI_ONLY)
                currentMutablePrefs[MjpegSettings.Key.USE_WIFI_ONLY] = settingsOld.useWiFiOnly

            if (settingsOld.enableIPv6 != MjpegSettings.Default.ENABLE_IPV6)
                currentMutablePrefs[MjpegSettings.Key.ENABLE_IPV6] = settingsOld.enableIPv6

            if (settingsOld.enableLocalHost != MjpegSettings.Default.ENABLE_LOCAL_HOST)
                currentMutablePrefs[MjpegSettings.Key.ENABLE_LOCAL_HOST] = settingsOld.enableLocalHost

            if (settingsOld.localHostOnly != MjpegSettings.Default.LOCAL_HOST_ONLY)
                currentMutablePrefs[MjpegSettings.Key.LOCAL_HOST_ONLY] = settingsOld.localHostOnly

            if (settingsOld.severPort != MjpegSettings.Default.SERVER_PORT)
                currentMutablePrefs[MjpegSettings.Key.SERVER_PORT] = settingsOld.severPort

            if (settingsOld.loggingVisible != AppSettings.Default.LOGGING_VISIBLE)
                currentMutablePrefs[AppSettings.Key.LOGGING_VISIBLE] = settingsOld.loggingVisible
        }

        return currentMutablePrefs.toPreferences()
    }

    override suspend fun cleanUp() {
        XLog.i(getLog("cleanUp"))
        File(appContext.filesDir, "preferences").deleteRecursively()
    }
}