package com.example.types;

import java.util.Objects;

public enum EnumAndRecord {
    SMALL(1) {
        @Override
        String label() {
            return "s";
        }
    },
    LARGE(10);

    private final int size;

    EnumAndRecord(int size) {
        this.size = size;
    }

    String label() {
        return name().toLowerCase();
    }

    /** A visit with a compact constructor. */
    public record Visit(String petName, java.time.LocalDate date) implements Comparable<Visit> {

        public Visit {
            Objects.requireNonNull(petName);
        }

        public Visit(String petName) {
            this(petName, null);
        }

        static Visit today(String petName) {
            return new Visit(petName, java.time.LocalDate.now());
        }

        @Override
        public int compareTo(Visit other) {
            return petName.compareTo(other.petName);
        }
    }
}
