package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Lists and filters declared members of one exact primary class with paging.
 */
public final class ListMembersTool implements McpTool {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "scope", "dex_name", "kind", "name", "descriptor",
            "include_annotations", "offset", "limit"
    );
    private static final Set<String> KINDS = Set.of(
            MemberToolSupport.ANY,
            MemberToolSupport.FIELD,
            MemberToolSupport.METHOD
    );
    private static final Comparator<ClassMember> MEMBER_ORDER = Comparator
            .comparing((ClassMember member) -> member.isField() ? 0 : 1)
            .thenComparing(ClassMember::getName)
            .thenComparing(ClassMember::getDescriptor);
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public ListMembersTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    ListMembersTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "list_members";
    }

    @Override
    public String description() {
        return "List declared fields and methods of one exact primary class with filtering and paging.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        Request request = parseRequest(arguments);
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", request, List.of());
        }

        Workspace workspace = workspaceSupplier.get();
        List<ClassToolSupport.Entry> matches = ClassToolSupport.exact(
                workspace.getPrimaryResource(),
                request.selection(),
                request.className()
        );
        if (matches.isEmpty()) {
            return error("class not found in requested primary scope", request, List.of());
        }
        if (matches.size() > 1) {
            return error("class is ambiguous; specify dex_name", request, matches);
        }

        ClassToolSupport.Entry entry = matches.getFirst();
        ClassInfo classInfo = entry.classInfo();
        List<ClassMember> members = classInfo.fieldAndMethodStream()
                .filter(member -> request.kind().equals(MemberToolSupport.ANY)
                        || (member.isField() == request.kind().equals(MemberToolSupport.FIELD)))
                .filter(member -> request.name() == null
                        || member.getName().contains(request.name()))
                .filter(member -> request.descriptor() == null
                        || member.getDescriptor().contains(request.descriptor()))
                .sorted(MEMBER_ORDER)
                .toList();

        int start = Math.min(request.offset(), members.size());
        int end = Math.min(start + request.limit(), members.size());
        JsonArray page = new JsonArray();
        members.subList(start, end).forEach(member ->
                page.add(MemberToolSupport.toJson(member, request.includeAnnotations())));

        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", classInfo.getName());
        ClassToolSupport.addIdentity(structured, entry);
        structured.addProperty("kind", request.kind());
        structured.add("name_filter", request.name() == null
                ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(request.name()));
        structured.add("descriptor_filter", request.descriptor() == null
                ? JsonNull.INSTANCE
                : new com.google.gson.JsonPrimitive(request.descriptor()));
        structured.add("members", page);
        structured.addProperty("field_count", classInfo.getFields().size());
        structured.addProperty("method_count", classInfo.getMethods().size());
        structured.addProperty("total", members.size());
        structured.addProperty("offset", start);
        structured.addProperty("returned", page.size());
        if (end < members.size()) {
            structured.addProperty("next_offset", end);
        } else {
            structured.add("next_offset", JsonNull.INSTANCE);
        }
        return McpTool.successResult(
                "listed " + members.size() + " matching members",
                structured
        );
    }

    private static JsonObject error(String message,
                                    Request request,
                                    List<ClassToolSupport.Entry> candidates) {
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", request.className());
        structured.addProperty("requested_scope", request.selection().scope().value());
        if (!candidates.isEmpty()) {
            structured.add("candidates", ClassToolSupport.candidates(candidates));
        }
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static Request parseRequest(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(
                arguments,
                false
        );
        String className = ToolArguments.requiredString(arguments, "class_name");
        if (className.length() > JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH) {
            throw new IllegalArgumentException("class_name is too long");
        }
        String kind = ToolArguments.optionalString(arguments, "kind");
        if (kind == null) {
            kind = MemberToolSupport.ANY;
        }
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("kind must be one of any, field, or method");
        }
        String name = bounded(arguments, "name", JvmClassMutationSupport.MAX_MEMBER_NAME_LENGTH);
        String descriptor = bounded(
                arguments,
                "descriptor",
                JvmClassMutationSupport.MAX_DESCRIPTOR_LENGTH
        );
        return new Request(
                className,
                selection,
                kind,
                name,
                descriptor,
                ToolArguments.bool(arguments, "include_annotations", false),
                ToolArguments.integer(arguments, "offset", 0, 0, Integer.MAX_VALUE),
                ToolArguments.integer(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT)
        );
    }

    private static String bounded(JsonObject arguments, String name, int maximum) {
        String value = ToolArguments.optionalString(arguments, name);
        if (value != null && value.length() > maximum) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return value;
    }

    private static JsonObject createInputSchema() {
        JsonObject className = new JsonObject();
        className.addProperty("type", "string");
        className.addProperty("minLength", 1);
        className.addProperty("maxLength", JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH);
        className.addProperty("description", "Exact primary JVM internal or DEX class name.");

        JsonObject kind = new JsonObject();
        kind.addProperty("type", "string");
        JsonArray kindValues = new JsonArray();
        kindValues.add(MemberToolSupport.ANY);
        kindValues.add(MemberToolSupport.FIELD);
        kindValues.add(MemberToolSupport.METHOD);
        kind.add("enum", kindValues);
        kind.addProperty("default", MemberToolSupport.ANY);

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("minLength", 1);
        name.addProperty("description", "Case-sensitive substring match on member name.");

        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("type", "string");
        descriptor.addProperty("minLength", 1);
        descriptor.addProperty("description", "Case-sensitive substring match on descriptor.");

        JsonObject includeAnnotations = new JsonObject();
        includeAnnotations.addProperty("type", "boolean");
        includeAnnotations.addProperty("default", false);

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
        properties.add("class_name", className);
        properties.add("kind", kind);
        properties.add("name", name);
        properties.add("descriptor", descriptor);
        properties.add("include_annotations", includeAnnotations);
        properties.add("offset", offset);
        properties.add("limit", limit);
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

    record Request(String className,
                   ClassToolSupport.Selection selection,
                   String kind,
                   String name,
                   String descriptor,
                   boolean includeAnnotations,
                   int offset,
                   int limit) {
    }
}
