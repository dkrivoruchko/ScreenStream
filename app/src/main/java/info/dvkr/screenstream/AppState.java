package info.dvkr.screenstream;

import android.databinding.ObservableBoolean;
import android.databinding.ObservableField;
import android.databinding.ObservableInt;

public final class AppState {
    public final ObservableField<String> serverAddress = new ObservableField<>();
    public final ObservableBoolean pinEnabled = new ObservableBoolean();
    public final ObservableBoolean pinAutoHide = new ObservableBoolean();
    public final ObservableField<String> streamPin = new ObservableField<>();
    public final ObservableBoolean streaming = new ObservableBoolean();
    public final ObservableBoolean wifiConnected = new ObservableBoolean();
    public final ObservableBoolean httpServerError = new ObservableBoolean();
    public final ObservableInt clients = new ObservableInt();
}
