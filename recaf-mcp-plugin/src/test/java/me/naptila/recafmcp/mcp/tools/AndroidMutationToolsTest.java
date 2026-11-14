package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.CodeBuilder;
import me.darknet.dex.tree.definitions.constant.IntConstant;
import me.darknet.dex.tree.type.Types;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.BasicAndroidClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AndroidMutationToolsTest {
    private static final String CLASS_NAME = "sample/android/Greeter";
    private static final String DEX_NAME = "classes.dex";

    @Test
    void addsFieldsAndMethodsWithStubBodies() {
        Workspace workspace = workspace(androidClass());
        AddAndroidMemberTool tool = new AddAndroidMemberTool(() -> true, () -> workspace);

        JsonObject fieldArguments = androidArguments();
        fieldArguments.addProperty("class_name", CLASS_NAME);
        fieldArguments.addProperty("kind", "field");
        fieldArguments.addProperty("name", "limit");
        fieldArguments.addProperty("descriptor", "I");
        fieldArguments.addProperty("access", "public static final");
        fieldArguments.addProperty("static_value", "12");
        JsonObject fieldResult = tool.execute(fieldArguments);
        assertFalse(fieldResult.get("isError").getAsBoolean(), fieldResult.toString());
        assertTrue(fieldResult.getAsJsonObject("structuredContent")
                .get("static_value_set")
                .getAsBoolean());

        ClassDefinition definition = definition(workspace);
        assertEquals(new IntConstant(12), definition.getField("limit", "I").getStaticValue());

        JsonObject methodArguments = androidArguments();
        methodArguments.addProperty("class_name", CLASS_NAME);
        methodArguments.addProperty("kind", "method");
        methodArguments.addProperty("name", "answer");
        methodArguments.addProperty("descriptor", "()I");
        methodArguments.addProperty("access", "public static");
        JsonObject methodResult = tool.execute(methodArguments);
        assertFalse(methodResult.get("isError").getAsBoolean(), methodResult.toString());
        assertTrue(methodResult.getAsJsonObject("structuredContent")
                .get("stub_body")
                .getAsBoolean());

        MethodMember added = definition.getMethod("answer", "()I");
        assertNotNull(added.getCode());
        assertEquals(2, added.getCode().getInstructions().size());

        AndroidClassInfo patched = workspace.getPrimaryResource()
                .getAndroidClassBundles()
                .get(DEX_NAME)
                .get(CLASS_NAME);
        try {
            AndroidClassInfo reread = software.coley.recaf.util.android.DexIOUtil
                    .read(me.naptila.recafmcp.mcp.android.AndroidDexReader
                            .encodeClass(patched, 39))
                    .get(CLASS_NAME);
            assertNotNull(reread);
            assertNotNull(reread.getDeclaredField("limit", "I"));
            assertNotNull(reread.getDeclaredMethod("answer", "()I"));
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }

        JsonObject duplicate = tool.execute(methodArguments);
        assertTrue(duplicate.get("isError").getAsBoolean());

        JsonObject badValue = androidArguments();
        badValue.addProperty("class_name", CLASS_NAME);
        badValue.addProperty("kind", "field");
        badValue.addProperty("name", "broken");
        badValue.addProperty("descriptor", "I");
        badValue.addProperty("static_value", "1");
        assertTrue(tool.execute(badValue).get("isError").getAsBoolean());
    }

    @Test
    void removesExactAndroidMembers() {
        Workspace workspace = workspace(androidClass());
        RemoveAndroidMemberTool tool = new RemoveAndroidMemberTool(
                () -> true,
                () -> workspace
        );

        JsonObject arguments = androidArguments();
        arguments.addProperty("class_name", CLASS_NAME);
        arguments.addProperty("kind", "method");
        arguments.addProperty("name", "greet");
        arguments.addProperty("descriptor", "()V");
        JsonObject result = tool.execute(arguments);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        assertTrue(result.getAsJsonObject("structuredContent").get("removed").getAsBoolean());
        assertNull(definition(workspace).getMethod("greet", "()V"));
        assertNull(workspace.getPrimaryResource()
                .getAndroidClassBundles()
                .get(DEX_NAME)
                .get(CLASS_NAME)
                .getDeclaredMethod("greet", "()V"));

        assertTrue(tool.execute(arguments).get("isError").getAsBoolean());
    }

    @Test
    void replacesAndroidMemberAccessAndKeepsCode() {
        Workspace workspace = workspace(androidClass());
        SetAndroidMemberAccessTool tool = new SetAndroidMemberAccessTool(
                () -> true,
                () -> workspace
        );
        JsonObject arguments = androidArguments();
        arguments.addProperty("class_name", CLASS_NAME);
        arguments.addProperty("kind", "method");
        arguments.addProperty("name", "greet");
        arguments.addProperty("descriptor", "()V");
        arguments.addProperty("access", "private static");

        JsonObject result = tool.execute(arguments);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        JsonObject structured = result.getAsJsonObject("structuredContent");
        assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                structured.get("previous_access").getAsInt());
        assertEquals(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                structured.get("access").getAsInt());

        MethodMember updated = definition(workspace).getMethod("greet", "()V");
        assertEquals(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, updated.getAccess());
        assertNotNull(updated.getCode());

        AndroidClassInfo patched = workspace.getPrimaryResource()
                .getAndroidClassBundles()
                .get(DEX_NAME)
                .get(CLASS_NAME);
        try {
            byte[] encoded = me.naptila.recafmcp.mcp.android.AndroidDexReader
                    .encodeClass(patched, 39);
            assertNotNull(software.coley.recaf.util.android.DexIOUtil
                    .read(encoded)
                    .get(CLASS_NAME));
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }

        JsonObject fieldArguments = androidArguments();
        fieldArguments.addProperty("class_name", CLASS_NAME);
        fieldArguments.addProperty("kind", "field");
        fieldArguments.addProperty("name", "count");
        fieldArguments.addProperty("descriptor", "I");
        fieldArguments.addProperty("access", "protected final");
        JsonObject fieldResult = tool.execute(fieldArguments);
        assertFalse(fieldResult.get("isError").getAsBoolean(), fieldResult.toString());
        assertEquals(
                Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL,
                definition(workspace).getField("count", "I").getAccess()
        );

        fieldArguments.addProperty("name", "missing");
        assertTrue(tool.execute(fieldArguments).get("isError").getAsBoolean());
    }

    @Test
    void addsSearchesAndRemovesAndroidAnnotations() {
        Workspace workspace = workspace(androidClass());
        AddAnnotationTool add = new AddAnnotationTool(() -> true, () -> workspace);
        JsonObject arguments = androidArguments();
        arguments.addProperty("class_name", CLASS_NAME);
        arguments.addProperty("descriptor", "Lsample/android/Marker;");
        JsonObject elements = new JsonObject();
        elements.addProperty("name", "greeter");
        elements.addProperty("version", "3");
        arguments.add("elements", elements);

        JsonObject result = add.execute(arguments);
        assertFalse(result.get("isError").getAsBoolean(), result.toString());
        assertEquals(2, result.getAsJsonObject("structuredContent")
                .get("element_count")
                .getAsInt());
        assertEquals(1, definition(workspace).getAnnotations().size());
        assertEquals(1, workspace.getPrimaryResource()
                .getAndroidClassBundles()
                .get(DEX_NAME)
                .get(CLASS_NAME)
                .getAnnotations()
                .size());

        JsonObject searchArguments = androidArguments();
        searchArguments.addProperty("descriptor", "sample/android/Marker");
        JsonObject searched = new SearchAnnotationsTool(() -> true, () -> workspace)
                .execute(searchArguments)
                .getAsJsonObject("structuredContent");
        assertEquals(1, searched.get("total").getAsInt());
        JsonObject hit = searched.getAsJsonArray("hits").get(0).getAsJsonObject();
        assertEquals("android", hit.get("class_scope").getAsString());
        assertEquals(DEX_NAME, hit.get("dex_name").getAsString());

        RemoveAnnotationTool remove = new RemoveAnnotationTool(() -> true, () -> workspace);
        JsonObject removeArguments = androidArguments();
        removeArguments.addProperty("class_name", CLASS_NAME);
        removeArguments.addProperty("descriptor", "Lsample/android/Marker;");
        JsonObject removed = remove.execute(removeArguments);
        assertFalse(removed.get("isError").getAsBoolean(), removed.toString());
        assertEquals(1, removed.getAsJsonObject("structuredContent")
                .get("removed_count")
                .getAsInt());
        assertEquals(0, definition(workspace).getAnnotations().size());
    }

    @Test
    void rejectsAndroidClassAccessReplacementButAppliesOtherAttributes() {
        Workspace workspace = workspace(androidClass());
        SetClassAttributesTool tool = new SetClassAttributesTool(
                () -> true,
                () -> workspace
        );

        JsonObject accessArguments = androidArguments();
        accessArguments.addProperty("class_name", CLASS_NAME);
        accessArguments.addProperty("access", "public final");
        JsonObject rejected = tool.execute(accessArguments);
        assertTrue(rejected.get("isError").getAsBoolean());
        assertTrue(rejected.getAsJsonObject("structuredContent")
                .get("error")
                .getAsString()
                .contains("access replacement"));

        JsonObject versionArguments = androidArguments();
        versionArguments.addProperty("class_name", CLASS_NAME);
        versionArguments.addProperty("classfile_version", Opcodes.V17);
        assertTrue(tool.execute(versionArguments).get("isError").getAsBoolean());

        JsonObject interfaceArguments = androidArguments();
        interfaceArguments.addProperty("class_name", CLASS_NAME);
        JsonArray add = new JsonArray();
        add.add("java/lang/Runnable");
        interfaceArguments.add("add_interfaces", add);
        interfaceArguments.addProperty("source_file", "Greeter.java");
        JsonObject applied = tool.execute(interfaceArguments);
        assertFalse(applied.get("isError").getAsBoolean(), applied.toString());
        ClassDefinition definition = definition(workspace);
        assertEquals("java/lang/Runnable", definition.getInterfaces().get(0).internalName());
        assertEquals("Greeter.java", definition.getSourceFile());

        JsonObject duplicate = tool.execute(interfaceArguments);
        assertFalse(duplicate.get("isError").getAsBoolean(), duplicate.toString());
        assertEquals(1, definition(workspace).getInterfaces().size());
    }

    @Test
    void listsPackagesAndValidatesAndroidWorkspace() {
        Workspace workspace = workspace(androidClass());
        JsonObject packageArguments = androidArguments();
        packageArguments.addProperty("depth", 2);
        JsonObject packages = new ListPackagesTool(() -> true, () -> workspace)
                .execute(packageArguments)
                .getAsJsonObject("structuredContent");
        assertEquals(1, packages.get("total_packages").getAsInt());
        JsonObject first = packages.getAsJsonArray("packages").get(0).getAsJsonObject();
        assertEquals("sample/android", first.get("package").getAsString());
        assertEquals(DEX_NAME, first.getAsJsonObject("class_count_by_dex")
                .keySet()
                .iterator()
                .next());

        JsonObject validated = new ValidateWorkspaceTool(() -> true, () -> workspace)
                .execute(androidArguments())
                .getAsJsonObject("structuredContent");
        assertEquals(1, validated.get("android_classes_checked").getAsInt());
        assertEquals(0, validated.get("jvm_classes_checked").getAsInt());
        assertTrue(validated.get("valid").getAsBoolean(), validated.toString());
    }

    private static JsonObject androidArguments() {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("scope", "android");
        arguments.addProperty("dex_name", DEX_NAME);
        return arguments;
    }

    private static ClassDefinition definition(Workspace workspace) {
        AndroidClassInfo classInfo = workspace.getPrimaryResource()
                .getAndroidClassBundles()
                .get(DEX_NAME)
                .get(CLASS_NAME);
        return classInfo.getBackingDefinition();
    }

    private static AndroidClassInfo androidClass() {
        ClassDefinition definition = new ClassDefinition(
                Types.instanceTypeFromInternalName(CLASS_NAME),
                Types.OBJECT,
                Opcodes.ACC_PUBLIC
        );
        definition.putField(new me.darknet.dex.tree.definitions.FieldMember(
                "count",
                (me.darknet.dex.tree.type.ClassType) Types.typeFromDescriptor("I"),
                Opcodes.ACC_PUBLIC
        ));
        MethodMember method = new MethodMember(
                "greet",
                Types.methodTypeFromDescriptor("()V"),
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC
        );
        method.setCode(new CodeBuilder()
                .registers(1)
                .return_void()
                .build());
        definition.putMethod(method);
        return new AndroidClassInfoBuilder(definition).build();
    }

    private static Workspace workspace(AndroidClassInfo classInfo) {
        BasicAndroidClassBundle bundle = new BasicAndroidClassBundle(39, new byte[0]);
        bundle.put(classInfo);
        Map<String, AndroidClassBundle> bundles = new LinkedHashMap<>();
        bundles.put(DEX_NAME, bundle);
        WorkspaceResource primary = new WorkspaceResourceBuilder()
                .withAndroidClassBundles(bundles)
                .build();
        return new BasicWorkspace(primary, List.of(), false);
    }
}
