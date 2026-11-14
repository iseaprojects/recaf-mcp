package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Writes one primary class or resource entry to an absolute filesystem path.
 */
public final class ExtractEntryTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of(
            "path", "output_path", "overwrite", "scope", "dex_name"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public ExtractEntryTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    ExtractEntryTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "extract_entry";
    }

    @Override
    public String description() {
        return "Write one primary class or resource entry's raw bytes to an absolute path.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String entryPath = ToolArguments.requiredString(arguments, "path");
        String requestedOutput = ToolArguments.requiredString(arguments, "output_path");
        boolean overwrite = ToolArguments.bool(arguments, "overwrite", false);
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(arguments, true);

        Path outputPath;
        try {
            outputPath = Path.of(requestedOutput);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("output_path is not a valid filesystem path", ex);
        }
        if (!outputPath.isAbsolute()) {
            throw new IllegalArgumentException("output_path must be absolute");
        }
        if (outputPath.getFileName() == null) {
            throw new IllegalArgumentException("output_path must name a file");
        }
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", entryPath, requestedOutput);
        }

        Workspace workspace = workspaceSupplier.get();
        List<ClassToolSupport.Entry> matches = ClassToolSupport.exact(
                workspace.getPrimaryResource(),
                selection,
                entryPath
        );
        if (matches.size() > 1) {
            JsonObject structured = errorMetadata(entryPath, requestedOutput);
            structured.add("candidates", ClassToolSupport.candidates(matches));
            structured.addProperty("error", "class name is ambiguous; specify dex_name");
            return McpTool.errorResult(
                    "class name is ambiguous; specify dex_name",
                    structured
            );
        }

        String kind;
        String format;
        byte[] content;
        ClassToolSupport.Entry classEntry = null;
        try {
            ToolCancellation.check();
            if (matches.size() == 1) {
                classEntry = matches.getFirst();
                if (classEntry.scope() == ClassToolSupport.Scope.JVM) {
                    JvmClassInfo classInfo = classEntry.classInfo().asJvmClass();
                    kind = "jvm_class";
                    format = "classfile";
                    content = classInfo.getBytecode();
                } else {
                    AndroidClassInfo classInfo = classEntry.classInfo().asAndroidClass();
                    var bundle = workspace.getPrimaryResource()
                            .getAndroidClassBundles()
                            .get(classEntry.dexName());
                    if (bundle == null) {
                        return error("DEX bundle is unavailable: " + classEntry.dexName(),
                                entryPath, requestedOutput);
                    }
                    kind = "android_class";
                    format = "single_class_dex";
                    content = GetClassBytecodeTool.singleClassDex(
                            classInfo,
                            bundle.getVersion()
                    );
                }
            } else {
                FileInfo fileInfo = workspace.getPrimaryResource()
                        .getFileBundle()
                        .get(entryPath);
                if (fileInfo == null || fileInfo.isClass()) {
                    return error("entry not found in the requested primary scope",
                            entryPath, requestedOutput);
                }
                kind = "file";
                format = fileInfo.getFileExtension() == null
                        ? "binary"
                        : fileInfo.getFileExtension();
                content = fileInfo.getRawContent();
            }
        } catch (IOException ex) {
            return error("entry encoding failed: " + ex.getMessage(),
                    entryPath, requestedOutput);
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error("entry extraction failed: " + ex.getMessage(),
                    entryPath, requestedOutput);
        }

        try {
            ToolCancellation.check();
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (overwrite) {
                Files.write(
                        outputPath,
                        content,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
            } else {
                Files.write(
                        outputPath,
                        content,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                );
            }
        } catch (java.nio.file.FileAlreadyExistsException ex) {
            return error("output_path already exists; pass overwrite=true",
                    entryPath, requestedOutput);
        } catch (IOException ex) {
            return error("failed to write output_path: " + ex.getMessage(),
                    entryPath, requestedOutput);
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("path", entryPath);
        structured.addProperty("output_path", outputPath.toString());
        structured.addProperty("kind", kind);
        structured.addProperty("format", format);
        structured.addProperty("size_bytes", content.length);
        structured.addProperty("sha256", FileToolSupport.sha256(content));
        structured.addProperty("overwritten", overwrite && Files.exists(outputPath));
        structured.addProperty("requested_scope", selection.scope().value());
        if (classEntry == null) {
            structured.add("class_scope", JsonNull.INSTANCE);
            structured.add("dex_name", JsonNull.INSTANCE);
        } else {
            ClassToolSupport.addHitIdentity(structured, classEntry);
        }
        return McpTool.successResult(
                "extracted " + content.length + " bytes to " + outputPath,
                structured
        );
    }

    private static JsonObject errorMetadata(String path, String outputPath) {
        JsonObject structured = new JsonObject();
        structured.addProperty("path", path);
        structured.addProperty("output_path", outputPath);
        structured.addProperty("extracted", false);
        return structured;
    }

    private static JsonObject error(String message, String path, String outputPath) {
        JsonObject structured = errorMetadata(path, outputPath);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("minLength", 1);
        path.addProperty(
                "description",
                "Primary JVM/Android internal class name or resource entry path."
        );

        JsonObject outputPath = new JsonObject();
        outputPath.addProperty("type", "string");
        outputPath.addProperty("minLength", 1);
        outputPath.addProperty("description", "Absolute destination file path.");

        JsonObject overwrite = new JsonObject();
        overwrite.addProperty("type", "boolean");
        overwrite.addProperty("default", false);
        overwrite.addProperty("description", "Replace an existing destination file.");

        JsonObject properties = new JsonObject();
        properties.add("path", path);
        properties.add("output_path", outputPath);
        properties.add("overwrite", overwrite);
        ClassToolSupport.addScopeProperties(properties, true);

        JsonArray required = new JsonArray();
        required.add("path");
        required.add("output_path");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
