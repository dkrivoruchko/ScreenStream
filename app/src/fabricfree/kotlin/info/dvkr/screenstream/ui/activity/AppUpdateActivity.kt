package info.dvkr.screenstream.ui.activity

import androidx.appcompat.app.AppCompatActivity
import info.dvkr.screenstream.data.settings.Settings
import org.koin.android.ext.android.inject

abstract class AppUpdateActivity : AppCompatActivity() {

    protected val settings: Settings by inject()


}