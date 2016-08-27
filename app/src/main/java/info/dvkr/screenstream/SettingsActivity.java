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
        int index;

        @Override
        public void onCreate(final Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.preferences);

            final CheckBoxPreference pinNewOnAppStartCheckBoxPreference = (CheckBoxPreference) findPreference("pin_new_on_app_start");
            final CheckBoxPreference pinAutoGenerateCheckBoxPreference = (CheckBoxPreference) findPreference("pin_change_on_start");
            final EditTextPreference pinNumberTextPreference = (EditTextPreference) findPreference("pin_manual");

            pinNewOnAppStartCheckBoxPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object data) {
                    final boolean newValue = (boolean) data;
                    pinNumberTextPreference.setEnabled((!newValue) && (!pinAutoGenerateCheckBoxPreference.isChecked()));
                    return true;
                }
            });

            pinAutoGenerateCheckBoxPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object data) {
                    final boolean newValue = (boolean) data;
                    pinNumberTextPreference.setEnabled((!newValue) && (!pinNewOnAppStartCheckBoxPreference.isChecked()));
                    return true;
                }
            });

            pinNumberTextPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object data) {
                    final int pinStringLength = data.toString().length();
                    if (pinStringLength != PIN_DIGITS_COUNT) {
                        Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.pin_digits_count), Toast.LENGTH_LONG).show();
                        return false;
                    }
                    return true;
                }
            });

            pinNumberTextPreference.setEnabled((!pinAutoGenerateCheckBoxPreference.isChecked()) && (!pinNewOnAppStartCheckBoxPreference.isChecked()));

            // Advanced

            final String portRange = String.format(getResources().getString(R.string.port_range), MIN_PORT_NUMBER, MAX_PORT_NUMBER);
            final EditTextPreference portNumberTextPreference = (EditTextPreference) findPreference("port_number");
            portNumberTextPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
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

            final ListPreference jpegQualityPreference = (ListPreference) findPreference("jpeg_quality");
            index = jpegQualityPreference.findIndexOfValue(jpegQualityPreference.getValue());
            jpegQualityPreference.setSummary(getResources().getString(R.string.settings_jpeg_quality_summary)
                    + getResources().getString(R.string.value) + jpegQualityPreference.getEntries()[index]);
            jpegQualityPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object data) {
                    int index = jpegQualityPreference.findIndexOfValue(data.toString());
                    jpegQualityPreference.setSummary(getResources().getString(R.string.settings_jpeg_quality_summary)
                            + getResources().getString(R.string.value) + jpegQualityPreference.getEntries()[index]);
                    return true;
                }
            });

            final ListPreference clientTimeoutPreference = (ListPreference) findPreference("client_connection_timeout");
            index = clientTimeoutPreference.findIndexOfValue(clientTimeoutPreference.getValue());
            clientTimeoutPreference.setSummary(getResources().getString(R.string.client_timeout_summary)
                    + getResources().getString(R.string.value) + clientTimeoutPreference.getEntries()[index]);
            clientTimeoutPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object data) {
                    int index = clientTimeoutPreference.findIndexOfValue(data.toString());
                    clientTimeoutPreference.setSummary(getResources().getString(R.string.client_timeout_summary)
                            + getResources().getString(R.string.value) + clientTimeoutPreference.getEntries()[index]);
                    return true;
                }
            });
        }
    }
}
