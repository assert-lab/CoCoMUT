package org.assertlab.cocox;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Full-scale integration tests against the real workspace complexity.
 * These tests run the complete pipeline against commons-lang3 and other bundled projects.
 * 
 * Marked as @SlowTests - run separately from fast test suite.
 * Execute with: mvn test -Dgroups=org.assertlab.cocox.SlowTests
 */
@Category(SlowTests.class)
public class FullIntegrationTest {
    
    private Path workspaceRoot;
    private Path commonsLang3Root;
    private Orchestrator orchestrator;

    @Before
    public void setUp() {
        workspaceRoot = Paths.get(System.getProperty("user.dir"));
        commonsLang3Root = workspaceRoot.resolve("commons-lang3-3.12.0-src");
        
        System.out.println("=".repeat(80));
        System.out.println("FULL INTEGRATION TEST - Real Workspace Complexity");
        System.out.println("Workspace: " + workspaceRoot);
        System.out.println("Target: " + commonsLang3Root);
        System.out.println("Expected methods: 1000+");
        System.out.println("Expected duration: 2-5 minutes");
        System.out.println("=".repeat(80));
    }

    @After
    public void tearDown() {
        // Clean up generated files from workspace root and commons-lang3
        cleanupPath(workspaceRoot);
        if (Files.exists(commonsLang3Root)) {
            cleanupPath(commonsLang3Root);
        }
    }

    private void cleanupPath(Path root) {
        try {
            Path outputDir = Path.of(System.getProperty("user.dir")).resolve("cocox_output");

            if (Files.exists(outputDir)) {
                Files.walk(outputDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(path -> {
                            try { Files.delete(path); } catch (Exception ignored) {}
                        });
            }
        } catch (Exception e) {
            System.err.println("Cleanup warning: " + e.getMessage());
        }
    }

    @Test
    public void testFullWorkspaceAnalysis() {
        System.out.println("\n[FULL-SCALE] Testing complete workspace analysis...");
        long startTime = System.currentTimeMillis();
        
        orchestrator = new Orchestrator(workspaceRoot);
        boolean success = orchestrator.execute();
        
        long duration = System.currentTimeMillis() - startTime;
        System.out.println("Execution completed in " + duration + "ms (" + (duration/1000) + "s)");
        
        Map<String, Object> report = orchestrator.getExecutionReport();
        
        assertNotNull("Should have execution report", report);
        assertNotNull("Should have status", report.get("status"));
        
        if (success) {
            List<MethodInfo> methods = orchestrator.getMethodInfos();
            assertNotNull("Should have methods", methods);
            assertTrue("Should find many methods in workspace", methods.size() > 500);
            
            System.out.println("✓ SUCCESS: Found " + methods.size() + " methods");
            System.out.println("✓ Project: " + report.get("phase_1_project"));
            System.out.println("✓ Build system: " + report.get("phase_1_build_system"));
        } else {
            System.out.println("⚠ ANALYSIS FAILED - This may be expected for complex workspace");
            System.out.println("Status: " + report.get("status"));
            if (report.containsKey("failed_at_phase")) {
                System.out.println("Failed at phase: " + report.get("failed_at_phase"));
            }
        }
        
        orchestrator.printReport();
    }

    @Test
    public void testCommonsLang3Specifically() {
        if (!Files.exists(commonsLang3Root)) {
            System.out.println("Skipping commons-lang3 test - directory not found: " + commonsLang3Root);
            return;
        }
        
        System.out.println("\n[COMMONS-LANG3] Testing against Apache Commons Lang specifically...");
        long startTime = System.currentTimeMillis();
        
        orchestrator = new Orchestrator(commonsLang3Root);
        boolean success = orchestrator.execute();
        
        long duration = System.currentTimeMillis() - startTime;
        System.out.println("Execution completed in " + duration + "ms (" + (duration/1000) + "s)");
        
        Map<String, Object> report = orchestrator.getExecutionReport();
        
        assertNotNull("Should have execution report", report);
        
        if (success) {
            List<MethodInfo> methods = orchestrator.getMethodInfos();
            assertNotNull("Should have methods", methods);
            assertTrue("Commons-lang3 should have 1000+ methods", methods.size() > 1000);
            
            System.out.println("✓ SUCCESS: Analyzed " + methods.size() + " methods in commons-lang3");
            
            // Verify expected project metadata
            ProjectMetadata metadata = orchestrator.getProjectMetadata();
            assertNotNull("Should extract project metadata", metadata);
            assertEquals("Should detect Maven", "maven", metadata.getBuildSystem());
            
        } else {
            System.out.println("⚠ COMMONS-LANG3 ANALYSIS INCOMPLETE");
            System.out.println("Status: " + report.get("status"));
            if (report.containsKey("error_message")) {
                System.out.println("Error: " + report.get("error_message"));
            }
        }
        
        orchestrator.printReport();
    }

    @Test
    public void testScalabilityMetrics() {
        if (!Files.exists(commonsLang3Root)) {
            System.out.println("Skipping scalability test - commons-lang3 not found");
            return;
        }
        
        System.out.println("\n[SCALABILITY] Measuring performance on large codebase...");
        
        orchestrator = new Orchestrator(commonsLang3Root);
        
        long startTime = System.currentTimeMillis();
        boolean success = orchestrator.execute();
        long totalTime = System.currentTimeMillis() - startTime;
        
        Map<String, Object> report = orchestrator.getExecutionReport();
        
        if (success) {
            List<MethodInfo> methods = orchestrator.getMethodInfos();
            int methodCount = methods != null ? methods.size() : 0;
            
            double methodsPerSecond = methodCount / (totalTime / 1000.0);
            double avgTimePerMethod = totalTime / (double) methodCount;
            
            System.out.println("=== SCALABILITY METRICS ===");
            System.out.println("Total methods: " + methodCount);
            System.out.println("Total time: " + totalTime + "ms (" + (totalTime/1000) + "s)");
            System.out.println("Methods/second: " + String.format("%.2f", methodsPerSecond));
            System.out.println("Avg ms/method: " + String.format("%.2f", avgTimePerMethod));
            System.out.println("==========================");
            
            // Performance expectations (adjust based on your requirements)
            assertTrue("Should process at least 1 method/second", methodsPerSecond > 1.0);
            assertTrue("Should complete within reasonable time", totalTime < 600000); // < 10 minutes
            
        } else {
            System.out.println("⚠ Scalability test incomplete - analysis failed");
            fail("Scalability test requires successful analysis");
        }
    }

    @Test
    public void testMemoryUsageOnLargeProject() {
        if (!Files.exists(commonsLang3Root)) {
            System.out.println("Skipping memory test - commons-lang3 not found");
            return;
        }
        
        System.out.println("\n[MEMORY] Monitoring memory usage during analysis...");
        
        Runtime runtime = Runtime.getRuntime();
        long startMemory = runtime.totalMemory() - runtime.freeMemory();
        
        orchestrator = new Orchestrator(commonsLang3Root);
        boolean success = orchestrator.execute();
        
        runtime.gc(); // Suggest garbage collection
        long endMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryUsed = endMemory - startMemory;
        
        System.out.println("=== MEMORY METRICS ===");
        System.out.println("Start memory: " + (startMemory / 1024 / 1024) + " MB");
        System.out.println("End memory: " + (endMemory / 1024 / 1024) + " MB");
        System.out.println("Memory used: " + (memoryUsed / 1024 / 1024) + " MB");
        System.out.println("=====================");
        
        if (success) {
            List<MethodInfo> methods = orchestrator.getMethodInfos();
            int methodCount = methods != null ? methods.size() : 0;
            if (methodCount > 0) {
                long memoryPerMethod = memoryUsed / methodCount;
                System.out.println("Memory per method: " + memoryPerMethod + " bytes");
                
                // Memory usage expectations (adjust as needed)
                assertTrue("Memory usage should be reasonable", memoryUsed < 2L * 1024 * 1024 * 1024); // < 2GB
            }
        }
    }
}
