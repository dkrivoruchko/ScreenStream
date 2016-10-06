package info.dvkr.screenstream;

import android.app.Application;

import info.dvkr.screenstream.data.AppData;
import info.dvkr.screenstream.data.local.PreferencesHelper;
import info.dvkr.screenstream.service.ForegroundService;
import info.dvkr.screenstream.viewmodel.MainActivityViewModel;


public class ScreenStreamApplication extends Application {
    private static ScreenStreamApplication sAppInstance;

    private AppData mAppData;
    private MainActivityViewModel mMainActivityViewModel;
    private PreferencesHelper mPreferencesHelper;

    @Override
    public void onCreate() {
        super.onCreate();
        sAppInstance = this;

        mAppData = new AppData(this);
        mMainActivityViewModel = new MainActivityViewModel(this);
        mPreferencesHelper = new PreferencesHelper(this);

        startService(ForegroundService.getStartIntent(this));
    }

    public static MainActivityViewModel getMainActivityViewModel() {
        return sAppInstance.mMainActivityViewModel;
    }

    public static AppData getAppData() {
        return sAppInstance.mAppData;
    }

    public static PreferencesHelper getAppPreference() {
        return sAppInstance.mPreferencesHelper;
    }
}