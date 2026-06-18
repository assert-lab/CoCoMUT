package poc;

/**
 * Fixture for method URI matching edge cases.
 */
public class SelectedMethodMatchingFixture {
    private final String label;

    /**
     * Constructor with no return type.
     *
     * @param label stored label
     */
    public SelectedMethodMatchingFixture(String label) {
        this.label = label;
    }

    /**
     * Control method that the current regex-based loader can locate.
     *
     * @param left left operand
     * @param right right operand
     * @return sum of both operands
     */
    public int add(int left, int right) {
        return left + right;
    }

    /**
     * Package-private generic method with a bounded type parameter.
     *
     * @param a first value
     * @param b second value
     * @param <T> comparable value type
     * @return the greater value
     */
    <T extends Comparable<T>> T max(final T a, final T b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /**
     * Method with an annotation before the declaration.
     *
     * @param input input value
     * @return input value
     */
    @Deprecated
    public String annotated(String input) {
        return label + input;
    }

    /**
     * Nested class used to verify that selected matching preserves declaring
     * class identity, not only source file identity.
     */
    public static class Nested {
        /**
         * Returns a nested value.
         *
         * @return nested value
         */
        public String value() {
            return "nested";
        }
    }
}

/**
 * Record fixture for compact constructor matching.
 *
 * @param start start value
 * @param end end value
 */
record RangeFixture(int start, int end) {
    /**
     * Compact constructor with no explicit parameter list.
     */
    RangeFixture {
        if (start > end) {
            throw new IllegalArgumentException("start > end");
        }
    }
}
