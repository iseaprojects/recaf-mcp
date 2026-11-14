package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Removes one field or method by exact name and descriptor.
 */
public final class RemoveMemberTool implements McpTool {
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

    public RemoveMemberTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    RemoveMemberTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "remove_member";
    }

    @Override
    public String description() {
        return "Remove one exact field or method from a primary JVM class.";
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
        ClassMember existing = kind.equals(MemberToolSupport.FIELD)
                ? original.getDeclaredField(name, descriptor)
                : original.getDeclaredMethod(name, descriptor);
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
                if (kind.equals(MemberToolSupport.FIELD)) {
                    node.fields.removeIf(field -> matches(field, name, descriptor));
                } else {
                    node.methods.removeIf(method -> matches(method, name, descriptor));
                }
            });
            ToolCancellation.check();
            JvmClassMutationSupport.store(workspace, patched);
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error(
                    "member removal failed: " + ex.getMessage(),
                    className,
                    kind,
                    name,
                    descriptor
            );
        }

        JsonObject structured = JvmClassMutationSupport.metadata(className, original);
        JvmClassMutationSupport.addPatchedHash(structured, workspace, className);
        structured.addProperty("removed", true);
        structured.addProperty("kind", kind);
        structured.add("member", MemberToolSupport.toJson(existing, true));
        return McpTool.successResult(
                "removed " + kind + " " + name + descriptor,
                structured
        );
    }

    private static boolean matches(FieldNode field, String name, String descriptor) {
        return field.name.equals(name) && field.desc.equals(descriptor);
    }

    private static boolean matches(MethodNode method, String name, String descriptor) {
        return method.name.equals(name) && method.desc.equals(descriptor);
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
        structured.addProperty("removed", false);
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
