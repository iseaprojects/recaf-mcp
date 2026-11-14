package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.remap.ClassDefinitionRemapper;
import me.darknet.dex.tree.remap.DefinitionRemapper;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.InstanceType;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.ReferenceType;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Copies one primary class under a new name, rewriting self references.
 */
public final class DuplicateClassTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "new_name", "scope", "dex_name"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public DuplicateClassTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    DuplicateClassTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "duplicate_class";
    }

    @Override
    public String description() {
        return "Copy one primary class under a new name, remapping self references.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String className = ToolArguments.requiredString(arguments, "class_name");
        String newName = ToolArguments.requiredString(arguments, "new_name");
        if (!AddClassTool.isValidInternalName(newName)) {
            throw new IllegalArgumentException("new_name must be a JVM internal name");
        }
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", className, newName);
        }

        Workspace workspace = workspaceSupplier.get();
        ClassMutationSupport.Resolution resolution = ClassMutationSupport.resolve(
                workspace.getPrimaryResource(),
                arguments,
                className,
                false
        );
        if (!resolution.found()) {
            return error(resolution.error(), className, newName);
        }
        ClassToolSupport.Entry entry = resolution.entry();

        JsonObject structured = ClassMutationSupport.errorMetadata(className);
        structured.addProperty("new_name", newName);
        ClassToolSupport.addIdentity(structured, entry);
        if (newName.equals(className)) {
            return McpTool.errorResult(
                    "new_name must differ from class_name",
                    structured
        );
        }

        try {
            ToolCancellation.check();
            if (entry.scope() == ClassToolSupport.Scope.JVM) {
                JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
                if (bundle.containsKey(newName)) {
                    structured.addProperty("error", "target class already exists");
                    return McpTool.errorResult("target class already exists", structured);
                }
                JvmClassInfo source = bundle.get(className);
                ClassWriter writer = new ClassWriter(0);
                ClassRemapper remapper = new ClassRemapper(
                        writer,
                        new SimpleRemapper(className, newName)
                );
                new ClassReader(source.getBytecode()).accept(remapper, 0);
                JvmClassInfo copy = new JvmClassInfoBuilder(writer.toByteArray()).build();
                if (!copy.getName().equals(newName)) {
                    structured.addProperty("error", "duplicated class name mismatch");
                    return McpTool.errorResult(
                            "duplicated class name mismatch",
                            structured
                    );
                }
                bundle.put(copy);
                structured.addProperty("duplicated", true);
                structured.addProperty("classfile_version", copy.getVersion());
                structured.addProperty("size_bytes", copy.getBytecode().length);
                structured.addProperty("sha256", JvmClassMutationSupport.sha256(
                        copy.getBytecode()
                ));
            } else {
                AndroidClassBundle bundle = ClassMutationSupport.androidBundle(
                        workspace,
                        entry
                );
                if (bundle.containsKey(newName)) {
                    structured.addProperty("error", "target class already exists");
                    return McpTool.errorResult("target class already exists", structured);
                }
                InstanceType target = me.darknet.dex.tree.type.Types
                        .instanceTypeFromInternalName(newName);
                ClassDefinition copy = new ClassDefinitionRemapper(
                        new DefinitionRemapper() {
                            @Override
                            public String mapInternalName(String name) {
                                return className.equals(name) ? newName : name;
                            }

                            @Override
                            public String mapFieldName(InstanceType owner,
                                                       String name,
                                                       ClassType type) {
                                return name;
                            }

                            @Override
                            public String mapMethodName(ReferenceType owner,
                                                        String name,
                                                        MethodType type) {
                                return name;
                            }
                        }
                ).remap(ClassMutationSupport.definition(entry));
                AndroidClassInfo info = new AndroidClassInfoBuilder(copy).build();
                if (!info.getName().equals(newName) || !copy.getType().equals(target)) {
                    structured.addProperty("error", "duplicated class name mismatch");
                    return McpTool.errorResult(
                            "duplicated class name mismatch",
                            structured
                    );
                }
                bundle.put(info);
                structured.addProperty("duplicated", true);
                structured.addProperty("field_count", info.getFields().size());
                structured.addProperty("method_count", info.getMethods().size());
            }
            ToolCancellation.check();
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            structured.addProperty("error", "class duplication failed: " + ex.getMessage());
            return McpTool.errorResult(
                    "class duplication failed: " + ex.getMessage(),
                    structured
            );
        }

        structured.addProperty("duplicated", true);
        structured.addProperty("self_references_remapped", true);
        return McpTool.successResult(
                "duplicated " + className + " as " + newName,
                structured
        );
    }

    private static JsonObject error(String message, String className, String newName) {
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
        structured.addProperty("new_name", newName);
        structured.addProperty("duplicated", false);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject className = new JsonObject();
        className.addProperty("type", "string");
        className.addProperty("minLength", 1);
        className.addProperty("maxLength", JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH);

        JsonObject newName = new JsonObject();
        newName.addProperty("type", "string");
        newName.addProperty("minLength", 1);
        newName.addProperty("maxLength", JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH);
        newName.addProperty("description", "Internal name of the new copy.");

        JsonObject properties = new JsonObject();
        properties.add("class_name", className);
        properties.add("new_name", newName);
        ClassToolSupport.addScopeProperties(properties, false);

        JsonArray required = new JsonArray();
        required.add("class_name");
        required.add("new_name");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
