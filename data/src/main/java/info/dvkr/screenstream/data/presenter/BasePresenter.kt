package info.dvkr.screenstream.data.presenter

import android.arch.lifecycle.ViewModel
import rx.subscriptions.CompositeSubscription
import timber.log.Timber

abstract class BasePresenter<T> : ViewModel() {

  protected val subscriptions = CompositeSubscription()
  protected var view: T? = null

  init {
    Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] Init")
  }

  abstract fun attach(newView: T)

  fun detach() {
    Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] Detach")
    subscriptions.clear()
    view = null
  }
}