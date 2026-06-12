package com.example;

/**
 * Tiny fixture type for pipeline integration tests.
 */
public final class Hello {

    public String greet(String name) {
        return prefix() + name;
    }

    private String prefix() {
        return "Hello, ";
    }

    public static void main(String[] args) {
        System.out.println(new Hello().greet("World"));
    }
}
