package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.FieldMember;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Sets or clears the ConstantValue of one exact field.
 */
public final class SetFieldConstantTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "name", "descriptor", "constant_value", "scope", "dex_name"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public SetFieldConstantTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    SetFieldConstantTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "set_field_constant";
    }

    @Override
    public String description() {
        return "Set or clear the constant value of one exact field.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String className = ToolArguments.requiredString(arguments, "class_name");
        String name = JvmClassMutationSupport.requiredMemberName(arguments, "name");
        String descriptor = JvmClassMutationSupport.requiredDescriptor(arguments);
        String rawConstant = arguments.has("constant_value")
                ? nullableString(arguments, "constant_value")
                : null;
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", className, name, descriptor);
        }

        Workspace workspace = workspaceSupplier.get();
        ClassMutationSupport.Resolution resolution = ClassMutationSupport.resolve(
                workspace.getPrimaryResource(),
                arguments,
                className,
                false
        );
        if (!resolution.found()) {
            return error(resolution.error(), className, name, descriptor);
        }
        ClassToolSupport.Entry entry = resolution.entry();

        JsonObject structured = ClassMutationSupport.errorMetadata(className);
        structured.addProperty("name", name);
        structured.addProperty("descriptor", descriptor);
        ClassToolSupport.addIdentity(structured, entry);

        Object parsed;
        Type type;
        try {
            type = AddFieldTool.fieldType(descriptor);
            parsed = rawConstant == null ? null : AddFieldTool.parseConstant(rawConstant, type);
        } catch (IllegalArgumentException ex) {
            return error(ex.getMessage(), className, name, descriptor);
        }

        try {
            ToolCancellation.check();
            if (entry.scope() == ClassToolSupport.Scope.JVM) {
                JvmClassInfo original = entry.classInfo().asJvmClass();
                org.objectweb.asm.tree.FieldNode target = findField(original, name, descriptor);
                if (target == null) {
                    return error(
                            "field with exact name and descriptor not found",
                            className,
                            name,
                            descriptor
                    );
                }
                structured.add("previous_constant", constantJson(target.value));
                JvmClassInfo patched = JvmClassMutationSupport.mutate(original, node -> {
                    for (org.objectweb.asm.tree.FieldNode field : node.fields) {
                        if (field.name.equals(name) && field.desc.equals(descriptor)) {
                            field.value = parsed;
                        }
                    }
                });
                JvmClassMutationSupport.store(workspace, patched);
                org.objectweb.asm.tree.FieldNode updated = findField(patched, name, descriptor);
                structured.add("constant", constantJson(updated == null ? null : updated.value));
            } else {
                ClassDefinition definition = ClassMutationSupport.definition(entry);
                FieldMember target = definition.getField(name, descriptor);
                if (target == null) {
                    return error(
                            "field with exact name and descriptor not found",
                            className,
                            name,
                            descriptor
                    );
                }
                structured.add(
                        "previous_constant",
                        constantJson(target.getStaticValue() == null
                                ? null : target.getStaticValue().toString())
                );
                var constant = AndroidMemberSupport.constant(parsed);
                target.setStaticValue(constant);
                ClassMutationSupport.storeAndroid(workspace, entry, definition);
                structured.add(
                        "constant",
                        constantJson(constant == null ? null : constant.toString())
                );
            }
            ToolCancellation.check();
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("field constant update failed: " + ex.getMessage(),
                    className, name, descriptor);
        }

        structured.addProperty("updated", true);
        structured.addProperty("cleared", parsed == null);
        return McpTool.successResult(
                (parsed == null ? "cleared" : "set") + " constant for " + name,
                structured
        );
    }

    private static org.objectweb.asm.tree.FieldNode findField(JvmClassInfo classInfo,
                                                              String name,
                                                              String descriptor) {
        try {
            ClassNode node = new ClassNode();
            new org.objectweb.asm.ClassReader(classInfo.getBytecode()).accept(node, 0);
            return node.fields.stream()
                    .filter(field -> field.name.equals(name) && field.desc.equals(descriptor))
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static com.google.gson.JsonElement constantJson(Object value) {
        if (value == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        if (value instanceof Number number) {
            return new com.google.gson.JsonPrimitive(number);
        }
        if (value instanceof Boolean bool) {
            return new com.google.gson.JsonPrimitive(bool);
        }
        return new com.google.gson.JsonPrimitive(String.valueOf(value));
    }

    private static String nullableString(JsonObject arguments, String name) {
        var element = arguments.get(name);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new IllegalArgumentException(name + " must be a nonblank string");
        }
        return element.getAsString();
    }

    private static JsonObject error(String message,
                                    String className,
                                    String name,
                                    String descriptor) {
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
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

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("minLength", 1);
        name.addProperty("maxLength", JvmClassMutationSupport.MAX_MEMBER_NAME_LENGTH);

        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("type", "string");
        descriptor.addProperty("minLength", 1);
        descriptor.addProperty("maxLength", JvmClassMutationSupport.MAX_DESCRIPTOR_LENGTH);

        JsonObject constant = new JsonObject();
        constant.addProperty("type", "string");
        constant.addProperty(
                "description",
                "Constant value parsed against the descriptor; omit or null to clear it."
        );

        JsonObject properties = new JsonObject();
        properties.add("class_name", className);
        properties.add("name", name);
        properties.add("descriptor", descriptor);
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
