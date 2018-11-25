package info.dvkr.screenstream.di

import com.ironz.binaryprefs.BinaryPreferencesBuilder
import com.jakewharton.rxrelay.BehaviorRelay
import info.dvkr.screenstream.data.image.ImageNotify
import info.dvkr.screenstream.data.presenter.clients.ClientsPresenter
import info.dvkr.screenstream.data.presenter.foreground.FgPresenter
import info.dvkr.screenstream.data.presenter.settings.SettingsPresenter
import info.dvkr.screenstream.data.presenter.start.StartPresenter
import info.dvkr.screenstream.data.settings.SettingsImpl
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.eventbus.EventBusImpl
import info.dvkr.screenstream.domain.globalstatus.GlobalStatus
import info.dvkr.screenstream.domain.globalstatus.GlobalStatusImpl
import info.dvkr.screenstream.domain.settings.Settings
import info.dvkr.screenstream.image.ImageNotifyImpl
import org.koin.android.ext.koin.androidApplication
import org.koin.android.viewmodel.ext.koin.viewModel
import org.koin.dsl.module.module
import timber.log.Timber

val koinModule = module {

    single { EventBusImpl() as EventBus }

    single { GlobalStatusImpl() as GlobalStatus }

    single { BehaviorRelay.create<ByteArray>() as BehaviorRelay }

    single { ImageNotifyImpl(androidApplication()) as ImageNotify }

    single {
        SettingsImpl(
            BinaryPreferencesBuilder(androidApplication())
                .exceptionHandler { Timber.e(it) }.build()
        ) as Settings
    }

    viewModel { StartPresenter(get(), get()) }

    viewModel { SettingsPresenter(get(), get()) }

    viewModel { ClientsPresenter(get()) }

    single { FgPresenter(get(), get(), get(), get()) }
}
