package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.builder.FileInfoBuilder;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.FileBundle;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Adds or replaces one non-class file inside the primary resource.
 */
public final class WriteFileTool implements McpTool {
    private static final int MAX_RAW_BYTES = 16 * 1024 * 1024;
    private static final Set<String> ARGUMENTS = Set.of(
            "path", "content", "encoding", "overwrite"
    );
    private static final Set<String> ENCODINGS = Set.of("text", "base64");
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public WriteFileTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    WriteFileTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Add or replace one non-class file in the primary resource with text or base64 content.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String path = normalizePath(ToolArguments.requiredString(arguments, "path"));
        String content = ToolArguments.requiredString(arguments, "content");
        String encoding = ToolArguments.optionalString(arguments, "encoding");
        if (encoding == null) {
            encoding = "text";
        }
        if (!ENCODINGS.contains(encoding)) {
            throw new IllegalArgumentException("encoding must be text or base64");
        }
        boolean overwrite = ToolArguments.bool(arguments, "overwrite", false);
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", path);
        }

        byte[] bytes;
        try {
            bytes = encoding.equals("base64")
                    ? Base64.getDecoder().decode(content)
                    : content.getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("content is not valid base64");
        }
        if (bytes.length > MAX_RAW_BYTES) {
            throw new IllegalArgumentException("content exceeds the 16 MiB limit");
        }

        Workspace workspace = workspaceSupplier.get();
        FileBundle bundle = workspace.getPrimaryResource().getFileBundle();
        FileInfo existing = bundle.get(path);
        if (existing != null && !overwrite) {
            return error("file already exists; set overwrite=true to replace it", path);
        }

        FileInfo replacement = new FileInfoBuilder<>()
                .withName(path)
                .withRawContent(bytes)
                .build();
        try {
            ToolCancellation.check();
            bundle.put(replacement);
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("file write failed: " + ex.getMessage(), path);
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("path", path);
        structured.addProperty("created", existing == null);
        structured.addProperty("replaced", existing != null);
        if (existing == null) {
            structured.add("previous_size_bytes", JsonNull.INSTANCE);
            structured.add("previous_sha256", JsonNull.INSTANCE);
        } else {
            structured.addProperty(
                    "previous_size_bytes",
                    existing.getRawContent().length
            );
            structured.addProperty(
                    "previous_sha256",
                    FileToolSupport.sha256(existing.getRawContent())
            );
        }
        structured.addProperty("size_bytes", bytes.length);
        structured.addProperty("sha256", FileToolSupport.sha256(bytes));
        structured.addProperty("encoding", encoding);
        structured.addProperty("present_after_write", bundle.get(path) != null);
        structured.addProperty("file_count", bundle.size());
        return McpTool.successResult(
                (existing == null ? "added" : "replaced") + " file " + path,
                structured
        );
    }

    private static String normalizePath(String value) {
        String path = value.replace('\\', '/').trim();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        if (path.endsWith(".class")) {
            throw new IllegalArgumentException(
                    "class entries must be edited through patch_class or remove_class"
            );
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("path must be a normalized relative path");
            }
        }
        return path;
    }

    private static JsonObject error(String message, String path) {
        JsonObject structured = new JsonObject();
        structured.addProperty("path", path);
        structured.addProperty("created", false);
        structured.addProperty("replaced", false);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("minLength", 1);
        path.addProperty("description", "Relative slash-separated path inside the primary resource.");

        JsonObject content = new JsonObject();
        content.addProperty("type", "string");
        content.addProperty("description", "UTF-8 text or base64-encoded bytes.");

        JsonObject encoding = new JsonObject();
        encoding.addProperty("type", "string");
        JsonArray values = new JsonArray();
        values.add("text");
        values.add("base64");
        encoding.add("enum", values);
        encoding.addProperty("default", "text");

        JsonObject overwrite = new JsonObject();
        overwrite.addProperty("type", "boolean");
        overwrite.addProperty("default", false);
        overwrite.addProperty("description", "Replace an existing file with the same path.");

        JsonObject properties = new JsonObject();
        properties.add("path", path);
        properties.add("content", content);
        properties.add("encoding", encoding);
        properties.add("overwrite", overwrite);

        JsonArray required = new JsonArray();
        required.add("path");
        required.add("content");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
