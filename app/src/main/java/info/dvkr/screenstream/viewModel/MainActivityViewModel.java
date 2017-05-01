package info.dvkr.screenstream.viewModel;

import android.content.Context;
import android.databinding.BaseObservable;
import android.databinding.Bindable;
import android.graphics.Point;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.widget.ToggleButton;

import org.greenrobot.eventbus.EventBus;

import info.dvkr.screenstream.BR;
import info.dvkr.screenstream.R;
import info.dvkr.screenstream.data.BusMessages;

import static java.util.Locale.US;

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
    private int mResizeFactor;
    private Point mScreenSize;

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

    public void setResizeFactor(final int resizeFactor) {
        mResizeFactor = resizeFactor;
        notifyPropertyChanged(BR.resizeText);
        notifyPropertyChanged(BR.resizeTextColor);
    }

    public void setScreenSize(final Point screenSize) {
        mScreenSize = screenSize;
        notifyPropertyChanged(BR.resizeText);
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

    @Bindable
    public String getResizeText() {
        final float scale = mResizeFactor / 10f;
        return mContext.getString(R.string.main_activity_resize_factor)
                + String.format(US, " %.1fx: ", mResizeFactor / 10f)
                + (int) (mScreenSize.x * scale)
                + "x"
                + (int) (mScreenSize.y * scale);
    }

    @Bindable
    public int getResizeTextColor() {
        return mResizeFactor > 10 ?
                ContextCompat.getColor(mContext, R.color.colorAccent) :
                ContextCompat.getColor(mContext, R.color.textColorSecondary);

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