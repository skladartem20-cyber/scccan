package com.warehouse.scanner;

import android.app.Application;
import android.content.Context;

/**
 * Класс приложения. Применяет сохранённый язык ко всему приложению при запуске.
 */
public class App extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base, Prefs.lang(base)));
    }
}
