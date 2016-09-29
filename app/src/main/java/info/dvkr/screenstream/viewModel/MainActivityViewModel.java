package info.dvkr.screenstream.viewmodel;

import android.content.Context;
import android.databinding.BaseObservable;
import android.databinding.Bindable;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.widget.ToggleButton;

import org.greenrobot.eventbus.EventBus;

import info.dvkr.screenstream.BR;
import info.dvkr.screenstream.R;
import info.dvkr.screenstream.data.BusMessages;

import static info.dvkr.screenstream.ScreenStreamApplication.getAppData;

public final class MainActivityViewModel extends BaseObservable {
    private final Context mContext;

    private String mServerAddress;
    private boolean mPinEnabled;
    private boolean mPinAutoHide;
    private String mStreamPin;
    private boolean mIsStreaming;
    private boolean mWiFiConnected;
    private boolean mHttpServerError;
    private int mClients;

    public MainActivityViewModel(Context context) {
        this.mContext = context;
    }

    public void setServerAddress(final String serverAddress) {
        mServerAddress = serverAddress;
        notifyPropertyChanged(BR.serverAddressText);
    }

    public void setPinEnabled(final boolean pinEnabled) {
        mPinEnabled = pinEnabled;
        notifyPropertyChanged(BR.pinTitleText);
        notifyPropertyChanged(BR.pinTitleColor);
        notifyPropertyChanged(BR.pinVisibility);
    }

    public void setPinAutoHide(final boolean pinAutoHide) {
        mPinAutoHide = pinAutoHide;
        notifyPropertyChanged(BR.pinText);
    }

    public void setStreamPin(final String streamPin) {
        mStreamPin = streamPin;
        notifyPropertyChanged(BR.pinText);
    }

    public void setStreaming(final boolean streaming) {
        mIsStreaming = streaming;
        notifyPropertyChanged(BR.streaming);
        notifyPropertyChanged(BR.pinText);
    }

    public void setWiFiConnected(final boolean wiFiConnected) {
        mWiFiConnected = wiFiConnected;
        notifyPropertyChanged(BR.serverAddressText);
        notifyPropertyChanged(BR.serverAddressColor);
        notifyPropertyChanged(BR.toggleButtonEnabled);
    }

    public void setHttpServerError(final boolean httpServerError) {
        mHttpServerError = httpServerError;
        notifyPropertyChanged(BR.toggleButtonEnabled);
    }

    public void setClients(final int clients) {
        mClients = clients;
        notifyPropertyChanged(BR.connectedClientsText);
    }

    @Bindable
    public boolean isStreaming() {
        return mIsStreaming;
    }

    public boolean isWiFiConnected() {
        return mWiFiConnected;
    }

    @Bindable
    public String getServerAddressText() {
        return mWiFiConnected ?
                mServerAddress :
                mContext.getString(R.string.main_activity_no_wifi_connected);
    }

    @Bindable
    public int getServerAddressColor() {
        return mWiFiConnected ?
                ContextCompat.getColor(mContext, R.color.textColorSecondary) :
                ContextCompat.getColor(mContext, R.color.colorAccent);

    }

    @Bindable
    public String getPinTitleText() {
        return mPinEnabled ?
                mContext.getString(R.string.main_activity_pin) :
                mContext.getString(R.string.main_activity_pin_disabled);
    }

    @Bindable
    public int getPinTitleColor() {
        return mPinEnabled ?
                ContextCompat.getColor(mContext, R.color.textColorSecondary) :
                ContextCompat.getColor(mContext, R.color.colorPrimary);

    }

    @Bindable
    public String getPinText() {
        return (mIsStreaming && mPinAutoHide) ?
                mContext.getString(R.string.main_activity_pin_asterisks) :
                mStreamPin;
    }

    @Bindable
    public int getPinVisibility() {
        return mPinEnabled ? View.VISIBLE : View.GONE;
    }

    @Bindable
    public boolean getToggleButtonEnabled() {
        return mWiFiConnected && !mHttpServerError;
    }

    @Bindable
    public String getConnectedClientsText() {
        return String.format(mContext.getString(R.string.main_activity_connected_clients), mClients);
    }

    public void onToggleButtonClick(View v) {
        if (mIsStreaming) {
            EventBus.getDefault().post(new BusMessages(BusMessages.MESSAGE_ACTION_STREAMING_STOP));
        } else {
            ((ToggleButton) v).setChecked(false);
            EventBus.getDefault().post(new BusMessages(BusMessages.MESSAGE_ACTION_STREAMING_TRY_START));
        }
    }
}
