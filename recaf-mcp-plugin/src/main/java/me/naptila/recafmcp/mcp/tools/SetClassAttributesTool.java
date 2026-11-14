package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.Types;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Rewrites class-level attributes for one primary class.
 */
public final class SetClassAttributesTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "access", "super_name", "interfaces", "add_interfaces",
            "remove_interfaces", "signature", "source_file", "classfile_version",
            "scope", "dex_name"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public SetClassAttributesTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    SetClassAttributesTool(BooleanSupplier hasWorkspace,
                           Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "set_class_attributes";
    }

    @Override
    public String description() {
        return "Replace class access flags, superclass, interfaces, signature, or classfile version.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String className = ToolArguments.requiredString(arguments, "class_name");
        String accessSpec = ToolArguments.optionalString(arguments, "access");
        String superName = ToolArguments.optionalString(arguments, "super_name");
        if (superName != null && !AddClassTool.isValidInternalName(superName)) {
            throw new IllegalArgumentException("super_name must be a JVM internal name");
        }
        List<String> interfaces = ToolArguments.optionalStringArray(arguments, "interfaces");
        List<String> addInterfaces = ToolArguments.optionalStringArray(
                arguments,
                "add_interfaces"
        );
        List<String> removeInterfaces = ToolArguments.optionalStringArray(
                arguments,
                "remove_interfaces"
        );
        for (List<String> list : List.of(
                interfaces == null ? List.<String>of() : interfaces,
                addInterfaces == null ? List.<String>of() : addInterfaces,
                removeInterfaces == null ? List.<String>of() : removeInterfaces
        )) {
            for (String entry : list) {
                if (!AddClassTool.isValidInternalName(entry)) {
                    throw new IllegalArgumentException(
                            "interface names must be JVM internal names"
                    );
                }
            }
        }
        if (interfaces != null && (addInterfaces != null || removeInterfaces != null)) {
            throw new IllegalArgumentException(
                    "interfaces cannot be combined with add_interfaces or remove_interfaces"
            );
        }
        String signature = nullableString(arguments, "signature");
        String sourceFile = nullableString(arguments, "source_file");
        Integer classVersion = arguments.has("classfile_version")
                ? ToolArguments.integer(
                        arguments,
                        "classfile_version",
                        0,
                        45,
                        Runtime.version().feature() + 44
                )
                : null;
        if (accessSpec == null && superName == null && interfaces == null
                && addInterfaces == null && removeInterfaces == null
                && signature == null && sourceFile == null && classVersion == null) {
            throw new IllegalArgumentException("at least one attribute must be provided");
        }
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", className);
        }

        Workspace workspace = workspaceSupplier.get();
        ClassMutationSupport.Resolution resolution = ClassMutationSupport.resolve(
                workspace.getPrimaryResource(),
                arguments,
                className,
                false
        );
        if (!resolution.found()) {
            return error(resolution.error(), className);
        }
        ClassToolSupport.Entry entry = resolution.entry();
        boolean jvm = entry.scope() == ClassToolSupport.Scope.JVM;
        if (!jvm && classVersion != null) {
            return error("classfile_version is only supported for JVM classes", className);
        }
        if (!jvm && accessSpec != null) {
            return error(
                    "access replacement is not supported for Android classes; "
                            + "recreate the member or use run_recaf_script",
                    className
            );
        }

        JsonObject structured = ClassMutationSupport.errorMetadata(className);
        ClassToolSupport.addIdentity(structured, entry);
        structured.addProperty("updated", true);
        classSnapshot(structured, entry.classInfo(), "before");

        try {
            ToolCancellation.check();
            if (jvm) {
                JvmClassInfo original = entry.classInfo().asJvmClass();
                JvmClassInfo patched = JvmClassMutationSupport.mutate(
                        original,
                        node -> applyJvm(
                                node,
                                accessSpec,
                                superName,
                                interfaces,
                                addInterfaces,
                                removeInterfaces,
                                signature,
                                sourceFile,
                                classVersion
                        )
                );
                if (!patched.getName().equals(className)) {
                    return error("mutated class name does not match the original", className);
                }
                JvmClassMutationSupport.store(workspace, patched);
                classSnapshot(structured, patched, "after");
            } else {
                ClassDefinition definition = ClassMutationSupport.definition(entry);
                applyAndroid(
                        definition,
                        superName,
                        interfaces,
                        addInterfaces,
                        removeInterfaces,
                        signature,
                        sourceFile
                );
                AndroidClassInfo updated = ClassMutationSupport.storeAndroid(
                        workspace,
                        entry,
                        definition
                );
                classSnapshot(structured, updated, "after");
            }
            ToolCancellation.check();
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("class attribute update failed: " + ex.getMessage(), className);
        }

        return McpTool.successResult("updated class attributes for " + className, structured);
    }

    private static void applyJvm(ClassNode node,
                                 String accessSpec,
                                 String superName,
                                 List<String> interfaces,
                                 List<String> addInterfaces,
                                 List<String> removeInterfaces,
                                 String signature,
                                 String sourceFile,
                                 Integer classVersion) {
        if (accessSpec != null) {
            int access = MemberToolSupport.parseClassAccess(accessSpec);
            if ((access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_MODULE)) == 0) {
                access |= Opcodes.ACC_SUPER;
            }
            node.access = access;
        }
        if (superName != null) {
            if ("java/lang/Object".equals(node.name)) {
                throw new IllegalArgumentException("java/lang/Object cannot have a superclass");
            }
            node.superName = superName;
        }
        if (interfaces != null) {
            node.interfaces = new ArrayList<>(new LinkedHashSet<>(interfaces));
        }
        if (addInterfaces != null) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(node.interfaces);
            merged.addAll(addInterfaces);
            node.interfaces = new ArrayList<>(merged);
        }
        if (removeInterfaces != null) {
            node.interfaces.removeAll(removeInterfaces);
        }
        if (signature != null) {
            node.signature = signature.isEmpty() ? null : signature;
        }
        if (sourceFile != null) {
            node.sourceFile = sourceFile.isEmpty() ? null : sourceFile;
        }
        if (classVersion != null) {
            node.version = classVersion;
        }
    }

    private static void applyAndroid(ClassDefinition definition,
                                     String superName,
                                     List<String> interfaces,
                                     List<String> addInterfaces,
                                     List<String> removeInterfaces,
                                     String signature,
                                     String sourceFile) {
        if (superName != null) {
            definition.setSuperClass(Types.instanceTypeFromInternalName(superName));
        }
        if (interfaces != null) {
            definition.getInterfaces().clear();
            interfaces.stream()
                    .distinct()
                    .map(Types::instanceTypeFromInternalName)
                    .forEach(definition::addInterface);
        }
        if (addInterfaces != null) {
            Set<String> existing = new LinkedHashSet<>();
            for (InstanceType type : definition.getInterfaces()) {
                existing.add(type.internalName());
            }
            for (String name : addInterfaces) {
                if (existing.add(name)) {
                    definition.addInterface(Types.instanceTypeFromInternalName(name));
                }
            }
        }
        if (removeInterfaces != null) {
            definition.getInterfaces().removeIf(type ->
                    removeInterfaces.contains(type.internalName()));
        }
        if (signature != null) {
            definition.setSignature(signature.isEmpty() ? null : signature);
        }
        if (sourceFile != null) {
            definition.setSourceFile(sourceFile.isEmpty() ? null : sourceFile);
        }
    }

    private static void classSnapshot(JsonObject target, ClassInfo classInfo, String prefix) {
        target.addProperty(prefix + "_access", classInfo.getAccess());
        target.add(prefix + "_modifiers", MemberToolSupport.classModifiers(
                classInfo.getAccess()
        ));
        if (classInfo.getSuperName() == null) {
            target.add(prefix + "_super_name", com.google.gson.JsonNull.INSTANCE);
        } else {
            target.addProperty(prefix + "_super_name", classInfo.getSuperName());
        }
        JsonArray json = new JsonArray();
        classInfo.getInterfaces().forEach(json::add);
        target.add(prefix + "_interfaces", json);
        if (classInfo instanceof JvmClassInfo jvmClass) {
            target.addProperty(prefix + "_classfile_version", jvmClass.getVersion());
        }
    }

    private static String nullableString(JsonObject arguments, String name) {
        JsonElement element = arguments.get(name);
        if (element == null) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return element.getAsString();
    }

    private static JsonObject error(String message, String className) {
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
        structured.addProperty("updated", false);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject className = new JsonObject();
        className.addProperty("type", "string");
        className.addProperty("minLength", 1);
        className.addProperty("maxLength", JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH);

        JsonObject access = new JsonObject();
        access.addProperty("type", "string");
        access.addProperty(
                "description",
                "Complete replacement flag set: public, final, super, interface, abstract, "
                        + "synthetic, annotation, enum."
        );

        JsonObject superName = new JsonObject();
        superName.addProperty("type", "string");
        superName.addProperty("description", "New superclass internal name.");

        JsonObject interfaces = arraySchema(
                "Complete replacement interface list.");
        JsonObject addInterfaces = arraySchema("Interfaces to append.");
        JsonObject removeInterfaces = arraySchema("Interfaces to remove.");

        JsonObject signature = new JsonObject();
        signature.addProperty("type", "string");
        signature.addProperty("description", "Generic signature; empty string clears it.");

        JsonObject sourceFile = new JsonObject();
        sourceFile.addProperty("type", "string");
        sourceFile.addProperty("description", "SourceFile attribute; empty string clears it.");

        JsonObject version = new JsonObject();
        version.addProperty("type", "integer");
        version.addProperty("minimum", 45);
        version.addProperty("description", "Classfile major version; JVM classes only.");

        JsonObject properties = new JsonObject();
        properties.add("class_name", className);
        properties.add("access", access);
        properties.add("super_name", superName);
        properties.add("interfaces", interfaces);
        properties.add("add_interfaces", addInterfaces);
        properties.add("remove_interfaces", removeInterfaces);
        properties.add("signature", signature);
        properties.add("source_file", sourceFile);
        properties.add("classfile_version", version);
        ClassToolSupport.addScopeProperties(properties, false);

        JsonArray required = new JsonArray();
        required.add("class_name");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject arraySchema(String description) {
        JsonObject array = new JsonObject();
        array.addProperty("type", "array");
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        array.add("items", items);
        array.addProperty("description", description);
        return array;
    }
}
