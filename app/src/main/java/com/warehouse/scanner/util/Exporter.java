package com.warehouse.scanner.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.warehouse.scanner.db.Models.CountRow;
import com.warehouse.scanner.db.Models.DiffRow;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Экспорт результатов в CSV-файл и передача его в другое приложение
 * (почта, мессенджер, файловый менеджер) через системное меню «Поделиться».
 *
 * Файлы сохраняются в папку exports/ внутри каталога приложения,
 * доступ к ним для других приложений выдаётся через FileProvider.
 *
 * CSV пишется с разделителем «;» и BOM в начале — так Microsoft Excel
 * сразу открывает файл с правильной кириллицей и разбивкой по столбцам.
 */
public class Exporter {

    /** Папка для экспортов внутри приложения. */
    private static File exportsDir(Context c) {
        File dir = new File(c.getExternalFilesDir(null), "exports");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    /** Экранирование значения для CSV. */
    private static String esc(String s) {
        if (s == null) s = "";
        boolean quote = s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        s = s.replace("\"", "\"\"");
        return quote ? "\"" + s + "\"" : s;
    }

    /** Экспорт списка подсчёта (факт сканирования). Возвращает файл. */
    public static File exportCounts(Context c, String storeName, List<CountRow> rows) throws Exception {
        File f = new File(exportsDir(c), "scan_" + stamp() + ".csv");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), Charset.forName("UTF-8"))) {
            w.write('\uFEFF'); // BOM для Excel
            w.write("Магазин;" + esc(storeName) + "\n");
            w.write("Штрихкод;Название;Количество\n");
            int total = 0;
            for (CountRow r : rows) {
                w.write(esc(r.barcode) + ";" + esc(r.name) + ";" + r.qty + "\n");
                total += r.qty;
            }
            w.write(";ИТОГО позиций;" + rows.size() + "\n");
            w.write(";ИТОГО единиц;" + total + "\n");
        }
        return f;
    }

    /** Экспорт отчёта сверки (план/факт/расхождения). Возвращает файл. */
    public static File exportReconcile(Context c, String storeName, List<DiffRow> rows) throws Exception {
        File f = new File(exportsDir(c), "reconcile_" + stamp() + ".csv");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), Charset.forName("UTF-8"))) {
            w.write('\uFEFF');
            w.write("Магазин;" + esc(storeName) + "\n");
            w.write("Штрихкод;Название;План;Факт;Расхождение;Статус\n");
            for (DiffRow r : rows) {
                w.write(esc(r.barcode) + ";" + esc(r.name) + ";" + r.expected + ";" + r.fact
                        + ";" + r.diff() + ";" + statusText(r.status) + "\n");
            }
        }
        return f;
    }

    private static String statusText(int status) {
        switch (status) {
            case 0: return "Совпадает";
            case 1: return "Недостача";
            case 2: return "Излишек";
            case 3: return "Не отсканирован";
            case 4: return "Лишний (нет в плане)";
            default: return "";
        }
    }

    /** Открыть системное меню «Поделиться» для файла. */
    public static void share(Context c, File file) {
        Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", file);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        c.startActivity(Intent.createChooser(i, file.getName()));
    }
}
