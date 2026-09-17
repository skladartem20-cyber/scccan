package com.warehouse.scanner.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.warehouse.scanner.Prefs;
import com.warehouse.scanner.R;
import com.warehouse.scanner.db.DbHelper;
import com.warehouse.scanner.db.Models.CountRow;
import com.warehouse.scanner.util.Exporter;
import com.warehouse.scanner.util.ScanInput;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Раздел «Сканирование и подсчёт».
 *
 * Пользователь сканирует товары один за другим (встроенным сканером). Для
 * каждого штрихкода приложение увеличивает счётчик и подставляет название из
 * активной базы. Сверху — крупная плашка последнего товара и счётчики
 * (позиции / всего единиц). Ниже — список позиций с кнопками +/− и удалением.
 * Внизу — ручной ввод, очистка, экспорт в CSV и сохранение подсчёта как «план».
 */
public class ScanCountActivity extends BaseActivity implements ScanInput.Listener {

    private TextView activeStore, warn, lastBarcode, lastName, totalLines, totalQty;
    private EditText hiddenInput;
    private RecyclerView list;
    private CountAdapter adapter;

    private ScanInput scanInput;
    private long storeId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_count);
        setTitle(R.string.scan_title);
        enableBack();

        activeStore = findViewById(R.id.active_store);
        warn = findViewById(R.id.warn);
        lastBarcode = findViewById(R.id.last_barcode);
        lastName = findViewById(R.id.last_name);
        totalLines = findViewById(R.id.total_lines);
        totalQty = findViewById(R.id.total_qty);
        hiddenInput = findViewById(R.id.hidden_input);
        list = findViewById(R.id.list);

        ((TextView) findViewById(R.id.hint)).setText(R.string.scan_hint);

        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CountAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.btn_manual).setOnClickListener(v -> manualInput());
        findViewById(R.id.btn_clear).setOnClickListener(v -> clearAll());
        findViewById(R.id.btn_export).setOnClickListener(v -> export());
        findViewById(R.id.btn_save_expected).setOnClickListener(v -> saveAsExpected());

        scanInput = new ScanInput(this, hiddenInput, this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        storeId = Prefs.activeStore(this);
        refreshHeader();
        reloadList();
        if (Prefs.MODE_BROADCAST.equals(Prefs.scanMode(this))) {
            scanInput.registerBroadcast();
        } else {
            scanInput.focusField();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        scanInput.unregisterBroadcast();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanInput.release();
    }

    private void refreshHeader() {
        if (storeId > 0 && DbHelper.get(this).getStore(storeId) != null) {
            activeStore.setText(getString(R.string.active_store_fmt,
                    DbHelper.get(this).getStore(storeId).name));
            warn.setVisibility(View.GONE);
        } else {
            activeStore.setText(R.string.active_store_none);
            warn.setText(R.string.no_active_store_warn);
            warn.setVisibility(View.VISIBLE);
        }
    }

    // --------------- приём штрихкода ---------------

    @Override
    public void onBarcode(String barcode) {
        String bc = barcode == null ? "" : barcode.trim();
        if (bc.isEmpty()) return;

        String name = storeId > 0 ? DbHelper.get(this).findProductName(storeId, bc) : null;
        boolean known = name != null && !name.isEmpty();

        DbHelper.get(this).incScan(storeId, bc, known ? name : "");

        lastBarcode.setText(bc);
        lastName.setText(known ? name
                : (storeId > 0 ? getString(R.string.not_in_base) : getString(R.string.unknown_product)));

        reloadList();
        // держим фокус для клавиатурного режима
        if (!Prefs.MODE_BROADCAST.equals(Prefs.scanMode(this))) scanInput.focusField();
    }

    private void reloadList() {
        List<CountRow> rows = DbHelper.get(this).listScans(storeId);
        adapter.setData(rows);
        totalLines.setText(getString(R.string.total_lines_fmt, DbHelper.get(this).totalScanLines(storeId)));
        totalQty.setText(getString(R.string.total_qty_fmt, DbHelper.get(this).totalScanQty(storeId)));
    }

    // --------------- действия ---------------

    private void manualInput() {
        final EditText input = new EditText(this);
        input.setHint(R.string.barcode_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.manual_input)
                .setView(input)
                .setPositiveButton(R.string.ok, (d, w) -> onBarcode(input.getText().toString()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void clearAll() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.clear_confirm)
                .setPositiveButton(R.string.clear_all, (d, w) -> {
                    DbHelper.get(this).clearScans(storeId);
                    lastBarcode.setText("");
                    lastName.setText("");
                    reloadList();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void export() {
        List<CountRow> rows = DbHelper.get(this).listScans(storeId);
        if (rows.isEmpty()) {
            Toast.makeText(this, R.string.empty, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String storeName = storeId > 0 && DbHelper.get(this).getStore(storeId) != null
                    ? DbHelper.get(this).getStore(storeId).name : "-";
            File f = Exporter.exportCounts(this, storeName, rows);
            Toast.makeText(this, getString(R.string.exported_fmt, f.getName()), Toast.LENGTH_LONG).show();
            Exporter.share(this, f);
        } catch (Exception e) {
            Toast.makeText(this, R.string.import_error, Toast.LENGTH_LONG).show();
        }
    }

    private void saveAsExpected() {
        if (storeId <= 0) {
            Toast.makeText(this, R.string.no_active_store_warn, Toast.LENGTH_LONG).show();
            return;
        }
        DbHelper.get(this).copyScansToExpected(storeId);
        Toast.makeText(this, R.string.saved_expected, Toast.LENGTH_LONG).show();
    }

    // --------------- адаптер ---------------

    private class CountAdapter extends RecyclerView.Adapter<CountAdapter.VH> {
        private final List<CountRow> data = new ArrayList<>();

        void setData(List<CountRow> rows) {
            data.clear();
            data.addAll(rows);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_count, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            CountRow r = data.get(position);
            h.qty.setText(String.valueOf(r.qty));
            h.barcode.setText(r.barcode);
            if (!TextUtils.isEmpty(r.name)) {
                h.name.setText(r.name);
                h.name.setVisibility(View.VISIBLE);
            } else {
                h.name.setText(R.string.not_in_base);
                h.name.setVisibility(View.VISIBLE);
            }
            h.btnPlus.setOnClickListener(v -> {
                DbHelper.get(ScanCountActivity.this).setScanQty(storeId, r.barcode, r.qty + 1);
                reloadList();
            });
            h.btnMinus.setOnClickListener(v -> {
                DbHelper.get(ScanCountActivity.this).setScanQty(storeId, r.barcode, r.qty - 1);
                reloadList();
            });
            h.btnRemove.setOnClickListener(v -> {
                DbHelper.get(ScanCountActivity.this).removeScan(storeId, r.barcode);
                reloadList();
            });
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView qty, barcode, name, btnPlus, btnMinus, btnRemove;
            VH(View v) {
                super(v);
                qty = v.findViewById(R.id.item_qty);
                barcode = v.findViewById(R.id.item_barcode);
                name = v.findViewById(R.id.item_name);
                btnPlus = v.findViewById(R.id.btn_plus);
                btnMinus = v.findViewById(R.id.btn_minus);
                btnRemove = v.findViewById(R.id.btn_remove);
            }
        }
    }
}
