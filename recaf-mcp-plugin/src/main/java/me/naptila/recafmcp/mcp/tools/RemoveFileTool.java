package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.FileBundle;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Removes one non-class file from the primary resource file bundle.
 */
public final class RemoveFileTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of("path");
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public RemoveFileTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    RemoveFileTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "remove_file";
    }

    @Override
    public String description() {
        return "Delete one non-class file from the primary resource.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String path = ToolArguments.requiredString(arguments, "path");
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", path);
        }

        Workspace workspace = workspaceSupplier.get();
        FileBundle bundle = workspace.getPrimaryResource().getFileBundle();
        FileInfo existing = bundle.get(path);
        if (existing == null) {
            return error("file not found in primary resource", path);
        }
        if (existing.isClass()) {
            return error("path refers to a class; use remove_class instead", path);
        }

        try {
            ToolCancellation.check();
            bundle.remove(path);
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("file removal failed: " + ex.getMessage(), path);
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("path", path);
        structured.addProperty("removed", true);
        structured.addProperty("removed_size_bytes", existing.getRawContent().length);
        structured.addProperty("removed_sha256", FileToolSupport.sha256(
                existing.getRawContent()
        ));
        structured.addProperty("present_after_remove", bundle.get(path) != null);
        structured.addProperty("file_count", bundle.size());
        return McpTool.successResult("removed file " + path, structured);
    }

    private static JsonObject error(String message, String path) {
        JsonObject structured = new JsonObject();
        structured.addProperty("path", path);
        structured.addProperty("removed", false);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("minLength", 1);
        path.addProperty("description", "Exact slash-separated path in the primary file bundle.");

        JsonObject properties = new JsonObject();
        properties.add("path", path);

        JsonArray required = new JsonArray();
        required.add("path");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
