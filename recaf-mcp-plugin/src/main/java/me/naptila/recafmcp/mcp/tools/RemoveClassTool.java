package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Removes exactly one class from the primary JVM or Android bundle.
 */
public final class RemoveClassTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "scope", "dex_name"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public RemoveClassTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    RemoveClassTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "remove_class";
    }

    @Override
    public String description() {
        return "Delete one exact JVM or Android/DEX class from the primary resource.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(
                arguments,
                false
        );
        String className = ToolArguments.requiredString(arguments, "class_name");
        if (className.length() > JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH) {
            throw new IllegalArgumentException("class_name is too long");
        }
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", className, selection, List.of());
        }

        Workspace workspace = workspaceSupplier.get();
        List<ClassToolSupport.Entry> matches = ClassToolSupport.exact(
                workspace.getPrimaryResource(),
                selection,
                className
        );
        if (matches.isEmpty()) {
            return error(
                    "class not found in requested primary scope",
                    className,
                    selection,
                    List.of()
            );
        }
        if (matches.size() > 1) {
            return error("class is ambiguous; specify dex_name", className, selection, matches);
        }

        ClassToolSupport.Entry entry = matches.getFirst();
        int sizeBefore;
        int sizeAfter;
        try {
            ToolCancellation.check();
            if (entry.scope() == ClassToolSupport.Scope.ANDROID) {
                AndroidClassBundle bundle = workspace.getPrimaryResource()
                        .getAndroidClassBundles()
                        .get(entry.dexName());
                sizeBefore = bundle.size();
                bundle.remove(className);
                sizeAfter = bundle.size();
            } else {
                JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
                sizeBefore = bundle.size();
                bundle.remove(className);
                sizeAfter = bundle.size();
            }
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error(
                    "class removal failed: " + ex.getMessage(),
                    className,
                    selection,
                    List.of()
            );
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
        ClassToolSupport.addIdentity(structured, entry);
        structured.addProperty("removed", sizeAfter < sizeBefore);
        structured.addProperty("bundle_size_before", sizeBefore);
        structured.addProperty("bundle_size_after", sizeAfter);
        structured.addProperty(
                "present_after_remove",
                entry.scope() == ClassToolSupport.Scope.ANDROID
                        ? workspace.getPrimaryResource()
                        .getAndroidClassBundles()
                        .get(entry.dexName())
                        .containsKey(className)
                        : workspace.getPrimaryResource()
                        .getJvmClassBundle()
                        .containsKey(className)
        );
        return McpTool.successResult("removed class " + className, structured);
    }

    private static JsonObject error(String message,
                                    String className,
                                    ClassToolSupport.Selection selection,
                                    List<ClassToolSupport.Entry> candidates) {
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
        structured.addProperty("requested_scope", selection.scope().value());
        if (selection.dexName() == null) {
            structured.add("requested_dex_name",
                    com.google.gson.JsonNull.INSTANCE);
        } else {
            structured.addProperty("requested_dex_name", selection.dexName());
        }
        if (!candidates.isEmpty()) {
            structured.add("candidates", ClassToolSupport.candidates(candidates));
        }
        structured.addProperty("removed", false);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject className = new JsonObject();
        className.addProperty("type", "string");
        className.addProperty("minLength", 1);
        className.addProperty("maxLength", JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH);
        className.addProperty("description", "Exact primary JVM internal or DEX class name.");

        JsonObject properties = new JsonObject();
        properties.add("class_name", className);
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
}
