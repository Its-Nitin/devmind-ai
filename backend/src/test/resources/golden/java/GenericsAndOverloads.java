package com.example.generics;

import java.util.List;
import java.util.Map;

public final class GenericsAndOverloads<T extends Comparable<T>, R> extends Base<T> implements Repository<T>, AutoCloseable {

    public T find(int id) {
        return null;
    }

    public T find(String name) {
        return null;
    }

    public List<T> find(List<Integer> ids) {
        return null;
    }

    public T find(Map<String, List<Integer>> index, java.util.Map.Entry<String, T> hint) {
        return null;
    }

    public void find(String... names) {}

    public <U> U[] map(T[][] items, Class<? extends U> type) {
        return null;
    }

    @Override
    public void close() {}
}
