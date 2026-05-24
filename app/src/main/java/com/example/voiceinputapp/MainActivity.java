package com.example.voiceinputapp;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private TextView tvStatus;
    private TextView tvPermissionStatus;
    private TextView tvImeEnabledStatus;
    private TextView tvImeSelectedStatus;
    private Button btnGrantMic;
    private Button btnSwitchIme;

    private final ActivityResultLauncher<String> audioPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                refreshSetupStatus();
                if (isGranted) {
                    updateStatus(getString(R.string.status_home_ready));
                } else {
                    updateStatus(getString(R.string.status_permission_denied));
                    showToast(getString(R.string.toast_permission_required));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        bindActions();
        refreshSetupStatus();
        updateHomeStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSetupStatus();
        updateHomeStatus();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus);
        tvImeEnabledStatus = findViewById(R.id.tvImeEnabledStatus);
        tvImeSelectedStatus = findViewById(R.id.tvImeSelectedStatus);
        btnGrantMic = findViewById(R.id.btnGrantMic);
        btnSwitchIme = findViewById(R.id.btnSwitchIme);
    }

    private void bindActions() {
        btnGrantMic.setOnClickListener(v -> requestMicPermission());
        btnSwitchIme.setOnClickListener(v -> handleImeAction());
    }

    private void requestMicPermission() {
        if (hasAudioPermission()) {
            updateStatus(getString(R.string.status_home_ready));
            showToast(getString(R.string.setup_mic_already_granted));
            return;
        }
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
    }

    private void refreshSetupStatus() {
        boolean hasPermission = hasAudioPermission();
        boolean imeEnabled = isOurImeEnabled();
        boolean imeSelected = isOurImeDefault();

        tvPermissionStatus.setText(getString(
                hasPermission ? R.string.setup_mic_granted : R.string.setup_mic_missing
        ));
        tvImeEnabledStatus.setText(getString(
                imeEnabled ? R.string.setup_ime_enabled : R.string.setup_ime_disabled
        ));
        tvImeSelectedStatus.setText(getString(
                imeSelected ? R.string.setup_ime_selected : R.string.setup_ime_not_selected
        ));

        btnGrantMic.setEnabled(!hasPermission);
        btnSwitchIme.setEnabled(true);
        btnSwitchIme.setText(getString(
                imeEnabled ? R.string.button_switch_ime : R.string.button_enable_ime
        ));
    }

    private void updateHomeStatus() {
        if (!hasBaiduConfig()) {
            updateStatus(getString(R.string.status_config_missing));
            return;
        }
        if (!hasAudioPermission()) {
            updateStatus(getString(R.string.status_home_need_permission));
            return;
        }
        if (!isOurImeEnabled()) {
            updateStatus(getString(R.string.status_home_enable_ime));
            return;
        }
        if (!isOurImeDefault()) {
            updateStatus(getString(R.string.status_home_switch_ime));
            return;
        }
        updateStatus(getString(R.string.status_home_ready));
    }

    private void openImeSettings() {
        startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        showToast(getString(R.string.toast_open_ime_settings));
    }

    private void handleImeAction() {
        if (!isOurImeEnabled()) {
            openImeSettings();
            return;
        }
        showImePicker();
    }

    private void showImePicker() {
        InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.showInputMethodPicker();
            updateStatus(getString(R.string.status_ime_guide));
        }
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isOurImeEnabled() {
        String enabledImeIds = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_INPUT_METHODS
        );
        if (TextUtils.isEmpty(enabledImeIds)) {
            return false;
        }

        String imeIdLong = getImeIdLong();
        String imeIdShort = getImeIdShort();
        String[] enabledItems = enabledImeIds.split(":");
        for (String item : enabledItems) {
            if (imeIdLong.equals(item) || imeIdShort.equals(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOurImeDefault() {
        String defaultImeId = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD
        );
        if (TextUtils.isEmpty(defaultImeId)) {
            return false;
        }
        return defaultImeId.equals(getImeIdLong()) || defaultImeId.equals(getImeIdShort());
    }

    private String getImeIdLong() {
        return new ComponentName(this, VoiceInputMethodService.class).flattenToString();
    }

    private String getImeIdShort() {
        return new ComponentName(this, VoiceInputMethodService.class).flattenToShortString();
    }

    private boolean hasBaiduConfig() {
        return !BuildConfig.BAIDU_API_KEY.isEmpty() && !BuildConfig.BAIDU_SECRET_KEY.isEmpty();
    }

    private void updateStatus(String status) {
        tvStatus.setText(status);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
