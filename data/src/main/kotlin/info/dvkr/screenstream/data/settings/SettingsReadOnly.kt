package info.dvkr.screenstream.data.settings

import kotlinx.coroutines.flow.Flow

interface SettingsReadOnly {
    val nightModeFlow: Flow<Int>

    val keepAwakeFlow: Flow<Boolean>
    val stopOnSleepFlow: Flow<Boolean>
    val startOnBootFlow: Flow<Boolean>
    val autoStartStopFlow: Flow<Boolean>
    val notifySlowConnectionsFlow: Flow<Boolean>
    val htmlEnableButtonsFlow: Flow<Boolean>
    val htmlShowPressStartFlow: Flow<Boolean>
    val htmlBackColorFlow: Flow<Int>

    val vrModeFlow: Flow<Int>
    val imageCropFlow: Flow<Boolean>
    val imageCropTopFlow: Flow<Int>
    val imageCropBottomFlow: Flow<Int>
    val imageCropLeftFlow: Flow<Int>
    val imageCropRightFlow: Flow<Int>
    val imageGrayscaleFlow: Flow<Boolean>
    val jpegQualityFlow: Flow<Int>
    val resizeFactorFlow: Flow<Int>
    val rotationFlow: Flow<Int>
    val maxFPSFlow: Flow<Int>

    val enablePinFlow: Flow<Boolean>
    val hidePinOnStartFlow: Flow<Boolean>
    val newPinOnAppStartFlow: Flow<Boolean>
    val autoChangePinFlow: Flow<Boolean>
    val pinFlow: Flow<String>
    val blockAddressFlow: Flow<Boolean>

    val useWiFiOnlyFlow: Flow<Boolean>
    val enableIPv6Flow: Flow<Boolean>
    val enableLocalHostFlow: Flow<Boolean>
    val localHostOnlyFlow: Flow<Boolean>
    val serverPortFlow: Flow<Int>

    val loggingVisibleFlow: Flow<Boolean>

    val lastUpdateRequestMillisFlow: Flow<Long>
}