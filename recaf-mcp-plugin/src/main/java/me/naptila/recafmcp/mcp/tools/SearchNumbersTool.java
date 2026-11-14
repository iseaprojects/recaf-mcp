package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.path.AndroidInstructionPathNode;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.JvmInstructionPathNode;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.path.ResourcePathNode;
import software.coley.recaf.services.search.SearchFeedback;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.match.NumberPredicate;
import software.coley.recaf.services.search.match.NumberPredicateProvider;
import software.coley.recaf.services.search.query.NumberQuery;
import software.coley.recaf.services.search.result.NumberResult;
import software.coley.recaf.services.search.result.Result;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Searches numeric literals across the selected primary class scope.
 */
public final class SearchNumbersTool implements McpTool {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;
    private static final int MAX_COLLECTED_RESULTS = 20_000;
    private static final Set<String> MODES = Set.of(
            "equals", "not_equals", "greater_than", "greater_equal",
            "less_than", "less_equal", "range", "any_of"
    );
    private static final Set<String> ARGUMENTS = Set.of(
            "value", "mode", "value2", "values", "scope", "dex_name",
            "filter", "offset", "limit"
    );
    private static final Comparator<NumberHit> HIT_ORDER = Comparator
            .comparing(NumberHit::className)
            .thenComparing(hit -> hit.containingMemberName() == null
                    ? "" : hit.containingMemberName())
            .thenComparing(hit -> hit.instructionIndex() == null
                    ? -1 : hit.instructionIndex())
            .thenComparing(hit -> hit.value().doubleValue());
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final NumberSearchOperation searchOperation;

    public SearchNumbersTool(WorkspaceManager workspaceManager,
                             SearchService searchService,
                             NumberPredicateProvider predicateProvider) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                (workspace, request) -> runSearch(
                        workspace,
                        searchService,
                        predicateProvider,
                        request
                )
        );
    }

    SearchNumbersTool(BooleanSupplier hasWorkspace,
                      Supplier<Workspace> workspaceSupplier,
                      NumberSearchOperation searchOperation) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.searchOperation = Objects.requireNonNull(searchOperation, "searchOperation");
    }

    @Override
    public String name() {
        return "search_numbers";
    }

    @Override
    public String description() {
        return "Search numeric constants and operands with comparison or range predicates.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        Request request = parseRequest(arguments);
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", request);
        }

        Workspace workspace = workspaceSupplier.get();
        SearchOutcome outcome;
        try {
            outcome = searchOperation.run(workspace, request);
        } catch (Exception ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("number search failed: " + exceptionMessage(ex), request);
        }

        List<NumberHit> hits = new ArrayList<>(outcome.hits());
        if (request.filter() != null) {
            hits.removeIf(hit -> !hit.className().contains(request.filter()));
        }
        hits.sort(HIT_ORDER);
        int start = Math.min(request.offset(), hits.size());
        int end = Math.min(start + request.limit(), hits.size());
        JsonArray page = new JsonArray();
        hits.subList(start, end).forEach(hit -> page.add(toJson(hit)));

        JsonObject structured = metadata(request);
        structured.add("hits", page);
        structured.addProperty("total", hits.size());
        structured.addProperty("offset", start);
        structured.addProperty("returned", page.size());
        if (end < hits.size()) {
            structured.addProperty("next_offset", end);
        } else {
            structured.add("next_offset", JsonNull.INSTANCE);
        }
        structured.addProperty("collection_truncated", outcome.collectionTruncated());
        return McpTool.successResult(
                "found " + hits.size() + " numeric matches",
                structured
        );
    }

    static SearchOutcome runSearch(Workspace workspace,
                                   SearchService searchService,
                                   NumberPredicateProvider predicateProvider,
                                   Request request) {
        NumberPredicate predicate = predicate(predicateProvider, request);
        WorkspaceResource primary = workspace.getPrimaryResource();
        Set<ClassInfo> selectedClasses = Collections.newSetFromMap(new IdentityHashMap<>());
        ClassToolSupport.entries(primary, request.selection())
                .forEach(entry -> selectedClasses.add(entry.classInfo()));
        AtomicInteger accepted = new AtomicInteger();
        SearchFeedback feedback = new SearchFeedback() {
            @Override
            public boolean hasRequestedCancellation() {
                return ToolCancellation.requested()
                        || accepted.get() >= MAX_COLLECTED_RESULTS;
            }

            @Override
            public boolean doVisitClass(ClassInfo classInfo) {
                return selectedClasses.contains(classInfo);
            }

            @Override
            public boolean doAcceptResult(Result<?> result) {
                if (!(result instanceof NumberResult)) {
                    return false;
                }
                while (true) {
                    int current = accepted.get();
                    if (current >= MAX_COLLECTED_RESULTS) {
                        return false;
                    }
                    if (accepted.compareAndSet(current, current + 1)) {
                        return true;
                    }
                }
            }
        };

        List<NumberHit> hits = new ArrayList<>();
        for (Result<?> result
                : searchService.search(workspace, List.of(new NumberQuery(predicate)), feedback)) {
            ToolCancellation.check();
            if (!(result instanceof NumberResult numberResult)) {
                continue;
            }
            PathNode<?> path = result.getPath();
            ResourcePathNode resourcePath = findPath(path, ResourcePathNode.class);
            ClassPathNode classPath = findPath(path, ClassPathNode.class);
            if (resourcePath == null
                    || resourcePath.getValue() != primary
                    || classPath == null
                    || !(classPath.getValue() instanceof ClassInfo classInfo)) {
                continue;
            }
            ClassToolSupport.Entry entry = ClassToolSupport.identify(primary, classInfo);
            if (entry == null || !selectedClasses.contains(classInfo)) {
                continue;
            }
            ClassMemberPathNode memberPath = findPath(path, ClassMemberPathNode.class);
            JvmInstructionPathNode jvmInstruction = findPath(
                    path,
                    JvmInstructionPathNode.class
            );
            AndroidInstructionPathNode androidInstruction = findPath(
                    path,
                    AndroidInstructionPathNode.class
            );
            Integer instructionIndex = jvmInstruction != null
                    ? jvmInstruction.getInstructionIndex()
                    : androidInstruction == null
                    ? null
                    : androidInstruction.getInstructionIndex();
            hits.add(new NumberHit(
                    numberResult.getValue(),
                    entry.className(),
                    entry.scope().value(),
                    entry.dexName(),
                    memberPath == null ? null : memberPath.getValue().isField()
                            ? "field" : "method",
                    memberPath == null ? null : memberPath.getValue().getName(),
                    memberPath == null ? null : memberPath.getValue().getDescriptor(),
                    instructionIndex
            ));
        }
        return new SearchOutcome(hits, accepted.get() >= MAX_COLLECTED_RESULTS);
    }

    private static NumberPredicate predicate(NumberPredicateProvider provider,
                                             Request request) {
        return switch (request.mode()) {
            case "equals" -> provider.newEqualsPredicate(request.value());
            case "not_equals" -> provider.newNotEqualsPredicate(request.value());
            case "greater_than" -> provider.newGreaterThanPredicate(request.value());
            case "greater_equal" ->
                    provider.newGreaterThanOrEqualPredicate(request.value());
            case "less_than" -> provider.newLessThanPredicate(request.value());
            case "less_equal" -> provider.newLessThanOrEqualPredicate(request.value());
            case "range" -> provider.newRangePredicate(request.value(), request.value2());
            case "any_of" -> provider.newAnyOfPredicate(request.anyOf());
            default -> throw new IllegalArgumentException("mode has an unsupported value");
        };
    }

    private static Number parseNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("value must be a number");
        }
        String value = raw.trim();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("value must be a number");
            }
        }
    }

    private static Request parseRequest(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(arguments, true);
        String mode = ToolArguments.optionalString(arguments, "mode");
        if (mode == null) {
            mode = "equals";
        }
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("mode has an unsupported value");
        }
        Number value = parseNumber(ToolArguments.requiredString(arguments, "value"));
        Number value2 = null;
        List<Number> anyOf = List.of();
        if (mode.equals("range")) {
            value2 = parseNumber(ToolArguments.requiredString(arguments, "value2"));
        }
        if (mode.equals("any_of")) {
            String values = ToolArguments.requiredString(arguments, "values");
            List<Number> parsed = new ArrayList<>();
            for (String item : values.split(",")) {
                parsed.add(parseNumber(item));
            }
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("values must contain at least one number");
            }
            anyOf = List.copyOf(parsed);
        }
        String filter = ToolArguments.optionalString(arguments, "filter");
        if (filter != null && filter.length() > 500) {
            throw new IllegalArgumentException("filter is too long");
        }
        return new Request(
                value,
                value2,
                anyOf,
                mode,
                filter,
                selection,
                ToolArguments.integer(arguments, "offset", 0, 0, Integer.MAX_VALUE),
                ToolArguments.integer(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT)
        );
    }

    private static JsonObject metadata(Request request) {
        JsonObject json = new JsonObject();
        json.addProperty("mode", request.mode());
        json.add("value", new com.google.gson.JsonPrimitive(request.value()));
        if (request.value2() == null) {
            json.add("value2", JsonNull.INSTANCE);
        } else {
            json.add("value2", new com.google.gson.JsonPrimitive(request.value2()));
        }
        JsonArray anyOf = new JsonArray();
        request.anyOf().forEach(number -> anyOf.add(new com.google.gson.JsonPrimitive(number)));
        json.add("any_of", anyOf);
        if (request.filter() == null) {
            json.add("filter", JsonNull.INSTANCE);
        } else {
            json.addProperty("filter", request.filter());
        }
        json.addProperty("requested_scope", request.selection().scope().value());
        if (request.selection().dexName() == null) {
            json.add("dex_name", JsonNull.INSTANCE);
        } else {
            json.addProperty("dex_name", request.selection().dexName());
        }
        return json;
    }

    private static JsonObject toJson(NumberHit hit) {
        JsonObject json = new JsonObject();
        json.add("value", new com.google.gson.JsonPrimitive(hit.value()));
        json.addProperty("value_type", hit.value() == null
                ? "unknown" : hit.value().getClass().getSimpleName());
        json.addProperty("class_name", hit.className());
        json.addProperty("class_scope", hit.classScope());
        addNullable(json, "dex_name", hit.dexName());
        addNullable(json, "containing_member_kind", hit.containingMemberKind());
        addNullable(json, "containing_member_name", hit.containingMemberName());
        addNullable(
                json,
                "containing_member_descriptor",
                hit.containingMemberDescriptor()
        );
        if (hit.instructionIndex() == null) {
            json.add("instruction_index", JsonNull.INSTANCE);
        } else {
            json.addProperty("instruction_index", hit.instructionIndex());
        }
        return json;
    }

    private static JsonObject error(String message, Request request) {
        JsonObject structured = metadata(request);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static <T extends PathNode<?>> T findPath(PathNode<?> start, Class<T> type) {
        PathNode<?> current = start;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getParent();
        }
        return null;
    }

    private static void addNullable(JsonObject target, String name, String value) {
        if (value == null) {
            target.add(name, JsonNull.INSTANCE);
        } else {
            target.addProperty(name, value);
        }
    }

    private static String exceptionMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private static JsonObject createInputSchema() {
        JsonObject value = new JsonObject();
        value.addProperty("type", "string");
        value.addProperty("minLength", 1);
        value.addProperty("description", "Primary numeric query value, for example 42 or 3.5.");

        JsonObject mode = new JsonObject();
        mode.addProperty("type", "string");
        JsonArray modes = new JsonArray();
        MODES.stream().sorted().forEach(modes::add);
        mode.add("enum", modes);
        mode.addProperty("default", "equals");

        JsonObject value2 = new JsonObject();
        value2.addProperty("type", "string");
        value2.addProperty("minLength", 1);
        value2.addProperty("description", "Upper bound for mode=range.");

        JsonObject values = new JsonObject();
        values.addProperty("type", "string");
        values.addProperty("minLength", 1);
        values.addProperty(
                "description",
                "Comma-separated numbers for mode=any_of, for example 1,2,3."
        );

        JsonObject filter = new JsonObject();
        filter.addProperty("type", "string");
        filter.addProperty("minLength", 1);
        filter.addProperty("description", "Case-sensitive substring filter on class name.");

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
        properties.add("value", value);
        properties.add("mode", mode);
        properties.add("value2", value2);
        properties.add("values", values);
        properties.add("filter", filter);
        properties.add("offset", offset);
        properties.add("limit", limit);
        ClassToolSupport.addScopeProperties(properties, true);

        JsonArray required = new JsonArray();
        required.add("value");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    @FunctionalInterface
    interface NumberSearchOperation {
        SearchOutcome run(Workspace workspace, Request request);
    }

    record Request(Number value,
                   Number value2,
                   List<Number> anyOf,
                   String mode,
                   String filter,
                   ClassToolSupport.Selection selection,
                   int offset,
                   int limit) {
    }

    record NumberHit(Number value,
                     String className,
                     String classScope,
                     String dexName,
                     String containingMemberKind,
                     String containingMemberName,
                     String containingMemberDescriptor,
                     Integer instructionIndex) {
    }

    record SearchOutcome(List<NumberHit> hits, boolean collectionTruncated) {
        SearchOutcome {
            hits = List.copyOf(hits);
        }
    }
}
