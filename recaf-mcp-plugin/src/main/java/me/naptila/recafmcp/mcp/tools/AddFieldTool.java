package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Adds one field to an existing primary JVM class.
 */
public final class AddFieldTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "name", "descriptor", "access", "constant_value",
            "scope", "dex_name"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public AddFieldTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    AddFieldTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "add_field";
    }

    @Override
    public String description() {
        return "Add a field with explicit descriptor, access flags, and optional constant value.";
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
        String name = JvmClassMutationSupport.requiredMemberName(arguments, "name");
        String descriptor = JvmClassMutationSupport.requiredDescriptor(arguments);
        if (!JvmClassMutationSupport.isValidMemberName(name)) {
            throw new IllegalArgumentException("name is not a valid JVM field name");
        }
        Type type = fieldType(descriptor);
        String accessSpec = ToolArguments.optionalString(arguments, "access");
        int access = MemberToolSupport.parseMemberAccess(
                accessSpec == null ? "private" : accessSpec,
                false
        );
        String constantSpec = ToolArguments.optionalString(arguments, "constant_value");
        Object constant = constantSpec == null ? null : parseConstant(constantSpec, type);
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", className);
        }

        Workspace workspace = workspaceSupplier.get();
        JvmClassInfo original = JvmClassMutationSupport.lookup(workspace, className);
        if (original == null) {
            return error("class not found in primary JVM resource", className);
        }
        if (findField(original, name, descriptor) != null) {
            return error("field with the same name and descriptor already exists", className);
        }

        JvmClassInfo patched;
        try {
            ToolCancellation.check();
            patched = JvmClassMutationSupport.mutate(original, node ->
                    node.fields.add(new FieldNode(access, name, descriptor, null, constant)));
            if (!patched.getName().equals(className)) {
                return error("mutated class name does not match the original", className);
            }
            ToolCancellation.check();
            JvmClassMutationSupport.store(workspace, patched);
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("field addition failed: " + ex.getMessage(), className);
        }

        FieldMember added = patched.getDeclaredField(name, descriptor);
        JsonObject structured = JvmClassMutationSupport.metadata(className, original);
        JvmClassMutationSupport.addPatchedHash(structured, workspace, className);
        if (added == null) {
            structured.addProperty("field_added", false);
            return McpTool.errorResult("field was not present after patching", structured);
        }
        structured.addProperty("field_added", true);
        structured.add("field", MemberToolSupport.toJson(added, true));
        return McpTool.successResult(
                "added field " + name + " to " + className,
                structured
        );
    }

    static Type fieldType(String descriptor) {
        if (descriptor.startsWith("(")) {
            throw new IllegalArgumentException("descriptor must be a field descriptor");
        }
        try {
            Type type = Type.getType(descriptor);
            if (type.getSort() == Type.METHOD || type.getSort() == Type.VOID) {
                throw new IllegalArgumentException("descriptor must be a field descriptor");
            }
            return type;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("descriptor is not a valid field descriptor");
        }
    }

    static Object parseConstant(String raw, Type type) {
        try {
            return switch (type.getSort()) {
                case Type.BOOLEAN -> raw.equalsIgnoreCase("true")
                        ? 1
                        : raw.equalsIgnoreCase("false") ? 0 : Integer.parseInt(raw) != 0 ? 1 : 0;
                case Type.BYTE, Type.SHORT, Type.INT -> Integer.parseInt(raw);
                case Type.CHAR -> raw.length() == 1
                        ? (int) raw.charAt(0)
                        : Integer.parseInt(raw);
                case Type.LONG -> Long.parseLong(raw);
                case Type.FLOAT -> Float.parseFloat(raw);
                case Type.DOUBLE -> Double.parseDouble(raw);
                case Type.OBJECT -> {
                    if (!"java/lang/String".equals(type.getInternalName())) {
                        throw new IllegalArgumentException(
                                "constant_value is only supported for primitives and java.lang.String"
                        );
                    }
                    yield raw;
                }
                default -> throw new IllegalArgumentException(
                        "constant_value is not supported for " + type.getDescriptor()
                );
            };
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "constant_value does not match descriptor " + type.getDescriptor()
            );
        }
    }

    private static FieldMember findField(JvmClassInfo classInfo,
                                         String name,
                                         String descriptor) {
        return classInfo.getDeclaredField(name, descriptor);
    }

    private static JsonObject error(String message, String className) {
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
        structured.addProperty("scope", "primary_jvm");
        structured.addProperty("field_added", false);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject className = new JsonObject();
        className.addProperty("type", "string");
        className.addProperty("minLength", 1);
        className.addProperty("maxLength", JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH);
        className.addProperty("description", "Exact primary JVM internal class name.");

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("minLength", 1);
        name.addProperty("maxLength", JvmClassMutationSupport.MAX_MEMBER_NAME_LENGTH);
        name.addProperty("description", "New field name.");

        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("type", "string");
        descriptor.addProperty("minLength", 1);
        descriptor.addProperty("maxLength", JvmClassMutationSupport.MAX_DESCRIPTOR_LENGTH);
        descriptor.addProperty("description", "JVM field descriptor, for example I or Ljava/lang/String;.");

        JsonObject access = new JsonObject();
        access.addProperty("type", "string");
        access.addProperty("default", "private");
        access.addProperty(
                "description",
                "Comma-separated flags: public, private, protected, static, final, volatile, "
                        + "transient, synthetic, enum."
        );

        JsonObject constant = new JsonObject();
        constant.addProperty("type", "string");
        constant.addProperty(
                "description",
                "Optional ConstantValue: parsed against the descriptor."
        );

        JsonObject properties = new JsonObject();
        properties.add("class_name", className);
        properties.add("name", name);
        properties.add("descriptor", descriptor);
        properties.add("access", access);
        properties.add("constant_value", constant);
        ClassToolSupport.addScopeProperties(properties, false);

        JsonArray required = new JsonArray();
        required.add("class_name");
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
