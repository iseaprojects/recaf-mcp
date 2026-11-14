package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Replaces the access-flag set of one exact field or method.
 */
public final class SetMemberAccessTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "kind", "name", "descriptor", "access", "scope", "dex_name"
    );
    private static final Set<String> KINDS = Set.of(
            MemberToolSupport.FIELD,
            MemberToolSupport.METHOD
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public SetMemberAccessTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    SetMemberAccessTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "set_member_access";
    }

    @Override
    public String description() {
        return "Replace the access modifiers of one exact field or method in a JVM class.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String className = ToolArguments.requiredString(arguments, "class_name");
        JvmClassMutationSupport.Selection selection = JvmClassMutationSupport.resolve(
                arguments,
                className
        );
        String kind = ToolArguments.requiredString(arguments, "kind");
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("kind must be field or method");
        }
        String name = JvmClassMutationSupport.requiredMemberName(arguments, "name");
        String descriptor = JvmClassMutationSupport.requiredDescriptor(arguments);
        String accessSpec = ToolArguments.requiredString(arguments, "access");
        boolean method = kind.equals(MemberToolSupport.METHOD);
        int access = MemberToolSupport.parseMemberAccess(accessSpec, method);
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", className, kind, name, descriptor);
        }

        Workspace workspace = workspaceSupplier.get();
        JvmClassInfo original = JvmClassMutationSupport.lookup(workspace, className);
        if (original == null) {
            return error(
                    "class not found in primary JVM resource",
                    className,
                    kind,
                    name,
                    descriptor
            );
        }
        ClassMember existing = method
                ? original.getDeclaredMethod(name, descriptor)
                : original.getDeclaredField(name, descriptor);
        if (existing == null) {
            return error(
                    "member with exact name and descriptor not found",
                    className,
                    kind,
                    name,
                    descriptor
            );
        }

        JvmClassInfo patched;
        try {
            ToolCancellation.check();
            patched = JvmClassMutationSupport.mutate(original, node -> {
                if (method) {
                    node.methods.stream()
                            .filter(item -> item.name.equals(name) && item.desc.equals(descriptor))
                            .forEach(item -> item.access = access);
                } else {
                    node.fields.stream()
                            .filter(item -> item.name.equals(name) && item.desc.equals(descriptor))
                            .forEach(item -> item.access = access);
                }
            });
            ToolCancellation.check();
            JvmClassMutationSupport.store(workspace, patched);
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error(
                    "access update failed: " + ex.getMessage(),
                    className,
                    kind,
                    name,
                    descriptor
            );
        }

        ClassMember updated = method
                ? patched.getDeclaredMethod(name, descriptor)
                : patched.getDeclaredField(name, descriptor);
        JsonObject structured = JvmClassMutationSupport.metadata(className, original);
        JvmClassMutationSupport.addPatchedHash(structured, workspace, className);
        structured.addProperty("kind", kind);
        structured.addProperty("name", name);
        structured.addProperty("descriptor", descriptor);
        structured.addProperty("previous_access", existing.getAccess());
        structured.add(
                "previous_modifiers",
                MemberToolSupport.memberModifiers(existing.getAccess(), method)
        );
        if (updated == null) {
            structured.addProperty("updated", false);
            return McpTool.errorResult("member was not present after patching", structured);
        }
        structured.addProperty("updated", true);
        structured.addProperty("access", updated.getAccess());
        structured.add("modifiers", MemberToolSupport.memberModifiers(updated.getAccess(), method));
        return McpTool.successResult(
                "updated access for " + kind + " " + name,
                structured
        );
    }

    private static JsonObject error(String message,
                                    String className,
                                    String kind,
                                    String name,
                                    String descriptor) {
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
        structured.addProperty("scope", "primary_jvm");
        structured.addProperty("kind", kind);
        structured.addProperty("name", name);
        structured.addProperty("descriptor", descriptor);
        structured.addProperty("updated", false);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject className = new JsonObject();
        className.addProperty("type", "string");
        className.addProperty("minLength", 1);
        className.addProperty("maxLength", JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH);
        className.addProperty("description", "Exact primary JVM internal class name.");

        JsonObject kind = new JsonObject();
        kind.addProperty("type", "string");
        JsonArray values = new JsonArray();
        values.add(MemberToolSupport.FIELD);
        values.add(MemberToolSupport.METHOD);
        kind.add("enum", values);

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("minLength", 1);
        name.addProperty("maxLength", JvmClassMutationSupport.MAX_MEMBER_NAME_LENGTH);

        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("type", "string");
        descriptor.addProperty("minLength", 1);
        descriptor.addProperty("maxLength", JvmClassMutationSupport.MAX_DESCRIPTOR_LENGTH);

        JsonObject access = new JsonObject();
        access.addProperty("type", "string");
        access.addProperty(
                "description",
                "Complete replacement flag set; use none to clear all flags."
        );

        JsonObject properties = new JsonObject();
        properties.add("class_name", className);
        properties.add("kind", kind);
        properties.add("name", name);
        properties.add("descriptor", descriptor);
        properties.add("access", access);
        ClassToolSupport.addScopeProperties(properties, false);

        JsonArray required = new JsonArray();
        required.add("class_name");
        required.add("kind");
        required.add("name");
        required.add("descriptor");
        required.add("access");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
