package org.assertlab.context4docugen;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Test suite for source-backed method identification.
 */
public class MethodIdentifierTest {
    private ProjectMetadata projectMetadata;
    private MethodIdentifier identifier;

    @Before
    public void setUp() throws IOException {
        ProjectAnalyzer analyzer = new ProjectAnalyzer(Paths.get(System.getProperty("user.dir")));
        projectMetadata = analyzer.analyze();
        identifier = new MethodIdentifier(projectMetadata);
    }

    @Test
    public void identifiesMethodsWithUriIdentity() throws IOException {
        List<MethodInfo> methods = identifier.identify();
        assertNotNull("Methods list should not be null", methods);
        assertTrue("Should find at least some methods", methods.size() > 0);

        for (MethodInfo method : methods) {
            assertTrue("ID should be a method URI", method.getId().contains("#"));
            assertTrue("Method URI should include signature", method.getId().contains("("));
            assertTrue("Method URI should include erased return type", method.getId().contains("):"));
        }
    }

    @Test
    public void methodInfoFieldsArePopulated() throws IOException {
        List<MethodInfo> methods = identifier.identify();

        assertTrue("Should find at least one method", methods.size() > 0);
        MethodInfo method = methods.get(0);

        assertNotNull("ID should be set", method.getId());
        assertNotNull("Class name should be set", method.getClassname());
        assertNotNull("Method name should be set", method.getMethodName());
        assertNotNull("Method signature should be set", method.getMethodSignature());
        assertNotNull("Source file should be set", method.getSourceFile());
        assertTrue("Line number should be positive", method.getLineNumber() > 0);
    }

    @Test
    public void methodInfoEqualityUsesUriIdentity() {
        MethodInfo method1 = new MethodInfo.Builder()
                .id("src/main/java/Test.java#Test.test():void")
                .classname("TestClass")
                .methodName("test")
                .methodSignature("test()")
                .sourceFile(Paths.get("Test.java"))
                .lineNumber(1)
                .build();

        MethodInfo method2 = new MethodInfo.Builder()
                .id("src/main/java/Test.java#Test.test():void")
                .classname("TestClass")
                .methodName("test")
                .methodSignature("test()")
                .sourceFile(Paths.get("Test.java"))
                .lineNumber(1)
                .build();

        assertTrue("Methods with same URI identity should be equal", method1.equals(method2));
    }
}
