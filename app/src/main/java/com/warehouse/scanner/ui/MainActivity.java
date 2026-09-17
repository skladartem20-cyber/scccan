package com.warehouse.scanner.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.warehouse.scanner.Prefs;
import com.warehouse.scanner.R;
import com.warehouse.scanner.db.DbHelper;
import com.warehouse.scanner.db.Models.Store;

/**
 * Главный экран — меню приложения.
 *
 * Сверху: индикатор активной базы (магазина) и переключатель языка RU / 中文.
 * Ниже: четыре крупные кнопки-раздела, каждый с коротким описанием:
 *   1) Магазины (базы товаров)
 *   2) Сканирование и подсчёт
 *   3) Сверка и расхождения
 *   4) Настройки
 */
public class MainActivity extends BaseActivity {

    private TextView activeStoreView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(R.string.app_name);

        activeStoreView = findViewById(R.id.active_store);

        setupLanguageChips();
        setupMenu();
    }

    private void setupLanguageChips() {
        TextView ru = findViewById(R.id.lang_ru);
        TextView zh = findViewById(R.id.lang_zh);
        String cur = Prefs.lang(this);
        ru.setSelected("ru".equals(cur));
        zh.setSelected("zh".equals(cur));

        ru.setOnClickListener(v -> switchLang("ru"));
        zh.setOnClickListener(v -> switchLang("zh"));
    }

    private void switchLang(String lang) {
        if (lang.equals(Prefs.lang(this))) return;
        Prefs.setLang(this, lang);
        recreate(); // применяем язык ко всему интерфейсу
    }

    private void setupMenu() {
        findViewById(R.id.card_stores).setOnClickListener(v ->
                startActivity(new Intent(this, StoresActivity.class)));
        findViewById(R.id.card_scan).setOnClickListener(v ->
                startActivity(new Intent(this, ScanCountActivity.class)));
        findViewById(R.id.card_reconcile).setOnClickListener(v ->
                startActivity(new Intent(this, ReconcileActivity.class)));
        findViewById(R.id.card_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshActiveStore();
    }

    private void refreshActiveStore() {
        long id = Prefs.activeStore(this);
        Store s = id > 0 ? DbHelper.get(this).getStore(id) : null;
        if (s == null) {
            activeStoreView.setText(getString(R.string.active_store_none));
        } else {
            activeStoreView.setText(getString(R.string.active_store_fmt, s.name));
        }
    }
}
