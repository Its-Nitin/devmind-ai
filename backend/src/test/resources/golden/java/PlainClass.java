package com.example.owners;

import java.util.ArrayList;
import java.util.List;

/**
 * An owner of pets.
 */
public class PlainClass {

    private static final int MAX_PETS = 10;

    /** The display name. */
    private final String name;

    private int visits, cancellations;

    @Deprecated
    private List<String> nicknames = new ArrayList<>();

    public PlainClass(String name) {
        this.name = name;
    }

    /**
     * Returns the name.
     *
     * @return never null
     */
    public String getName() {
        return name;
    }

    // An ordinary comment is not part of the range.
    void recordVisit() {
        visits++;
    }

    static boolean isFull(int pets) {
        return pets >= MAX_PETS;
    }
}
