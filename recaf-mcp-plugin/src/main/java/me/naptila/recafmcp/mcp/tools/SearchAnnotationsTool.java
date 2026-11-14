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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Finds classes or members annotated with a descriptor substring.
 */
public final class SearchAnnotationsTool implements McpTool {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1_000;
    private static final int MAX_COLLECTED = 20_000;
    private static final Set<String> TARGETS = Set.of("any", "class", "field", "method");
    private static final Set<String> ARGUMENTS = Set.of(
            "descriptor", "target", "scope", "dex_name", "offset", "limit"
    );
    private static final Comparator<Hit> HIT_ORDER = Comparator
            .comparing(Hit::className)
            .thenComparing(Hit::target)
            .thenComparing(hit -> hit.memberName() == null ? "" : hit.memberName())
            .thenComparing(hit -> hit.memberDescriptor() == null ? "" : hit.memberDescriptor())
            .thenComparing(Hit::annotation);
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public SearchAnnotationsTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    SearchAnnotationsTool(BooleanSupplier hasWorkspace,
                          Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "search_annotations";
    }

    @Override
    public String description() {
        return "Find classes and members annotated with a descriptor substring.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String term = ToolArguments.requiredString(arguments, "descriptor");
        String target = ToolArguments.optionalString(arguments, "target");
        if (target == null) {
            target = "any";
        }
        if (!TARGETS.contains(target)) {
            throw new IllegalArgumentException("target must be any, class, field, or method");
        }
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(arguments, true);
        int offset = ToolArguments.integer(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = ToolArguments.integer(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", term, target);
        }

        Workspace workspace = workspaceSupplier.get();
        List<Hit> hits = new ArrayList<>();
        int scanned = 0;
        boolean truncated = false;
        outer:
        for (ClassToolSupport.Entry entry
                : ClassToolSupport.entries(workspace.getPrimaryResource(), selection)) {
            ToolCancellation.check();
            scanned++;
            ClassInfo classInfo = entry.classInfo();
            for (AnnotationInfo annotation : classInfo.getAnnotations()) {
                if (hits.size() >= MAX_COLLECTED) {
                    truncated = true;
                    break outer;
                }
                if (matches(annotation, term, target, "class")) {
                    hits.add(Hit.of(entry, annotation, "class", null));
                }
            }
            if (!target.equals("any") && !target.equals("field") && !target.equals("method")) {
                continue;
            }
            for (ClassMember member : classInfo.fieldAndMethodStream().toList()) {
                String kind = member.isField() ? "field" : "method";
                if (!target.equals("any") && !target.equals(kind)) {
                    continue;
                }
                for (AnnotationInfo annotation : member.getAnnotations()) {
                    if (hits.size() >= MAX_COLLECTED) {
                        truncated = true;
                        break outer;
                    }
                    if (matches(annotation, term, kind, kind)) {
                        hits.add(Hit.of(entry, annotation, kind, member));
                    }
                }
            }
        }

        hits.sort(HIT_ORDER);
        int start = Math.min(offset, hits.size());
        int end = Math.min(start + limit, hits.size());
        JsonArray page = new JsonArray();
        hits.subList(start, end).forEach(hit -> page.add(hit.toJson()));

        JsonObject structured = new JsonObject();
        structured.addProperty("descriptor_filter", term);
        structured.addProperty("target", target);
        structured.addProperty("requested_scope", selection.scope().value());
        if (selection.dexName() == null) {
            structured.add("dex_name", JsonNull.INSTANCE);
        } else {
            structured.addProperty("dex_name", selection.dexName());
        }
        structured.addProperty("classes_scanned", scanned);
        structured.add("hits", page);
        structured.addProperty("total", hits.size());
        structured.addProperty("offset", start);
        structured.addProperty("returned", page.size());
        if (end < hits.size()) {
            structured.addProperty("next_offset", end);
        } else {
            structured.add("next_offset", JsonNull.INSTANCE);
        }
        structured.addProperty("collection_truncated", truncated);
        return McpTool.successResult(
                "found " + hits.size() + " annotated targets",
                structured
        );
    }

    private static boolean matches(AnnotationInfo annotation,
                                   String term,
                                   String requested,
                                   String actual) {
        if (!requested.equals("any") && !requested.equals(actual)) {
            return false;
        }
        return annotation.getDescriptor().contains(term)
                || MemberToolSupport.classNameFromDescriptor(annotation.getDescriptor())
                .contains(term);
    }

    private static JsonObject error(String message, String term, String target) {
        JsonObject structured = new JsonObject();
        structured.addProperty("descriptor_filter", term);
        structured.addProperty("target", target);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("type", "string");
        descriptor.addProperty("minLength", 1);
        descriptor.addProperty(
                "description",
                "Case-sensitive substring of the annotation descriptor or class name."
        );

        JsonObject target = new JsonObject();
        target.addProperty("type", "string");
        JsonArray values = new JsonArray();
        values.add("any");
        values.add("class");
        values.add("field");
        values.add("method");
        target.add("enum", values);
        target.addProperty("default", "any");

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
        properties.add("descriptor", descriptor);
        properties.add("target", target);
        properties.add("offset", offset);
        properties.add("limit", limit);
        ClassToolSupport.addScopeProperties(properties, true);

        JsonArray required = new JsonArray();
        required.add("descriptor");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private record Hit(String className,
                       String classScope,
                       String dexName,
                       String target,
                       String memberName,
                       String memberDescriptor,
                       String annotation,
                       String annotationType,
                       boolean visible) {
        static Hit of(ClassToolSupport.Entry entry,
                      AnnotationInfo annotation,
                      String target,
                      ClassMember member) {
            return new Hit(
                    entry.className(),
                    entry.scope().value(),
                    entry.dexName(),
                    target,
                    member == null ? null : member.getName(),
                    member == null ? null : member.getDescriptor(),
                    annotation.getDescriptor(),
                    MemberToolSupport.classNameFromDescriptor(annotation.getDescriptor()),
                    annotation.isVisible()
            );
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("class_name", className);
            json.addProperty("class_scope", classScope);
            if (dexName == null) {
                json.add("dex_name", JsonNull.INSTANCE);
            } else {
                json.addProperty("dex_name", dexName);
            }
            json.addProperty("target", target);
            if (memberName == null) {
                json.add("member_name", JsonNull.INSTANCE);
                json.add("member_descriptor", JsonNull.INSTANCE);
            } else {
                json.addProperty("member_name", memberName);
                json.addProperty("member_descriptor", memberDescriptor);
            }
            json.addProperty("annotation", annotation);
            json.addProperty("annotation_type", annotationType);
            json.addProperty("visible", visible);
            return json;
        }
    }
}
