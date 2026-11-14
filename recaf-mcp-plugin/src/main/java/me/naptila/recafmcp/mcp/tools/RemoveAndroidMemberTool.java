package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Removes one field or method from a primary Android/DEX class.
 */
public final class RemoveAndroidMemberTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "kind", "name", "descriptor", "scope", "dex_name"
    );
    private static final Set<String> KINDS = Set.of(
            MemberToolSupport.FIELD,
            MemberToolSupport.METHOD
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public RemoveAndroidMemberTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    RemoveAndroidMemberTool(BooleanSupplier hasWorkspace,
                            Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "remove_android_member";
    }

    @Override
    public String description() {
        return "Remove one exact field or method from a primary Android/DEX class.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String className = ToolArguments.requiredString(arguments, "class_name");
        String kind = ToolArguments.requiredString(arguments, "kind");
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("kind must be field or method");
        }
        String name = JvmClassMutationSupport.requiredMemberName(arguments, "name");
        String descriptor = JvmClassMutationSupport.requiredDescriptor(arguments);
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", className, kind, name, descriptor);
        }

        Workspace workspace = workspaceSupplier.get();
        ClassMutationSupport.Resolution resolution = ClassMutationSupport.resolve(
                workspace.getPrimaryResource(),
                arguments,
                className,
                false
        );
        if (!resolution.found()) {
            return error(resolution.error(), className, kind, name, descriptor);
        }
        ClassToolSupport.Entry entry = resolution.entry();
        if (entry.scope() != ClassToolSupport.Scope.ANDROID) {
            return error(
                    "remove_android_member requires an Android/DEX class; "
                            + "use remove_member for JVM classes",
                    className,
                    kind,
                    name,
                    descriptor
            );
        }

        boolean method = kind.equals(MemberToolSupport.METHOD);
        AndroidClassInfo original = entry.classInfo().asAndroidClass();
        var existing = method
                ? original.getDeclaredMethod(name, descriptor)
                : original.getDeclaredField(name, descriptor);
        if (existing == null) {
            return error("member with exact name and descriptor not found",
                    className, kind, name, descriptor);
        }

        JsonObject structured = ClassMutationSupport.errorMetadata(className);
        ClassToolSupport.addIdentity(structured, entry);
        structured.addProperty("kind", kind);
        structured.addProperty("name", name);
        structured.addProperty("descriptor", descriptor);
        structured.add("member", MemberToolSupport.toJson(existing, true));

        try {
            ToolCancellation.check();
            ClassDefinition definition = ClassMutationSupport.definition(entry);
            boolean removed = method
                    ? AndroidMemberSupport.removeMethod(definition, name, descriptor)
                    : AndroidMemberSupport.removeField(definition, name, descriptor);
            if (!removed) {
                return error("the DEX definition did not contain the requested member",
                        className, kind, name, descriptor);
            }
            ClassMutationSupport.storeAndroid(workspace, entry, definition);
            ToolCancellation.check();
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("Android member removal failed: " + ex.getMessage(),
                    className, kind, name, descriptor);
        }

        structured.addProperty("removed", true);
        return McpTool.successResult("removed " + kind + " " + name + descriptor, structured);
    }

    private static JsonObject error(String message,
                                    String className,
                                    String kind,
                                    String name,
                                    String descriptor) {
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
        structured.addProperty("kind", kind);
        structured.addProperty("name", name);
        structured.addProperty("descriptor", descriptor);
        structured.addProperty("removed", false);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject className = new JsonObject();
        className.addProperty("type", "string");
        className.addProperty("minLength", 1);
        className.addProperty("maxLength", JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH);

        JsonObject kind = new JsonObject();
        kind.addProperty("type", "string");
        JsonArray kinds = new JsonArray();
        kinds.add(MemberToolSupport.FIELD);
        kinds.add(MemberToolSupport.METHOD);
        kind.add("enum", kinds);

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("minLength", 1);
        name.addProperty("maxLength", JvmClassMutationSupport.MAX_MEMBER_NAME_LENGTH);

        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("type", "string");
        descriptor.addProperty("minLength", 1);
        descriptor.addProperty("maxLength", JvmClassMutationSupport.MAX_DESCRIPTOR_LENGTH);

        JsonObject properties = new JsonObject();
        properties.add("class_name", className);
        properties.add("kind", kind);
        properties.add("name", name);
        properties.add("descriptor", descriptor);
        ClassToolSupport.addScopeProperties(properties, false);

        JsonArray required = new JsonArray();
        required.add("class_name");
        required.add("kind");
        required.add("name");
        required.add("descriptor");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
