package com.warehouse.scanner.util;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Лёгкий читатель файлов .xlsx без внешних библиотек (без Apache POI).
 *
 * Файл .xlsx — это zip-архив с XML внутри. Мы читаем:
 *   - xl/sharedStrings.xml — общие строки (если есть),
 *   - xl/workbook.xml + xl/_rels/workbook.xml.rels — список листов,
 *   - xl/worksheets/sheetN.xml — сами данные.
 *
 * Важно: поддерживаются и общие строки (t="s"), и inline-строки (t="inlineStr"),
 * потому что выгрузки Ozon хранят данные именно как inline-строки.
 */
public class XlsxReader implements Closeable {

    private final ZipFile zip;
    private final String[] shared;
    private final List<String[]> sheets = new ArrayList<>(); // элемент: [имя листа, путь к xml]

    public XlsxReader(File file) throws Exception {
        zip = new ZipFile(file);
        shared = readSharedStrings();
        readSheetList();
    }

    /** Имена листов книги в порядке следования. */
    public List<String> sheetNames() {
        List<String> out = new ArrayList<>();
        for (String[] s : sheets) out.add(s[0]);
        return out;
    }

    public int sheetCount() { return sheets.size(); }

    /** Прочитать все строки листа по индексу. Каждая строка — список ячеек (текст). */
    public List<List<String>> readSheet(int index) throws Exception {
        if (index < 0 || index >= sheets.size()) return new ArrayList<>();
        String path = sheets.get(index)[1];
        ZipEntry e = zip.getEntry(path);
        if (e == null) return new ArrayList<>();
        try (InputStream in = zip.getInputStream(e)) {
            return parseSheet(in);
        }
    }

    // ---------- разбор общих строк ----------

    private String[] readSharedStrings() {
        ZipEntry e = zip.getEntry("xl/sharedStrings.xml");
        if (e == null) return new String[0];
        List<String> out = new ArrayList<>();
        try (InputStream in = zip.getInputStream(e)) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = null;
            boolean inT = false;
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    if (n.equals("si")) sb = new StringBuilder();
                    else if (n.equals("t")) inT = true;
                } else if (ev == XmlPullParser.TEXT) {
                    if (inT && sb != null) sb.append(p.getText());
                } else if (ev == XmlPullParser.END_TAG) {
                    String n = p.getName();
                    if (n.equals("t")) inT = false;
                    else if (n.equals("si")) { out.add(sb == null ? "" : sb.toString()); sb = null; }
                }
                ev = p.next();
            }
        } catch (Exception ignore) {
            return out.toArray(new String[0]);
        }
        return out.toArray(new String[0]);
    }

    // ---------- список листов ----------

    private void readSheetList() {
        // 1) rId -> target из rels
        List<String[]> rels = new ArrayList<>(); // [rid, target]
        ZipEntry relsE = zip.getEntry("xl/_rels/workbook.xml.rels");
        if (relsE != null) {
            try (InputStream in = zip.getInputStream(relsE)) {
                XmlPullParser p = Xml.newPullParser();
                p.setInput(new InputStreamReader(in, "UTF-8"));
                int ev = p.getEventType();
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && p.getName().equals("Relationship")) {
                        String id = p.getAttributeValue(null, "Id");
                        String target = p.getAttributeValue(null, "Target");
                        if (id != null && target != null) rels.add(new String[]{id, target});
                    }
                    ev = p.next();
                }
            } catch (Exception ignore) { }
        }

        // 2) имена листов + rId из workbook.xml
        ZipEntry wbE = zip.getEntry("xl/workbook.xml");
        if (wbE != null) {
            try (InputStream in = zip.getInputStream(wbE)) {
                XmlPullParser p = Xml.newPullParser();
                p.setInput(new InputStreamReader(in, "UTF-8"));
                int ev = p.getEventType();
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && p.getName().equals("sheet")) {
                        String name = p.getAttributeValue(null, "name");
                        String rid = null;
                        for (int i = 0; i < p.getAttributeCount(); i++) {
                            if (p.getAttributeName(i).endsWith("id")) { rid = p.getAttributeValue(i); break; }
                        }
                        String target = resolveTarget(rels, rid);
                        if (name != null && target != null) sheets.add(new String[]{name, target});
                    }
                    ev = p.next();
                }
            } catch (Exception ignore) { }
        }

        // запасной вариант: если список пуст — берём все worksheets/*.xml
        if (sheets.isEmpty()) {
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            int i = 1;
            while (en.hasMoreElements()) {
                ZipEntry z = en.nextElement();
                if (z.getName().startsWith("xl/worksheets/") && z.getName().endsWith(".xml")) {
                    sheets.add(new String[]{"Лист " + (i++), z.getName()});
                }
            }
        }
    }

    private String resolveTarget(List<String[]> rels, String rid) {
        if (rid == null) return null;
        for (String[] r : rels) {
            if (r[0].equals(rid)) {
                String t = r[1];
                if (t.startsWith("/")) return t.substring(1);       // "/xl/worksheets/sheet2.xml"
                if (t.startsWith("xl/")) return t;
                return "xl/" + t;                                     // "worksheets/sheet1.xml"
            }
        }
        return null;
    }

    // ---------- разбор листа ----------

    private List<List<String>> parseSheet(InputStream in) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new InputStreamReader(in, "UTF-8"));

        java.util.TreeMap<Integer, String> cur = null;
        int curCol = -1;
        String curType = null;
        StringBuilder cell = null;
        boolean capture = false;

        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String n = p.getName();
                if (n.equals("row")) {
                    cur = new java.util.TreeMap<>();
                } else if (n.equals("c")) {
                    String ref = p.getAttributeValue(null, "r");
                    curCol = ref != null ? colOf(ref) : (cur == null || cur.isEmpty() ? 0 : cur.lastKey() + 1);
                    curType = p.getAttributeValue(null, "t");
                    cell = new StringBuilder();
                } else if (n.equals("v") || n.equals("t")) {
                    capture = true;
                }
            } else if (ev == XmlPullParser.TEXT) {
                if (capture && cell != null) cell.append(p.getText());
            } else if (ev == XmlPullParser.END_TAG) {
                String n = p.getName();
                if (n.equals("v") || n.equals("t")) {
                    capture = false;
                } else if (n.equals("c")) {
                    if (cur != null) cur.put(curCol, resolve(curType, cell == null ? "" : cell.toString()));
                } else if (n.equals("row")) {
                    List<String> rowList = new ArrayList<>();
                    if (cur != null && !cur.isEmpty()) {
                        int max = cur.lastKey();
                        for (int i = 0; i <= max; i++) {
                            String s = cur.get(i);
                            rowList.add(s == null ? "" : s);
                        }
                    }
                    rows.add(rowList);
                    cur = null;
                }
            }
            ev = p.next();
        }
        return rows;
    }

    private String resolve(String type, String raw) {
        if (raw == null) raw = "";
        if (type == null) return raw;
        switch (type) {
            case "s":
                try {
                    int idx = Integer.parseInt(raw.trim());
                    return (idx >= 0 && idx < shared.length) ? shared[idx] : "";
                } catch (Exception e) { return ""; }
            case "b":
                return raw.equals("1") ? "TRUE" : "FALSE";
            case "inlineStr":
            case "str":
            case "n":
            default:
                return raw;
        }
    }

    /** "A"->0, "B"->1, ... "AA"->26. Из ссылки вида "C4" берём только буквы. */
    private static int colOf(String ref) {
        int col = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') col = col * 26 + (ch - 'A' + 1);
            else if (ch >= 'a' && ch <= 'z') col = col * 26 + (ch - 'a' + 1);
            else break;
        }
        return col - 1;
    }

    @Override
    public void close() {
        try { zip.close(); } catch (Exception ignore) { }
    }
}
