package info.dvkr.screenstream.di

import android.os.HandlerThread
import com.ironz.binaryprefs.BinaryPreferencesBuilder
import com.ironz.binaryprefs.Preferences
import com.jakewharton.rxrelay.BehaviorRelay
import info.dvkr.screenstream.data.image.ImageNotify
import info.dvkr.screenstream.data.presenter.PresenterFactory
import info.dvkr.screenstream.data.presenter.foreground.ForegroundPresenter
import info.dvkr.screenstream.data.settings.SettingsImpl
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.eventbus.EventBusImpl
import info.dvkr.screenstream.domain.globalstatus.GlobalStatus
import info.dvkr.screenstream.domain.globalstatus.GlobalStatusImpl
import info.dvkr.screenstream.domain.settings.Settings
import info.dvkr.screenstream.image.ImageNotifyImpl
import org.koin.android.module.AndroidModule
import rx.Scheduler
import rx.android.schedulers.AndroidSchedulers
import timber.log.Timber

class KoinModule : AndroidModule() {
    private val eventThread = HandlerThread("SSEventThread")
    private val eventScheduler: Scheduler

    init {
        eventThread.start()
        eventScheduler = AndroidSchedulers.from(eventThread.looper)
    }

    override fun context() = applicationContext {

        provide { eventScheduler } bind (Scheduler::class)

        provide { EventBusImpl(get()) } bind (EventBus::class)

        provide { GlobalStatusImpl() } bind (GlobalStatus::class)

        provide { BehaviorRelay.create<ByteArray>() } bind (BehaviorRelay::class)

        provide { ImageNotifyImpl(androidApplication) } bind (ImageNotify::class)

        provide {
            BinaryPreferencesBuilder(androidApplication)
                    .exceptionHandler { Timber.e(it, "BinaryPreferencesBuilder") }
                    .build()
        } bind (Preferences::class)

        provide { SettingsImpl(get()) } bind (Settings::class)

        provide { PresenterFactory(get(), get(), get(), get()) } bind (PresenterFactory::class)

        provide { ForegroundPresenter(get(), get(), get(), get(), get()) } bind (ForegroundPresenter::class)

    }
}