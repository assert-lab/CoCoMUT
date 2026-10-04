package org.assertlab.cocomut;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.assertlab.cocomut.source.ProjectModel;
import org.assertlab.cocomut.source.SourceBackends;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Opt-in end-to-end regressions against the pinned subjects from #22 and #23. */
public class JavadocPinnedSubjectsTest {
    @Test
    public void jedisImportedBuilderLinkResolves() throws Exception {
        JsonNode row = extract("cocomut.jedisProject", "60b6eaa041aac701f5a5c52a410eafc4a9d81c3c",
                "src/main/java/redis/clients/jedis/builders/AbstractClientBuilder.java#redis.clients.jedis.builders.AbstractClientBuilder.applyDeprecatedCommandObjectFields(redis.clients.jedis.DefaultJedisClientConfig$Builder):redis.clients.jedis.DefaultJedisClientConfig$Builder");
        JsonNode refs = row.path("javadoc_metadata").path("javadoc_references");
        int matches = 0;
        for (JsonNode ref : refs) {
            if (!"DefaultJedisClientConfig.Builder".equals(ref.path("target").asText())) {
                continue;
            }
            matches++;
            assertEquals("resolved_type", ref.path("resolution").asText());
            assertEquals("project", ref.path("reference_domain").asText());
            assertEquals("src/main/java/redis/clients/jedis/DefaultJedisClientConfig.java#redis.clients.jedis.DefaultJedisClientConfig$Builder",
                    ref.path("type_uri").asText());
        }
        assertTrue("Missing audited Builder reference: " + refs, matches > 0);
    }

    @Test
    public void fastjsonMethodTypeParameterNeverResolvesToBenchmarkType() throws Exception {
        JsonNode row = extract("cocomut.fastjsonProject", "3697c2d37cd659d2a94543093d0d08cb4baf4d73",
                "core/src/main/java/com/alibaba/fastjson2/JSON.java#com.alibaba.fastjson2.JSON.parseObject(char[],int,int,java.lang.reflect.Type,com.alibaba.fastjson2.JSONReader$Feature[]):java.lang.Object");
        JsonNode refs = row.path("javadoc_metadata").path("javadoc_references");
        int matches = 0;
        for (JsonNode ref : refs) {
            if (!"T".equals(ref.path("target").asText())) {
                continue;
            }
            matches++;
            assertEquals("unresolved", ref.path("resolution").asText());
            assertEquals("unresolved", ref.path("reference_domain").asText());
            assertEquals("lexical_type_parameter", ref.path("unresolved_reason").asText());
            assertFalse(ref.has("type_uri"));
            assertFalse(ref.has("resolved_type"));
        }
        assertEquals("Both description and return links must be checked: " + refs, 2, matches);
    }

    private static JsonNode extract(String property, String revision, String methodUri) throws Exception {
        String supplied = System.getProperty(property);
        Assume.assumeTrue("Set " + property + " to the issue's pinned subject checkout", supplied != null);
        Path project = Path.of(supplied).toAbsolutePath();
        // Source-only mirrors use a provenance marker after the source transfer
        // has been verified. Normal checkouts must be at the exact issue commit.
        Path marker = project.resolve(".cocomut-pinned-revision");
        if (Files.exists(marker)) {
            assertEquals(revision, Files.readString(marker).strip());
        } else {
            Process git = new ProcessBuilder("git", "-C", project.toString(), "rev-parse", "HEAD").start();
            assertEquals(revision, new String(git.getInputStream().readAllBytes()).strip());
            assertEquals(0, git.waitFor());
        }
        ProjectMetadata metadata = new ProjectAnalyzer(project, true, "maven", false).analyze();
        try (var session = SourceBackends.spoon().open(ProjectModel.from(metadata))) {
            MethodInfo method = new MethodIdentifier(metadata).identify(session).stream()
                    .filter(m -> methodUri.equals(m.getMethodUri())).findFirst()
                    .orElseThrow(() -> new AssertionError("Pinned focal method missing: " + methodUri));
            MethodContext context = new ContextExtractor(metadata, null, session).extractContext(method);
            assertNotNull(context);
            String requested = System.getProperty("cocomut.lexicalOutputDir");
            Path output = requested == null ? Files.createTempDirectory("cocomut-pinned-links")
                    : Path.of(requested);
            Files.createDirectories(output);
            Path jsonl = output.resolve(property.substring("cocomut.".length()) + ".jsonl");
            new JsonGenerator(output).generateJsonLinesFile(Map.of(methodUri, context), jsonl);
            ObjectMapper mapper = new ObjectMapper();
            var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                    mapper.readTree(Path.of(System.getProperty("user.dir")).getParent()
                            .resolve("schemas/method-context.schema.json").toFile()));
            List<String> lines = Files.readAllLines(jsonl);
            assertEquals(1, lines.size());
            JsonNode row = mapper.readTree(lines.get(0));
            assertTrue(schema.validate(row).toString(), schema.validate(row).isEmpty());
            if (requested == null) {
                Files.delete(jsonl);
                Files.delete(output);
            }
            return row;
        }
    }
}
