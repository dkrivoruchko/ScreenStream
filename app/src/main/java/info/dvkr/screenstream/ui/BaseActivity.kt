package info.dvkr.screenstream.ui

import android.os.Bundle
import android.support.v7.app.AppCompatActivity

import info.dvkr.screenstream.ScreenStreamApp
import info.dvkr.screenstream.dagger.component.NonConfigurationComponent

abstract class BaseActivity : AppCompatActivity() {
    private lateinit var injector: NonConfigurationComponent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val savedInjector = lastCustomNonConfigurationInstance
        if (null == savedInjector) {
            injector = (application as ScreenStreamApp).appComponent().plusActivityComponent()
        } else {
            injector = savedInjector as NonConfigurationComponent
        }

        inject(injector)
    }

    abstract fun inject(injector: NonConfigurationComponent)

    override fun onRetainCustomNonConfigurationInstance(): Any = injector
}