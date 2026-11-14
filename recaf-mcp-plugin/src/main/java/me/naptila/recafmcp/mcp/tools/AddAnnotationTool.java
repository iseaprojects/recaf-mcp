package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.FieldMember;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.annotation.Annotation;
import me.darknet.dex.tree.definitions.annotation.AnnotationPart;
import me.darknet.dex.tree.type.Types;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Adds one annotation to a class, field, or method.
 */
public final class AddAnnotationTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "descriptor", "target", "name", "member_descriptor",
            "visible", "elements", "scope", "dex_name"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public AddAnnotationTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    AddAnnotationTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "add_annotation";
    }

    @Override
    public String description() {
        return "Attach one annotation with constant elements to a class, field, or method.";
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
        boolean visible = ToolArguments.bool(arguments, "visible", true);
        JsonObject elements = ToolArguments.optionalObject(arguments, "elements");
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
        structured.addProperty("visible", visible);

        final String annotationTarget = target;
        final String annotationMemberName = memberName;
        final String annotationMemberDescriptor = memberDescriptor;
        try {
            ToolCancellation.check();
            if (entry.scope() == ClassToolSupport.Scope.JVM) {
                AnnotationNode node = AnnotationToolSupport.node(descriptor, elements);
                JvmClassInfo original = entry.classInfo().asJvmClass();
                JvmClassInfo patched = JvmClassMutationSupport.mutate(original, root -> {
                    List<AnnotationNode> annotations = annotationList(
                            root,
                            annotationTarget,
                            annotationMemberName,
                            annotationMemberDescriptor,
                            visible
                    );
                    if (annotations == null) {
                        throw new IllegalArgumentException(
                                memberTargetMissing(annotationTarget));
                    }
                    if (hasAnnotation(annotations, descriptor)) {
                        throw new IllegalStateException("annotation is already present");
                    }
                    annotations.add(node);
                });
                JvmClassMutationSupport.store(workspace, patched);
            } else {
                ClassDefinition definition = ClassMutationSupport.definition(entry);
                List<Annotation> annotations;
                java.util.function.Consumer<Annotation> adder;
                if (target.equals(AnnotationToolSupport.CLASS_TARGET)) {
                    annotations = definition.getAnnotations();
                    adder = definition::addAnnotation;
                } else if (target.equals(AnnotationToolSupport.FIELD_TARGET)) {
                    FieldMember field = definition.getField(memberName, memberDescriptor);
                    if (field == null) {
                        return error(memberTargetMissing(target),
                                className, descriptor, target);
                    }
                    annotations = field.getAnnotations();
                    adder = field::addAnnotation;
                } else {
                    MethodMember method = definition.getMethod(memberName, memberDescriptor);
                    if (method == null) {
                        return error(memberTargetMissing(target),
                                className, descriptor, target);
                    }
                    annotations = method.getAnnotations();
                    adder = method::addAnnotation;
                }
                String internal = AnnotationToolSupport.internalName(descriptor);
                if (annotations.stream().anyMatch(annotation ->
                        annotation.annotation().type().internalName().equals(internal))) {
                    return error("annotation is already present",
                            className, descriptor, target);
                }
                adder.accept(new Annotation(
                        AnnotationToolSupport.dexVisibility(visible),
                        new AnnotationPart(
                                Types.instanceTypeFromDescriptor(descriptor),
                                AnnotationToolSupport.dexElements(elements)
                        )
                ));
                ClassMutationSupport.storeAndroid(workspace, entry, definition);
            }
            ToolCancellation.check();
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("annotation addition failed: " + ex.getMessage(),
                    className, descriptor, target);
        }

        structured.addProperty("added", true);
        structured.addProperty(
                "element_count",
                elements == null ? 0 : elements.size()
        );
        return McpTool.successResult(
                "added annotation " + descriptor + " to " + target,
                structured
        );
    }

    private static List<AnnotationNode> annotationList(ClassNode root,
                                                       String target,
                                                       String memberName,
                                                       String memberDescriptor,
                                                       boolean visible) {
        java.util.function.Supplier<List<AnnotationNode>> getter;
        java.util.function.Consumer<List<AnnotationNode>> setter;
        if (target.equals(AnnotationToolSupport.CLASS_TARGET)) {
            getter = () -> visible ? root.visibleAnnotations : root.invisibleAnnotations;
            setter = value -> {
                if (visible) {
                    root.visibleAnnotations = value;
                } else {
                    root.invisibleAnnotations = value;
                }
            };
        } else if (target.equals(AnnotationToolSupport.FIELD_TARGET)) {
            FieldNode field = root.fields.stream()
                    .filter(item -> item.name.equals(memberName)
                            && item.desc.equals(memberDescriptor))
                    .findFirst()
                    .orElse(null);
            if (field == null) {
                return null;
            }
            getter = () -> visible ? field.visibleAnnotations : field.invisibleAnnotations;
            setter = value -> {
                if (visible) {
                    field.visibleAnnotations = value;
                } else {
                    field.invisibleAnnotations = value;
                }
            };
        } else {
            MethodNode method = root.methods.stream()
                    .filter(item -> item.name.equals(memberName)
                            && item.desc.equals(memberDescriptor))
                    .findFirst()
                    .orElse(null);
            if (method == null) {
                return null;
            }
            getter = () -> visible ? method.visibleAnnotations : method.invisibleAnnotations;
            setter = value -> {
                if (visible) {
                    method.visibleAnnotations = value;
                } else {
                    method.invisibleAnnotations = value;
                }
            };
        }
        List<AnnotationNode> existing = getter.get();
        if (existing == null) {
            existing = new ArrayList<>();
            setter.accept(existing);
        }
        return existing;
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations, String descriptor) {
        return annotations.stream().anyMatch(node -> descriptor.equals(node.desc));
    }

    private static String memberTargetMissing(String target) {
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
        structured.addProperty("added", false);
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
        descriptor.addProperty(
                "description",
                "Annotation descriptor such as Lkotlin/Metadata; or a class name."
        );

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
        memberDescriptor.addProperty("description", "Member descriptor when target is a member.");

        JsonObject visible = new JsonObject();
        visible.addProperty("type", "boolean");
        visible.addProperty("default", true);
        visible.addProperty("description", "Runtime-visible annotation; false means build-time.");

        JsonObject elements = new JsonObject();
        elements.addProperty("type", "object");
        elements.addProperty(
                "description",
                "Element name to primitive, string, or array value."
        );

        JsonObject properties = new JsonObject();
        properties.add("class_name", className);
        properties.add("descriptor", descriptor);
        properties.add("target", target);
        properties.add("name", name);
        properties.add("member_descriptor", memberDescriptor);
        properties.add("visible", visible);
        properties.add("elements", elements);
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
