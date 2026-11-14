package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.util.io.ByteSource;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicFileBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceMutationToolsTest {
    private static final String SAMPLE = "fixture/Sample";
    private static final String TEXT_PATH = "assets/config.txt";

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesReplacesAndRemovesFiles() {
        Workspace workspace = fileWorkspace();
        WriteFileTool write = new WriteFileTool(() -> true, () -> workspace);

        JsonObject textArguments = new JsonObject();
        textArguments.addProperty("path", TEXT_PATH);
        textArguments.addProperty("content", "hello");
        JsonObject created = write.execute(textArguments)
                .getAsJsonObject("structuredContent");
        assertTrue(created.get("created").getAsBoolean());
        assertFalse(created.get("replaced").getAsBoolean());
        assertEquals(
                "hello",
                new String(
                        workspace.getPrimaryResource()
                                .getFileBundle()
                                .get(TEXT_PATH)
                                .getRawContent(),
                        StandardCharsets.UTF_8
                )
        );

        JsonObject conflict = write.execute(textArguments);
        assertTrue(conflict.get("isError").getAsBoolean());

        textArguments.addProperty("overwrite", true);
        JsonObject replaced = write.execute(textArguments)
                .getAsJsonObject("structuredContent");
        assertTrue(replaced.get("replaced").getAsBoolean());

        JsonObject binaryArguments = new JsonObject();
        binaryArguments.addProperty("path", "assets/data.bin");
        binaryArguments.addProperty("encoding", "base64");
        binaryArguments.addProperty(
                "content",
                Base64.getEncoder().encodeToString(new byte[]{0, 1, 2, (byte) 0xFF})
        );
        JsonObject binary = write.execute(binaryArguments)
                .getAsJsonObject("structuredContent");
        assertEquals(4, binary.get("size_bytes").getAsInt());

        JsonObject classPath = new JsonObject();
        classPath.addProperty("path", SAMPLE + ".class");
        classPath.addProperty("content", "x");
        assertThrows(IllegalArgumentException.class, () -> write.execute(classPath));

        JsonObject removeArguments = new JsonObject();
        removeArguments.addProperty("path", TEXT_PATH);
        JsonObject removed = new RemoveFileTool(() -> true, () -> workspace)
                .execute(removeArguments)
                .getAsJsonObject("structuredContent");
        assertTrue(removed.get("removed").getAsBoolean());
        assertNull(workspace.getPrimaryResource().getFileBundle().get(TEXT_PATH));
    }

    @Test
    void removesExactClassesAndRejectsMissingOnes() {
        Workspace workspace = ToolTestFixtures.workspace(
                ToolTestFixtures.simpleClass(SAMPLE, "java/lang/Object")
        );
        RemoveClassTool tool = new RemoveClassTool(() -> true, () -> workspace);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("class_name", SAMPLE);

        JsonObject removed = tool.execute(arguments).getAsJsonObject("structuredContent");
        assertTrue(removed.get("removed").getAsBoolean());
        assertEquals(0, workspace.getPrimaryResource().getJvmClassBundle().size());

        JsonObject missing = tool.execute(arguments);
        assertTrue(missing.get("isError").getAsBoolean());
    }

    @Test
    void listsAndRemovesSupportingResources() {
        WorkspaceResource supporting = new WorkspaceResourceBuilder()
                .withJvmClassBundle(supportingBundle())
                .build();
        Workspace workspace = new BasicWorkspace(
                new WorkspaceResourceBuilder().build(),
                List.of(supporting),
                false
        );

        JsonObject listed = new ListSupportingResourcesTool(() -> true, () -> workspace)
                .execute(new JsonObject())
                .getAsJsonObject("structuredContent");
        assertEquals(1, listed.get("supporting_resource_count").getAsInt());
        JsonObject resource = listed.getAsJsonArray("supporting_resources")
                .get(0)
                .getAsJsonObject();
        assertEquals(1, resource.get("jvm_class_count").getAsInt());

        JsonObject removeArguments = new JsonObject();
        removeArguments.addProperty("index", 0);
        JsonObject removed = new RemoveSupportingResourceTool(() -> true, () -> workspace)
                .execute(removeArguments)
                .getAsJsonObject("structuredContent");
        assertTrue(removed.get("removed").getAsBoolean());
        assertEquals(0, workspace.getSupportingResources().size());
    }

    @Test
    void importsSupportingResourcesAndValidatesPaths() throws IOException {
        Workspace workspace = new BasicWorkspace(
                new WorkspaceResourceBuilder().build(),
                List.of(),
                false
        );
        Path library = temporaryDirectory.resolve("library.jar");
        Files.write(library, new byte[]{1, 2, 3});
        AddSupportingResourceTool tool = new AddSupportingResourceTool(
                () -> true,
                () -> workspace,
                new FakeImporter()
        );

        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", library.toString());
        JsonObject added = tool.execute(arguments).getAsJsonObject("structuredContent");
        assertTrue(added.get("added").getAsBoolean());
        assertEquals(1, workspace.getSupportingResources().size());

        JsonObject missing = new JsonObject();
        missing.addProperty("path", temporaryDirectory.resolve("missing.jar").toString());
        assertTrue(tool.execute(missing).get("isError").getAsBoolean());
    }

    private static Workspace fileWorkspace() {
        BasicFileBundle files = new BasicFileBundle();
        WorkspaceResource primary = new WorkspaceResourceBuilder()
                .withFileBundle(files)
                .build();
        return new BasicWorkspace(primary, List.of(), false);
    }

    private static software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle
    supportingBundle() {
        var bundle = new software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle();
        bundle.put(ToolTestFixtures.simpleClass("library/Support", "java/lang/Object"));
        return bundle;
    }

    private static final class FakeImporter implements ResourceImporter {
        @Override
        public WorkspaceResource importResource(ByteSource source) {
            return new WorkspaceResourceBuilder().build();
        }

        @Override
        public WorkspaceResource importResource(Path path) {
            return new WorkspaceResourceBuilder().build();
        }

        @Override
        public WorkspaceResource importResource(URL url) {
            return new WorkspaceResourceBuilder().build();
        }
    }
}
