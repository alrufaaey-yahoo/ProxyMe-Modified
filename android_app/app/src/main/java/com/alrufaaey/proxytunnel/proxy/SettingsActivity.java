package com.alrufaaey.proxytunnel.proxy;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.SearchView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.alrufaaey.proxytunnel.R;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "SettingsActivity";
    private static final String TITLE_TAG = "Settings";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.activity_settings, new SettingsFragment(), "preference_root")
                .commit();
        } else {
            setTitle(savedInstanceState.getCharSequence(TITLE_TAG));
        }
        getSupportFragmentManager().addOnBackStackChangedListener(new FragmentManager.OnBackStackChangedListener() {
            @Override
            public void onBackStackChanged() {
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                setTitle(R.string.title_activity_settings);
            }
            }
        });
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putCharSequence(TITLE_TAG, getTitle());
    }

    @Override
    public boolean onSupportNavigateUp() {
        if (getSupportFragmentManager().popBackStackImmediate()) {
            return true;
        }
        return super.onSupportNavigateUp();
    }

    /**
     * Inner Classes.
     */

    public static class SettingsFragment extends PreferenceFragmentCompat implements Preference.OnPreferenceClickListener {
        public static final String VPN_CONNECTION_MODE = "vpn_connection_mode";
        public static final String VPN_DISALLOWED_APPLICATION_LIST = "vpn_disallowed_application_list";
        public static final String VPN_ALLOWED_APPLICATION_LIST = "vpn_allowed_application_list";
        public static final String VPN_CLEAR_ALL_SELECTION = "vpn_clear_all_selection";

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            addPreferencesFromResource(R.xml.preferences);
            setHasOptionsMenu(true);

            /* Allowed / Disallowed Application */
            final ListPreference prefPackage = findPreference(VPN_CONNECTION_MODE);
            final PreferenceScreen prefDisallow = findPreference(VPN_DISALLOWED_APPLICATION_LIST);
            final PreferenceScreen prefAllow = findPreference(VPN_ALLOWED_APPLICATION_LIST);
            final PreferenceScreen clearAllSelection = findPreference(VPN_CLEAR_ALL_SELECTION);
            clearAllSelection.setOnPreferenceClickListener(this);

            prefPackage.setOnPreferenceChangeListener((preference, value) -> {
            if (preference instanceof ListPreference) {
                final ListPreference listPreference = (ListPreference) preference;
                int index = listPreference.findIndexOfValue((String) value);
                prefDisallow.setEnabled(index == MyApplication.VPNMode.DISALLOW.ordinal());
                prefAllow.setEnabled(index == MyApplication.VPNMode.ALLOW.ordinal());

                // Set the summary to reflect the new value.
                preference.setSummary(index >= 0 ? listPreference.getEntries()[index] : null);

                MyApplication.VPNMode mode =  MyApplication.VPNMode.values()[index];
                MyApplication.getInstance().storeVPNMode(mode);
            }
            return true;
            });
            prefPackage.setSummary(prefPackage.getEntry());
            prefDisallow.setEnabled(MyApplication.VPNMode.DISALLOW.name().equals(prefPackage.getValue()));
            prefAllow.setEnabled(MyApplication.VPNMode.ALLOW.name().equals(prefPackage.getValue()));

            updateMenuItem();
        }

        private void updateMenuItem() {
            final PreferenceScreen prefDisallow = findPreference(VPN_DISALLOWED_APPLICATION_LIST);
            final PreferenceScreen prefAllow = findPreference(VPN_ALLOWED_APPLICATION_LIST);

            int countDisallow = MyApplication.getInstance().loadVPNApplication(MyApplication.VPNMode.DISALLOW).size();
            int countAllow = MyApplication.getInstance().loadVPNApplication(MyApplication.VPNMode.ALLOW).size();
            prefDisallow.setTitle(getString(R.string.pref_header_disallowed_application_list) + String.format(" (%d)", countDisallow));
            prefAllow.setTitle(getString(R.string.pref_header_allowed_application_list) + String.format(" (%d)", countAllow));
        }

        /*
         * https://developer.android.com/guide/topics/ui/settings/organize-your-settings
         */

        // リスナー部分
        @Override
        public boolean onPreferenceClick(Preference preference) {
            // keyを見てクリックされたPreferenceを特定
            switch (preference.getKey()) {
                case VPN_DISALLOWED_APPLICATION_LIST:
                case VPN_ALLOWED_APPLICATION_LIST:
                    break;
                case VPN_CLEAR_ALL_SELECTION:
                    new AlertDialog.Builder(getActivity())
                        .setTitle(getString(R.string.title_activity_settings))
                        .setMessage(getString(R.string.pref_dialog_clear_all_application_msg))
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Set<String> set = new HashSet<>();
                                MyApplication.getInstance().storeVPNApplication(MyApplication.VPNMode.ALLOW, set);
                                MyApplication.getInstance().storeVPNApplication(MyApplication.VPNMode.DISALLOW, set);
                                updateMenuItem();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                    break;
            }
            return false;
        }

    }

    public static class DisallowedPackageListFragment extends PackageListFragment {
        public DisallowedPackageListFragment() {
            super(MyApplication.VPNMode.DISALLOW);
        }
    }

    public static class AllowedPackageListFragment extends PackageListFragment  {
        public AllowedPackageListFragment() {
            super(MyApplication.VPNMode.ALLOW);
        }
    }

    protected abstract static class PackageListFragment extends PreferenceFragmentCompat
            implements SearchView.OnQueryTextListener, SearchView.OnCloseListener {
        private final Map<String, Boolean> mAllPackageInfoMap = new HashMap<>();
        private final static String PREF_VPN_APPLICATION_ORDERBY = "pref_vpn_application_app_orderby";
        private final static String PREF_VPN_APPLICATION_FILTERBY = "pref_vpn_application_app_filterby";
        private final static String PREF_VPN_APPLICATION_SORTBY = "pref_vpn_application_app_sortby";

        private AsyncTaskProgress task;

        private MyApplication.VPNMode mode;
        private MyApplication.AppSortBy appSortBy = MyApplication.AppSortBy.APPNAME;
        private MyApplication.AppOrderBy appOrderBy = MyApplication.AppOrderBy.ASC;
        private MyApplication.AppSortBy appFilterBy = MyApplication.AppSortBy.APPNAME;
        private PreferenceScreen mFilterPreferenceScreen;
        private SearchView searchView;

        public PackageListFragment(MyApplication.VPNMode mode) {
            super();
            this.mode = mode;
            this.task = new AsyncTaskProgress(this);
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setHasOptionsMenu(true);
            mFilterPreferenceScreen = getPreferenceManager().createPreferenceScreen(getActivity());
            setPreferenceScreen(mFilterPreferenceScreen);
        }

        @Override
        public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
            super.onCreateOptionsMenu(menu, inflater);
            // Menuの設定
            inflater.inflate(R.menu.menu_search, menu);
            //MenuCompat.setGroupDividerEnabled(menu, true);

            final MenuItem menuSearch = menu.findItem(R.id.menu_search_item);
            this.searchView = (SearchView) menuSearch.getActionView();
            this.searchView.setOnQueryTextListener(this);
            this.searchView.setOnCloseListener(this);
            this.searchView.setSubmitButtonEnabled(false);

            switch (this.appOrderBy) {
                case ASC: {
                    final MenuItem menuItem = menu.findItem(R.id.menu_sort_order_asc);
                    menuItem.setChecked(true);
                    break;
                }
                case DESC: {
                    final MenuItem menuItem = menu.findItem(R.id.menu_sort_order_desc);
                    menuItem.setChecked(true);
                    break;
                }
            }

            switch (this.appFilterBy) {
                case APPNAME: {
                    final MenuItem menuItem = menu.findItem(R.id.menu_filter_app_name);
                    menuItem.setChecked(true);
                    break;
                }
                case PKGNAME: {
                    final MenuItem menuItem = menu.findItem(R.id.menu_filter_pkg_name);
                    menuItem.setChecked(true);
                    break;
                }
            }
        }
    }

    private static class AsyncTaskProgress extends AsyncTask<Void, Void, Void> {
        private final PackageListFragment fragment;
        public AsyncTaskProgress(PackageListFragment fragment) {
            this.fragment = fragment;
        }
        @Override
        protected Void doInBackground(Void... voids) {
            return null;
        }
    }
}
