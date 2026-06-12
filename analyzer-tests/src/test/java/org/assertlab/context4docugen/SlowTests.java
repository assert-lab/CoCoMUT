package org.assertlab.context4docugen;

/**
 * Marker interface for slow integration tests.
 * 
 * Usage:
 * - Run fast tests only: mvn test (excludes SlowTests by default)
 * - Run slow tests only: mvn test -Dgroups=org.assertlab.context4docugen.SlowTests
 * - Run all tests: mvn test -Dgroups=org.assertlab.context4docugen.SlowTests,org.assertlab.context4docugen.FastTests
 */
public interface SlowTests {
}