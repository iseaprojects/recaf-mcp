package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Adds one method with an optional default-return stub body.
 */
public final class AddMethodTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "name", "descriptor", "access", "scope", "dex_name"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public AddMethodTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    AddMethodTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "add_method";
    }

    @Override
    public String description() {
        return "Add a method; concrete methods get a default-value return stub body.";
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
            throw new IllegalArgumentException(
                    "name must be a regular method name; <init> and <clinit> are not supported"
            );
        }
        Type methodType = methodType(descriptor);
        String accessSpec = ToolArguments.optionalString(arguments, "access");
        int access = MemberToolSupport.parseMemberAccess(
                accessSpec == null ? "public" : accessSpec,
                true
        );
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", className);
        }

        Workspace workspace = workspaceSupplier.get();
        JvmClassInfo original = JvmClassMutationSupport.lookup(workspace, className);
        if (original == null) {
            return error("class not found in primary JVM resource", className);
        }
        if (original.getDeclaredMethod(name, descriptor) != null) {
            return error("method with the same name and descriptor already exists", className);
        }

        MethodNode method = new MethodNode(access, name, descriptor, null, null);
        boolean needsBody = (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
        if (needsBody) {
            method.instructions = defaultBody(methodType.getReturnType());
        }

        JvmClassInfo patched;
        try {
            ToolCancellation.check();
            patched = JvmClassMutationSupport.mutate(original, node -> node.methods.add(method));
            if (!patched.getName().equals(className)) {
                return error("mutated class name does not match the original", className);
            }
            ToolCancellation.check();
            JvmClassMutationSupport.store(workspace, patched);
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("method addition failed: " + ex.getMessage(), className);
        }

        MethodMember added = patched.getDeclaredMethod(name, descriptor);
        JsonObject structured = JvmClassMutationSupport.metadata(className, original);
        JvmClassMutationSupport.addPatchedHash(structured, workspace, className);
        if (added == null) {
            structured.addProperty("method_added", false);
            return McpTool.errorResult("method was not present after patching", structured);
        }
        structured.addProperty("method_added", true);
        structured.addProperty("stub_body", needsBody);
        structured.addProperty("abstract_or_native", !needsBody);
        structured.add("method", MemberToolSupport.toJson(added, true));
        return McpTool.successResult(
                "added method " + name + descriptor + " to " + className,
                structured
        );
    }

    static Type methodType(String descriptor) {
        if (!descriptor.startsWith("(")) {
            throw new IllegalArgumentException("descriptor must be a JVM method descriptor");
        }
        try {
            Type.getMethodType(descriptor);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("descriptor is not a valid method descriptor");
        }
        return Type.getMethodType(descriptor);
    }

    static InsnList defaultBody(Type returnType) {
        InsnList instructions = new InsnList();
        switch (returnType.getSort()) {
            case Type.VOID -> instructions.add(new InsnNode(Opcodes.RETURN));
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                instructions.add(new InsnNode(Opcodes.ICONST_0));
                instructions.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.LONG -> {
                instructions.add(new InsnNode(Opcodes.LCONST_0));
                instructions.add(new InsnNode(Opcodes.LRETURN));
            }
            case Type.FLOAT -> {
                instructions.add(new InsnNode(Opcodes.FCONST_0));
                instructions.add(new InsnNode(Opcodes.FRETURN));
            }
            case Type.DOUBLE -> {
                instructions.add(new InsnNode(Opcodes.DCONST_0));
                instructions.add(new InsnNode(Opcodes.DRETURN));
            }
            default -> {
                instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                instructions.add(new InsnNode(Opcodes.ARETURN));
            }
        }
        return instructions;
    }

    private static JsonObject error(String message, String className) {
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
        structured.addProperty("scope", "primary_jvm");
        structured.addProperty("method_added", false);
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
        name.addProperty("description", "New method name; constructors are rejected.");

        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("type", "string");
        descriptor.addProperty("minLength", 1);
        descriptor.addProperty("maxLength", JvmClassMutationSupport.MAX_DESCRIPTOR_LENGTH);
        descriptor.addProperty("description", "JVM method descriptor, for example (I)Ljava/lang/String;.");

        JsonObject access = new JsonObject();
        access.addProperty("type", "string");
        access.addProperty("default", "public");
        access.addProperty(
                "description",
                "Comma-separated flags: public, private, protected, static, final, synchronized, "
                        + "bridge, varargs, native, abstract, strict, synthetic."
        );

        JsonObject properties = new JsonObject();
        properties.add("class_name", className);
        properties.add("name", name);
        properties.add("descriptor", descriptor);
        properties.add("access", access);
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
