package com.warehouse.scanner;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Простое хранилище настроек приложения (обёртка над SharedPreferences).
 * Здесь лежат: выбранный язык, id активной базы (магазина), режим сканера,
 * параметры broadcast-режима, флаги звука и вибрации.
 */
public class Prefs {
    private static final String FILE = "ws_prefs";

    public static final String KEY_LANG = "lang";                 // "ru" | "zh"
    public static final String KEY_ACTIVE_STORE = "active_store";  // long id или -1
    public static final String KEY_SCAN_MODE = "scan_mode";        // "keyboard" | "broadcast"
    public static final String KEY_BC_ACTION = "bc_action";        // action для broadcast
    public static final String KEY_BC_EXTRA = "bc_extra";          // имя extra с данными
    public static final String KEY_VIBRATE = "vibrate";
    public static final String KEY_SOUND = "sound";

    public static final String MODE_KEYBOARD = "keyboard";
    public static final String MODE_BROADCAST = "broadcast";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String lang(Context c) { return sp(c).getString(KEY_LANG, "ru"); }
    public static void setLang(Context c, String v) { sp(c).edit().putString(KEY_LANG, v).apply(); }

    public static long activeStore(Context c) { return sp(c).getLong(KEY_ACTIVE_STORE, -1L); }
    public static void setActiveStore(Context c, long id) { sp(c).edit().putLong(KEY_ACTIVE_STORE, id).apply(); }

    public static String scanMode(Context c) { return sp(c).getString(KEY_SCAN_MODE, MODE_KEYBOARD); }
    public static void setScanMode(Context c, String v) { sp(c).edit().putString(KEY_SCAN_MODE, v).apply(); }

    public static String bcAction(Context c) { return sp(c).getString(KEY_BC_ACTION, "android.intent.ACTION_DECODE_DATA"); }
    public static void setBcAction(Context c, String v) { sp(c).edit().putString(KEY_BC_ACTION, v).apply(); }

    public static String bcExtra(Context c) { return sp(c).getString(KEY_BC_EXTRA, "barcode_string"); }
    public static void setBcExtra(Context c, String v) { sp(c).edit().putString(KEY_BC_EXTRA, v).apply(); }

    public static boolean vibrate(Context c) { return sp(c).getBoolean(KEY_VIBRATE, true); }
    public static void setVibrate(Context c, boolean v) { sp(c).edit().putBoolean(KEY_VIBRATE, v).apply(); }

    public static boolean sound(Context c) { return sp(c).getBoolean(KEY_SOUND, true); }
    public static void setSound(Context c, boolean v) { sp(c).edit().putBoolean(KEY_SOUND, v).apply(); }
}
