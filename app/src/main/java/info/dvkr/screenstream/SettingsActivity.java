package info.dvkr.screenstream;


import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;
import android.widget.Toast;

public final class SettingsActivity extends PreferenceActivity {
    private static final int minPortNumber = 1025;
    private static final int maxPortNumber = 65534;

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

            final String portRange = String.format(getResources().getString(R.string.port_range), minPortNumber, maxPortNumber);
            final EditTextPreference portNumberTextPreference = (EditTextPreference) findPreference("port_number");
            portNumberTextPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object data) {
                    final int portNumber = Integer.parseInt((String) data);
                    if ((portNumber < minPortNumber) || (portNumber > maxPortNumber)) {
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
