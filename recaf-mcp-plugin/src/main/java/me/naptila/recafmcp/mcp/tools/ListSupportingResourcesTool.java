package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Lists removable supporting resources and internal Recaf resources.
 */
public final class ListSupportingResourcesTool implements McpTool {
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public ListSupportingResourcesTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    ListSupportingResourcesTool(BooleanSupplier hasWorkspace,
                                Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "list_supporting_resources";
    }

    @Override
    public String description() {
        return "List library/supporting resources attached to the open workspace.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        if (!arguments.isEmpty()) {
            throw new IllegalArgumentException(
                    "list_supporting_resources does not accept arguments"
            );
        }
        if (!hasWorkspace.getAsBoolean()) {
            return error();
        }

        Workspace workspace = workspaceSupplier.get();
        List<WorkspaceResource> supporting = workspace.getSupportingResources();
        JsonArray resources = new JsonArray();
        for (int index = 0; index < supporting.size(); index++) {
            resources.add(SupportingResourceSupport.describe(
                    supporting.get(index),
                    index,
                    false
            ));
        }
        JsonArray internal = new JsonArray();
        List<WorkspaceResource> internalResources = workspace.getInternalSupportingResources();
        for (int index = 0; index < internalResources.size(); index++) {
            internal.add(SupportingResourceSupport.describe(
                    internalResources.get(index),
                    index,
                    true
            ));
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("workspace_open", true);
        structured.add("supporting_resources", resources);
        structured.addProperty("supporting_resource_count", supporting.size());
        structured.add("internal_supporting_resources", internal);
        structured.addProperty("internal_supporting_resource_count", internal.size());
        return McpTool.successResult(
                "workspace has " + supporting.size() + " supporting resources",
                structured
        );
    }

    private static JsonObject error() {
        JsonObject structured = new JsonObject();
        structured.addProperty("workspace_open", false);
        structured.addProperty("error", "workspace not open");
        return McpTool.errorResult("workspace not open", structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
