package com.warehouse.scanner.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.warehouse.scanner.Prefs;
import com.warehouse.scanner.R;
import com.warehouse.scanner.db.DbHelper;
import com.warehouse.scanner.db.Models.CountRow;
import com.warehouse.scanner.db.Models.DiffRow;
import com.warehouse.scanner.util.ColumnGuesser;
import com.warehouse.scanner.util.Exporter;
import com.warehouse.scanner.util.XlsxReader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Раздел «Сверка и расхождения».
 *
 * Шаг 1 — план: загрузить плановое количество из файла .xlsx (с выбором
 * колонок) или взять ранее сохранённый подсчёт как план.
 * Шаг 2 — факт: берётся из текущего подсчёта сканирования активной базы.
 * Кнопка «Сравнить» строит таблицу расхождений (недостача / излишек /
 * не отсканирован / лишний), показывает сводку и позволяет отфильтровать
 * только проблемные позиции и выгрузить отчёт в CSV.
 */
public class ReconcileActivity extends BaseActivity {

    private static final int REQ_PICK_XLSX = 201;

    private TextView expectedStatus, factStatus, summary;
    private RecyclerView list;
    private ProgressBar progress;
    private View filterRow;
    private TextView filterAll, filterProblems;
    private DiffAdapter adapter;

    private long storeId;
    private boolean showOnlyProblems = false;
    private List<DiffRow> lastResult = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reconcile);
        setTitle(R.string.reconcile_title);
        enableBack();

        ((TextView) findViewById(R.id.hint)).setText(R.string.reconcile_hint);
        expectedStatus = findViewById(R.id.expected_status);
        factStatus = findViewById(R.id.fact_status);
        summary = findViewById(R.id.summary);
        progress = findViewById(R.id.progress);
        filterRow = findViewById(R.id.filter_row);
        filterAll = findViewById(R.id.filter_all);
        filterProblems = findViewById(R.id.filter_problems);

        list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DiffAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.btn_load_file).setOnClickListener(v -> pickXlsx());
        findViewById(R.id.btn_load_saved).setOnClickListener(v -> loadSavedExpected());
        findViewById(R.id.btn_compare).setOnClickListener(v -> compare());
        findViewById(R.id.btn_export).setOnClickListener(v -> exportReport());

        filterAll.setOnClickListener(v -> { showOnlyProblems = false; applyFilter(); });
        filterProblems.setOnClickListener(v -> { showOnlyProblems = true; applyFilter(); });
    }

    @Override
    protected void onResume() {
        super.onResume();
        storeId = Prefs.activeStore(this);
        refreshStatus();
    }

    private void refreshStatus() {
        int exp = storeId > 0 ? DbHelper.get(this).expectedCount(storeId) : 0;
        int fact = storeId > 0 ? DbHelper.get(this).totalScanLines(storeId) : 0;
        expectedStatus.setText(getString(R.string.expected_loaded_fmt, exp));
        factStatus.setText(getString(R.string.fact_loaded_fmt, fact));
    }

    // --------------- загрузка плана ---------------

    private void loadSavedExpected() {
        if (storeId <= 0) {
            Toast.makeText(this, R.string.no_active_store_warn, Toast.LENGTH_LONG).show();
            return;
        }
        // план уже в таблице expected (например, из «Сохранить как план»). Просто обновим статус.
        int exp = DbHelper.get(this).expectedCount(storeId);
        if (exp == 0) {
            Toast.makeText(this, R.string.import_no_rows, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, getString(R.string.expected_loaded_fmt, exp), Toast.LENGTH_SHORT).show();
        }
        refreshStatus();
    }

    private void pickXlsx() {
        if (storeId <= 0) {
            Toast.makeText(this, R.string.no_active_store_warn, Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(Intent.createChooser(i, getString(R.string.load_expected_file)), REQ_PICK_XLSX);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_XLSX && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            try {
                File cached = copyToCache(data.getData());
                parseExpectedFile(cached);
            } catch (Exception e) {
                Toast.makeText(this, R.string.import_error, Toast.LENGTH_LONG).show();
            }
        }
    }

    private File copyToCache(Uri uri) throws Exception {
        File dir = new File(getCacheDir(), "import");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "plan_" + System.currentTimeMillis() + ".xlsx");
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out;
    }

    private void parseExpectedFile(File file) {
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                XlsxReader reader = new XlsxReader(file);
                int best = 0, bestRows = -1;
                for (int i = 0; i < reader.sheetCount(); i++) {
                    int rows = reader.readSheet(i).size();
                    if (rows > bestRows) { bestRows = rows; best = i; }
                }
                final List<List<String>> rows = reader.readSheet(best);
                reader.close();
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    showColumnDialog(rows);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, R.string.import_error, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** Диалог выбора колонок «штрихкод / количество / название» для плана. */
    private void showColumnDialog(List<List<String>> rows) {
        if (rows.isEmpty()) {
            Toast.makeText(this, R.string.import_no_rows, Toast.LENGTH_LONG).show();
            return;
        }
        final int headerRow = ColumnGuesser.guessHeaderRow(rows);
        List<String> header = headerRow < rows.size() ? rows.get(headerRow) : new ArrayList<>();
        int width = header.size();
        for (int i = headerRow; i < Math.min(rows.size(), headerRow + 6); i++)
            width = Math.max(width, rows.get(i).size());

        final List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.column_none));
        for (int i = 0; i < width; i++) {
            String title = i < header.size() && header.get(i) != null ? header.get(i).trim() : "";
            labels.add(title.isEmpty() ? colLetter(i) : colLetter(i) + ": " + title);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        final Spinner bcSp = labeledSpinner(root, getString(R.string.barcode_col), labels);
        final Spinner qtySp = labeledSpinner(root, getString(R.string.expected_qty_col), labels);
        final Spinner nameSp = labeledSpinner(root, getString(R.string.name_col), labels);

        int gBc = ColumnGuesser.guessBarcodeCol(header);
        int gQty = ColumnGuesser.guessQtyCol(header);
        int gName = ColumnGuesser.guessNameCol(header);
        bcSp.setSelection(gBc >= 0 ? gBc + 1 : 0);
        qtySp.setSelection(gQty >= 0 ? gQty + 1 : 0);
        nameSp.setSelection(gName >= 0 ? gName + 1 : 0);

        new AlertDialog.Builder(this)
                .setTitle(R.string.load_expected_file)
                .setView(root)
                .setPositiveButton(R.string.save, (d, w) -> {
                    int bc = bcSp.getSelectedItemPosition() - 1;
                    int qty = qtySp.getSelectedItemPosition() - 1;
                    int nm = nameSp.getSelectedItemPosition() - 1;
                    if (bc < 0) {
                        Toast.makeText(this, R.string.barcode_col_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    List<CountRow> plan = ColumnGuesser.extractExpected(rows, headerRow, bc, nm, qty);
                    if (plan.isEmpty()) {
                        Toast.makeText(this, R.string.import_no_rows, Toast.LENGTH_LONG).show();
                        return;
                    }
                    DbHelper.get(this).replaceExpected(storeId, plan);
                    Toast.makeText(this, getString(R.string.expected_loaded_fmt, plan.size()),
                            Toast.LENGTH_LONG).show();
                    refreshStatus();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private Spinner labeledSpinner(LinearLayout root, String label, List<String> items) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setPadding(0, dp(8), 0, dp(2));
        root.addView(tv);
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new ArrayList<>(items));
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        root.addView(sp);
        return sp;
    }

    // --------------- сравнение ---------------

    private void compare() {
        if (storeId <= 0) {
            Toast.makeText(this, R.string.no_active_store_warn, Toast.LENGTH_LONG).show();
            return;
        }
        List<CountRow> expected = DbHelper.get(this).listExpected(storeId);
        List<CountRow> fact = DbHelper.get(this).listScans(storeId);
        if (expected.isEmpty() || fact.isEmpty()) {
            Toast.makeText(this, R.string.no_data_compare, Toast.LENGTH_LONG).show();
            return;
        }

        Map<String, CountRow> exMap = new LinkedHashMap<>();
        for (CountRow r : expected) exMap.put(r.barcode, r);
        Map<String, CountRow> ftMap = new LinkedHashMap<>();
        for (CountRow r : fact) ftMap.put(r.barcode, r);

        // объединяем штрихкоды: сначала план, затем лишние из факта
        List<String> keys = new ArrayList<>(exMap.keySet());
        for (String k : ftMap.keySet()) if (!exMap.containsKey(k)) keys.add(k);

        List<DiffRow> result = new ArrayList<>();
        int cMatch = 0, cShort = 0, cSurplus = 0, cMissing = 0, cExtra = 0;
        for (String bc : keys) {
            CountRow e = exMap.get(bc);
            CountRow f = ftMap.get(bc);
            int exp = e != null ? e.qty : 0;
            int fct = f != null ? f.qty : 0;

            DiffRow d = new DiffRow();
            d.barcode = bc;
            d.expected = exp;
            d.fact = fct;
            d.name = pickName(e, f, bc);

            if (exp > 0 && fct == 0) { d.status = 3; cMissing++; }
            else if (exp == 0 && fct > 0) { d.status = 4; cExtra++; }
            else if (fct == exp) { d.status = 0; cMatch++; }
            else if (fct < exp) { d.status = 1; cShort++; }
            else { d.status = 2; cSurplus++; }

            result.add(d);
        }

        lastResult = result;
        summary.setText(getString(R.string.summary_fmt, cMatch, cShort, cSurplus, cMissing, cExtra));
        summary.setVisibility(View.VISIBLE);
        filterRow.setVisibility(View.VISIBLE);
        applyFilter();
    }

    private String pickName(CountRow e, CountRow f, String bc) {
        if (e != null && e.name != null && !e.name.isEmpty()) return e.name;
        if (f != null && f.name != null && !f.name.isEmpty()) return f.name;
        String fromBase = DbHelper.get(this).findProductName(storeId, bc);
        return fromBase != null ? fromBase : "";
    }

    private void applyFilter() {
        filterAll.setSelected(!showOnlyProblems);
        filterProblems.setSelected(showOnlyProblems);
        List<DiffRow> shown = new ArrayList<>();
        for (DiffRow d : lastResult) {
            if (!showOnlyProblems || d.status != 0) shown.add(d);
        }
        adapter.setData(shown);
    }

    private void exportReport() {
        if (lastResult.isEmpty()) {
            Toast.makeText(this, R.string.no_data_compare, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String storeName = DbHelper.get(this).getStore(storeId) != null
                    ? DbHelper.get(this).getStore(storeId).name : "-";
            File f = Exporter.exportReconcile(this, storeName, lastResult);
            Toast.makeText(this, getString(R.string.exported_fmt, f.getName()), Toast.LENGTH_LONG).show();
            Exporter.share(this, f);
        } catch (Exception e) {
            Toast.makeText(this, R.string.import_error, Toast.LENGTH_LONG).show();
        }
    }

    // --------------- утилиты ---------------

    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }

    private static String colLetter(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        while (n >= 0) { sb.insert(0, (char) ('A' + (n % 26))); n = n / 26 - 1; }
        return sb.toString();
    }

    // --------------- адаптер ---------------

    private class DiffAdapter extends RecyclerView.Adapter<DiffAdapter.VH> {
        private final List<DiffRow> data = new ArrayList<>();

        void setData(List<DiffRow> rows) {
            data.clear();
            data.addAll(rows);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_diff, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            DiffRow d = data.get(position);
            h.name.setText(d.name == null || d.name.isEmpty() ? d.barcode : d.name);
            h.barcode.setText(d.barcode);
            h.expected.setText(getString(R.string.col_expected) + ": " + d.expected);
            h.fact.setText(getString(R.string.col_fact) + ": " + d.fact);
            int diff = d.diff();
            h.diff.setText(getString(R.string.col_diff) + ": " + (diff > 0 ? "+" + diff : String.valueOf(diff)));

            int colorRes;
            String statusText;
            switch (d.status) {
                case 0: colorRes = R.color.ok_green; statusText = getString(R.string.status_match); break;
                case 1: colorRes = R.color.err_red; statusText = getString(R.string.status_shortage); break;
                case 2: colorRes = R.color.warn_orange; statusText = getString(R.string.status_surplus); break;
                case 3: colorRes = R.color.err_red; statusText = getString(R.string.status_missing); break;
                default: colorRes = R.color.warn_orange; statusText = getString(R.string.status_extra); break;
            }
            int color = ContextCompat.getColor(ReconcileActivity.this, colorRes);
            h.status.setText(statusText);
            h.status.setTextColor(color);
            h.stripe.setBackgroundColor(color);
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView name, barcode, expected, fact, diff, status;
            View stripe;
            VH(View v) {
                super(v);
                name = v.findViewById(R.id.diff_name);
                barcode = v.findViewById(R.id.diff_barcode);
                expected = v.findViewById(R.id.diff_expected);
                fact = v.findViewById(R.id.diff_fact);
                diff = v.findViewById(R.id.diff_diff);
                status = v.findViewById(R.id.diff_status);
                stripe = v.findViewById(R.id.diff_stripe);
            }
        }
    }
}
