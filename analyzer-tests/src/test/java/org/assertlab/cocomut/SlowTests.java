package org.assertlab.cocomut;

/**
 * Marker interface for slow integration tests.
 * 
 * Usage:
 * - Run fast tests only: mvn test (excludes SlowTests by default)
 * - Run slow tests only: mvn test -Dgroups=org.assertlab.cocomut.SlowTests
 * - Run all tests: mvn test -Dgroups=org.assertlab.cocomut.SlowTests,org.assertlab.cocomut.FastTests
 */
public interface SlowTests {
}