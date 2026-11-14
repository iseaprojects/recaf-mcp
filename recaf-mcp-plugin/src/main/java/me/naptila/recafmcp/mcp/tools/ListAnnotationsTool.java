package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.annotation.AnnotationInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Summarizes annotation descriptors used across the primary class scope.
 */
public final class ListAnnotationsTool implements McpTool {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;
    private static final int MAX_SAMPLES = 3;
    private static final Set<String> ARGUMENTS = Set.of(
            "scope", "dex_name", "filter", "include_members", "offset", "limit"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public ListAnnotationsTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    ListAnnotationsTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "list_annotations";
    }

    @Override
    public String description() {
        return "List annotation descriptors used by primary classes and members with counts.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(arguments, true);
        String filter = ToolArguments.optionalString(arguments, "filter");
        boolean includeMembers = ToolArguments.bool(arguments, "include_members", true);
        int offset = ToolArguments.integer(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = ToolArguments.integer(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", selection, filter);
        }

        Workspace workspace = workspaceSupplier.get();
        Map<String, Accumulator> annotations = new LinkedHashMap<>();
        int scanned = 0;
        for (ClassToolSupport.Entry entry
                : ClassToolSupport.entries(workspace.getPrimaryResource(), selection)) {
            ToolCancellation.check();
            scanned++;
            ClassInfo classInfo = entry.classInfo();
            collect(annotations, entry, classInfo, classInfo.getAnnotations(), "class");
            if (!includeMembers) {
                continue;
            }
            for (ClassMember member : classInfo.fieldAndMethodStream().toList()) {
                collect(
                        annotations,
                        entry,
                        classInfo,
                        member.getAnnotations(),
                        member.isField() ? "field" : "method"
                );
            }
        }

        List<Accumulator> ordered = annotations.values().stream()
                .filter(item -> filter == null || item.descriptor.contains(filter))
                .sorted(Comparator.comparing(Accumulator::descriptor))
                .toList();
        int start = Math.min(offset, ordered.size());
        int end = Math.min(start + limit, ordered.size());
        JsonArray page = new JsonArray();
        ordered.subList(start, end).forEach(item -> page.add(item.toJson()));

        JsonObject structured = new JsonObject();
        structured.addProperty("requested_scope", selection.scope().value());
        if (selection.dexName() == null) {
            structured.add("dex_name", JsonNull.INSTANCE);
        } else {
            structured.addProperty("dex_name", selection.dexName());
        }
        structured.add("filter", filter == null
                ? JsonNull.INSTANCE
                : new com.google.gson.JsonPrimitive(filter));
        structured.addProperty("include_members", includeMembers);
        structured.addProperty("classes_scanned", scanned);
        structured.add("annotations", page);
        structured.addProperty("total", ordered.size());
        structured.addProperty("offset", start);
        structured.addProperty("returned", page.size());
        if (end < ordered.size()) {
            structured.addProperty("next_offset", end);
        } else {
            structured.add("next_offset", JsonNull.INSTANCE);
        }
        return McpTool.successResult(
                "found " + ordered.size() + " distinct annotations",
                structured
        );
    }

    private static void collect(Map<String, Accumulator> annotations,
                                ClassToolSupport.Entry entry,
                                ClassInfo classInfo,
                                List<AnnotationInfo> values,
                                String target) {
        for (AnnotationInfo annotation : values) {
            annotations.computeIfAbsent(
                    annotation.getDescriptor(),
                    descriptor -> new Accumulator(descriptor)
            ).record(entry, classInfo, annotation, target);
        }
    }

    private static JsonObject error(String message,
                                    ClassToolSupport.Selection selection,
                                    String filter) {
        JsonObject structured = new JsonObject();
        structured.addProperty("requested_scope", selection.scope().value());
        if (filter != null) {
            structured.addProperty("filter", filter);
        }
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject filter = new JsonObject();
        filter.addProperty("type", "string");
        filter.addProperty("minLength", 1);
        filter.addProperty("description", "Case-sensitive substring filter on the descriptor.");

        JsonObject includeMembers = new JsonObject();
        includeMembers.addProperty("type", "boolean");
        includeMembers.addProperty("default", true);

        JsonObject offset = new JsonObject();
        offset.addProperty("type", "integer");
        offset.addProperty("minimum", 0);
        offset.addProperty("default", 0);

        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("minimum", 1);
        limit.addProperty("maximum", MAX_LIMIT);
        limit.addProperty("default", DEFAULT_LIMIT);

        JsonObject properties = new JsonObject();
        properties.add("filter", filter);
        properties.add("include_members", includeMembers);
        properties.add("offset", offset);
        properties.add("limit", limit);
        ClassToolSupport.addScopeProperties(properties, true);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static final class Accumulator {
        private final String descriptor;
        private final Set<String> classes = new java.util.TreeSet<>();
        private int total;
        private int classCount;
        private int fieldCount;
        private int methodCount;
        private int visibleCount;
        private int invisibleCount;
        private final JsonArray samples = new JsonArray();

        private Accumulator(String descriptor) {
            this.descriptor = descriptor;
        }

        String descriptor() {
            return descriptor;
        }

        void record(ClassToolSupport.Entry entry,
                    ClassInfo classInfo,
                    AnnotationInfo annotation,
                    String target) {
            total++;
            classes.add(entry.scope().value() + ":" + entry.className());
            switch (target) {
                case "class" -> classCount++;
                case "field" -> fieldCount++;
                default -> methodCount++;
            }
            if (annotation.isVisible()) {
                visibleCount++;
            } else {
                invisibleCount++;
            }
            if (samples.size() < MAX_SAMPLES) {
                JsonObject sample = new JsonObject();
                sample.addProperty("class_name", classInfo.getName());
                ClassToolSupport.addHitIdentity(sample, entry);
                sample.addProperty("target", target);
                samples.add(sample);
            }
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("descriptor", descriptor);
            json.addProperty(
                    "type",
                    MemberToolSupport.classNameFromDescriptor(descriptor)
            );
            json.addProperty("occurrences", total);
            json.addProperty("class_count", classes.size());
            json.addProperty("class_annotation_count", classCount);
            json.addProperty("field_annotation_count", fieldCount);
            json.addProperty("method_annotation_count", methodCount);
            json.addProperty("visible_count", visibleCount);
            json.addProperty("invisible_count", invisibleCount);
            json.add("samples", samples);
            return json;
        }
    }
}
