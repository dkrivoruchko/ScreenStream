package info.dvkr.screenstream.data.settings.old

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
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
            if (settingsOld.nightMode != Settings.Default.NIGHT_MODE)
                currentMutablePrefs[Settings.Key.NIGHT_MODE] = settingsOld.nightMode

            if (settingsOld.keepAwake != Settings.Default.KEEP_AWAKE)
                currentMutablePrefs[Settings.Key.KEEP_AWAKE] = settingsOld.keepAwake

            if (settingsOld.stopOnSleep != Settings.Default.STOP_ON_SLEEP)
                currentMutablePrefs[Settings.Key.STOP_ON_SLEEP] = settingsOld.stopOnSleep

            if (settingsOld.startOnBoot != Settings.Default.START_ON_BOOT)
                currentMutablePrefs[Settings.Key.START_ON_BOOT] = settingsOld.startOnBoot

            if (settingsOld.autoStartStop != Settings.Default.AUTO_START_STOP)
                currentMutablePrefs[Settings.Key.AUTO_START_STOP] = settingsOld.startOnBoot

            if (settingsOld.notifySlowConnections != Settings.Default.NOTIFY_SLOW_CONNECTIONS)
                currentMutablePrefs[Settings.Key.NOTIFY_SLOW_CONNECTIONS] = settingsOld.notifySlowConnections


            if (settingsOld.htmlEnableButtons != Settings.Default.HTML_ENABLE_BUTTONS)
                currentMutablePrefs[Settings.Key.HTML_ENABLE_BUTTONS] = settingsOld.htmlEnableButtons

            if (settingsOld.htmlShowPressStart != Settings.Default.HTML_SHOW_PRESS_START)
                currentMutablePrefs[Settings.Key.HTML_SHOW_PRESS_START] = settingsOld.htmlShowPressStart

            if (settingsOld.htmlBackColor != Settings.Default.HTML_BACK_COLOR)
                currentMutablePrefs[Settings.Key.HTML_BACK_COLOR] = settingsOld.htmlBackColor


            if (settingsOld.vrMode != Settings.Default.VR_MODE_DISABLE)
                currentMutablePrefs[Settings.Key.VR_MODE] = settingsOld.vrMode

            if (settingsOld.imageCrop != Settings.Default.IMAGE_CROP)
                currentMutablePrefs[Settings.Key.IMAGE_CROP] = settingsOld.imageCrop

            if (settingsOld.imageCropTop != Settings.Default.IMAGE_CROP_TOP)
                currentMutablePrefs[Settings.Key.IMAGE_CROP_TOP] = settingsOld.imageCropTop

            if (settingsOld.imageCropBottom != Settings.Default.IMAGE_CROP_BOTTOM)
                currentMutablePrefs[Settings.Key.IMAGE_CROP_BOTTOM] = settingsOld.imageCropBottom

            if (settingsOld.imageCropLeft != Settings.Default.IMAGE_CROP_LEFT)
                currentMutablePrefs[Settings.Key.IMAGE_CROP_LEFT] = settingsOld.imageCropLeft

            if (settingsOld.imageCropRight != Settings.Default.IMAGE_CROP_RIGHT)
                currentMutablePrefs[Settings.Key.IMAGE_CROP_RIGHT] = settingsOld.imageCropRight

            if (settingsOld.imageGrayscale != Settings.Default.IMAGE_GRAYSCALE)
                currentMutablePrefs[Settings.Key.IMAGE_GRAYSCALE] = settingsOld.imageGrayscale

            if (settingsOld.jpegQuality != Settings.Default.JPEG_QUALITY)
                currentMutablePrefs[Settings.Key.JPEG_QUALITY] = settingsOld.jpegQuality

            if (settingsOld.resizeFactor != Settings.Default.RESIZE_FACTOR)
                currentMutablePrefs[Settings.Key.RESIZE_FACTOR] = settingsOld.resizeFactor

            if (settingsOld.rotation != Settings.Default.ROTATION)
                currentMutablePrefs[Settings.Key.ROTATION] = settingsOld.rotation

            if (settingsOld.maxFPS != Settings.Default.MAX_FPS)
                currentMutablePrefs[Settings.Key.MAX_FPS] = settingsOld.maxFPS


            if (settingsOld.enablePin != Settings.Default.ENABLE_PIN)
                currentMutablePrefs[Settings.Key.ENABLE_PIN] = settingsOld.enablePin

            if (settingsOld.hidePinOnStart != Settings.Default.HIDE_PIN_ON_START)
                currentMutablePrefs[Settings.Key.HIDE_PIN_ON_START] = settingsOld.hidePinOnStart

            if (settingsOld.newPinOnAppStart != Settings.Default.NEW_PIN_ON_APP_START)
                currentMutablePrefs[Settings.Key.NEW_PIN_ON_APP_START] = settingsOld.newPinOnAppStart

            if (settingsOld.autoChangePin != Settings.Default.AUTO_CHANGE_PIN)
                currentMutablePrefs[Settings.Key.AUTO_CHANGE_PIN] = settingsOld.autoChangePin

            if (settingsOld.pin != Settings.Default.PIN)
                currentMutablePrefs[Settings.Key.PIN] = settingsOld.pin

            if (settingsOld.blockAddress != Settings.Default.BLOCK_ADDRESS)
                currentMutablePrefs[Settings.Key.BLOCK_ADDRESS] = settingsOld.blockAddress


            if (settingsOld.useWiFiOnly != Settings.Default.USE_WIFI_ONLY)
                currentMutablePrefs[Settings.Key.USE_WIFI_ONLY] = settingsOld.useWiFiOnly

            if (settingsOld.enableIPv6 != Settings.Default.ENABLE_IPV6)
                currentMutablePrefs[Settings.Key.ENABLE_IPV6] = settingsOld.enableIPv6

            if (settingsOld.enableLocalHost != Settings.Default.ENABLE_LOCAL_HOST)
                currentMutablePrefs[Settings.Key.ENABLE_LOCAL_HOST] = settingsOld.enableLocalHost

            if (settingsOld.localHostOnly != Settings.Default.LOCAL_HOST_ONLY)
                currentMutablePrefs[Settings.Key.LOCAL_HOST_ONLY] = settingsOld.localHostOnly

            if (settingsOld.severPort != Settings.Default.SERVER_PORT)
                currentMutablePrefs[Settings.Key.SERVER_PORT] = settingsOld.severPort

            if (settingsOld.loggingVisible != Settings.Default.LOGGING_VISIBLE)
                currentMutablePrefs[Settings.Key.LOGGING_VISIBLE] = settingsOld.loggingVisible
        }

        return currentMutablePrefs.toPreferences()
    }

    override suspend fun cleanUp() {
        XLog.i(getLog("cleanUp"))
        File(appContext.filesDir, "preferences").deleteRecursively()
    }
}