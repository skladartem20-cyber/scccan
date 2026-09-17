package com.warehouse.scanner;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * Помогает переключать язык интерфейса (русский / китайский).
 * Оборачивает Context нужной локалью — вызывается в attachBaseContext каждого экрана.
 */
public class LocaleHelper {

    public static Context wrap(Context context, String lang) {
        if (lang == null || lang.isEmpty()) lang = "ru";
        Locale locale = new Locale(lang);
        Locale.setDefault(locale);

        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLayoutDirection(locale);
        return context.createConfigurationContext(config);
    }
}
