package com.warehouse.scanner.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.warehouse.scanner.db.Models.CountRow;
import com.warehouse.scanner.db.Models.Product;
import com.warehouse.scanner.db.Models.Store;

import java.util.ArrayList;
import java.util.List;

/**
 * Вся работа с базой данных SQLite (без внешних библиотек).
 *
 * Таблицы:
 *  - stores         : список магазинов (баз товаров)
 *  - products       : товары каждого магазина (штрихкод -> название)
 *  - scan_counts    : текущий подсчёт сканирования по каждому магазину (факт)
 *  - expected       : плановое количество по каждому магазину (план для сверки)
 */
public class DbHelper extends SQLiteOpenHelper {

    private static final String DB = "warehouse.db";
    private static final int VER = 1;

    private static DbHelper INSTANCE;

    public static synchronized DbHelper get(Context c) {
        if (INSTANCE == null) INSTANCE = new DbHelper(c.getApplicationContext());
        return INSTANCE;
    }

    private DbHelper(Context context) { super(context, DB, null, VER); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE stores(" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "note TEXT," +
                "created_at INTEGER," +
                "product_count INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE products(" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "store_id INTEGER NOT NULL," +
                "barcode TEXT NOT NULL," +
                "name TEXT," +
                "article TEXT)");
        db.execSQL("CREATE INDEX idx_prod ON products(store_id, barcode)");

        db.execSQL("CREATE TABLE scan_counts(" +
                "store_id INTEGER NOT NULL," +
                "barcode TEXT NOT NULL," +
                "name TEXT," +
                "qty INTEGER DEFAULT 0," +
                "updated_at INTEGER," +
                "PRIMARY KEY(store_id, barcode))");

        db.execSQL("CREATE TABLE expected(" +
                "store_id INTEGER NOT NULL," +
                "barcode TEXT NOT NULL," +
                "name TEXT," +
                "qty INTEGER DEFAULT 0," +
                "PRIMARY KEY(store_id, barcode))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) { /* первая версия */ }

    // ======================= МАГАЗИНЫ =======================

    public long createStore(String name, String note) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("note", note);
        v.put("created_at", System.currentTimeMillis());
        v.put("product_count", 0);
        return getWritableDatabase().insert("stores", null, v);
    }

    public void renameStore(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        getWritableDatabase().update("stores", v, "_id=?", new String[]{String.valueOf(id)});
    }

    public void deleteStore(long id) {
        SQLiteDatabase db = getWritableDatabase();
        String[] a = {String.valueOf(id)};
        db.delete("products", "store_id=?", a);
        db.delete("scan_counts", "store_id=?", a);
        db.delete("expected", "store_id=?", a);
        db.delete("stores", "_id=?", a);
    }

    public List<Store> listStores() {
        List<Store> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT _id,name,note,created_at,product_count FROM stores ORDER BY _id DESC", null);
        while (c.moveToNext()) {
            out.add(new Store(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4)));
        }
        c.close();
        return out;
    }

    public Store getStore(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT _id,name,note,created_at,product_count FROM stores WHERE _id=?",
                new String[]{String.valueOf(id)});
        Store s = null;
        if (c.moveToFirst())
            s = new Store(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4));
        c.close();
        return s;
    }

    /** Загрузить список товаров в базу магазина одной транзакцией. */
    public void insertProducts(long storeId, List<Product> products) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Product p : products) {
                ContentValues v = new ContentValues();
                v.put("store_id", storeId);
                v.put("barcode", p.barcode);
                v.put("name", p.name);
                v.put("article", p.article);
                db.insert("products", null, v);
            }
            ContentValues cv = new ContentValues();
            cv.put("product_count", products.size());
            db.update("stores", cv, "_id=?", new String[]{String.valueOf(storeId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Найти товар по штрихкоду в базе магазина. Возвращает название или null. */
    public String findProductName(long storeId, String barcode) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT name FROM products WHERE store_id=? AND barcode=? LIMIT 1",
                new String[]{String.valueOf(storeId), barcode});
        String name = null;
        if (c.moveToFirst()) name = c.getString(0);
        c.close();
        return name;
    }

    // ======================= ПОДСЧЁТ (ФАКТ) =======================

    /**
     * Увеличить количество отсканированного штрихкода на 1 (создать при первом скане).
     * Реализовано через INSERT OR IGNORE + UPDATE — работает на всех версиях SQLite
     * (UPSERT-синтаксис недоступен на Android 7–8).
     */
    public void incScan(long storeId, String barcode, String name) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        db.execSQL("INSERT OR IGNORE INTO scan_counts(store_id,barcode,name,qty,updated_at) VALUES(?,?,?,0,?)",
                new Object[]{storeId, barcode, name, now});
        db.execSQL("UPDATE scan_counts SET qty=qty+1, updated_at=?, " +
                        "name=CASE WHEN (name IS NULL OR name='') THEN ? ELSE name END " +
                        "WHERE store_id=? AND barcode=?",
                new Object[]{now, name, storeId, barcode});
    }

    public void setScanQty(long storeId, String barcode, int qty) {
        if (qty <= 0) { removeScan(storeId, barcode); return; }
        ContentValues v = new ContentValues();
        v.put("qty", qty);
        v.put("updated_at", System.currentTimeMillis());
        int n = getWritableDatabase().update("scan_counts", v, "store_id=? AND barcode=?",
                new String[]{String.valueOf(storeId), barcode});
        if (n == 0) {
            v.put("store_id", storeId);
            v.put("barcode", barcode);
            getWritableDatabase().insert("scan_counts", null, v);
        }
    }

    public void removeScan(long storeId, String barcode) {
        getWritableDatabase().delete("scan_counts", "store_id=? AND barcode=?",
                new String[]{String.valueOf(storeId), barcode});
    }

    public void clearScans(long storeId) {
        getWritableDatabase().delete("scan_counts", "store_id=?", new String[]{String.valueOf(storeId)});
    }

    /** Список отсканированного, недавние сверху. */
    public List<CountRow> listScans(long storeId) {
        List<CountRow> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT barcode,name,qty FROM scan_counts WHERE store_id=? ORDER BY updated_at DESC",
                new String[]{String.valueOf(storeId)});
        while (c.moveToNext()) out.add(new CountRow(c.getString(0), c.getString(1), c.getInt(2)));
        c.close();
        return out;
    }

    public int totalScanQty(long storeId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(qty),0) FROM scan_counts WHERE store_id=?",
                new String[]{String.valueOf(storeId)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    public int totalScanLines(long storeId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM scan_counts WHERE store_id=?",
                new String[]{String.valueOf(storeId)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    // ======================= ПЛАН (EXPECTED) =======================

    public void clearExpected(long storeId) {
        getWritableDatabase().delete("expected", "store_id=?", new String[]{String.valueOf(storeId)});
    }

    /** Записать план из списка строк (полностью заменяет предыдущий план магазина). */
    public void replaceExpected(long storeId, List<CountRow> rows) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("expected", "store_id=?", new String[]{String.valueOf(storeId)});
            for (CountRow r : rows) {
                ContentValues v = new ContentValues();
                v.put("store_id", storeId);
                v.put("barcode", r.barcode);
                v.put("name", r.name);
                v.put("qty", r.qty);
                db.insertWithOnConflict("expected", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Скопировать текущий подсчёт (факт) в план. */
    public void copyScansToExpected(long storeId) {
        replaceExpected(storeId, listScans(storeId));
    }

    public List<CountRow> listExpected(long storeId) {
        List<CountRow> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT barcode,name,qty FROM expected WHERE store_id=? ORDER BY name",
                new String[]{String.valueOf(storeId)});
        while (c.moveToNext()) out.add(new CountRow(c.getString(0), c.getString(1), c.getInt(2)));
        c.close();
        return out;
    }

    public int expectedCount(long storeId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM expected WHERE store_id=?",
                new String[]{String.valueOf(storeId)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }
}
