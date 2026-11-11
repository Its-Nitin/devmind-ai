package com.devmind.project.internal.web;

import java.util.Arrays;
import org.springframework.data.domain.Sort;

/**
 * The sort whitelist for project lists (API-6, DB-5). Clients send {@code field[,asc|desc]}; anything else is rejected
 * by {@link #PATTERN} before it reaches this class.
 */
enum ProjectSort {
    NAME("name"),
    CREATED_AT("createdAt");

    static final String PATTERN = "^(name|createdAt)(,(asc|desc))?$";
    static final String DEFAULT = "createdAt,desc";

    private final String property;

    ProjectSort(String property) {
        this.property = property;
    }

    /**
     * Turns a value matching {@link #PATTERN} into a sort, with the internal ID as a tie-breaker so pages are stable.
     */
    static Sort parse(String value) {
        String[] parts = value.split(",", 2);
        ProjectSort field = Arrays.stream(values())
                .filter(candidate -> candidate.property.equals(parts[0]))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Sort field not whitelisted"));
        Sort.Direction direction = parts.length == 2 ? Sort.Direction.fromString(parts[1]) : Sort.Direction.ASC;
        return Sort.by(direction, field.property).and(Sort.by(direction, "id"));
    }
}
