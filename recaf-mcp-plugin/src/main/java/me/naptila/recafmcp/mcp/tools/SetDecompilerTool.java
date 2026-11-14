package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.DecompilerManagerConfig;
import software.coley.recaf.services.decompile.JvmDecompiler;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Changes the preferred JVM or Android decompiler for the current session.
 */
public final class SetDecompilerTool implements McpTool {
    private static final Set<String> ARGUMENTS = Set.of("name", "target");
    private static final Set<String> TARGETS = Set.of("jvm", "android");
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final DecompilerManager decompilerManager;

    public SetDecompilerTool(DecompilerManager decompilerManager) {
        this.decompilerManager = Objects.requireNonNull(
                decompilerManager,
                "decompilerManager"
        );
    }

    @Override
    public String name() {
        return "set_decompiler";
    }

    @Override
    public String description() {
        return "Select the default JVM or Android decompiler used by later decompile calls.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        String requested = ToolArguments.requiredString(arguments, "name");
        String target = ToolArguments.optionalString(arguments, "target");
        if (target == null) {
            target = "jvm";
        }
        if (!TARGETS.contains(target)) {
            throw new IllegalArgumentException("target must be jvm or android");
        }

        DecompilerManagerConfig config = decompilerManager.getServiceConfig();
        if (target.equals("android")) {
            if (decompilerManager.getAndroidDecompiler(requested) == null) {
                return error("Android decompiler not installed: " + requested, target, requested);
            }
            String previous = config.getPreferredAndroidDecompiler().getValue();
            config.getPreferredAndroidDecompiler().setValue(requested);
            JsonObject structured = metadata(target, previous, requested);
            structured.add("available_decompilers", androidNames());
            return McpTool.successResult(
                    "default Android decompiler set to " + requested,
                    structured
            );
        }

        if (decompilerManager.getJvmDecompiler(requested) == null) {
            return error("JVM decompiler not installed: " + requested, target, requested);
        }
        String previous = config.getPreferredJvmDecompiler().getValue();
        config.getPreferredJvmDecompiler().setValue(requested);
        JvmDecompiler active = decompilerManager.getTargetJvmDecompiler();
        JsonObject structured = metadata(target, previous, requested);
        structured.addProperty(
                "active_decompiler",
                active == null ? requested : active.getName()
        );
        List<DecompilerToolSupport.Descriptor> descriptors =
                DecompilerToolSupport.descriptors(decompilerManager);
        DecompilerToolSupport.addAvailability(structured, descriptors);
        return McpTool.successResult(
                "default JVM decompiler set to " + requested,
                structured
        );
    }

    private JsonArray androidNames() {
        JsonArray names = new JsonArray();
        decompilerManager.getAndroidDecompilers().stream()
                .map(decompiler -> decompiler.getName())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(names::add);
        return names;
    }

    private static JsonObject metadata(String target, String previous, String requested) {
        JsonObject structured = new JsonObject();
        structured.addProperty("target", target);
        structured.addProperty("requested_decompiler", requested);
        if (previous == null) {
            structured.add("previous_decompiler", JsonNull.INSTANCE);
        } else {
            structured.addProperty("previous_decompiler", previous);
        }
        structured.addProperty("applied", true);
        return structured;
    }

    private static JsonObject error(String message, String target, String requested) {
        JsonObject structured = new JsonObject();
        structured.addProperty("target", target);
        structured.addProperty("requested_decompiler", requested);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("minLength", 1);
        name.addProperty("description", "Exact installed decompiler name.");

        JsonObject target = new JsonObject();
        target.addProperty("type", "string");
        JsonArray values = new JsonArray();
        values.add("jvm");
        values.add("android");
        target.add("enum", values);
        target.addProperty("default", "jvm");

        JsonObject properties = new JsonObject();
        properties.add("name", name);
        properties.add("target", target);

        JsonArray required = new JsonArray();
        required.add("name");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
