package com.warehouse.scanner.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

import com.warehouse.scanner.Prefs;
import com.warehouse.scanner.R;

/**
 * Раздел «Настройки».
 *
 * Здесь настраиваются: язык интерфейса (RU / 中文), режим работы сканера
 * (клавиатурный или broadcast) с параметрами broadcast-режима, а также
 * звук и вибрация при сканировании. Внизу — краткая справка о приложении.
 */
public class SettingsActivity extends BaseActivity {

    private RadioButton langRu, langZh, modeKeyboard, modeBroadcast;
    private View broadcastBox;
    private EditText bcAction, bcExtra;
    private SwitchCompat swVibrate, swSound;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle(R.string.settings_title);
        enableBack();

        langRu = findViewById(R.id.lang_ru);
        langZh = findViewById(R.id.lang_zh);
        modeKeyboard = findViewById(R.id.mode_keyboard);
        modeBroadcast = findViewById(R.id.mode_broadcast);
        broadcastBox = findViewById(R.id.broadcast_box);
        bcAction = findViewById(R.id.bc_action);
        bcExtra = findViewById(R.id.bc_extra);
        swVibrate = findViewById(R.id.sw_vibrate);
        swSound = findViewById(R.id.sw_sound);

        ((TextView) findViewById(R.id.about_text)).setText(R.string.about_text);
        ((TextView) findViewById(R.id.mode_keyboard_desc)).setText(R.string.mode_keyboard_desc);
        ((TextView) findViewById(R.id.mode_broadcast_desc)).setText(R.string.mode_broadcast_desc);

        loadState();
        wire();
    }

    private void loadState() {
        String lang = Prefs.lang(this);
        langRu.setChecked("ru".equals(lang));
        langZh.setChecked("zh".equals(lang));

        boolean broadcast = Prefs.MODE_BROADCAST.equals(Prefs.scanMode(this));
        modeKeyboard.setChecked(!broadcast);
        modeBroadcast.setChecked(broadcast);
        broadcastBox.setVisibility(broadcast ? View.VISIBLE : View.GONE);

        bcAction.setText(Prefs.bcAction(this));
        bcExtra.setText(Prefs.bcExtra(this));
        swVibrate.setChecked(Prefs.vibrate(this));
        swSound.setChecked(Prefs.sound(this));
    }

    private void wire() {
        langRu.setOnClickListener(v -> setLang("ru"));
        langZh.setOnClickListener(v -> setLang("zh"));

        modeKeyboard.setOnClickListener(v -> {
            Prefs.setScanMode(this, Prefs.MODE_KEYBOARD);
            broadcastBox.setVisibility(View.GONE);
        });
        modeBroadcast.setOnClickListener(v -> {
            Prefs.setScanMode(this, Prefs.MODE_BROADCAST);
            broadcastBox.setVisibility(View.VISIBLE);
        });

        swVibrate.setOnCheckedChangeListener((b, c) -> Prefs.setVibrate(this, c));
        swSound.setOnCheckedChangeListener((b, c) -> Prefs.setSound(this, c));

        findViewById(R.id.btn_save).setOnClickListener(v -> saveBroadcast());
    }

    private void setLang(String lang) {
        if (lang.equals(Prefs.lang(this))) return;
        Prefs.setLang(this, lang);
        Toast.makeText(this, R.string.restart_needed, Toast.LENGTH_SHORT).show();
        recreate();
    }

    private void saveBroadcast() {
        String action = bcAction.getText().toString().trim();
        String extra = bcExtra.getText().toString().trim();
        if (!action.isEmpty()) Prefs.setBcAction(this, action);
        if (!extra.isEmpty()) Prefs.setBcExtra(this, extra);
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
    }
}
