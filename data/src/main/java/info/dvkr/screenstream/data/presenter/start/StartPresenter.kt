package info.dvkr.screenstream.data.presenter.start

import info.dvkr.screenstream.data.presenter.BasePresenter
import info.dvkr.screenstream.domain.eventbus.EventBus
import info.dvkr.screenstream.domain.globalstatus.GlobalStatus
import kotlinx.coroutines.experimental.ThreadPoolDispatcher
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.channels.actor
import timber.log.Timber

class StartPresenter internal constructor(private val actorContext: ThreadPoolDispatcher,
                                          private val eventBus: EventBus,
                                          private val globalStatus: GlobalStatus) : BasePresenter<StartView>() {

  private val actor: SendChannel<StartView.FromEvent>

  init {
    actor = actor(actorContext, Channel.UNLIMITED) {
      for (fromEvent in this) {
        // Events from StartActivity
        Timber.d("[${Thread.currentThread().name} @${this.hashCode()}] fromEvent: $fromEvent")

        when (fromEvent) {
        // Relaying message to FgPresenter
          is StartView.FromEvent.CurrentInterfacesRequest -> {
            eventBus.sendEvent(EventBus.GlobalEvent.CurrentInterfacesRequest())
          }

        // Sending message to StartActivity
          is StartView.FromEvent.TryStartStream -> {
            if (globalStatus.isStreamRunning.get()) return@actor
            globalStatus.error.set(null)
            view?.toEvent(StartView.ToEvent.TryToStart())
          }

        // Relaying message to FgPresenter
          is StartView.FromEvent.StopStream -> {
            if (!globalStatus.isStreamRunning.get()) return@actor
            eventBus.sendEvent(EventBus.GlobalEvent.StopStream())
          }

        // Relaying message to FgPresenter
          is StartView.FromEvent.AppExit -> {
            eventBus.sendEvent(EventBus.GlobalEvent.AppExit())
          }

        // Getting  Error
          is StartView.FromEvent.Error -> {
            globalStatus.error.set(fromEvent.error)
            view?.toEvent(StartView.ToEvent.Error(fromEvent.error))
          }

        // Getting current Error
          is StartView.FromEvent.GetError -> {
            view?.toEvent(StartView.ToEvent.Error(globalStatus.error.get()))
          }
        }
      }
    }
  }

  // Events from StartActivity to StartActivityPresenter
  fun offer(fromEvent: StartView.FromEvent) = actor.offer(fromEvent)

  override fun onCleared() {
    actor.close()
    super.onCleared()
  }

  override fun attach(newView: StartView) {
    Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] Attach")

    view?.let { detach() }
    view = newView

    // Global events
    eventBus.getEvent().filter {
      it is EventBus.GlobalEvent.StreamStatus ||
          it is EventBus.GlobalEvent.ResizeFactor ||
          it is EventBus.GlobalEvent.EnablePin ||
          it is EventBus.GlobalEvent.SetPin ||
          it is EventBus.GlobalEvent.CurrentClients ||
          it is EventBus.GlobalEvent.CurrentInterfaces ||
          it is EventBus.GlobalEvent.TrafficPoint
    }.subscribe { globalEvent ->
      Timber.d("[${Thread.currentThread().name} @${this.hashCode()}] globalEvent: $globalEvent")

      when (globalEvent) {
      // From ImageGeneratorImpl
        is EventBus.GlobalEvent.StreamStatus -> {
          view?.toEvent(StartView.ToEvent.OnStreamStartStop(globalStatus.isStreamRunning.get()))
        }

      // From SettingsPresenter
        is EventBus.GlobalEvent.ResizeFactor -> {
          view?.toEvent(StartView.ToEvent.ResizeFactor(globalEvent.value))
        }

      // From SettingsPresenter
        is EventBus.GlobalEvent.EnablePin -> {
          view?.toEvent(StartView.ToEvent.EnablePin(globalEvent.value))
        }

      // From SettingsPresenter
        is EventBus.GlobalEvent.SetPin -> {
          view?.toEvent(StartView.ToEvent.SetPin(globalEvent.value))
        }

      // From HttpServerImpl
        is EventBus.GlobalEvent.CurrentClients -> {
          view?.toEvent(StartView.ToEvent.CurrentClients(globalEvent.clientsList))
        }

      // From FgPresenter
        is EventBus.GlobalEvent.CurrentInterfaces -> {
          view?.toEvent(StartView.ToEvent.CurrentInterfaces(globalEvent.interfaceList))
        }

      // From HttpServerImpl
        is EventBus.GlobalEvent.TrafficPoint -> {
          view?.toEvent(StartView.ToEvent.TrafficPoint(globalEvent.trafficPoint))
        }
      }
    }.also { subscriptions.add(it) }

    // Sending current stream status
    view?.toEvent(StartView.ToEvent.StreamRunning(globalStatus.isStreamRunning.get()))

    // Requesting current clients
    eventBus.sendEvent(EventBus.GlobalEvent.CurrentClientsRequest())
  }
}