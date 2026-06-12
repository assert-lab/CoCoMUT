package poc;

/**
 * Fixture for undergrad PoCs U1 and U2.
 */
public class UndergradIssueFixture {
    /**
     * Generic parameter fixture.
     *
     * @param first first choice
     * @param second fallback choice
     * @param <T> value type
     * @return first if non-null, otherwise second
     */
    public <T> T choose(final T first, T second) {
        return first != null ? first : second;
    }
}
