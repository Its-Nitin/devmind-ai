package com.example.nesting;

public class NestedTypes {

    static class StaticNested {
        int depth() {
            return 1;
        }

        class Deeper {
            int depth() {
                return 2;
            }
        }
    }

    class Inner {
        private final String label = "inner";
    }

    interface Callback {
        void call();
    }

    enum Mode {
        ON,
        OFF
    }

    void useLocalAndAnonymousClasses() {
        class Local {}
        Runnable task = new Runnable() {
            @Override
            public void run() {}
        };
    }
}
