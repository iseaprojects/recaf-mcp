package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.workspace.model.Workspace;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassInspectionAndMemberToolsTest {
    private static final String SAMPLE = "fixture/Sample";
    private static final String CHILD = "fixture/Child";

    @Test
    void returnsClassMetadataAndBoundedMemberPages() {
        Workspace workspace = sampleWorkspace();
        GetClassInfoTool info = new GetClassInfoTool(() -> true, () -> workspace);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("class_name", SAMPLE);
        arguments.addProperty("include_members", true);
        arguments.addProperty("member_limit", 2);

        JsonObject structured = info.execute(arguments)
                .getAsJsonObject("structuredContent");

        assertEquals(SAMPLE, structured.get("class_name").getAsString());
        assertEquals("java/lang/Object", structured.get("super_name").getAsString());
        assertEquals(1, structured.get("field_count").getAsInt());
        assertEquals(2, structured.get("method_count").getAsInt());
        assertEquals(2, structured.get("members_returned").getAsInt());
        assertEquals(3, structured.get("members_total").getAsInt());
        assertTrue(structured.get("members_truncated").getAsBoolean());
        assertEquals(64, structured.get("class_sha256").getAsString().length());

        ListMembersTool members = new ListMembersTool(() -> true, () -> workspace);
        JsonObject filter = new JsonObject();
        filter.addProperty("class_name", SAMPLE);
        filter.addProperty("kind", "field");
        JsonObject listed = members.execute(filter).getAsJsonObject("structuredContent");
        assertEquals(1, listed.get("total").getAsInt());
        JsonObject field = listed.getAsJsonArray("members").get(0).getAsJsonObject();
        assertEquals("count", field.get("name").getAsString());
        assertEquals("I", field.get("descriptor").getAsString());
        assertTrue(field.getAsJsonArray("modifiers").toString().contains("private"));
    }

    @Test
    void rejectsUnknownMembersAndValidatesArguments() {
        Workspace workspace = sampleWorkspace();
        GetClassInfoTool info = new GetClassInfoTool(() -> true, () -> workspace);
        JsonObject missing = new JsonObject();
        missing.addProperty("class_name", "fixture/Missing");
        assertTrue(info.execute(missing).get("isError").getAsBoolean());

        JsonObject badKind = new JsonObject();
        badKind.addProperty("class_name", SAMPLE);
        badKind.addProperty("kind", "property");
        assertThrows(
                IllegalArgumentException.class,
                () -> new ListMembersTool(() -> true, () -> workspace).execute(badKind)
        );
    }

    @Test
    void addsFieldsAndMethodsThatProduceVerifiableBytecode() throws Exception {
        Workspace workspace = sampleWorkspace();
        AddFieldTool addField = new AddFieldTool(() -> true, () -> workspace);
        JsonObject fieldArguments = new JsonObject();
        fieldArguments.addProperty("class_name", SAMPLE);
        fieldArguments.addProperty("name", "MAX");
        fieldArguments.addProperty("descriptor", "I");
        fieldArguments.addProperty("access", "public static final");
        fieldArguments.addProperty("constant_value", "7");
        JsonObject fieldResult = addField.execute(fieldArguments);
        assertFalse(fieldResult.get("isError").getAsBoolean());

        JsonObject methodArguments = new JsonObject();
        methodArguments.addProperty("class_name", SAMPLE);
        methodArguments.addProperty("name", "enabled");
        methodArguments.addProperty("descriptor", "()Z");
        methodArguments.addProperty("access", "public");
        JsonObject methodResult = new AddMethodTool(() -> true, () -> workspace)
                .execute(methodArguments);
        assertFalse(methodResult.get("isError").getAsBoolean());

        JvmClassInfo patched = workspace.getPrimaryResource()
                .getJvmClassBundle()
                .get(SAMPLE);
        assertNotNull(patched.getDeclaredField("MAX", "I"));
        assertEquals(7, patched.getDeclaredField("MAX", "I").getDefaultValue());
        assertNotNull(patched.getDeclaredMethod("enabled", "()Z"));

        Class<?> defined = ToolTestFixtures.define(patched);
        Constructor<?> constructor = defined.getDeclaredConstructor();
        Object instance = constructor.newInstance();
        Method enabled = defined.getMethod("enabled");
        assertEquals(false, enabled.invoke(instance));
        assertEquals(7, defined.getField("MAX").getInt(null));
    }

    @Test
    void rendersBytecodeAndUpdatesMemberAccessAndRemoval() {
        Workspace workspace = sampleWorkspace();
        JsonObject bytecodeArguments = new JsonObject();
        bytecodeArguments.addProperty("class_name", SAMPLE);
        bytecodeArguments.addProperty("name", "value");
        bytecodeArguments.addProperty("descriptor", "()I");
        JsonObject bytecode = new GetMethodBytecodeTool(() -> true, () -> workspace)
                .execute(bytecodeArguments)
                .getAsJsonObject("structuredContent");
        assertTrue(bytecode.get("text").getAsString().contains("IRETURN"));

        JsonObject accessArguments = new JsonObject();
        accessArguments.addProperty("class_name", SAMPLE);
        accessArguments.addProperty("kind", "field");
        accessArguments.addProperty("name", "count");
        accessArguments.addProperty("descriptor", "I");
        accessArguments.addProperty("access", "public static final");
        JsonObject access = new SetMemberAccessTool(() -> true, () -> workspace)
                .execute(accessArguments)
                .getAsJsonObject("structuredContent");
        assertTrue(access.get("updated").getAsBoolean());
        assertTrue(access.getAsJsonArray("modifiers").toString().contains("static"));

        JsonObject removeArguments = new JsonObject();
        removeArguments.addProperty("class_name", SAMPLE);
        removeArguments.addProperty("kind", "field");
        removeArguments.addProperty("name", "count");
        removeArguments.addProperty("descriptor", "I");
        JsonObject removed = new RemoveMemberTool(() -> true, () -> workspace)
                .execute(removeArguments)
                .getAsJsonObject("structuredContent");
        assertTrue(removed.get("removed").getAsBoolean());
        assertNull(workspace.getPrimaryResource()
                .getJvmClassBundle()
                .get(SAMPLE)
                .getDeclaredField("count", "I"));
    }

    @Test
    void reportsInheritanceRelationships() {
        Workspace workspace = sampleWorkspace();
        GetInheritanceTool tool = new GetInheritanceTool(
                () -> true,
                () -> workspace,
                InheritanceGraph::new
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("class_name", CHILD);

        JsonObject structured = tool.execute(arguments).getAsJsonObject("structuredContent");

        assertFalse(structured.has("error"));
        JsonArray parents = structured.getAsJsonArray("all_parents");
        assertTrue(parents.toString().contains(SAMPLE));
        assertTrue(parents.size() >= 1);
    }

    private static Workspace sampleWorkspace() {
        return ToolTestFixtures.workspace(
                ToolTestFixtures.simpleClass(SAMPLE, "java/lang/Object"),
                ToolTestFixtures.simpleClass(CHILD, SAMPLE)
        );
    }
}
