package com.niganma.noveldown;

/** 搜索结果条目。 */
public class SearchBook {
    public String sourceName;
    public String name;
    public String author;
    public String url;
    public String cover;

    public SearchBook(String sourceName, String name, String author, String url) {
        this.sourceName = sourceName;
        this.name = name;
        this.author = author;
        this.url = url;
    }
}