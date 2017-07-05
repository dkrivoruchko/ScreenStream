package info.dvkr.screenstream.dagger.module

import android.content.Context
import android.preference.PreferenceManager
import com.f2prateek.rx.preferences.RxSharedPreferences
import dagger.Module
import dagger.Provides
import info.dvkr.screenstream.model.Settings
import info.dvkr.screenstream.model.settings.SettingsImpl
import javax.inject.Singleton

@Singleton
@Module(includes = arrayOf(AppModule::class))
class SettingsModule {

    @Provides
    @Singleton
    internal fun getSettingsHelper(context: Context): Settings {
        //TODO PreferenceManager.getDefaultSharedPreferences(this).edit().clear().commit();
        val rxSharedPreferences = RxSharedPreferences.create(PreferenceManager.getDefaultSharedPreferences(context))
        return SettingsImpl(rxSharedPreferences)
    }
}