package info.dvkr.screenstream.ui

import android.os.Bundle
import android.support.v7.app.AppCompatActivity

import info.dvkr.screenstream.ScreenStreamApp
import info.dvkr.screenstream.dagger.component.NonConfigurationComponent

abstract class BaseActivity : AppCompatActivity() {
    private lateinit var mInjector: NonConfigurationComponent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val savedInjector = lastCustomNonConfigurationInstance
        if (null == savedInjector) {
            mInjector = (application as ScreenStreamApp).appComponent().plusActivityComponent()
        } else {
            mInjector = savedInjector as NonConfigurationComponent
        }

        inject(mInjector)
    }

    abstract fun inject(injector: NonConfigurationComponent)

    override fun onRetainCustomNonConfigurationInstance(): Any = mInjector
}