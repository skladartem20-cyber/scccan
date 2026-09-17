package com.warehouse.scanner.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
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
import com.warehouse.scanner.db.Models.Store;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Раздел «Магазины и базы товаров».
 *
 * Здесь пользователь: импортирует базу .xlsx (файл копируется во временный
 * каталог и открывается экран привязки колонок), видит список всех баз,
 * делает базу активной, переименовывает и удаляет базы.
 */
public class StoresActivity extends BaseActivity {

    private static final int REQ_PICK_XLSX = 101;

    private RecyclerView list;
    private View emptyView;
    private StoreAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stores);
        setTitle(R.string.stores_title);
        enableBack();

        emptyView = findViewById(R.id.empty_view);
        list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new StoreAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.btn_import).setOnClickListener(v -> pickXlsx());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        List<Store> stores = DbHelper.get(this).listStores();
        adapter.setData(stores);
        boolean empty = stores.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ------------- импорт файла -------------

    private void pickXlsx() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*"); // некоторые файловые провайдеры не знают mime xlsx
        startActivityForResult(Intent.createChooser(i, getString(R.string.import_db)), REQ_PICK_XLSX);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_XLSX && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                File cached = copyToCache(uri);
                Intent i = new Intent(this, ImportMappingActivity.class);
                i.putExtra(ImportMappingActivity.EXTRA_PATH, cached.getAbsolutePath());
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, R.string.import_error, Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Скопировать выбранный файл во временный каталог приложения. */
    private File copyToCache(Uri uri) throws Exception {
        File dir = new File(getCacheDir(), "import");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "import_" + System.currentTimeMillis() + ".xlsx");
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out;
    }

    // ------------- действия над магазином -------------

    private void setActive(Store s) {
        Prefs.setActiveStore(this, s.id);
        Toast.makeText(this, getString(R.string.store_selected_fmt, s.name), Toast.LENGTH_SHORT).show();
        adapter.notifyDataSetChanged();
    }

    private void renameStore(Store s) {
        final EditText input = new EditText(this);
        input.setText(s.name);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename_store)
                .setView(input)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!TextUtils.isEmpty(name)) {
                        DbHelper.get(this).renameStore(s.id, name);
                        reload();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteStore(Store s) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_store_confirm_fmt, s.name))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    DbHelper.get(this).deleteStore(s.id);
                    if (Prefs.activeStore(this) == s.id) Prefs.setActiveStore(this, -1);
                    Toast.makeText(this, R.string.store_deleted, Toast.LENGTH_SHORT).show();
                    reload();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------- адаптер списка -------------

    private class StoreAdapter extends RecyclerView.Adapter<StoreAdapter.VH> {
        private final List<Store> data = new ArrayList<>();

        void setData(List<Store> s) {
            data.clear();
            data.addAll(s);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_store, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Store s = data.get(position);
            h.name.setText(s.name);
            h.count.setText(getString(R.string.products_count_fmt, s.productCount));
            if (!TextUtils.isEmpty(s.note)) {
                h.note.setVisibility(View.VISIBLE);
                h.note.setText(s.note);
            } else {
                h.note.setVisibility(View.GONE);
            }
            boolean active = Prefs.activeStore(StoresActivity.this) == s.id;
            h.badge.setVisibility(active ? View.VISIBLE : View.GONE);

            h.itemView.setOnClickListener(v -> setActive(s));
            h.btnActive.setOnClickListener(v -> setActive(s));
            h.btnRename.setOnClickListener(v -> renameStore(s));
            h.btnDelete.setOnClickListener(v -> deleteStore(s));
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView name, count, note, badge, btnActive, btnRename, btnDelete;
            VH(View v) {
                super(v);
                name = v.findViewById(R.id.store_name);
                count = v.findViewById(R.id.store_count);
                note = v.findViewById(R.id.store_note);
                badge = v.findViewById(R.id.store_badge);
                btnActive = v.findViewById(R.id.btn_set_active);
                btnRename = v.findViewById(R.id.btn_rename);
                btnDelete = v.findViewById(R.id.btn_delete);
            }
        }
    }
}
