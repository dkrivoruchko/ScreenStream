package info.dvkr.screenstream.view;


import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import info.dvkr.screenstream.R;

import static info.dvkr.screenstream.ScreenStreamApplication.getAppPreference;
import static java.util.Locale.US;

public final class SettingsActivity extends PreferenceActivity {
    private static final int PIN_DIGITS_COUNT = 4;
    private static final int MIN_PORT_NUMBER = 1025;
    private static final int MAX_PORT_NUMBER = 65534;

    public static Intent getStartIntent(Context context) {
        return new Intent(context, SettingsActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction().replace(android.R.id.content, new ScreenStreamPreferenceFragment()).commit();
    }

    public static class ScreenStreamPreferenceFragment extends PreferenceFragment {
        int mResizeFactor;
        int mIndex;

        private void setResizeFactor(final int resizeFactor, final TextView resizeFactorText) {
            mResizeFactor = resizeFactor;
            resizeFactorText.setText(String.format(US, "%.1fx", mResizeFactor / 10f));
            if (mResizeFactor > 10) {
                resizeFactorText.setTextColor(ContextCompat.getColor(getActivity(), R.color.colorAccent));
                resizeFactorText.setTypeface(resizeFactorText.getTypeface(), Typeface.BOLD);
            } else {
                resizeFactorText.setTextColor(ContextCompat.getColor(getActivity(), R.color.textColorSecondary));
                resizeFactorText.setTypeface(Typeface.DEFAULT);
            }
        }

        @Override
        public void onCreate(final Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.preferences);

            // Image
            final Preference resizePreference = findPreference(getString(R.string.pref_key_resize_factor));
            resizePreference.setSummary(getString(R.string.pref_resize_summary)
                    + getString(R.string.settings_activity_value)
                    + String.format(US, "%.1fx", getAppPreference().getResizeFactor() / 10f));
            resizePreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(final Preference preference) {
                    final LayoutInflater layoutInflater = LayoutInflater.from(getActivity());
                    final View resizeView = layoutInflater.inflate(R.layout.pref_resize, null);
                    final TextView resizeFactor = (TextView) resizeView.findViewById(R.id.pref_resize_dialog_textView);
                    setResizeFactor(getAppPreference().getResizeFactor(), resizeFactor);

                    final SeekBar seekBar = (SeekBar) resizeView.findViewById(R.id.pref_resize_dialog_seekBar);
                    seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(final SeekBar seekBar, final int progress, final boolean fromUser) {
                            setResizeFactor(progress + 1, resizeFactor);
                        }

                        @Override
                        public void onStartTrackingTouch(final SeekBar seekBar) {

                        }

                        @Override
                        public void onStopTrackingTouch(final SeekBar seekBar) {

                        }
                    });
                    seekBar.setProgress(mResizeFactor - 1);

                    new AlertDialog.Builder(getActivity())
                            .setView(resizeView)
                            .setCancelable(true)
                            .setIcon(R.drawable.ic_pref_resize_black_24dp)
                            .setTitle(R.string.pref_resize)
                            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(final DialogInterface dialog, final int which) {
                                    getAppPreference().setResizeFactor(mResizeFactor);
                                    resizePreference.setSummary(getString(R.string.pref_resize_summary)
                                            + getString(R.string.settings_activity_value)
                                            + String.format(US, "%.1fx", getAppPreference().getResizeFactor() / 10f));
                                    dialog.dismiss();
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(final DialogInterface dialog, final int which) {
                                    dialog.cancel();
                                }
                            }).create().show();

                    return true;
                }
            });

            final ListPreference jpegQualityPreference = (ListPreference) findPreference(getString(R.string.pref_key_jpeg_quality));
            mIndex = jpegQualityPreference.findIndexOfValue(jpegQualityPreference.getValue());
            jpegQualityPreference.setSummary(getString(R.string.pref_jpeg_quality_summary)
                    + getString(R.string.settings_activity_value) + jpegQualityPreference.getEntries()[mIndex]);
            jpegQualityPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object data) {
                    final int index = jpegQualityPreference.findIndexOfValue(data.toString());
                    jpegQualityPreference.setSummary(getString(R.string.pref_jpeg_quality_summary)
                            + getString(R.string.settings_activity_value) + jpegQualityPreference.getEntries()[index]);
                    return true;
                }
            });

            // Security
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
                        Toast.makeText(getActivity().getApplicationContext(), getString(R.string.settings_activity_pin_digits_count), Toast.LENGTH_LONG).show();
                        return false;
                    }
                    return true;
                }
            });

            setPinTextPreference.setEnabled((!autoChangePinCheckBoxPreference.isChecked()) && (!newPinOnAppStartCheckBoxPreference.isChecked()));


            // Advanced
            final String portRange = String.format(getString(R.string.settings_activity_port_range), MIN_PORT_NUMBER, MAX_PORT_NUMBER);
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

            final ListPreference clientTimeoutPreference = (ListPreference) findPreference(getString(R.string.pref_key_client_con_timeout));
            mIndex = clientTimeoutPreference.findIndexOfValue(clientTimeoutPreference.getValue());
            clientTimeoutPreference.setSummary(getString(R.string.pref_client_timeout_summary)
                    + getString(R.string.settings_activity_value) + clientTimeoutPreference.getEntries()[mIndex]);
            clientTimeoutPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object data) {
                    final int index = clientTimeoutPreference.findIndexOfValue(data.toString());
                    clientTimeoutPreference.setSummary(getString(R.string.pref_client_timeout_summary)
                            + getString(R.string.settings_activity_value) + clientTimeoutPreference.getEntries()[index]);
                    return true;
                }
            });
        }
    }
}