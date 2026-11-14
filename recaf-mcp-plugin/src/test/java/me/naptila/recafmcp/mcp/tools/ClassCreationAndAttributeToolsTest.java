package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicFileBundle;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassCreationAndAttributeToolsTest {
    private static final String SAMPLE = "fixture/Sample";

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsLoadableClassWithDeclaredSupertypes() {
        Workspace workspace = ToolTestFixtures.workspace();
        AddClassTool tool = new AddClassTool(() -> true, () -> workspace);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("class_name", "fixture/Created");
        com.google.gson.JsonArray interfaces = new com.google.gson.JsonArray();
        interfaces.add("java/io/Serializable");
        arguments.add("interfaces", interfaces);
        arguments.addProperty("classfile_version", Opcodes.V17);

        JsonObject result = tool.execute(arguments);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        JsonObject structured = result.getAsJsonObject("structuredContent");
        assertTrue(structured.get("created").getAsBoolean());
        assertEquals("fixture/Created", structured.get("class_name").getAsString());

        JvmClassInfo created = workspace.getPrimaryResource()
                .getJvmClassBundle()
                .get("fixture/Created");
        assertNotNull(created);
        assertEquals("java/lang/Object", created.getSuperName());
        assertEquals(List.of("java/io/Serializable"), created.getInterfaces());
        assertNotNull(created.getDeclaredMethod("<init>", "()V"));
        assertEquals(Opcodes.V17, created.getVersion());
        assertNotNull(ToolTestFixtures.define(created));

        JsonObject duplicate = tool.execute(arguments);
        assertTrue(duplicate.get("isError").getAsBoolean());

        JsonObject invalid = new JsonObject();
        invalid.addProperty("class_name", "not a/valid name");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(invalid)
        );
    }

    @Test
    void duplicatesClassAndRemapsSelfReferences() {
        Workspace workspace = ToolTestFixtures.workspace(selfReferencingClass(SAMPLE));
        DuplicateClassTool tool = new DuplicateClassTool(() -> true, () -> workspace);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("class_name", SAMPLE);
        arguments.addProperty("new_name", "fixture/Copy");

        JsonObject result = tool.execute(arguments);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        JvmClassInfo copy = workspace.getPrimaryResource()
                .getJvmClassBundle()
                .get("fixture/Copy");
        assertNotNull(copy);
        assertEquals("fixture/Copy", copy.getName());
        assertNotNull(copy.getDeclaredField("self", "Lfixture/Copy;"));
        assertNotNull(ToolTestFixtures.define(copy));

        JsonObject again = tool.execute(arguments);
        assertTrue(again.get("isError").getAsBoolean());
    }

    @Test
    void replacesClassAttributes() {
        Workspace workspace = ToolTestFixtures.workspace(
                ToolTestFixtures.simpleClass(SAMPLE, "java/lang/Object")
        );
        SetClassAttributesTool tool = new SetClassAttributesTool(
                () -> true,
                () -> workspace
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("class_name", SAMPLE);
        arguments.addProperty("super_name", "java/lang/Number");
        com.google.gson.JsonArray add = new com.google.gson.JsonArray();
        add.add("java/lang/Comparable");
        arguments.add("add_interfaces", add);
        arguments.addProperty("signature", "<T:Ljava/lang/Object;>Ljava/lang/Number;");
        arguments.addProperty("source_file", "Sample.java");
        arguments.addProperty("classfile_version", Opcodes.V11);

        JsonObject result = tool.execute(arguments);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        JvmClassInfo updated = workspace.getPrimaryResource()
                .getJvmClassBundle()
                .get(SAMPLE);
        assertEquals("java/lang/Number", updated.getSuperName());
        assertEquals(List.of("java/lang/Comparable"), updated.getInterfaces());
        assertEquals("<T:Ljava/lang/Object;>Ljava/lang/Number;", updated.getSignature());
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(updated.getBytecode()).accept(node, 0);
        assertEquals("Sample.java", node.sourceFile);
        assertEquals(Opcodes.V11, updated.getVersion());

        JsonObject interfaces = new JsonObject();
        interfaces.addProperty("class_name", SAMPLE);
        com.google.gson.JsonArray replace = new com.google.gson.JsonArray();
        replace.add("java/io/Closeable");
        interfaces.add("interfaces", replace);
        assertFalse(tool.execute(interfaces).get("isError").getAsBoolean());
        assertEquals(
                List.of("java/io/Closeable"),
                workspace.getPrimaryResource()
                        .getJvmClassBundle()
                        .get(SAMPLE)
                        .getInterfaces()
        );

        JsonObject empty = new JsonObject();
        empty.addProperty("class_name", SAMPLE);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(empty)
        );
    }

    @Test
    void setsAndClearsFieldConstants() {
        Workspace workspace = ToolTestFixtures.workspace(
                ToolTestFixtures.simpleClass(SAMPLE, "java/lang/Object")
        );
        SetFieldConstantTool tool = new SetFieldConstantTool(() -> true, () -> workspace);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("class_name", SAMPLE);
        arguments.addProperty("name", "count");
        arguments.addProperty("descriptor", "I");
        arguments.addProperty("constant_value", "7");

        JsonObject result = tool.execute(arguments);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        JsonObject structured = result.getAsJsonObject("structuredContent");
        assertEquals(3, structured.get("previous_constant").getAsInt());
        assertEquals(7, structured.get("constant").getAsInt());
        assertEquals(7, workspace.getPrimaryResource()
                .getJvmClassBundle()
                .get(SAMPLE)
                .getDeclaredField("count", "I")
                .getDefaultValue());

        arguments.remove("constant_value");
        JsonObject cleared = tool.execute(arguments);
        assertFalse(cleared.get("isError").getAsBoolean(), cleared.toString());
        assertNull(workspace.getPrimaryResource()
                .getJvmClassBundle()
                .get(SAMPLE)
                .getDeclaredField("count", "I")
                .getDefaultValue());

        arguments.addProperty("name", "missing");
        assertTrue(tool.execute(arguments).get("isError").getAsBoolean());
    }

    @Test
    void addsListsSearchesAndRemovesAnnotations() {
        Workspace workspace = ToolTestFixtures.workspace(
                ToolTestFixtures.simpleClass(SAMPLE, "java/lang/Object")
        );
        AddAnnotationTool add = new AddAnnotationTool(() -> true, () -> workspace);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("class_name", SAMPLE);
        arguments.addProperty("descriptor", "sample/Marker");
        JsonObject elements = new JsonObject();
        elements.addProperty("value", "x");
        arguments.add("elements", elements);
        JsonObject added = add.execute(arguments);
        assertFalse(added.get("isError").getAsBoolean(), added.toString());
        assertTrue(added.getAsJsonObject("structuredContent").get("added").getAsBoolean());
        assertEquals(1, workspace.getPrimaryResource()
                .getJvmClassBundle()
                .get(SAMPLE)
                .getAnnotations()
                .size());

        JsonObject methodArguments = new JsonObject();
        methodArguments.addProperty("class_name", SAMPLE);
        methodArguments.addProperty("descriptor", "Lsample/MethodMarker;");
        methodArguments.addProperty("target", "method");
        methodArguments.addProperty("name", "value");
        methodArguments.addProperty("member_descriptor", "()I");
        assertFalse(add.execute(methodArguments).get("isError").getAsBoolean());
        assertEquals(1, workspace.getPrimaryResource()
                .getJvmClassBundle()
                .get(SAMPLE)
                .getDeclaredMethod("value", "()I")
                .getAnnotations()
                .size());

        JsonObject duplicate = add.execute(arguments);
        assertTrue(duplicate.get("isError").getAsBoolean());

        JsonObject listed = new ListAnnotationsTool(() -> true, () -> workspace)
                .execute(new JsonObject())
                .getAsJsonObject("structuredContent");
        assertEquals(2, listed.get("total").getAsInt());
        assertEquals(1, listed.get("classes_scanned").getAsInt());

        JsonObject searched = new SearchAnnotationsTool(() -> true, () -> workspace)
                .execute(searchArguments("MethodMarker"))
                .getAsJsonObject("structuredContent");
        assertEquals(1, searched.get("total").getAsInt());
        JsonObject hit = searched.getAsJsonArray("hits").get(0).getAsJsonObject();
        assertEquals("method", hit.get("target").getAsString());
        assertEquals("value", hit.get("member_name").getAsString());

        RemoveAnnotationTool remove = new RemoveAnnotationTool(() -> true, () -> workspace);
        JsonObject removeArguments = new JsonObject();
        removeArguments.addProperty("class_name", SAMPLE);
        removeArguments.addProperty("descriptor", "Lsample/Marker;");
        JsonObject removed = remove.execute(removeArguments);
        assertFalse(removed.get("isError").getAsBoolean(), removed.toString());
        assertEquals(1, removed.getAsJsonObject("structuredContent")
                .get("removed_count")
                .getAsInt());
        assertEquals(0, workspace.getPrimaryResource()
                .getJvmClassBundle()
                .get(SAMPLE)
                .getAnnotations()
                .size());
        assertTrue(remove.execute(removeArguments).get("isError").getAsBoolean());
    }

    @Test
    void extractsClassesAndFiles() throws IOException {
        BasicFileBundle files = new BasicFileBundle();
        files.put(new software.coley.recaf.info.builder.TextFileInfoBuilder()
                .withName("assets/config.txt")
                .withRawContent("hello".getBytes(StandardCharsets.UTF_8))
                .build());
        BasicJvmClassBundle classes = new BasicJvmClassBundle();
        JvmClassInfo sample = ToolTestFixtures.simpleClass(SAMPLE, "java/lang/Object");
        classes.put(sample);
        WorkspaceResource primary = new WorkspaceResourceBuilder()
                .withJvmClassBundle(classes)
                .withFileBundle(files)
                .build();
        Workspace workspace = new BasicWorkspace(primary, List.of(), false);
        ExtractEntryTool tool = new ExtractEntryTool(() -> true, () -> workspace);

        Path classOutput = temporaryDirectory.resolve("sample.class");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", SAMPLE);
        arguments.addProperty("output_path", classOutput.toString());
        JsonObject result = tool.execute(arguments);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        assertEquals("jvm_class", result.getAsJsonObject("structuredContent")
                .get("kind")
                .getAsString());
        assertArrayEquals(sample.getBytecode(), Files.readAllBytes(classOutput));

        JsonObject conflict = tool.execute(arguments);
        assertTrue(conflict.get("isError").getAsBoolean());
        arguments.addProperty("overwrite", true);
        assertFalse(tool.execute(arguments).get("isError").getAsBoolean());

        Path fileOutput = temporaryDirectory.resolve("nested/config.txt");
        JsonObject fileArguments = new JsonObject();
        fileArguments.addProperty("path", "assets/config.txt");
        fileArguments.addProperty("output_path", fileOutput.toString());
        JsonObject fileResult = tool.execute(fileArguments);
        assertFalse(fileResult.get("isError").getAsBoolean(), fileResult.toString());
        assertEquals("hello", Files.readString(fileOutput));

        JsonObject missing = new JsonObject();
        missing.addProperty("path", "assets/missing.txt");
        missing.addProperty("output_path", temporaryDirectory.resolve("x").toString());
        assertTrue(tool.execute(missing).get("isError").getAsBoolean());

        JsonObject relative = new JsonObject();
        relative.addProperty("path", SAMPLE);
        relative.addProperty("output_path", "relative.class");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(relative)
        );
    }

    @Test
    void groupsClassesIntoPackages() {
        Workspace workspace = ToolTestFixtures.workspace(
                ToolTestFixtures.simpleClass("a/b/One", "java/lang/Object"),
                ToolTestFixtures.simpleClass("a/b/c/Two", "java/lang/Object"),
                ToolTestFixtures.simpleClass("x/Three", "java/lang/Object"),
                ToolTestFixtures.simpleClass("Four", "java/lang/Object")
        );
        ListPackagesTool tool = new ListPackagesTool(() -> true, () -> workspace);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("depth", 2);
        JsonObject result = tool.execute(arguments);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        JsonObject structured = result.getAsJsonObject("structuredContent");
        assertEquals(3, structured.get("total_packages").getAsInt());
        assertEquals(4, structured.get("classes_scanned").getAsInt());
        JsonObject first = structured.getAsJsonArray("packages")
                .asList()
                .stream()
                .map(element -> element.getAsJsonObject())
                .filter(item -> item.get("package").getAsString().equals("a/b"))
                .findFirst()
                .orElseThrow();
        assertEquals(2, first.get("class_count").getAsInt());

        arguments.addProperty("include_default_package", false);
        assertEquals(2, tool.execute(arguments)
                .getAsJsonObject("structuredContent")
                .get("total_packages")
                .getAsInt());

        arguments.addProperty("filter", "a/");
        JsonObject filtered = tool.execute(arguments).getAsJsonObject("structuredContent");
        assertEquals(1, filtered.get("total_packages").getAsInt());
    }

    @Test
    void validatesWorkspaceStructure() {
        Workspace workspace = ToolTestFixtures.workspace(
                ToolTestFixtures.simpleClass(SAMPLE, "java/lang/Object"),
                duplicateFieldClass("fixture/Duplicates")
        );
        ValidateWorkspaceTool tool = new ValidateWorkspaceTool(() -> true, () -> workspace);
        JsonObject result = tool.execute(new JsonObject());
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        JsonObject structured = result.getAsJsonObject("structuredContent");
        assertFalse(structured.get("valid").getAsBoolean());
        assertEquals(2, structured.get("classes_checked").getAsInt());
        assertTrue(structured.getAsJsonObject("issues_by_code")
                .has("jvm_duplicate_field"));
        JsonObject issue = structured.getAsJsonArray("issues").get(0).getAsJsonObject();
        assertEquals("jvm_duplicate_field", issue.get("code").getAsString());
    }

    private static JsonObject searchArguments(String term) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("descriptor", term);
        return arguments;
    }

    private static JvmClassInfo selfReferencingClass(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "self", "L" + name + ";", null, null)
                .visitEnd();
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                "<init>",
                "()V",
                null,
                null
        );
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                "()V",
                false
        );
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return new JvmClassInfoBuilder(writer.toByteArray()).build();
    }

    private static JvmClassInfo duplicateFieldClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "value", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "value", "I", null, null).visitEnd();
        writer.visitEnd();
        return new JvmClassInfoBuilder(writer.toByteArray()).build();
    }
}
