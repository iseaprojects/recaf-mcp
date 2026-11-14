package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.properties.builtin.InputFilePathProperty;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Imports a jar, class directory, or archive as a supporting library resource.
 */
public final class AddSupportingResourceTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of("path");
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;
    private final ResourceImporter resourceImporter;

    public AddSupportingResourceTool(WorkspaceManager workspaceManager,
                                     ResourceImporter resourceImporter) {
        this(
                workspaceManager::hasCurrentWorkspace,
                workspaceManager::getCurrent,
                resourceImporter
        );
    }

    AddSupportingResourceTool(BooleanSupplier hasWorkspace,
                              Supplier<Workspace> workspaceSupplier,
                              ResourceImporter resourceImporter) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.resourceImporter = Objects.requireNonNull(resourceImporter, "resourceImporter");
    }

    @Override
    public String name() {
        return "add_supporting_resource";
    }

    @Override
    public String description() {
        return "Attach a jar, class directory, or archive as a library/supporting resource.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String input = ToolArguments.requiredString(arguments, "path");
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", input);
        }

        Path path;
        try {
            path = Path.of(input).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("path is not a valid filesystem path");
        }
        if (!Files.exists(path)) {
            return error("path does not exist", input);
        }
        if (!Files.isReadable(path)) {
            return error("path is not readable", input);
        }
        Path canonical = SupportingResourceSupport.canonicalize(path);

        Workspace workspace = workspaceSupplier.get();
        if (isPrimaryInput(workspace, canonical)) {
            return error("path is the current primary workspace input", input);
        }
        if (SupportingResourceSupport.containsPath(workspace, canonical)) {
            JsonObject structured = new JsonObject();
            structured.addProperty("path", canonical.toString());
            structured.addProperty("added", false);
            structured.addProperty(
                    "supporting_resource_count",
                    workspace.getSupportingResources().size()
            );
            structured.addProperty("already_present", true);
            return McpTool.successResult("supporting resource already present", structured);
        }

        WorkspaceResource resource;
        try {
            ToolCancellation.check();
            resource = resourceImporter.importResource(canonical);
            ToolCancellation.check();
        } catch (IOException | RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("failed to import supporting resource: " + ex.getMessage(), input);
        }
        if (resource == null) {
            return error("resource importer returned no resource", input);
        }
        try {
            workspace.addSupportingResource(resource);
        } catch (RuntimeException ex) {
            resource.close();
            return error("failed to attach supporting resource: " + ex.getMessage(), input);
        }
        ToolCancellation.check();

        JsonObject structured = new JsonObject();
        structured.addProperty("added", true);
        structured.addProperty("already_present", false);
        structured.add("resource", SupportingResourceSupport.describe(
                resource,
                workspace.getSupportingResources().indexOf(resource),
                false
        ));
        structured.addProperty(
                "supporting_resource_count",
                workspace.getSupportingResources().size()
        );
        JsonArray names = new JsonArray();
        workspace.getSupportingResources()
                .forEach(item -> names.add(
                        Objects.requireNonNullElse(
                                SupportingResourceSupport.displayName(item),
                                "<resource>"
                        )
                ));
        structured.add("supporting_resource_names", names);
        return McpTool.successResult(
                "attached supporting resource " + (SupportingResourceSupport.displayName(resource)),
                structured
        );
    }

    private static boolean isPrimaryInput(Workspace workspace, Path path) {
        if (!(workspace.getPrimaryResource() instanceof WorkspaceFileResource fileResource)) {
            return false;
        }
        Path primary = InputFilePathProperty.get(fileResource.getFileInfo());
        return primary != null
                && path.equals(SupportingResourceSupport.canonicalize(primary));
    }

    private static JsonObject error(String message, String path) {
        JsonObject structured = new JsonObject();
        structured.addProperty("path", path);
        structured.addProperty("added", false);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("minLength", 1);
        path.addProperty("description", "Absolute path to a jar, class directory, or archive.");

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
