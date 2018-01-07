package info.dvkr.screenstream.ui

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import info.dvkr.screenstream.domain.utils.Utils
import timber.log.Timber

abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("[${Utils.getLogPrefix(this)}] onCreate")
    }

    override fun onDestroy() {
        Timber.i("[${Utils.getLogPrefix(this)}] onDestroy")
        super.onDestroy()
    }
}