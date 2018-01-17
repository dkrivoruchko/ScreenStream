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
import kotlinx.coroutines.experimental.CoroutineExceptionHandler
import kotlinx.coroutines.experimental.newSingleThreadContext
import org.koin.android.architecture.ext.viewModel
import org.koin.android.ext.koin.androidApplication
import org.koin.dsl.module.applicationContext
import timber.log.Timber
import kotlin.coroutines.experimental.CoroutineContext

val koinModule = applicationContext {

    bean {
        newSingleThreadContext("SSEventContext") +
                CoroutineExceptionHandler { _, ex -> // TODO() Temp Solution
                    Timber.e(ex)
                    throw ex
                } as CoroutineContext
    }

    bean { EventBusImpl() as EventBus }

    bean { GlobalStatusImpl() as GlobalStatus }

    bean { BehaviorRelay.create<ByteArray>() as BehaviorRelay }

    bean { ImageNotifyImpl(androidApplication()) as ImageNotify }

    bean { SettingsImpl(BinaryPreferencesBuilder(androidApplication()).exceptionHandler { Timber.e(it) }.build()) as Settings }

    viewModel { StartPresenter(get(), get(), get()) }

    viewModel { SettingsPresenter(get(), get(), get()) }

    viewModel { ClientsPresenter(get(), get()) }

    bean { FgPresenter(get(), get(), get(), get(), get()) }
}
