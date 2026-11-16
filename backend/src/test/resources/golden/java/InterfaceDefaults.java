package com.example.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface InterfaceDefaults<T> extends Comparable<T> {

    String PREFIX = "pet-";

    String id();

    default String displayId() {
        return PREFIX + id();
    }

    static <T> InterfaceDefaults<T> none() {
        return null;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface Audited {
        String value() default "";
    }
}
