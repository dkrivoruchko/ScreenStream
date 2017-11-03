package info.dvkr.screenstream.dagger.module

import android.content.Context
import android.preference.PreferenceManager
import android.util.Log
import com.crashlytics.android.Crashlytics
import com.ironz.binaryprefs.BinaryPreferencesBuilder
import dagger.Module
import dagger.Provides
import info.dvkr.screenstream.data.BuildConfig
import info.dvkr.screenstream.data.settings.SettingsImpl
import info.dvkr.screenstream.domain.settings.Settings
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
        return SettingsImpl(preferences)
    }
}