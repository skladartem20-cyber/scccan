package com.warehouse.scanner.util;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import com.warehouse.scanner.Prefs;

/**
 * Помощник получения штрихкодов со встроенного сканера UROVO.
 *
 * Поддерживает два режима (выбираются в Настройках):
 *
 *  1. KEYBOARD (по умолчанию) — сканер настроен как «клавиатура»: после
 *     считывания он «печатает» штрихкод в активное поле и жмёт Enter.
 *     Мы держим на экране скрытое поле ввода в фокусе и ловим Enter.
 *
 *  2. BROADCAST — сканер шлёт системный broadcast с данными. Мы
 *     подписываемся на указанный action и достаём штрихкод из extra.
 *     Работает без фокуса на поле; параметры задаются в Настройках.
 *
 * При каждом успешном считывании даём обратную связь: короткая вибрация
 * и/или звуковой сигнал (если включено в настройках).
 */
public class ScanInput {

    public interface Listener {
        void onBarcode(String barcode);
    }

    private final Activity activity;
    private final EditText hiddenField;
    private final Listener listener;

    private ToneGenerator tone;
    private BroadcastReceiver receiver;
    private boolean broadcastRegistered = false;

    public ScanInput(Activity activity, EditText hiddenField, Listener listener) {
        this.activity = activity;
        this.hiddenField = hiddenField;
        this.listener = listener;
        setupKeyboardField();
    }

    // ----------------- режим КЛАВИАТУРА -----------------

    private void setupKeyboardField() {
        if (hiddenField == null) return;
        // не показываем экранную клавиатуру — ввод идёт от аппаратного сканера
        hiddenField.setShowSoftInputOnFocus(false);

        // Enter от сканера (в большинстве прошивок UROVO)
        hiddenField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_NEXT
                    || actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                submitFromField();
                return true;
            }
            return false;
        });

        // запасной перехват аппаратного Enter
        hiddenField.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP
                    && (keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                submitFromField();
                return true;
            }
            return false;
        });

        // если прошивка шлёт перевод строки в тексте — тоже обрабатываем
        hiddenField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (s.length() == 0) return;
                char last = s.charAt(s.length() - 1);
                if (last == '\n' || last == '\r') submitFromField();
            }
        });
    }

    private void submitFromField() {
        if (hiddenField == null) return;
        String raw = hiddenField.getText().toString();
        hiddenField.setText("");
        String bc = raw == null ? "" : raw.trim();
        if (!bc.isEmpty()) deliver(bc);
    }

    /** Держать фокус на скрытом поле (вызывать в onResume для keyboard-режима). */
    public void focusField() {
        if (hiddenField == null) return;
        hiddenField.setText("");
        hiddenField.requestFocus();
    }

    // ----------------- режим BROADCAST -----------------

    /** Возможные имена extra со штрихкодом у разных прошивок. */
    private static final String[] KNOWN_STRING_EXTRAS = {
            "barcode_string", "barcode", "data", "scannerdata", "SCAN_BARCODE1",
            "EXTRA_BARCODE_DECODED_DATA", "scanResult", "BARCODE"
    };
    private static final String[] KNOWN_BYTE_EXTRAS = {
            "barocode", "barcode", "data", "EXTRA_BARCODE_DECODED_DATA"
    };

    private String extractBarcode(Intent intent) {
        // 1) заданное пользователем имя extra
        String custom = Prefs.bcExtra(activity);
        if (custom != null && !custom.isEmpty()) {
            String v = intent.getStringExtra(custom);
            if (v != null && !v.trim().isEmpty()) return v.trim();
            byte[] b = intent.getByteArrayExtra(custom);
            if (b != null && b.length > 0) return new String(b).trim();
        }
        // 2) известные строковые extra
        for (String key : KNOWN_STRING_EXTRAS) {
            String v = intent.getStringExtra(key);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        // 3) известные байтовые extra
        for (String key : KNOWN_BYTE_EXTRAS) {
            byte[] b = intent.getByteArrayExtra(key);
            if (b != null && b.length > 0) return new String(b).trim();
        }
        return null;
    }

    /** Подписаться на broadcast сканера (вызывать в onResume для broadcast-режима). */
    public void registerBroadcast() {
        if (broadcastRegistered) return;
        String action = Prefs.bcAction(activity);
        if (action == null || action.isEmpty()) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String bc = extractBarcode(intent);
                if (bc != null && !bc.isEmpty()) deliver(bc);
            }
        };
        IntentFilter filter = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            activity.registerReceiver(receiver, filter);
        }
        broadcastRegistered = true;
    }

    /** Отписаться от broadcast (вызывать в onPause). */
    public void unregisterBroadcast() {
        if (broadcastRegistered && receiver != null) {
            try { activity.unregisterReceiver(receiver); } catch (Exception ignored) {}
        }
        broadcastRegistered = false;
    }

    // ----------------- общая обработка -----------------

    private void deliver(String barcode) {
        feedback();
        if (listener != null) listener.onBarcode(barcode);
    }

    private void feedback() {
        if (Prefs.vibrate(activity)) {
            try {
                Vibrator v = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) v.vibrate(40);
            } catch (Exception ignored) {}
        }
        if (Prefs.sound(activity)) {
            try {
                if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
            } catch (Exception ignored) {}
        }
    }

    /** Освободить ресурсы (вызывать в onDestroy). */
    public void release() {
        unregisterBroadcast();
        if (tone != null) {
            try { tone.release(); } catch (Exception ignored) {}
            tone = null;
        }
    }
}
