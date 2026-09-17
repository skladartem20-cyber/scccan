package com.warehouse.scanner.db;

/**
 * Простые модели данных приложения.
 */
public class Models {

    /** Магазин = именованная база товаров. */
    public static class Store {
        public long id;
        public String name;
        public String note;
        public long createdAt;
        public int productCount;

        public Store(long id, String name, String note, long createdAt, int productCount) {
            this.id = id; this.name = name; this.note = note;
            this.createdAt = createdAt; this.productCount = productCount;
        }
    }

    /** Товар в базе конкретного магазина. */
    public static class Product {
        public String barcode;
        public String name;
        public String article;

        public Product(String barcode, String name, String article) {
            this.barcode = barcode == null ? "" : barcode.trim();
            this.name = name == null ? "" : name.trim();
            this.article = article == null ? "" : article.trim();
        }
    }

    /** Строка подсчёта / плана: штрихкод + название + количество. */
    public static class CountRow {
        public String barcode;
        public String name;
        public int qty;

        public CountRow(String barcode, String name, int qty) {
            this.barcode = barcode; this.name = name; this.qty = qty;
        }
    }

    /** Строка результата сверки. */
    public static class DiffRow {
        public String barcode;
        public String name;
        public int expected;
        public int fact;
        /** 0=совпадает,1=недостача,2=излишек,3=не отсканирован,4=лишний */
        public int status;

        public int diff() { return fact - expected; }
    }
}
