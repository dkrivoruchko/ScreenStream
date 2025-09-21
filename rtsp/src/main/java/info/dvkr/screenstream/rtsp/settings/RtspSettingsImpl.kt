package info.dvkr.screenstream.rtsp.settings

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
import java.io.IOException

internal class RtspSettingsImpl(
    context: Context
) : RtspSettings {

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { ex -> XLog.e(ex); emptyPreferences() },
        produceFile = { context.preferencesDataStoreFile("RTSP_settings") } // Sync name with backup config
    )

    override val data: StateFlow<RtspSettings.Data> = dataStore.data
        .map { preferences -> preferences.toRtspSettings() }
        .catch { cause ->
            XLog.e(this@RtspSettingsImpl.getLog("getCatching"), cause)
            if (cause is IOException) emit(RtspSettings.Data()) else throw cause
        }
        .stateIn(
            CoroutineScope(Dispatchers.IO),
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000),
            RtspSettings.Data()
        )

    override suspend fun updateData(transform: RtspSettings.Data.() -> RtspSettings.Data) = withContext(NonCancellable + Dispatchers.IO) {
        dataStore.edit { preferences ->
            val newSettings = transform.invoke(preferences.toRtspSettings())

            preferences.apply {
                clear()

                if (newSettings.keepAwake != RtspSettings.Default.KEEP_AWAKE)
                    set(RtspSettings.Key.KEEP_AWAKE, newSettings.keepAwake)

                if (newSettings.stopOnSleep != RtspSettings.Default.STOP_ON_SLEEP)
                    set(RtspSettings.Key.STOP_ON_SLEEP, newSettings.stopOnSleep)

                if (newSettings.stopOnConfigurationChange != RtspSettings.Default.STOP_ON_CONFIGURATION_CHANGE)
                    set(RtspSettings.Key.STOP_ON_CONFIGURATION_CHANGE, newSettings.stopOnConfigurationChange)


                if (newSettings.serverAddress != RtspSettings.Default.SERVER_ADDRESS)
                    set(RtspSettings.Key.SERVER_ADDRESS, newSettings.serverAddress)

                if (newSettings.protocol != RtspSettings.Default.PROTOCOL)
                    set(RtspSettings.Key.PROTOCOL, newSettings.protocol)

                if (newSettings.mode != RtspSettings.Default.MODE)
                    set(RtspSettings.Key.MODE, newSettings.mode.name)

                if (newSettings.videoCodecAutoSelect != RtspSettings.Default.VIDEO_CODEC_AUTO_SELECT)
                    set(RtspSettings.Key.VIDEO_CODEC_AUTO_SELECT, newSettings.videoCodecAutoSelect)

                if (newSettings.videoCodec != RtspSettings.Default.VIDEO_CODEC)
                    set(RtspSettings.Key.VIDEO_CODEC, newSettings.videoCodec)

                if (newSettings.videoResizeFactor != RtspSettings.Default.VIDEO_RESIZE_FACTOR) {
                    set(RtspSettings.Key.VIDEO_RESIZE_FACTOR, newSettings.videoResizeFactor)
                }

                if (newSettings.videoFps != RtspSettings.Default.VIDEO_FPS)
                    set(RtspSettings.Key.VIDEO_FPS, newSettings.videoFps)

                if (newSettings.videoBitrateBits != RtspSettings.Default.VIDEO_BITRATE)
                    set(RtspSettings.Key.VIDEO_BITRATE, newSettings.videoBitrateBits)


                if (newSettings.audioCodecAutoSelect != RtspSettings.Default.AUDIO_CODEC_AUTO_SELECT)
                    set(RtspSettings.Key.AUDIO_CODEC_AUTO_SELECT, newSettings.audioCodecAutoSelect)

                if (newSettings.audioCodec != RtspSettings.Default.AUDIO_CODEC)
                    set(RtspSettings.Key.AUDIO_CODEC, newSettings.audioCodec)

                if (newSettings.audioBitrateBits != RtspSettings.Default.AUDIO_BITRATE)
                    set(RtspSettings.Key.AUDIO_BITRATE, newSettings.audioBitrateBits)

                if (newSettings.enableMic != RtspSettings.Default.ENABLE_MIC)
                    set(RtspSettings.Key.ENABLE_MIC, newSettings.enableMic)

                if (newSettings.muteMic != RtspSettings.Default.MUTE_MIC)
                    set(RtspSettings.Key.MUTE_MIC, newSettings.muteMic)

                if (newSettings.volumeMic != RtspSettings.Default.VOLUME_MIC)
                    set(RtspSettings.Key.VOLUME_MIC, newSettings.volumeMic)

                if (newSettings.enableDeviceAudio != RtspSettings.Default.ENABLE_DEVICE_AUDIO)
                    set(RtspSettings.Key.ENABLE_DEVICE_AUDIO, newSettings.enableDeviceAudio)

                if (newSettings.muteDeviceAudio != RtspSettings.Default.MUTE_DEVICE_AUDIO)
                    set(RtspSettings.Key.MUTE_DEVICE_AUDIO, newSettings.muteDeviceAudio)

                if (newSettings.volumeDeviceAudio != RtspSettings.Default.VOLUME_DEVICE_AUDIO)
                    set(RtspSettings.Key.VOLUME_DEVICE_AUDIO, newSettings.volumeDeviceAudio)

                if (newSettings.stereoAudio != RtspSettings.Default.STEREO_AUDIO)
                    set(RtspSettings.Key.STEREO_AUDIO, newSettings.stereoAudio)

                if (newSettings.audioEchoCanceller != RtspSettings.Default.AUDIO_ECHO_CANCELLER)
                    set(RtspSettings.Key.AUDIO_ECHO_CANCELLER, newSettings.audioEchoCanceller)

                if (newSettings.audioNoiseSuppressor != RtspSettings.Default.AUDIO_NOISE_SUPPRESSOR)
                    set(RtspSettings.Key.AUDIO_NOISE_SUPPRESSOR, newSettings.audioNoiseSuppressor)

                if (newSettings.interfaceFilter != RtspSettings.Default.INTERFACE_FILTER)
                    set(RtspSettings.Key.INTERFACE_FILTER, newSettings.interfaceFilter)

                if (newSettings.addressFilter != RtspSettings.Default.ADDRESS_FILTER)
                    set(RtspSettings.Key.ADDRESS_FILTER, newSettings.addressFilter)

                if (newSettings.enableIPv4 != RtspSettings.Default.ENABLE_IPV4)
                    set(RtspSettings.Key.ENABLE_IPV4, newSettings.enableIPv4)

                if (newSettings.enableIPv6 != RtspSettings.Default.ENABLE_IPV6)
                    set(RtspSettings.Key.ENABLE_IPV6, newSettings.enableIPv6)

                if (newSettings.serverPort != RtspSettings.Default.SERVER_PORT)
                    set(RtspSettings.Key.SERVER_PORT, newSettings.serverPort)

                if (newSettings.serverPath != RtspSettings.Default.SERVER_PATH)
                    set(RtspSettings.Key.SERVER_PATH, newSettings.serverPath)
            }
        }
        Unit
    }

    private fun Preferences.toRtspSettings(): RtspSettings.Data = RtspSettings.Data(
        keepAwake = this[RtspSettings.Key.KEEP_AWAKE] ?: RtspSettings.Default.KEEP_AWAKE,
        stopOnSleep = this[RtspSettings.Key.STOP_ON_SLEEP] ?: RtspSettings.Default.STOP_ON_SLEEP,
        stopOnConfigurationChange = this[RtspSettings.Key.STOP_ON_CONFIGURATION_CHANGE] ?: RtspSettings.Default.STOP_ON_CONFIGURATION_CHANGE,

        serverAddress = this[RtspSettings.Key.SERVER_ADDRESS] ?: RtspSettings.Default.SERVER_ADDRESS,
        protocol = this[RtspSettings.Key.PROTOCOL] ?: RtspSettings.Default.PROTOCOL,
        mode = runCatching {
            this[RtspSettings.Key.MODE]?.let { name -> RtspSettings.Values.Mode.valueOf(name) }
        }.getOrNull() ?: RtspSettings.Default.MODE,

        videoCodecAutoSelect = this[RtspSettings.Key.VIDEO_CODEC_AUTO_SELECT] ?: RtspSettings.Default.VIDEO_CODEC_AUTO_SELECT,
        videoCodec = this[RtspSettings.Key.VIDEO_CODEC] ?: RtspSettings.Default.VIDEO_CODEC,
        videoResizeFactor = this[RtspSettings.Key.VIDEO_RESIZE_FACTOR] ?: RtspSettings.Default.VIDEO_RESIZE_FACTOR,
        videoFps = this[RtspSettings.Key.VIDEO_FPS] ?: RtspSettings.Default.VIDEO_FPS,
        videoBitrateBits = this[RtspSettings.Key.VIDEO_BITRATE] ?: RtspSettings.Default.VIDEO_BITRATE,

        audioCodecAutoSelect = this[RtspSettings.Key.AUDIO_CODEC_AUTO_SELECT] ?: RtspSettings.Default.AUDIO_CODEC_AUTO_SELECT,
        audioCodec = this[RtspSettings.Key.AUDIO_CODEC] ?: RtspSettings.Default.AUDIO_CODEC,
        audioBitrateBits = this[RtspSettings.Key.AUDIO_BITRATE] ?: RtspSettings.Default.AUDIO_BITRATE,
        enableMic = this[RtspSettings.Key.ENABLE_MIC] ?: RtspSettings.Default.ENABLE_MIC,
        muteMic = this[RtspSettings.Key.MUTE_MIC] ?: RtspSettings.Default.MUTE_MIC,
        volumeMic = this[RtspSettings.Key.VOLUME_MIC] ?: RtspSettings.Default.VOLUME_MIC,
        enableDeviceAudio = this[RtspSettings.Key.ENABLE_DEVICE_AUDIO] ?: RtspSettings.Default.ENABLE_DEVICE_AUDIO,
        muteDeviceAudio = this[RtspSettings.Key.MUTE_DEVICE_AUDIO] ?: RtspSettings.Default.MUTE_DEVICE_AUDIO,
        volumeDeviceAudio = this[RtspSettings.Key.VOLUME_DEVICE_AUDIO] ?: RtspSettings.Default.VOLUME_DEVICE_AUDIO,
        stereoAudio = this[RtspSettings.Key.STEREO_AUDIO] ?: RtspSettings.Default.STEREO_AUDIO,
        audioEchoCanceller = this[RtspSettings.Key.AUDIO_ECHO_CANCELLER] ?: RtspSettings.Default.AUDIO_ECHO_CANCELLER,
        audioNoiseSuppressor = this[RtspSettings.Key.AUDIO_NOISE_SUPPRESSOR] ?: RtspSettings.Default.AUDIO_NOISE_SUPPRESSOR,

        interfaceFilter = this[RtspSettings.Key.INTERFACE_FILTER] ?: RtspSettings.Default.INTERFACE_FILTER,
        addressFilter = this[RtspSettings.Key.ADDRESS_FILTER] ?: RtspSettings.Default.ADDRESS_FILTER,
        enableIPv4 = this[RtspSettings.Key.ENABLE_IPV4] ?: RtspSettings.Default.ENABLE_IPV4,
        enableIPv6 = this[RtspSettings.Key.ENABLE_IPV6] ?: RtspSettings.Default.ENABLE_IPV6,
        serverPort = this[RtspSettings.Key.SERVER_PORT] ?: RtspSettings.Default.SERVER_PORT,
        serverPath = this[RtspSettings.Key.SERVER_PATH] ?: RtspSettings.Default.SERVER_PATH,
    )
}