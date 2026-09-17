package com.warehouse.scanner.util;

import com.warehouse.scanner.db.Models.CountRow;
import com.warehouse.scanner.db.Models.Product;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Помощник для «умного» разбора импортируемой таблицы.
 *
 * Определяет: строку заголовков, столбец штрихкода, столбец названия,
 * столбец артикула и столбец количества — по ключевым словам на русском,
 * китайском и английском. Пользователь потом может поправить выбор вручную.
 */
public class ColumnGuesser {

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    /** Найти строку заголовков: ищем строку, где есть и штрихкод, и название. */
    public static int guessHeaderRow(List<List<String>> rows) {
        int limit = Math.min(rows.size(), 30);
        for (int i = 0; i < limit; i++) {
            List<String> r = rows.get(i);
            boolean hasBc = false, hasName = false;
            for (String cell : r) {
                String s = norm(cell);
                if (containsAny(s, "штрихкод", "barcode", "条形码", "条码", "штрих-код")) hasBc = true;
                if (containsAny(s, "назван", "наимен", "名称", "商品名", "product name", "товар")) hasName = true;
            }
            if (hasBc && hasName) return i;
        }
        // если не нашли по двум признакам — первая строка с ключом штрихкода
        for (int i = 0; i < limit; i++) {
            for (String cell : rows.get(i)) {
                if (containsAny(norm(cell), "штрихкод", "barcode", "条形码", "条码")) return i;
            }
        }
        return 0;
    }

    /** Определить столбец штрихкода. Избегаем «уникальный/сгенерировать/вручную». */
    public static int guessBarcodeCol(List<String> header) {
        int best = -1;
        for (int i = 0; i < header.size(); i++) {
            String s = norm(header.get(i));
            if (!containsAny(s, "штрихкод", "barcode", "条形码", "条码", "штрих-код")) continue;
            if (containsAny(s, "уникальн", "unique", "сгенер", "generate", "вручну", "manual", "ошибк", "error"))
                continue;
            // предпочитаем самый короткий/точный заголовок
            if (best == -1 || s.length() < norm(header.get(best)).length()) best = i;
        }
        if (best != -1) return best;
        // запасной вариант — любой столбец с «штрихкод»
        for (int i = 0; i < header.size(); i++)
            if (containsAny(norm(header.get(i)), "штрихкод", "barcode", "条")) return i;
        return 0;
    }

    /** Определить столбец названия товара. */
    public static int guessNameCol(List<String> header) {
        for (int i = 0; i < header.size(); i++) {
            String s = norm(header.get(i));
            if (containsAny(s, "назван", "наимен", "名称", "商品名", "product name")) return i;
        }
        for (int i = 0; i < header.size(); i++) {
            String s = norm(header.get(i));
            if (containsAny(s, "товар", "name", "名") && !containsAny(s, "id", "артикул", "article")) return i;
        }
        return -1;
    }

    /** Определить столбец артикула (необязательный). */
    public static int guessArticleCol(List<String> header) {
        for (int i = 0; i < header.size(); i++) {
            String s = norm(header.get(i));
            if (containsAny(s, "артикул", "article", "货号", "sku")) return i;
        }
        return -1;
    }

    /** Определить столбец количества (для импорта плана). */
    public static int guessQtyCol(List<String> header) {
        for (int i = 0; i < header.size(); i++) {
            String s = norm(header.get(i));
            if (containsAny(s, "кол-во", "количество", "qty", "quantity", "数量", "кол.")) return i;
        }
        return -1;
    }

    private static String get(List<String> row, int col) {
        if (col < 0 || col >= row.size()) return "";
        String v = row.get(col);
        return v == null ? "" : v.trim();
    }

    private static boolean isJunkBarcode(String bc) {
        if (bc.isEmpty()) return true;
        if (bc.length() > 40) return true;
        String s = bc.toLowerCase(Locale.ROOT);
        // строки-описания из шапки Ozon
        return s.contains("нередактируемое") || s.contains("редактируемое")
                || s.contains("обязательное") || s.startsWith("уникальный");
    }

    /**
     * Извлечь товары из таблицы. Пропускает пустые/служебные строки,
     * удаляет дубликаты по штрихкоду (оставляет первый).
     */
    public static List<Product> extractProducts(List<List<String>> rows, int headerRow,
                                                int bcCol, int nameCol, int artCol) {
        LinkedHashMap<String, Product> map = new LinkedHashMap<>();
        for (int i = headerRow + 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            String bc = get(r, bcCol);
            if (isJunkBarcode(bc)) continue;
            String name = get(r, nameCol);
            String art = get(r, artCol);
            if (!map.containsKey(bc)) map.put(bc, new Product(bc, name, art));
        }
        return new ArrayList<>(map.values());
    }

    /**
     * Извлечь план (штрихкод + количество) из таблицы.
     * Одинаковые штрихкоды суммируются.
     */
    public static List<CountRow> extractExpected(List<List<String>> rows, int headerRow,
                                                 int bcCol, int nameCol, int qtyCol) {
        LinkedHashMap<String, CountRow> map = new LinkedHashMap<>();
        for (int i = headerRow + 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            String bc = get(r, bcCol);
            if (isJunkBarcode(bc)) continue;
            String name = get(r, nameCol);
            int qty = parseQty(get(r, qtyCol));
            CountRow ex = map.get(bc);
            if (ex == null) {
                map.put(bc, new CountRow(bc, name, qty));
            } else {
                ex.qty += qty;
                if ((ex.name == null || ex.name.isEmpty()) && !name.isEmpty()) ex.name = name;
            }
        }
        return new ArrayList<>(map.values());
    }

    /** Разобрать количество из ячейки (допускает «5», «5.0», «5,0», « 5 шт»). */
    public static int parseQty(String s) {
        if (s == null) return 0;
        StringBuilder digits = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch >= '0' && ch <= '9') digits.append(ch);
            else if ((ch == '.' || ch == ',') && digits.length() > 0) break; // берём целую часть
        }
        if (digits.length() == 0) return 0;
        try {
            long v = Long.parseLong(digits.toString());
            return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
