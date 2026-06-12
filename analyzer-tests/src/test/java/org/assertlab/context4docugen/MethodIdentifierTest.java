package org.assertlab.context4docugen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 * Test suite for MethodIdentifier Phase 2 component
 */
public class MethodIdentifierTest {
    private ProjectMetadata projectMetadata;
    private MethodIdentifier identifier;

    @Before
    public void setUp() throws IOException {
        // Analyze the current project to get metadata
        ProjectAnalyzer analyzer = new ProjectAnalyzer(Paths.get(System.getProperty("user.dir")));
        projectMetadata = analyzer.analyze();
        
        // Create identifier with default settings
        identifier = new MethodIdentifier(projectMetadata);
    }

    @Test
    public void testMethodIdentification() throws IOException {
        List<MethodInfo> methods = identifier.identify();
        assertNotNull("Methods list should not be null", methods);
        assertTrue("Should find at least some methods", methods.size() > 0);
    }

    @Test
    public void testUriGenerationIgnoresLegacySequentialStrategy() throws IOException {
        MethodIdentifier seqIdentifier = new MethodIdentifier(
                projectMetadata, 
                MethodIdentifier.IdStrategy.SEQUENTIAL, 
                null,
                Paths.get(System.getProperty("user.dir"))
        );
        List<MethodInfo> methods = seqIdentifier.identify();
        
        assertTrue("Should have identified methods", methods.size() > 0);
        
        for (MethodInfo method : methods) {
            assertTrue("ID should be a method URI", method.getId().contains("#"));
            assertTrue("Method URI should include signature", method.getId().contains("("));
        }
    }

    @Test
    public void testUriGenerationIgnoresLegacyUuidStrategy() throws IOException {
        MethodIdentifier uuidIdentifier = new MethodIdentifier(
                projectMetadata,
                MethodIdentifier.IdStrategy.UUID,
                null,
                Paths.get(System.getProperty("user.dir"))
        );
        List<MethodInfo> methods = uuidIdentifier.identify();
        
        assertTrue("Should have identified methods", methods.size() > 0);
        
        for (MethodInfo method : methods) {
            String id = method.getId();
            assertNotNull("ID should not be null", id);
            assertTrue("ID should be a method URI", id.contains("#"));
        }
    }

    @Test
    public void testUriGenerationIgnoresLegacyHashStrategy() throws IOException {
        MethodIdentifier hashIdentifier = new MethodIdentifier(
                projectMetadata,
                MethodIdentifier.IdStrategy.HASH,
                null,
                Paths.get(System.getProperty("user.dir"))
        );
        List<MethodInfo> methods = hashIdentifier.identify();
        
        assertTrue("Should have identified methods", methods.size() > 0);
        
        for (MethodInfo method : methods) {
            String id = method.getId();
            assertNotNull("ID should not be null", id);
            assertTrue("ID should be a method URI", id.contains("#"));
        }
    }

    @Test
    public void testMethodInfoFields() throws IOException {
        List<MethodInfo> methods = identifier.identify();
        
        if (methods.size() > 0) {
            MethodInfo method = methods.get(0);
            
            assertNotNull("ID should be set", method.getId());
            assertNotNull("Class name should be set", method.getClassname());
            assertNotNull("Method name should be set", method.getMethodName());
            assertNotNull("Method signature should be set", method.getMethodSignature());
            assertNotNull("Source file should be set", method.getSourceFile());
            assertTrue("Line number should be positive", method.getLineNumber() > 0);
        }
    }

    @Test
    public void testCsvOutput() throws IOException {
        Path tempDir = Files.createTempDirectory("method-identifier-test");
        Path csvPath = tempDir.resolve("methods.csv");
        
        try {
            MethodIdentifier csvIdentifier = new MethodIdentifier(
                    projectMetadata,
                    MethodIdentifier.IdStrategy.SEQUENTIAL,
                    null,
                    tempDir
            );
            
            List<MethodInfo> methods = csvIdentifier.identifyAndWriteCsv(csvPath);
            
            assertTrue("CSV file should be created", Files.exists(csvPath));
            assertTrue("CSV file should not be empty", Files.size(csvPath) > 0);
            
            // Check CSV content
            List<String> lines = Files.readAllLines(csvPath);
            assertTrue("CSV should have header row", lines.size() > 1);
            assertEquals("First line should be header",
                    "method_uri|classname|methodname|filepath|line_number|signature", lines.get(0));
            
            // Check data rows
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                String[] parts = line.split("\\|", 6);
                assertTrue("Each row should expose at least method_uri, class, method, and file",
                        parts.length >= 4);
            }
        } finally {
            // Cleanup
            Files.deleteIfExists(csvPath);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    public void testSampling() throws IOException {
        List<MethodInfo> allMethods = identifier.identify();
        
        if (allMethods.size() > 10) {
            MethodIdentifier samplingIdentifier = new MethodIdentifier(
                    projectMetadata,
                    MethodIdentifier.IdStrategy.SEQUENTIAL,
                    5,  // Sample only 5 methods
                    Paths.get(System.getProperty("user.dir"))
            );
            
            List<MethodInfo> sampledMethods = samplingIdentifier.identify();
            
            assertEquals("Should have exactly 5 sampled methods", 5, sampledMethods.size());
            assertTrue("Sampled methods should be subset of all methods", sampledMethods.size() <= allMethods.size());
        }
    }

    @Test
    public void testMethodToCsvRow() {
        MethodInfo method = new MethodInfo.Builder()
                .id("123")
                .classname("com.example.MyClass")
                .methodName("testMethod")
                .methodSignature("testMethod(String, int)")
                .sourceFile(Paths.get("/path/to/MyClass.java"))
                .lineNumber(42)
                .columnNumber(0)
                .visibility("public")
                .isStatic(true)
                .returnType("void")
                .build();
        
        String csvRow = method.toCsvRow();
        String[] parts = csvRow.split("\\|");
        
        assertEquals("CSV row should have 6 parts", 6, parts.length);
        assertEquals("First part should be method URI", "123", parts[0]);
        assertEquals("Second part should be classname", "com.example.MyClass", parts[1]);
        assertEquals("Third part should be method name", "testMethod", parts[2]);
        assertEquals("Fourth part should be line number be present", "42", parts[4]);
        assertEquals("Sixth part should be signature", "testMethod(String, int)", parts[5]);
    }

    @Test
    public void testMethodInfoEquality() {
        MethodInfo method1 = new MethodInfo.Builder()
                .id("1")
                .classname("TestClass")
                .methodName("test")
                .methodSignature("test()")
                .sourceFile(Paths.get("Test.java"))
                .lineNumber(1)
                .build();
        
        MethodInfo method2 = new MethodInfo.Builder()
                .id("1")
                .classname("TestClass")
                .methodName("test")
                .methodSignature("test()")
                .sourceFile(Paths.get("Test.java"))
                .lineNumber(1)
                .build();
        
        assertEquals("Methods with same id, classname, and name should be equal", method1, method2);
    }

    @Test
    public void testDefaultCsvPath() {
        Path outputDir = Paths.get("/output");
        MethodIdentifier customIdentifier = new MethodIdentifier(
                projectMetadata,
                MethodIdentifier.IdStrategy.SEQUENTIAL,
                null,
                outputDir
        );
        
        Path csvPath = customIdentifier.getDefaultCsvPath();
        assertEquals("Default CSV path should be output/methods.csv", outputDir.resolve("methods.csv"), csvPath);
    }
}
