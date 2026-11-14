package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.FieldMember;
import me.darknet.dex.tree.definitions.MethodMember;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Replaces the access-flag set of one exact Android/DEX field or method.
 */
public final class SetAndroidMemberAccessTool implements McpTool {
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

    public SetAndroidMemberAccessTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    SetAndroidMemberAccessTool(BooleanSupplier hasWorkspace,
                               Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "set_android_member_access";
    }

    @Override
    public String description() {
        return "Replace the access modifiers of one exact field or method in an Android/DEX class.";
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
        String accessSpec = ToolArguments.requiredString(arguments, "access");
        boolean method = kind.equals(MemberToolSupport.METHOD);
        int access = MemberToolSupport.parseMemberAccess(accessSpec, method);
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
                    "set_android_member_access requires an Android/DEX class; "
                            + "use set_member_access for JVM classes",
                    className,
                    kind,
                    name,
                    descriptor
            );
        }

        AndroidClassInfo original = entry.classInfo().asAndroidClass();
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
        if (method && (access & Opcodes.ACC_ABSTRACT) != 0) {
            access &= ~Opcodes.ACC_NATIVE;
        }

        JsonObject structured = ClassMutationSupport.errorMetadata(className);
        ClassToolSupport.addIdentity(structured, entry);
        structured.addProperty("kind", kind);
        structured.addProperty("name", name);
        structured.addProperty("descriptor", descriptor);
        structured.addProperty("previous_access", existing.getAccess());
        structured.add(
                "previous_modifiers",
                MemberToolSupport.memberModifiers(existing.getAccess(), method)
        );

        try {
            ToolCancellation.check();
            ClassDefinition definition = ClassMutationSupport.definition(entry);
            if (method) {
                MethodMember target = definition.getMethod(name, descriptor);
                if (target == null) {
                    return error("the DEX definition did not contain the requested method",
                            className, kind, name, descriptor);
                }
                if ((access & Opcodes.ACC_ABSTRACT) != 0) {
                    target.setCode(null);
                } else if (target.getCode() == null && (access & Opcodes.ACC_NATIVE) == 0) {
                    return error(
                            "cannot make an abstract or codeless method concrete "
                                    + "through an access change",
                            className,
                            kind,
                            name,
                            descriptor
                    );
                }
                AndroidMemberSupport.replaceMethodAccess(
                        definition,
                        target,
                        access
                );
            } else {
                FieldMember target = definition.getField(name, descriptor);
                if (target == null) {
                    return error("the DEX definition did not contain the requested field",
                            className, kind, name, descriptor);
                }
                AndroidMemberSupport.replaceFieldAccess(
                        definition,
                        target,
                        access
                );
            }
            AndroidClassInfo updated = ClassMutationSupport.storeAndroid(
                    workspace,
                    entry,
                    definition
            );
            ClassMember stored = method
                    ? updated.getDeclaredMethod(name, descriptor)
                    : updated.getDeclaredField(name, descriptor);
            if (stored == null || stored.getAccess() != access) {
                return error(
                        "the access change did not persist in the DEX bundle",
                        className,
                        kind,
                        name,
                        descriptor
                );
            }
            structured.addProperty("access", access);
            structured.add(
                    "modifiers",
                    MemberToolSupport.memberModifiers(access, method)
            );
            ToolCancellation.check();
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("access update failed: " + ex.getMessage(),
                    className, kind, name, descriptor);
        }

        structured.addProperty("updated", true);
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
