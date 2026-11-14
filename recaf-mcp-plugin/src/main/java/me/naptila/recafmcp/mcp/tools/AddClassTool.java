package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Creates a new primary JVM class with a generated default constructor.
 */
public final class AddClassTool implements McpTool {
    private static final int DEFAULT_CLASS_VERSION = Opcodes.V1_8;
    private static final int MIN_CLASS_VERSION = 45;
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "super_name", "interfaces", "access", "signature",
            "classfile_version"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public AddClassTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    AddClassTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "add_class";
    }

    @Override
    public String description() {
        return "Create a primary JVM class with a default constructor and declared supertypes.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String className = ToolArguments.requiredString(arguments, "class_name");
        if (className.length() > JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH) {
            throw new IllegalArgumentException("class_name is too long");
        }
        if (!isValidInternalName(className)) {
            throw new IllegalArgumentException("class_name must be a JVM internal name");
        }
        String superName = ToolArguments.optionalString(arguments, "super_name");
        if (superName == null) {
            superName = "java/lang/Object";
        } else if (!isValidInternalName(superName)) {
            throw new IllegalArgumentException("super_name must be a JVM internal name");
        }
        List<String> interfaces = ToolArguments.optionalStringArray(arguments, "interfaces");
        if (interfaces == null) {
            interfaces = List.of();
        }
        for (String entry : interfaces) {
            if (!isValidInternalName(entry)) {
                throw new IllegalArgumentException("interfaces must contain JVM internal names");
            }
        }
        String accessSpec = ToolArguments.optionalString(arguments, "access");
        int access = MemberToolSupport.parseClassAccess(
                accessSpec == null ? "public super" : accessSpec
        );
        String signature = ToolArguments.optionalString(arguments, "signature");
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", className);
        }

        Workspace workspace = workspaceSupplier.get();
        JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
        if (bundle.containsKey(className)) {
            return error("class already exists in the primary JVM resource", className);
        }

        int maximum = Runtime.version().feature() + 44;
        int version = ToolArguments.integer(
                arguments,
                "classfile_version",
                inferVersion(bundle),
                MIN_CLASS_VERSION,
                maximum
        );
        boolean isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
        boolean isModule = (access & Opcodes.ACC_MODULE) != 0;
        if ((isInterface || isModule) && !"java/lang/Object".equals(superName)) {
            return error("interfaces and modules must extend java/lang/Object", className);
        }
        int effectiveAccess = isInterface || isModule ? access : access | Opcodes.ACC_SUPER;

        JvmClassInfo created;
        try {
            ToolCancellation.check();
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            writer.visit(
                    version,
                    effectiveAccess,
                    className,
                    signature,
                    superName,
                    interfaces.isEmpty() ? null : interfaces.toArray(String[]::new)
            );
            if (!isInterface && !isModule) {
                MethodVisitor constructor = writer.visitMethod(
                        Opcodes.ACC_PUBLIC,
                        "<init>",
                        "()V",
                        null,
                        null
                );
                constructor.visitCode();
                constructor.visitVarInsn(Opcodes.ALOAD, 0);
                constructor.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        superName,
                        "<init>",
                        "()V",
                        false
                );
                constructor.visitInsn(Opcodes.RETURN);
                constructor.visitMaxs(0, 0);
                constructor.visitEnd();
            }
            writer.visitEnd();
            created = new JvmClassInfoBuilder(writer.toByteArray()).build();
            if (!created.getName().equals(className)) {
                return error("generated class name does not match the request", className);
            }
            ToolCancellation.check();
            bundle.put(created);
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("class creation failed: " + exceptionMessage(ex), className);
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
        structured.addProperty("scope", "primary_jvm");
        structured.addProperty("created", true);
        structured.addProperty("classfile_version", created.getVersion());
        structured.addProperty("access", created.getAccess());
        structured.add("modifiers", MemberToolSupport.classModifiers(created.getAccess()));
        structured.addProperty("super_name", Objects.requireNonNullElse(
                created.getSuperName(),
                ""
        ));
        JsonArray interfaceJson = new JsonArray();
        created.getInterfaces().forEach(interfaceJson::add);
        structured.add("interfaces", interfaceJson);
        structured.addProperty("constructor_generated", !isInterface && !isModule);
        structured.addProperty(
                "field_count",
                bundle.get(className).getFields().size()
        );
        structured.addProperty(
                "method_count",
                bundle.get(className).getMethods().size()
        );
        structured.addProperty(
                "size_bytes",
                Objects.requireNonNull(bundle.get(className)).getBytecode().length
        );
        structured.addProperty(
                "sha256",
                JvmClassMutationSupport.sha256(Objects.requireNonNull(
                        bundle.get(className)
                ).getBytecode())
        );
        return McpTool.successResult("created class " + className, structured);
    }

    static int inferVersion(JvmClassBundle bundle) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (JvmClassInfo classInfo : bundle.values()) {
            counts.merge(classInfo.getVersion(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.<Integer, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .orElse(DEFAULT_CLASS_VERSION);
    }

    static boolean isValidInternalName(String value) {
        if (value.isBlank() || value.startsWith("[") || value.startsWith(".")
                || value.endsWith("/") || value.contains("..")) {
            return false;
        }
        for (String segment : value.split("/", -1)) {
            if (!segment.matches("[A-Za-z_$][A-Za-z0-9_$-]*")) {
                return false;
            }
        }
        return true;
    }

    private static JsonObject error(String message, String className) {
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
        structured.addProperty("scope", "primary_jvm");
        structured.addProperty("created", false);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static String exceptionMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private static JsonObject createInputSchema() {
        JsonObject className = new JsonObject();
        className.addProperty("type", "string");
        className.addProperty("minLength", 1);
        className.addProperty("maxLength", JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH);
        className.addProperty("description", "New primary JVM internal class name.");

        JsonObject superName = new JsonObject();
        superName.addProperty("type", "string");
        superName.addProperty("description", "Superclass internal name; defaults to java/lang/Object.");

        JsonObject interfaces = new JsonObject();
        interfaces.addProperty("type", "array");
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        interfaces.add("items", items);
        interfaces.addProperty("description", "Implemented interface internal names.");

        JsonObject access = new JsonObject();
        access.addProperty("type", "string");
        access.addProperty("default", "public super");
        access.addProperty(
                "description",
                "Comma-separated class flags: public, final, super, interface, abstract, "
                        + "synthetic, annotation, enum."
        );

        JsonObject signature = new JsonObject();
        signature.addProperty("type", "string");
        signature.addProperty("description", "Optional generic signature.");

        JsonObject version = new JsonObject();
        version.addProperty("type", "integer");
        version.addProperty("minimum", MIN_CLASS_VERSION);
        version.addProperty("description",
                "Classfile major version; defaults to the workspace's most common version.");

        JsonObject properties = new JsonObject();
        properties.add("class_name", className);
        properties.add("super_name", superName);
        properties.add("interfaces", interfaces);
        properties.add("access", access);
        properties.add("signature", signature);
        properties.add("classfile_version", version);

        JsonArray required = new JsonArray();
        required.add("class_name");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
