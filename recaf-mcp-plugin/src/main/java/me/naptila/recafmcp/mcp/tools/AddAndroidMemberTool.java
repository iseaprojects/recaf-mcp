package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.FieldMember;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.MethodType;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Adds one field or method declaration to a primary Android/DEX class.
 */
public final class AddAndroidMemberTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "kind", "name", "descriptor", "access",
            "static_value", "scope", "dex_name"
    );
    private static final Set<String> KINDS = Set.of(
            MemberToolSupport.FIELD,
            MemberToolSupport.METHOD
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public AddAndroidMemberTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    AddAndroidMemberTool(BooleanSupplier hasWorkspace,
                         Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "add_android_member";
    }

    @Override
    public String description() {
        return "Add a field or method declaration to a primary Android/DEX class.";
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
        boolean method = kind.equals(MemberToolSupport.METHOD);
        String name = JvmClassMutationSupport.requiredMemberName(arguments, "name");
        if (!JvmClassMutationSupport.isValidMethodName(name)) {
            throw new IllegalArgumentException("name is not a valid member name");
        }
        String descriptor = JvmClassMutationSupport.requiredDescriptor(arguments);
        String accessSpec = ToolArguments.optionalString(arguments, "access");
        int access = MemberToolSupport.parseMemberAccess(
                accessSpec == null ? "public" : accessSpec,
                method
        );
        String staticValue = arguments.has("static_value")
                ? ToolArguments.requiredString(arguments, "static_value")
                : null;
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
                    "add_android_member requires an Android/DEX class; "
                            + "use add_field or add_method for JVM classes",
                    className,
                    kind,
                    name,
                    descriptor
            );
        }

        JsonObject structured = ClassMutationSupport.errorMetadata(className);
        ClassToolSupport.addIdentity(structured, entry);
        structured.addProperty("kind", kind);
        structured.addProperty("name", name);
        structured.addProperty("descriptor", descriptor);

        boolean stubBody = false;
        try {
            ToolCancellation.check();
            ClassDefinition definition = ClassMutationSupport.definition(entry);
            if (method) {
                MethodType type = AndroidMemberSupport.parseMethodType(descriptor);
                stubBody = (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
                AndroidMemberSupport.addMethod(definition, name, type, access);
            } else {
                ClassType type = AndroidMemberSupport.parseFieldType(descriptor);
                var constant = staticValue == null
                        ? null
                        : AndroidMemberSupport.constant(
                                AddFieldTool.parseConstant(staticValue, Type.getType(descriptor))
                        );
                if (staticValue != null
                        && (access & Opcodes.ACC_STATIC) == 0) {
                    return error(
                            "static_value requires the static access flag",
                            className,
                            kind,
                            name,
                            descriptor
                    );
                }
                FieldMember field = AndroidMemberSupport.addField(
                        definition,
                        name,
                        type,
                        access,
                        constant
                );
                structured.addProperty(
                        "static_value_set",
                        field.getStaticValue() != null
                );
            }
            AndroidClassInfo updated = ClassMutationSupport.storeAndroid(
                    workspace,
                    entry,
                    definition
            );
            if (method) {
                var added = updated.getDeclaredMethod(name, descriptor);
                if (added == null) {
                    return error("method was not present after patching",
                            className, kind, name, descriptor);
                }
                structured.add("member", MemberToolSupport.toJson(added, true));
                structured.addProperty("stub_body", stubBody);
            } else {
                var added = updated.getDeclaredField(name, descriptor);
                if (added == null) {
                    return error("field was not present after patching",
                            className, kind, name, descriptor);
                }
                structured.add("member", MemberToolSupport.toJson(added, true));
                structured.addProperty("stub_body", false);
            }
            structured.addProperty("access", access);
            ToolCancellation.check();
        } catch (IllegalStateException ex) {
            return error(ex.getMessage(), className, kind, name, descriptor);
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("Android member addition failed: " + ex.getMessage(),
                    className, kind, name, descriptor);
        }

        structured.addProperty("added", true);
        return McpTool.successResult(
                "added " + kind + " " + name + descriptor,
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
        structured.addProperty("added", false);
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
        access.addProperty("default", "public");
        access.addProperty(
                "description",
                "Comma-separated flags: public, private, protected, static, final, "
                        + "synchronized, volatile, transient, native, abstract, synthetic."
        );

        JsonObject staticValue = new JsonObject();
        staticValue.addProperty("type", "string");
        staticValue.addProperty(
                "description",
                "Static field value; requires a static field and a primitive or string type."
        );

        JsonObject properties = new JsonObject();
        properties.add("class_name", className);
        properties.add("kind", kind);
        properties.add("name", name);
        properties.add("descriptor", descriptor);
        properties.add("access", access);
        properties.add("static_value", staticValue);
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
