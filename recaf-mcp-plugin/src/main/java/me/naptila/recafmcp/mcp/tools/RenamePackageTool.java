package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.MappingsAdapter;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Renames a JVM package prefix and rewrites all primary-resource references.
 */
public final class RenamePackageTool implements McpTool {
    private static final int MAX_LISTED_CLASSES = 1_000;
    private static final Set<String> ARGUMENTS = Set.of("old_package", "new_package");
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final MappingApplierService mappingApplierService;

    public RenamePackageTool(WorkspaceManager workspaceManager,
                             MappingApplierService mappingApplierService) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                mappingApplierService
        );
    }

    RenamePackageTool(BooleanSupplier hasWorkspace,
                      Supplier<Workspace> workspaceSupplier,
                      MappingApplierService mappingApplierService) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.mappingApplierService = Objects.requireNonNull(
                mappingApplierService,
                "mappingApplierService"
        );
    }

    @Override
    public String name() {
        return "rename_package";
    }

    @Override
    public String description() {
        return "Rename a JVM package prefix and rewrite references in the primary resource.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String oldPackage = normalizePackage(ToolArguments.requiredString(
                arguments,
                "old_package"
        ));
        String newPackage = normalizePackage(ToolArguments.requiredString(
                arguments,
                "new_package"
        ));
        if (oldPackage.equals(newPackage)) {
            throw new IllegalArgumentException("new_package must differ from old_package");
        }
        if (oldPackage.startsWith(newPackage + "/")) {
            throw new IllegalArgumentException(
                    "new_package must not be a parent of old_package"
            );
        }
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", oldPackage, newPackage);
        }

        Workspace workspace = workspaceSupplier.get();
        String prefix = oldPackage + "/";
        Map<String, String> renames = new TreeMap<>();
        for (String className : workspace.getPrimaryResource().getJvmClassBundle().keySet()) {
            if (className.startsWith(prefix)) {
                renames.put(className, newPackage + "/" + className.substring(prefix.length()));
            }
        }
        if (renames.isEmpty()) {
            return error(
                    "no primary JVM classes found under the requested package",
                    oldPackage,
                    newPackage
            );
        }
        Set<String> existing = workspace.getPrimaryResource().getJvmClassBundle().keySet();
        for (Map.Entry<String, String> rename : renames.entrySet()) {
            if (existing.contains(rename.getValue())
                    && !renames.containsKey(rename.getValue())) {
                return error(
                        "rename target already exists: " + rename.getValue(),
                        oldPackage,
                        newPackage
                );
            }
        }

        MappingsAdapter mappings = new MappingsAdapter(true, true);
        mappings.enableClassLookup(workspace);
        renames.forEach(mappings::addClass);

        MappingMutationSupport.Summary summary;
        try {
            ToolCancellation.check();
            MappingResults results = mappingApplierService
                    .inWorkspace(workspace)
                    .applyToPrimaryResource(mappings);
            summary = MappingMutationSupport.summarize(workspace, results);
            ToolCancellation.check();
            results.apply();
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("package rename failed: " + ex.getMessage(), oldPackage, newPackage);
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("old_package", oldPackage);
        structured.addProperty("new_package", newPackage);
        structured.addProperty("renamed_class_count", renames.size());
        JsonArray classes = new JsonArray();
        List<Map.Entry<String, String>> entries = new ArrayList<>(renames.entrySet());
        entries.stream().limit(MAX_LISTED_CLASSES).forEach(entry -> {
            JsonObject rename = new JsonObject();
            rename.addProperty("old_name", entry.getKey());
            rename.addProperty("new_name", entry.getValue());
            classes.add(rename);
        });
        structured.add("renamed_classes", classes);
        structured.addProperty("renamed_classes_truncated", entries.size() > MAX_LISTED_CLASSES);
        MappingMutationSupport.addSummary(structured, summary);
        MappingMutationSupport.addPersistenceMetadata(structured, workspace, true);
        return McpTool.successResult(
                "renamed package " + oldPackage + " to " + newPackage,
                structured
        );
    }

    private static String normalizePackage(String value) {
        String name = value.replace('.', '/').replace('\\', '/').trim();
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        while (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("package names must not be empty");
        }
        for (String segment : name.split("/")) {
            if (!segment.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                throw new IllegalArgumentException(
                        "package segments must be valid Java identifiers"
                );
            }
        }
        return name;
    }

    private static JsonObject error(String message,
                                    String oldPackage,
                                    String newPackage) {
        JsonObject structured = new JsonObject();
        structured.addProperty("old_package", oldPackage);
        structured.addProperty("new_package", newPackage);
        structured.addProperty("renamed_class_count", 0);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject oldPackage = new JsonObject();
        oldPackage.addProperty("type", "string");
        oldPackage.addProperty("minLength", 1);
        oldPackage.addProperty(
                "description",
                "Existing package in internal or dotted form, for example com/example or com.example."
        );

        JsonObject newPackage = new JsonObject();
        newPackage.addProperty("type", "string");
        newPackage.addProperty("minLength", 1);
        newPackage.addProperty("description", "Replacement package name.");

        JsonObject properties = new JsonObject();
        properties.add("old_package", oldPackage);
        properties.add("new_package", newPackage);

        JsonArray required = new JsonArray();
        required.add("old_package");
        required.add("new_package");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
