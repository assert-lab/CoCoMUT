package org.assertlab.cocox;

/**
 * Marker interface for slow integration tests.
 * 
 * Usage:
 * - Run fast tests only: mvn test (excludes SlowTests by default)
 * - Run slow tests only: mvn test -Dgroups=org.assertlab.cocox.SlowTests
 * - Run all tests: mvn test -Dgroups=org.assertlab.cocox.SlowTests,org.assertlab.cocox.FastTests
 */
public interface SlowTests {
}