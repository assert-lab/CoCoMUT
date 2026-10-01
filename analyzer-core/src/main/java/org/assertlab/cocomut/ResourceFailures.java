package org.assertlab.cocomut;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Resource failures must not be converted into recoverable analysis warnings. */
final class ResourceFailures {
    private ResourceFailures() {}

    static Error find(Throwable failure) {
        // Libraries may wrap VM resource errors in ordinary runtime exceptions.
        // Identity tracking also makes malformed cyclic cause chains safe.
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof OutOfMemoryError || current instanceof StackOverflowError) {
                return (Error) current;
            }
        }
        return null;
    }

    static void rethrowIfPresent(Throwable failure) {
        Error resourceFailure = find(failure);
        if (resourceFailure != null) {
            throw resourceFailure;
        }
    }
}
