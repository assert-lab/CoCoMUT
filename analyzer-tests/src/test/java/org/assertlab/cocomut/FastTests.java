package org.assertlab.cocomut;

/**
 * Marker interface for fast unit/integration tests.
 * 
 * Usage:
 * - Run fast tests only: mvn test (default)
 * - Run fast tests explicitly: mvn test -Dgroups=org.assertlab.cocomut.FastTests
 */
public interface FastTests {
}