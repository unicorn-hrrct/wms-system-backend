package com.example.demo.common;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

public class PageResult<T> {

    private final long total;
    private final long pageNum;
    private final long pageSize;
    private final long pages;
    private final List<T> list;

    private PageResult(long total, long pageNum, long pageSize, long pages, List<T> list) {
        this.total = total;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
        this.pages = pages;
        this.list = list;
    }

    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(
            page.getTotal(),
            page.getCurrent(),
            page.getSize(),
            page.getPages(),
            page.getRecords()
        );
    }

    public long getTotal() {
        return total;
    }

    public long getPageNum() {
        return pageNum;
    }

    public long getPageSize() {
        return pageSize;
    }

    public long getPages() {
        return pages;
    }

    public List<T> getList() {
        return list;
    }
}
