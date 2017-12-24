package info.dvkr.screenstream.data.presenter

import android.arch.lifecycle.ViewModel
import android.arch.lifecycle.ViewModelProvider
import info.dvkr.screenstream.data.presenter.clients.ClientsPresenter
import info.dvkr.screenstream.data.presenter.settings.SettingsPresenter
import info.dvkr.screenstream.data.presenter.start.StartPresenter
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.globalstatus.GlobalStatus
import info.dvkr.screenstream.domain.settings.Settings
import kotlinx.coroutines.experimental.ThreadPoolDispatcher
import rx.Scheduler


class PresenterFactory constructor(private val settings: Settings,
                                   private val eventScheduler: Scheduler,
                                   private val actorContext: ThreadPoolDispatcher,
                                   private val eventBus: EventBus,
                                   private val globalStatus: GlobalStatus) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StartPresenter::class.java)) {
            return StartPresenter(actorContext, eventBus, globalStatus) as T
        }

        if (modelClass.isAssignableFrom(SettingsPresenter::class.java)) {
            return SettingsPresenter(settings, eventScheduler, eventBus) as T
        }

        if (modelClass.isAssignableFrom(ClientsPresenter::class.java)) {
            return ClientsPresenter(eventScheduler, eventBus) as T
        }

        throw  IllegalArgumentException("Unknown Presenter class")
    }
}