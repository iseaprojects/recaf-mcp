package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.FieldMember;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Removes one annotation descriptor from a class, field, or method.
 */
public final class RemoveAnnotationTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "descriptor", "target", "name", "member_descriptor",
            "visible", "scope", "dex_name"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public RemoveAnnotationTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    RemoveAnnotationTool(BooleanSupplier hasWorkspace,
                         Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "remove_annotation";
    }

    @Override
    public String description() {
        return "Remove one annotation descriptor from a class, field, or method.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String className = ToolArguments.requiredString(arguments, "class_name");
        String descriptor = AnnotationToolSupport.descriptor(
                ToolArguments.requiredString(arguments, "descriptor")
        );
        String target = ToolArguments.optionalString(arguments, "target");
        if (target == null) {
            target = AnnotationToolSupport.CLASS_TARGET;
        }
        if (!AnnotationToolSupport.TARGETS.contains(target)) {
            throw new IllegalArgumentException("target must be class, field, or method");
        }
        String memberName = null;
        String memberDescriptor = null;
        if (!target.equals(AnnotationToolSupport.CLASS_TARGET)) {
            memberName = JvmClassMutationSupport.requiredMemberName(arguments, "name");
            memberDescriptor = JvmClassMutationSupport.requiredDescriptor(
                    arguments,
                    "member_descriptor"
            );
        }
        Boolean visible = arguments.has("visible")
                ? ToolArguments.bool(arguments, "visible", true)
                : null;
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", className, descriptor, target);
        }

        Workspace workspace = workspaceSupplier.get();
        ClassMutationSupport.Resolution resolution = ClassMutationSupport.resolve(
                workspace.getPrimaryResource(),
                arguments,
                className,
                false
        );
        if (!resolution.found()) {
            return error(resolution.error(), className, descriptor, target);
        }
        ClassToolSupport.Entry entry = resolution.entry();

        JsonObject structured = ClassMutationSupport.errorMetadata(className);
        ClassToolSupport.addIdentity(structured, entry);
        structured.addProperty("annotation", descriptor);
        structured.addProperty("target", target);

        AtomicInteger removed = new AtomicInteger();
        final String annotationTarget = target;
        final String annotationMemberName = memberName;
        final String annotationMemberDescriptor = memberDescriptor;
        try {
            ToolCancellation.check();
            if (entry.scope() == ClassToolSupport.Scope.JVM) {
                JvmClassInfo original = entry.classInfo().asJvmClass();
                JvmClassInfo patched = JvmClassMutationSupport.mutate(original, root -> {
                    List<List<org.objectweb.asm.tree.AnnotationNode>> lists =
                            targetLists(
                                    root,
                                    annotationTarget,
                                    annotationMemberName,
                                    annotationMemberDescriptor,
                                    visible
                            );
                    if (lists == null) {
                        throw new IllegalArgumentException(memberMissing(annotationTarget));
                    }
                    for (List<org.objectweb.asm.tree.AnnotationNode> list : lists) {
                        if (list == null) {
                            continue;
                        }
                        int before = list.size();
                        list.removeIf(node -> descriptor.equals(node.desc));
                        removed.addAndGet(before - list.size());
                    }
                });
                if (removed.get() > 0) {
                    JvmClassMutationSupport.store(workspace, patched);
                }
            } else {
                ClassDefinition definition = ClassMutationSupport.definition(entry);
                List<Annotation> annotations;
                if (target.equals(AnnotationToolSupport.CLASS_TARGET)) {
                    annotations = definition.getAnnotations();
                } else if (target.equals(AnnotationToolSupport.FIELD_TARGET)) {
                    FieldMember field = definition.getField(memberName, memberDescriptor);
                    if (field == null) {
                        return error(memberMissing(target), className, descriptor, target);
                    }
                    annotations = field.getAnnotations();
                } else {
                    MethodMember method = definition.getMethod(memberName, memberDescriptor);
                    if (method == null) {
                        return error(memberMissing(target), className, descriptor, target);
                    }
                    annotations = method.getAnnotations();
                }
                String internal = AnnotationToolSupport.internalName(descriptor);
                int before = annotations.size();
                annotations.removeIf(annotation ->
                        matches(annotation, internal, visible));
                removed.set(before - annotations.size());
                if (removed.get() > 0) {
                    ClassMutationSupport.storeAndroid(workspace, entry, definition);
                }
            }
            ToolCancellation.check();
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("annotation removal failed: " + ex.getMessage(),
                    className, descriptor, target);
        }

        if (removed.get() == 0) {
            return error("annotation not found on the requested target",
                    className, descriptor, target);
        }
        structured.addProperty("removed", true);
        structured.addProperty("removed_count", removed.get());
        return McpTool.successResult(
                "removed " + removed.get() + " annotation(s) " + descriptor,
                structured
        );
    }

    private static List<List<org.objectweb.asm.tree.AnnotationNode>> targetLists(
            ClassNode root,
            String target,
            String memberName,
            String memberDescriptor,
            Boolean visible) {
        if (target.equals(AnnotationToolSupport.CLASS_TARGET)) {
            return visible == null
                    ? java.util.Arrays.asList(root.visibleAnnotations, root.invisibleAnnotations)
                    : java.util.Collections.singletonList(
                            visible ? root.visibleAnnotations : root.invisibleAnnotations);
        }
        if (target.equals(AnnotationToolSupport.FIELD_TARGET)) {
            FieldNode field = root.fields.stream()
                    .filter(item -> item.name.equals(memberName)
                            && item.desc.equals(memberDescriptor))
                    .findFirst()
                    .orElse(null);
            if (field == null) {
                return null;
            }
            return visible == null
                    ? java.util.Arrays.asList(
                            field.visibleAnnotations,
                            field.invisibleAnnotations)
                    : java.util.Collections.singletonList(
                            visible ? field.visibleAnnotations : field.invisibleAnnotations);
        }
        MethodNode method = root.methods.stream()
                .filter(item -> item.name.equals(memberName)
                        && item.desc.equals(memberDescriptor))
                .findFirst()
                .orElse(null);
        if (method == null) {
            return null;
        }
        return visible == null
                ? java.util.Arrays.asList(
                        method.visibleAnnotations,
                        method.invisibleAnnotations)
                : java.util.Collections.singletonList(
                        visible ? method.visibleAnnotations : method.invisibleAnnotations);
    }

    private static boolean matches(Annotation annotation,
                                   String internalName,
                                   Boolean visible) {
        if (!annotation.annotation().type().internalName().equals(internalName)) {
            return false;
        }
        if (visible == null) {
            return true;
        }
        boolean runtime = annotation.visibility() == Annotation.VISIBILITY_RUNTIME;
        return runtime == visible;
    }

    private static String memberMissing(String target) {
        return target + " with exact name and descriptor not found";
    }

    private static JsonObject error(String message,
                                    String className,
                                    String descriptor,
                                    String target) {
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
        structured.addProperty("annotation", descriptor);
        structured.addProperty("target", target);
        structured.addProperty("removed", false);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject className = new JsonObject();
        className.addProperty("type", "string");
        className.addProperty("minLength", 1);
        className.addProperty("maxLength", JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH);

        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("type", "string");
        descriptor.addProperty("minLength", 1);

        JsonObject target = new JsonObject();
        target.addProperty("type", "string");
        JsonArray values = new JsonArray();
        values.add(AnnotationToolSupport.CLASS_TARGET);
        values.add(AnnotationToolSupport.FIELD_TARGET);
        values.add(AnnotationToolSupport.METHOD_TARGET);
        target.add("enum", values);
        target.addProperty("default", AnnotationToolSupport.CLASS_TARGET);

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description", "Member name when target is field or method.");

        JsonObject memberDescriptor = new JsonObject();
        memberDescriptor.addProperty("type", "string");

        JsonObject visible = new JsonObject();
        visible.addProperty("type", "boolean");
        visible.addProperty(
                "description",
                "Restrict removal to runtime-visible or build-time annotations."
        );

        JsonObject properties = new JsonObject();
        properties.add("class_name", className);
        properties.add("descriptor", descriptor);
        properties.add("target", target);
        properties.add("name", name);
        properties.add("member_descriptor", memberDescriptor);
        properties.add("visible", visible);
        ClassToolSupport.addScopeProperties(properties, false);

        JsonArray required = new JsonArray();
        required.add("class_name");
        required.add("descriptor");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
