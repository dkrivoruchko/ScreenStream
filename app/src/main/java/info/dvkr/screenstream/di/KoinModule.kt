package info.dvkr.screenstream.di

import android.os.HandlerThread
import com.ironz.binaryprefs.BinaryPreferencesBuilder
import com.jakewharton.rxrelay.BehaviorRelay
import info.dvkr.screenstream.data.image.ImageNotify
import info.dvkr.screenstream.data.presenter.PresenterFactory
import info.dvkr.screenstream.data.presenter.foreground.FgPresenter
import info.dvkr.screenstream.data.settings.SettingsImpl
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.eventbus.EventBusImpl
import info.dvkr.screenstream.domain.globalstatus.GlobalStatus
import info.dvkr.screenstream.domain.globalstatus.GlobalStatusImpl
import info.dvkr.screenstream.domain.settings.Settings
import info.dvkr.screenstream.image.ImageNotifyImpl
import kotlinx.coroutines.experimental.newSingleThreadContext
import org.koin.android.ext.koin.androidApplication
import org.koin.dsl.module.applicationContext
import rx.Scheduler
import rx.android.schedulers.AndroidSchedulers
import timber.log.Timber

val koinModule = applicationContext {
  val scheduler = object {
    private val eventThread = HandlerThread("SSEventThread")
    val eventScheduler: Scheduler

    init {
      eventThread.start()
      eventScheduler = AndroidSchedulers.from(eventThread.looper)
    }
  }

  bean { scheduler.eventScheduler }

  bean { newSingleThreadContext("SSEventContext") } // TODO Release

  bean { EventBusImpl(get()) as EventBus }

  bean { GlobalStatusImpl() as GlobalStatus }

  bean { BehaviorRelay.create<ByteArray>() as BehaviorRelay }

  bean { ImageNotifyImpl(androidApplication()) as ImageNotify }

  bean { SettingsImpl(BinaryPreferencesBuilder(androidApplication()).exceptionHandler { Timber.e(it) }.build()) as Settings }

  bean { PresenterFactory(get(), get(), get(), get(), get()) }

  bean { FgPresenter(get(), get(), get(), get(), get()) }
}
