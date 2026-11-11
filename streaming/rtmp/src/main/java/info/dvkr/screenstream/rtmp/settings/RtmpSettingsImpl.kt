package info.dvkr.screenstream.rtmp.settings

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
import org.koin.core.annotation.Singleton
import java.io.IOException

@Singleton(binds = [RtmpSettings::class])
internal class RtmpSettingsImpl(
    context: Context
) : RtmpSettings {

    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { ex -> XLog.e(ex); emptyPreferences() },
        produceFile = { context.preferencesDataStoreFile("RTMP_settings") } // Sync name with backup config
    )

    override val data: StateFlow<RtmpSettings.Data> = dataStore.data
        .map { preferences -> preferences.toRtmpSettings() }
        .catch { cause ->
            XLog.e(this@RtmpSettingsImpl.getLog("getCatching"), cause)
            if (cause is IOException) emit(RtmpSettings.Data()) else throw cause
        }
        .stateIn(
            coroutineScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000),
            RtmpSettings.Data()
        )


    override suspend fun updateData(transform: RtmpSettings.Data.() -> RtmpSettings.Data) = withContext(NonCancellable + Dispatchers.IO) {
        dataStore.edit { preferences ->
            val newSettings = transform.invoke(preferences.toRtmpSettings())

            preferences.apply {
                clear()

                if (newSettings.keepAwake != RtmpSettings.Default.KEEP_AWAKE)
                    set(RtmpSettings.Key.KEEP_AWAKE, newSettings.keepAwake)

                if (newSettings.stopOnSleep != RtmpSettings.Default.STOP_ON_SLEEP)
                    set(RtmpSettings.Key.STOP_ON_SLEEP, newSettings.stopOnSleep)

                if (newSettings.stopOnConfigurationChange != RtmpSettings.Default.STOP_ON_CONFIGURATION_CHANGE)
                    set(RtmpSettings.Key.STOP_ON_CONFIGURATION_CHANGE, newSettings.stopOnConfigurationChange)


                if (newSettings.serverAddress != RtmpSettings.Default.SERVER_ADDRESS)
                    set(RtmpSettings.Key.SERVER_ADDRESS, newSettings.serverAddress)

                if (newSettings.mode != RtmpSettings.Default.MODE)
                    set(RtmpSettings.Key.MODE, newSettings.mode.name)

                if (newSettings.videoCodecAutoSelect != RtmpSettings.Default.VIDEO_CODEC_AUTO_SELECT)
                    set(RtmpSettings.Key.VIDEO_CODEC_AUTO_SELECT, newSettings.videoCodecAutoSelect)

                if (newSettings.videoCodec != RtmpSettings.Default.VIDEO_CODEC)
                    set(RtmpSettings.Key.VIDEO_CODEC, newSettings.videoCodec)

                if (newSettings.videoH264Profile != RtmpSettings.Default.VIDEO_H264_PROFILE)
                    set(RtmpSettings.Key.VIDEO_H264_PROFILE, newSettings.videoH264Profile.name)

                if (newSettings.videoResizeFactor != RtmpSettings.Default.VIDEO_RESIZE_FACTOR) {
                    set(RtmpSettings.Key.VIDEO_RESIZE_FACTOR, newSettings.videoResizeFactor)
                }

                if (newSettings.videoFps != RtmpSettings.Default.VIDEO_FPS)
                    set(RtmpSettings.Key.VIDEO_FPS, newSettings.videoFps)

                if (newSettings.videoBitrateBits != RtmpSettings.Default.VIDEO_BITRATE)
                    set(RtmpSettings.Key.VIDEO_BITRATE, newSettings.videoBitrateBits)


                if (newSettings.audioCodecAutoSelect != RtmpSettings.Default.AUDIO_CODEC_AUTO_SELECT)
                    set(RtmpSettings.Key.AUDIO_CODEC_AUTO_SELECT, newSettings.audioCodecAutoSelect)

                if (newSettings.audioCodec != RtmpSettings.Default.AUDIO_CODEC)
                    set(RtmpSettings.Key.AUDIO_CODEC, newSettings.audioCodec)

                if (newSettings.audioBitrateBits != RtmpSettings.Default.AUDIO_BITRATE)
                    set(RtmpSettings.Key.AUDIO_BITRATE, newSettings.audioBitrateBits)

                if (newSettings.enableMic != RtmpSettings.Default.ENABLE_MIC)
                    set(RtmpSettings.Key.ENABLE_MIC, newSettings.enableMic)

                if (newSettings.muteMic != RtmpSettings.Default.MUTE_MIC)
                    set(RtmpSettings.Key.MUTE_MIC, newSettings.muteMic)

                if (newSettings.volumeMic != RtmpSettings.Default.VOLUME_MIC)
                    set(RtmpSettings.Key.VOLUME_MIC, newSettings.volumeMic)

                if (newSettings.enableDeviceAudio != RtmpSettings.Default.ENABLE_DEVICE_AUDIO)
                    set(RtmpSettings.Key.ENABLE_DEVICE_AUDIO, newSettings.enableDeviceAudio)

                if (newSettings.muteDeviceAudio != RtmpSettings.Default.MUTE_DEVICE_AUDIO)
                    set(RtmpSettings.Key.MUTE_DEVICE_AUDIO, newSettings.muteDeviceAudio)

                if (newSettings.volumeDeviceAudio != RtmpSettings.Default.VOLUME_DEVICE_AUDIO)
                    set(RtmpSettings.Key.VOLUME_DEVICE_AUDIO, newSettings.volumeDeviceAudio)

                if (newSettings.stereoAudio != RtmpSettings.Default.STEREO_AUDIO)
                    set(RtmpSettings.Key.STEREO_AUDIO, newSettings.stereoAudio)

                if (newSettings.audioEchoCanceller != RtmpSettings.Default.AUDIO_ECHO_CANCELLER)
                    set(RtmpSettings.Key.AUDIO_ECHO_CANCELLER, newSettings.audioEchoCanceller)

                if (newSettings.audioNoiseSuppressor != RtmpSettings.Default.AUDIO_NOISE_SUPPRESSOR)
                    set(RtmpSettings.Key.AUDIO_NOISE_SUPPRESSOR, newSettings.audioNoiseSuppressor)

                if (newSettings.interfaceFilter != RtmpSettings.Default.INTERFACE_FILTER)
                    set(RtmpSettings.Key.INTERFACE_FILTER, newSettings.interfaceFilter)

                if (newSettings.addressFilter != RtmpSettings.Default.ADDRESS_FILTER)
                    set(RtmpSettings.Key.ADDRESS_FILTER, newSettings.addressFilter)

                if (newSettings.enableIPv4 != RtmpSettings.Default.ENABLE_IPV4)
                    set(RtmpSettings.Key.ENABLE_IPV4, newSettings.enableIPv4)

                if (newSettings.enableIPv6 != RtmpSettings.Default.ENABLE_IPV6)
                    set(RtmpSettings.Key.ENABLE_IPV6, newSettings.enableIPv6)

                if (newSettings.serverPort != RtmpSettings.Default.SERVER_PORT)
                    set(RtmpSettings.Key.SERVER_PORT, newSettings.serverPort)

                if (newSettings.serverPath != RtmpSettings.Default.SERVER_PATH)
                    set(RtmpSettings.Key.SERVER_PATH, newSettings.serverPath)
            }
        }
        Unit
    }

    private fun Preferences.toRtmpSettings(): RtmpSettings.Data = RtmpSettings.Data(
        keepAwake = this[RtmpSettings.Key.KEEP_AWAKE] ?: RtmpSettings.Default.KEEP_AWAKE,
        stopOnSleep = this[RtmpSettings.Key.STOP_ON_SLEEP] ?: RtmpSettings.Default.STOP_ON_SLEEP,
        stopOnConfigurationChange = this[RtmpSettings.Key.STOP_ON_CONFIGURATION_CHANGE] ?: RtmpSettings.Default.STOP_ON_CONFIGURATION_CHANGE,

        serverAddress = this[RtmpSettings.Key.SERVER_ADDRESS] ?: RtmpSettings.Default.SERVER_ADDRESS,
        mode = runCatching {
            this[RtmpSettings.Key.MODE]?.let { name -> RtmpSettings.Values.Mode.valueOf(name) }
        }.getOrNull() ?: RtmpSettings.Default.MODE,

        videoCodecAutoSelect = this[RtmpSettings.Key.VIDEO_CODEC_AUTO_SELECT] ?: RtmpSettings.Default.VIDEO_CODEC_AUTO_SELECT,
        videoCodec = this[RtmpSettings.Key.VIDEO_CODEC] ?: RtmpSettings.Default.VIDEO_CODEC,
        videoH264Profile = runCatching {
            this[RtmpSettings.Key.VIDEO_H264_PROFILE]?.let { name -> RtmpSettings.Values.H264Profile.valueOf(name) }
        }.getOrNull() ?: RtmpSettings.Default.VIDEO_H264_PROFILE,
        videoResizeFactor = this[RtmpSettings.Key.VIDEO_RESIZE_FACTOR] ?: RtmpSettings.Default.VIDEO_RESIZE_FACTOR,
        videoFps = this[RtmpSettings.Key.VIDEO_FPS] ?: RtmpSettings.Default.VIDEO_FPS,
        videoBitrateBits = this[RtmpSettings.Key.VIDEO_BITRATE] ?: RtmpSettings.Default.VIDEO_BITRATE,

        audioCodecAutoSelect = this[RtmpSettings.Key.AUDIO_CODEC_AUTO_SELECT] ?: RtmpSettings.Default.AUDIO_CODEC_AUTO_SELECT,
        audioCodec = this[RtmpSettings.Key.AUDIO_CODEC] ?: RtmpSettings.Default.AUDIO_CODEC,
        audioBitrateBits = this[RtmpSettings.Key.AUDIO_BITRATE] ?: RtmpSettings.Default.AUDIO_BITRATE,
        enableMic = this[RtmpSettings.Key.ENABLE_MIC] ?: RtmpSettings.Default.ENABLE_MIC,
        muteMic = this[RtmpSettings.Key.MUTE_MIC] ?: RtmpSettings.Default.MUTE_MIC,
        volumeMic = this[RtmpSettings.Key.VOLUME_MIC] ?: RtmpSettings.Default.VOLUME_MIC,
        enableDeviceAudio = this[RtmpSettings.Key.ENABLE_DEVICE_AUDIO] ?: RtmpSettings.Default.ENABLE_DEVICE_AUDIO,
        muteDeviceAudio = this[RtmpSettings.Key.MUTE_DEVICE_AUDIO] ?: RtmpSettings.Default.MUTE_DEVICE_AUDIO,
        volumeDeviceAudio = this[RtmpSettings.Key.VOLUME_DEVICE_AUDIO] ?: RtmpSettings.Default.VOLUME_DEVICE_AUDIO,
        stereoAudio = this[RtmpSettings.Key.STEREO_AUDIO] ?: RtmpSettings.Default.STEREO_AUDIO,
        audioEchoCanceller = this[RtmpSettings.Key.AUDIO_ECHO_CANCELLER] ?: RtmpSettings.Default.AUDIO_ECHO_CANCELLER,
        audioNoiseSuppressor = this[RtmpSettings.Key.AUDIO_NOISE_SUPPRESSOR] ?: RtmpSettings.Default.AUDIO_NOISE_SUPPRESSOR,

        interfaceFilter = this[RtmpSettings.Key.INTERFACE_FILTER] ?: RtmpSettings.Default.INTERFACE_FILTER,
        addressFilter = this[RtmpSettings.Key.ADDRESS_FILTER] ?: RtmpSettings.Default.ADDRESS_FILTER,
        enableIPv4 = this[RtmpSettings.Key.ENABLE_IPV4] ?: RtmpSettings.Default.ENABLE_IPV4,
        enableIPv6 = this[RtmpSettings.Key.ENABLE_IPV6] ?: RtmpSettings.Default.ENABLE_IPV6,
        serverPort = this[RtmpSettings.Key.SERVER_PORT] ?: RtmpSettings.Default.SERVER_PORT,
        serverPath = this[RtmpSettings.Key.SERVER_PATH] ?: RtmpSettings.Default.SERVER_PATH,
    )
}
