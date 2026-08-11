package org.assertlab.cocomut.source;

import static org.junit.Assert.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;

public class JavadocResolutionDiagnosticTest {

    @Test
    public void resolverAndSchemaUseTheSameDiagnosticVocabulary() throws Exception {
        Path repositoryRoot = Paths.get(System.getProperty("user.dir")).getParent();
        JsonNode values = new ObjectMapper()
                .readTree(repositoryRoot.resolve("schemas/method-context.schema.json").toFile())
                .path("$defs")
                .path("effectiveDocumentationItem")
                .path("properties")
                .path("diagnostic_code")
                .path("enum");

        Set<String> schemaValues = new LinkedHashSet<>();
        values.forEach(value -> schemaValues.add(value.asText()));
        assertEquals(JavadocResolutionDiagnostic.ids(), schemaValues);
    }

    @Test
    public void resolverAndSchemaUseTheSameInheritanceModeVocabulary() throws Exception {
        Path repositoryRoot = Paths.get(System.getProperty("user.dir")).getParent();
        JsonNode values = new ObjectMapper()
                .readTree(repositoryRoot.resolve("schemas/method-context.schema.json").toFile())
                .path("$defs")
                .path("effectiveDocumentationItem")
                .path("properties")
                .path("inheritance_mode")
                .path("enum");

        Set<String> schemaValues = new LinkedHashSet<>();
        values.forEach(value -> schemaValues.add(value.asText()));
        assertEquals(JavadocInheritanceMode.ids(), schemaValues);
    }
}
