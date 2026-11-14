package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.inheritance.InheritanceVertex;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Returns direct and transitive inheritance relationships for one exact class.
 */
public final class GetInheritanceTool implements McpTool {
    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 5_000;
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "scope", "dex_name", "limit"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final Function<Workspace, InheritanceGraph> graphProvider;

    public GetInheritanceTool(WorkspaceManager workspaceManager,
                              InheritanceGraphService inheritanceGraphService) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                inheritanceGraphService::getOrCreateInheritanceGraph
        );
    }

    GetInheritanceTool(BooleanSupplier hasWorkspace,
                       Supplier<Workspace> workspaceSupplier,
                       Function<Workspace, InheritanceGraph> graphProvider) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.graphProvider = Objects.requireNonNull(graphProvider, "graphProvider");
    }

    @Override
    public String name() {
        return "get_inheritance";
    }

    @Override
    public String description() {
        return "Read direct and transitive parents, children, and assignable supertypes.";
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
        InheritanceGraph graph = graphProvider.apply(workspace);
        InheritanceVertex vertex = graph.getVertex(request.className());
        if (vertex == null) {
            return error(
                    "inheritance graph has no vertex for the requested class",
                    request,
                    List.of()
            );
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", request.className());
        ClassToolSupport.addIdentity(structured, entry);
        structured.addProperty("library_vertex", vertex.isLibraryVertex());
        structured.addProperty("java_lang_object", vertex.isJavaLangObject());
        structured.addProperty("module", vertex.isModule());
        addNames(structured, "direct_parents", vertex.getParents(), request.limit());
        addNames(structured, "direct_children", vertex.getChildren(), request.limit());
        addNames(structured, "all_parents", vertex.getAllParents(), request.limit());
        addNames(structured, "all_children", vertex.getAllChildren(), request.limit());
        JsonArray supertypes = new JsonArray();
        java.util.Set<String> assignable = new TreeSet<>();
        assignable.add(vertex.getName());
        vertex.getAllParents().forEach(parent -> assignable.add(parent.getName()));
        assignable.stream().limit(request.limit()).forEach(supertypes::add);
        structured.add("assignable_supertypes", supertypes);
        structured.addProperty(
                "assignable_supertypes_count",
                assignable.size()
        );
        structured.addProperty(
                "assignable_supertypes_truncated",
                assignable.size() > request.limit()
        );
        return McpTool.successResult(
                "inheritance relationships for " + request.className(),
                structured
        );
    }

    private static void addNames(JsonObject target,
                                 String name,
                                 Set<InheritanceVertex> vertices,
                                 int limit) {
        List<String> names = vertices.stream()
                .map(InheritanceVertex::getName)
                .distinct()
                .sorted()
                .toList();        JsonArray array = new JsonArray();
        names.stream().limit(limit).forEach(array::add);
        target.add(name, array);
        target.addProperty(name + "_count", names.size());
        target.addProperty(name + "_truncated", names.size() > limit);
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
        return new Request(
                className,
                selection,
                ToolArguments.integer(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT)
        );
    }

    private static JsonObject createInputSchema() {
        JsonObject className = new JsonObject();
        className.addProperty("type", "string");
        className.addProperty("minLength", 1);
        className.addProperty("maxLength", JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH);
        className.addProperty("description", "Exact primary JVM internal or DEX class name.");

        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("minimum", 1);
        limit.addProperty("maximum", MAX_LIMIT);
        limit.addProperty("default", DEFAULT_LIMIT);
        limit.addProperty("description", "Maximum entries per relationship list.");

        JsonObject properties = new JsonObject();
        properties.add("class_name", className);
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
                   int limit) {
    }
}
