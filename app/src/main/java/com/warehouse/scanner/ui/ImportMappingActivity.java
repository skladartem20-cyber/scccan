package com.warehouse.scanner.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.warehouse.scanner.Prefs;
import com.warehouse.scanner.R;
import com.warehouse.scanner.db.DbHelper;
import com.warehouse.scanner.db.Models.Product;
import com.warehouse.scanner.util.ColumnGuesser;
import com.warehouse.scanner.util.XlsxReader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Экран привязки колонок при импорте базы.
 *
 * Читает выбранный .xlsx в фоне, показывает выпадающие списки для выбора
 * листа, строки заголовков и колонок (штрихкод / название / артикул).
 * Колонки определяются автоматически, но их можно поправить вручную.
 * Внизу — предпросмотр и поля названия/примечания магазина; по кнопке
 * «Сохранить базу» товары записываются в новый магазин, который сразу
 * становится активным.
 */
public class ImportMappingActivity extends BaseActivity {

    public static final String EXTRA_PATH = "path";

    private ProgressBar progress;
    private View content;
    private TextView hint, rowsDetected, previewText;
    private Spinner sheetSpinner, headerSpinner, bcSpinner, nameSpinner, artSpinner;
    private EditText storeName, storeNote;

    private XlsxReader reader;
    private List<List<String>> currentRows = new ArrayList<>();
    private List<String> columnLabels = new ArrayList<>();

    private boolean building = false; // защита от лишних колбэков при программной установке

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import_mapping);
        setTitle(R.string.mapping_title);
        enableBack();

        progress = findViewById(R.id.progress);
        content = findViewById(R.id.content);
        hint = findViewById(R.id.hint);
        rowsDetected = findViewById(R.id.rows_detected);
        previewText = findViewById(R.id.preview_text);
        sheetSpinner = findViewById(R.id.sheet_spinner);
        headerSpinner = findViewById(R.id.header_spinner);
        bcSpinner = findViewById(R.id.bc_spinner);
        nameSpinner = findViewById(R.id.name_spinner);
        artSpinner = findViewById(R.id.art_spinner);
        storeName = findViewById(R.id.store_name);
        storeNote = findViewById(R.id.store_note);
        hint.setText(R.string.mapping_hint);

        findViewById(R.id.btn_save).setOnClickListener(v -> save());

        String path = getIntent().getStringExtra(EXTRA_PATH);
        openFile(path);
    }

    // --------------- чтение файла ---------------

    private void openFile(String path) {
        progress.setVisibility(View.VISIBLE);
        content.setVisibility(View.GONE);
        new Thread(() -> {
            try {
                reader = new XlsxReader(new File(path));
                final List<String> sheets = reader.sheetNames();
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    content.setVisibility(View.VISIBLE);
                    setupSheetSpinner(sheets);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, R.string.import_error, Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }).start();
    }

    private void setupSheetSpinner(List<String> sheets) {
        sheetSpinner.setAdapter(simpleAdapter(sheets));
        sheetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                loadSheet(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        // выбираем лист с наибольшим числом строк (обычно это лист с данными)
        int best = pickBestSheet(sheets.size());
        sheetSpinner.setSelection(best);
        loadSheet(best);
    }

    private int pickBestSheet(int count) {
        int best = 0, bestRows = -1;
        for (int i = 0; i < count; i++) {
            try {
                int rows = reader.readSheet(i).size();
                if (rows > bestRows) { bestRows = rows; best = i; }
            } catch (Exception ignored) {}
        }
        return best;
    }

    private void loadSheet(int index) {
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            List<List<String>> rows;
            try {
                rows = reader.readSheet(index);
            } catch (Exception e) {
                rows = new ArrayList<>();
            }
            final List<List<String>> result = rows;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                currentRows = result;
                setupHeaderAndColumns();
            });
        }).start();
    }

    // --------------- заголовки и колонки ---------------

    private void setupHeaderAndColumns() {
        building = true;

        // строка заголовков
        int rowCount = currentRows.size();
        List<String> headerOptions = new ArrayList<>();
        int cap = Math.min(rowCount, 50);
        for (int i = 0; i < cap; i++) headerOptions.add(getString(R.string.header_row) + " " + (i + 1));
        headerSpinner.setAdapter(simpleAdapter(headerOptions));

        int guessHeader = ColumnGuesser.guessHeaderRow(currentRows);
        if (guessHeader >= headerOptions.size()) guessHeader = 0;
        headerSpinner.setSelection(guessHeader);
        headerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (!building) rebuildColumns(pos, true);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        rebuildColumns(guessHeader, true);
        building = false;
    }

    /** Построить списки колонок для выбранной строки заголовков. */
    private void rebuildColumns(int headerRow, boolean autoGuess) {
        List<String> header = headerRow < currentRows.size()
                ? currentRows.get(headerRow) : new ArrayList<>();

        // ширину берём максимальной среди нескольких строк, чтобы не потерять колонки
        int width = header.size();
        for (int i = headerRow; i < Math.min(currentRows.size(), headerRow + 6); i++)
            width = Math.max(width, currentRows.get(i).size());

        columnLabels = new ArrayList<>();
        columnLabels.add(getString(R.string.column_none)); // позиция 0 = «нет» (-1)
        for (int i = 0; i < width; i++) {
            String title = i < header.size() && header.get(i) != null ? header.get(i).trim() : "";
            String letter = colLetter(i);
            columnLabels.add(title.isEmpty() ? letter : (letter + ": " + title));
        }

        ArrayAdapter<String> ad = simpleAdapter(columnLabels);
        bcSpinner.setAdapter(ad);
        nameSpinner.setAdapter(simpleAdapter(columnLabels));
        artSpinner.setAdapter(simpleAdapter(columnLabels));

        if (autoGuess) {
            int bc = ColumnGuesser.guessBarcodeCol(header);
            int nm = ColumnGuesser.guessNameCol(header);
            int ar = ColumnGuesser.guessArticleCol(header);
            bcSpinner.setSelection(bc >= 0 ? bc + 1 : 0);
            nameSpinner.setSelection(nm >= 0 ? nm + 1 : 0);
            artSpinner.setSelection(ar >= 0 ? ar + 1 : 0);
        }

        AdapterView.OnItemSelectedListener refresh = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (!building) updatePreview();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
        bcSpinner.setOnItemSelectedListener(refresh);
        nameSpinner.setOnItemSelectedListener(refresh);
        artSpinner.setOnItemSelectedListener(refresh);

        updatePreview();
    }

    private int selectedCol(Spinner sp) {
        return sp.getSelectedItemPosition() - 1; // -1 = нет
    }

    private int selectedHeaderRow() {
        return headerSpinner.getSelectedItemPosition();
    }

    private void updatePreview() {
        int headerRow = selectedHeaderRow();
        int bc = selectedCol(bcSpinner);
        int nm = selectedCol(nameSpinner);
        int ar = selectedCol(artSpinner);

        List<Product> products = ColumnGuesser.extractProducts(currentRows, headerRow, bc, nm, ar);
        rowsDetected.setText(getString(R.string.rows_detected_fmt, products.size()));

        StringBuilder sb = new StringBuilder();
        int show = Math.min(products.size(), 8);
        for (int i = 0; i < show; i++) {
            Product p = products.get(i);
            sb.append("• ").append(p.barcode);
            if (!p.name.isEmpty()) sb.append("  —  ").append(p.name);
            sb.append("\n");
        }
        if (products.size() > show) sb.append("…");
        previewText.setText(sb.toString().trim());
    }

    // --------------- сохранение ---------------

    private void save() {
        String name = storeName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            Toast.makeText(this, R.string.store_name_required, Toast.LENGTH_SHORT).show();
            return;
        }
        int bc = selectedCol(bcSpinner);
        if (bc < 0) {
            Toast.makeText(this, R.string.barcode_col_required, Toast.LENGTH_SHORT).show();
            return;
        }
        int headerRow = selectedHeaderRow();
        int nm = selectedCol(nameSpinner);
        int ar = selectedCol(artSpinner);

        List<Product> products = ColumnGuesser.extractProducts(currentRows, headerRow, bc, nm, ar);
        if (products.isEmpty()) {
            Toast.makeText(this, R.string.import_no_rows, Toast.LENGTH_LONG).show();
            return;
        }
        String note = storeNote.getText().toString().trim();

        DbHelper db = DbHelper.get(this);
        long storeId = db.createStore(name, note);
        db.insertProducts(storeId, products);
        Prefs.setActiveStore(this, storeId);

        Toast.makeText(this, getString(R.string.store_saved_fmt, name, products.size()),
                Toast.LENGTH_LONG).show();
        finish();
    }

    // --------------- утилиты ---------------

    private ArrayAdapter<String> simpleAdapter(List<String> items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new ArrayList<>(items));
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    /** Индекс -> буква колонки (0->A, 26->AA). */
    private static String colLetter(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        }
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (reader != null) reader.close();
    }
}
