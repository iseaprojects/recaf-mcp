package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Removes one supporting resource identified by index, path, or name.
 */
public final class RemoveSupportingResourceTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of("index", "path", "name");
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public RemoveSupportingResourceTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    RemoveSupportingResourceTool(BooleanSupplier hasWorkspace,
                                 Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "remove_supporting_resource";
    }

    @Override
    public String description() {
        return "Detach one supporting resource by index, absolute path, or file name.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        int index = ToolArguments.integer(arguments, "index", -1, 0, Integer.MAX_VALUE);
        String pathValue = ToolArguments.optionalString(arguments, "path");
        String name = ToolArguments.optionalString(arguments, "name");
        int provided = (index >= 0 ? 1 : 0)
                + (pathValue != null ? 1 : 0)
                + (name != null ? 1 : 0);
        if (provided != 1) {
            throw new IllegalArgumentException("exactly one of index, path, or name is required");
        }
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", index, pathValue, name);
        }

        Workspace workspace = workspaceSupplier.get();
        Path path = pathValue == null ? null : Path.of(pathValue);
        WorkspaceResource resource = SupportingResourceSupport.match(
                workspace,
                path,
                name,
                index
        );
        if (resource == null) {
            return error(
                    "supporting resource not found",
                    index,
                    pathValue,
                    name
            );
        }

        JsonObject removed = SupportingResourceSupport.describe(resource, index, false);
        boolean success;
        try {
            success = workspace.removeSupportingResource(resource);
        } catch (RuntimeException ex) {
            return error(
                    "failed to remove supporting resource: " + ex.getMessage(),
                    index,
                    pathValue,
                    name
            );
        }
        if (!success) {
            return error(
                    "Recaf did not remove the supporting resource",
                    index,
                    pathValue,
                    name
            );
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("removed", true);
        structured.add("resource", removed);
        structured.addProperty(
                "supporting_resource_count",
                workspace.getSupportingResources().size()
        );
        JsonArray names = new JsonArray();
        workspace.getSupportingResources().forEach(item -> names.add(
                Objects.requireNonNullElse(
                        SupportingResourceSupport.displayName(item),
                        "<resource>"
                )
        ));
        structured.add("remaining_supporting_resources", names);
        return McpTool.successResult("detached supporting resource", structured);
    }

    private static JsonObject error(String message,
                                    int index,
                                    String path,
                                    String name) {
        JsonObject structured = new JsonObject();
        if (index >= 0) {
            structured.addProperty("index", index);
        }
        if (path != null) {
            structured.addProperty("path", path);
        }
        if (name != null) {
            structured.addProperty("name", name);
        }
        structured.addProperty("removed", false);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject index = new JsonObject();
        index.addProperty("type", "integer");
        index.addProperty("minimum", 0);
        index.addProperty("description", "Zero-based index from list_supporting_resources.");

        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("minLength", 1);
        path.addProperty("description", "Absolute path of the attached resource.");

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("minLength", 1);
        name.addProperty("description", "Exact file name, for example library.jar.");

        JsonObject properties = new JsonObject();
        properties.add("index", index);
        properties.add("path", path);
        properties.add("name", name);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("description", "Provide exactly one selector.");
        schema.add("properties", properties);
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
