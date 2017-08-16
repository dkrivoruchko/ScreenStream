package info.dvkr.screenstream.dagger.module

import android.content.Context
import android.graphics.Point
import android.preference.PreferenceManager
import android.util.Log
import android.view.WindowManager
import com.crashlytics.android.Crashlytics
import com.f2prateek.rx.preferences.RxSharedPreferences
import com.ironz.binaryprefs.BinaryPreferencesBuilder
import dagger.Module
import dagger.Provides
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.model.Settings
import info.dvkr.screenstream.model.settings.SettingsImpl
import javax.inject.Singleton


@Singleton
@Module(includes = arrayOf(AppModule::class))
class SettingsModule {

    @Provides
    @Singleton
    internal fun getSettingsHelper(context: Context): Settings {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().apply()
        val preferences = BinaryPreferencesBuilder(context)
                .exceptionHandler {
                    it?.let {
                        if (BuildConfig.DEBUG_MODE) Log.e("BinaryPreferences", it.toString())
                        Crashlytics.logException(it)
                    }
                }
                .build()

        val defaultDisplay = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val screenSize = Point()
        defaultDisplay.getRealSize(screenSize)
        val megaPixels = (screenSize.x * screenSize.y) / 1_000_000.0

        return SettingsImpl(RxSharedPreferences.create(preferences), megaPixels)
    }
}