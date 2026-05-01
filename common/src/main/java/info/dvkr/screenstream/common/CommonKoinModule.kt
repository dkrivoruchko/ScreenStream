package info.dvkr.screenstream.common

import info.dvkr.screenstream.common.module.StreamingModuleManager
import info.dvkr.screenstream.common.settings.AppSettings
import info.dvkr.screenstream.common.settings.AppSettingsImpl
import info.dvkr.screenstream.common.ui.mediaprojection.ScreenCapturePermissionViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

public val CommonKoinModule: Module = module {
    single(createdAtStart = true) { AppSettingsImpl(get()) } bind (AppSettings::class)
    single { StreamingModuleManager(getAll(), get()) }
    viewModelOf(::ScreenCapturePermissionViewModel)
}
