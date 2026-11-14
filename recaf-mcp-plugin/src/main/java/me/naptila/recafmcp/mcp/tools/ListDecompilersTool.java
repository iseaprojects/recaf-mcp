package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import software.coley.recaf.services.decompile.AndroidDecompiler;
import software.coley.recaf.services.decompile.DecompilerManager;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Lists the installed JVM and Android decompilers with their defaults.
 */
public final class ListDecompilersTool implements McpTool {
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final DecompilerManager decompilerManager;

    public ListDecompilersTool(DecompilerManager decompilerManager) {
        this.decompilerManager = Objects.requireNonNull(
                decompilerManager,
                "decompilerManager"
        );
    }

    @Override
    public String name() {
        return "list_decompilers";
    }

    @Override
    public String description() {
        return "List installed JVM and Android decompilers and the current defaults.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        if (!arguments.isEmpty()) {
            throw new IllegalArgumentException("list_decompilers does not accept arguments");
        }

        List<DecompilerToolSupport.Descriptor> jvm = DecompilerToolSupport.descriptors(
                decompilerManager
        );
        JsonObject structured = new JsonObject();
        DecompilerToolSupport.addAvailability(structured, jvm);
        JsonArray android = new JsonArray();
        AndroidDecompiler defaultAndroid = decompilerManager.getTargetAndroidDecompiler();
        decompilerManager.getAndroidDecompilers()
                .stream()
                .sorted(Comparator.comparing(
                        AndroidDecompiler::getName,
                        String.CASE_INSENSITIVE_ORDER
                ))
                .forEach(decompiler -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("name", decompiler.getName());
                    item.addProperty("version", decompiler.getVersion());
                    item.addProperty(
                            "default",
                            defaultAndroid != null
                                    && decompiler.getName().equalsIgnoreCase(
                                            defaultAndroid.getName()
                                    )
                    );
                    android.add(item);
                });
        structured.add("android_decompilers", android);
        if (defaultAndroid == null) {
            structured.addProperty("default_android_decompiler", "");
        } else {
            structured.addProperty("default_android_decompiler", defaultAndroid.getName());
        }

        StringBuilder text = new StringBuilder("installed decompilers: ");
        text.append(jvm.size()).append(" JVM");
        if (!android.isEmpty()) {
            text.append(", ").append(android.size()).append(" Android");
        }
        return McpTool.successResult(text.toString(), structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
