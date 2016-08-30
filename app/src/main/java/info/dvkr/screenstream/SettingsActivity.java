package info.dvkr.screenstream;


import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;
import android.widget.Toast;

public final class SettingsActivity extends PreferenceActivity {
    private static final int PIN_DIGITS_COUNT = 4;
    private static final int MIN_PORT_NUMBER = 1025;
    private static final int MAX_PORT_NUMBER = 65534;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction().replace(android.R.id.content, new ScreenStreamPreferenceFragment()).commit();
    }

    public static class ScreenStreamPreferenceFragment extends PreferenceFragment {
        int mIndex;

        @Override
        public void onCreate(final Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.preferences);

            final CheckBoxPreference newPinOnAppStartCheckBoxPreference = (CheckBoxPreference) findPreference(getString(R.string.pref_key_new_pin_on_app_start));
            final CheckBoxPreference autoChangePinCheckBoxPreference = (CheckBoxPreference) findPreference(getString(R.string.pref_key_auto_change_pin));
            final EditTextPreference setPinTextPreference = (EditTextPreference) findPreference(getString(R.string.pref_key_set_pin));

            newPinOnAppStartCheckBoxPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object data) {
                    final boolean newValue = (boolean) data;
                    setPinTextPreference.setEnabled((!newValue) && (!autoChangePinCheckBoxPreference.isChecked()));
                    return true;
                }
            });

            autoChangePinCheckBoxPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object data) {
                    final boolean newValue = (boolean) data;
                    setPinTextPreference.setEnabled((!newValue) && (!newPinOnAppStartCheckBoxPreference.isChecked()));
                    return true;
                }
            });

            setPinTextPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object data) {
                    final int pinStringLength = data.toString().length();
                    if (pinStringLength != PIN_DIGITS_COUNT) {
                        Toast.makeText(getActivity().getApplicationContext(), getString(R.string.pin_digits_count), Toast.LENGTH_LONG).show();
                        return false;
                    }
                    return true;
                }
            });

            setPinTextPreference.setEnabled((!autoChangePinCheckBoxPreference.isChecked()) && (!newPinOnAppStartCheckBoxPreference.isChecked()));

            final String portRange = String.format(getString(R.string.port_range), MIN_PORT_NUMBER, MAX_PORT_NUMBER);
            final EditTextPreference serverPortTextPreference = (EditTextPreference) findPreference(getString(R.string.pref_key_server_port));
            serverPortTextPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object data) {
                    final String portString = data.toString();
                    if (portString == null || portString.length() == 0 || portString.length() > 5 || portString.length() < 4) {
                        Toast.makeText(getActivity().getApplicationContext(), portRange, Toast.LENGTH_LONG).show();
                        return false;
                    }
                    final int portNumber = Integer.parseInt(portString);
                    if ((portNumber < MIN_PORT_NUMBER) || (portNumber > MAX_PORT_NUMBER)) {
                        Toast.makeText(getActivity().getApplicationContext(), portRange, Toast.LENGTH_LONG).show();
                        return false;
                    }
                    return true;
                }
            });

            final ListPreference jpegQualityPreference = (ListPreference) findPreference(getString(R.string.pref_key_jpeg_quality));
            mIndex = jpegQualityPreference.findIndexOfValue(jpegQualityPreference.getValue());
            jpegQualityPreference.setSummary(getString(R.string.settings_jpeg_quality_summary)
                    + getString(R.string.value) + jpegQualityPreference.getEntries()[mIndex]);
            jpegQualityPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object data) {
                    final int index = jpegQualityPreference.findIndexOfValue(data.toString());
                    jpegQualityPreference.setSummary(getString(R.string.settings_jpeg_quality_summary)
                            + getString(R.string.value) + jpegQualityPreference.getEntries()[index]);
                    return true;
                }
            });

            final ListPreference clientTimeoutPreference = (ListPreference) findPreference(getString(R.string.pref_key_client_con_timeout));
            mIndex = clientTimeoutPreference.findIndexOfValue(clientTimeoutPreference.getValue());
            clientTimeoutPreference.setSummary(getString(R.string.client_timeout_summary)
                    + getString(R.string.value) + clientTimeoutPreference.getEntries()[mIndex]);
            clientTimeoutPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object data) {
                    final int index = clientTimeoutPreference.findIndexOfValue(data.toString());
                    clientTimeoutPreference.setSummary(getString(R.string.client_timeout_summary)
                            + getString(R.string.value) + clientTimeoutPreference.getEntries()[index]);
                    return true;
                }
            });
        }
    }
}
