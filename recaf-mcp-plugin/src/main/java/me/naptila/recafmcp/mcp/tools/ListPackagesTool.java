package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Aggregates primary classes into their package namespaces.
 */
public final class ListPackagesTool implements McpTool {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 1_000;
    private static final int DEFAULT_DEPTH = 1;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_SAMPLES = 5;
    private static final Set<String> ARGUMENTS = Set.of(
            "scope", "dex_name", "filter", "depth", "include_default_package",
            "offset", "limit"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public ListPackagesTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    ListPackagesTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "list_packages";
    }

    @Override
    public String description() {
        return "Group primary classes into packages with recursive and direct class counts.";
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
        if (filter != null && filter.length() > 500) {
            throw new IllegalArgumentException("filter is too long");
        }
        int depth = ToolArguments.integer(
                arguments,
                "depth",
                DEFAULT_DEPTH,
                0,
                MAX_DEPTH
        );
        boolean includeDefault = ToolArguments.bool(
                arguments,
                "include_default_package",
                true
        );
        int offset = ToolArguments.integer(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = ToolArguments.integer(
                arguments,
                "limit",
                DEFAULT_LIMIT,
                1,
                MAX_LIMIT
        );
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", selection, filter);
        }

        Workspace workspace = workspaceSupplier.get();
        Map<String, MutablePackage> packages = new LinkedHashMap<>();
        int classes = 0;
        for (ClassToolSupport.Entry entry
                : ClassToolSupport.entries(workspace.getPrimaryResource(), selection)) {
            ToolCancellation.check();
            classes++;
            String packageName = packageOf(entry.className(), depth);
            if (packageName == null && !includeDefault) {
                continue;
            }
            packages.computeIfAbsent(
                    packageName == null ? "" : packageName,
                    MutablePackage::new
            ).record(entry);
        }

        List<MutablePackage> ordered = new ArrayList<>(packages.values());
        if (filter != null) {
            ordered.removeIf(item -> !item.name.contains(filter));
        }
        ordered.sort(Comparator.comparing(MutablePackage::name));
        int start = Math.min(offset, ordered.size());
        int end = Math.min(start + limit, ordered.size());
        JsonArray page = new JsonArray();
        for (MutablePackage item : ordered.subList(start, end)) {
            page.add(item.toJson());
        }

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
        structured.addProperty("depth", depth);
        structured.addProperty("include_default_package", includeDefault);
        structured.addProperty("classes_scanned", classes);
        structured.add("packages", page);
        structured.addProperty("total_packages", ordered.size());
        structured.addProperty("offset", start);
        structured.addProperty("returned", page.size());
        if (end < ordered.size()) {
            structured.addProperty("next_offset", end);
        } else {
            structured.add("next_offset", JsonNull.INSTANCE);
        }
        return McpTool.successResult(
                "listed " + page.size() + " of " + ordered.size() + " packages",
                structured
        );
    }

    static String packageOf(String className, int depth) {
        int index = className.lastIndexOf('/');
        if (index < 0) {
            return null;
        }
        String packageName = className.substring(0, index);
        if (depth <= 0) {
            return packageName;
        }
        String[] segments = packageName.split("/");
        int keep = Math.min(depth, segments.length);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < keep; i++) {
            if (i > 0) {
                builder.append('/');
            }
            builder.append(segments[i]);
        }
        return builder.toString();
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
        filter.addProperty("description", "Case-sensitive substring filter on the package name.");

        JsonObject depth = new JsonObject();
        depth.addProperty("type", "integer");
        depth.addProperty("minimum", 0);
        depth.addProperty("maximum", MAX_DEPTH);
        depth.addProperty("default", DEFAULT_DEPTH);
        depth.addProperty(
                "description",
                "Package segments to keep; 0 keeps the full package name."
        );

        JsonObject includeDefault = new JsonObject();
        includeDefault.addProperty("type", "boolean");
        includeDefault.addProperty("default", true);
        includeDefault.addProperty(
                "description",
                "Include classes that live directly in the default package."
        );

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
        properties.add("depth", depth);
        properties.add("include_default_package", includeDefault);
        properties.add("offset", offset);
        properties.add("limit", limit);
        ClassToolSupport.addScopeProperties(properties, true);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static final class MutablePackage {
        private final String name;
        private int classCount;
        private final Map<String, Integer> byScope = new TreeMap<>();
        private final Map<String, Integer> byDex = new TreeMap<>();
        private final JsonArray samples = new JsonArray();

        private MutablePackage(String name) {
            this.name = name;
        }

        String name() {
            return name;
        }

        void record(ClassToolSupport.Entry entry) {
            classCount++;
            byScope.merge(entry.scope().value(), 1, Integer::sum);
            if (entry.dexName() != null) {
                byDex.merge(entry.dexName(), 1, Integer::sum);
            }
            if (samples.size() < MAX_SAMPLES) {
                samples.add(entry.className());
            }
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("package", name);
            json.addProperty("display_name", name.isEmpty() ? "<default>" : name.replace('/', '.'));
            json.addProperty("class_count", classCount);
            JsonObject scopes = new JsonObject();
            byScope.forEach(scopes::addProperty);
            json.add("class_count_by_scope", scopes);
            JsonObject dexes = new JsonObject();
            byDex.forEach(dexes::addProperty);
            json.add("class_count_by_dex", dexes);
            json.add("sample_classes", samples);
            return json;
        }
    }
}
