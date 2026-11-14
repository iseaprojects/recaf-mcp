package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Shared ASM round-trip and JVM-primary-class resolution for member edits.
 */
final class JvmClassMutationSupport {
    static final int MAX_CLASS_NAME_LENGTH = 1_000;
    static final int MAX_MEMBER_NAME_LENGTH = 1_000;
    static final int MAX_DESCRIPTOR_LENGTH = 4_000;

    private JvmClassMutationSupport() {
    }

    static Selection resolve(JsonObject arguments, String className) {
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(
                arguments,
                false
        );
        if (selection.scope() != ClassToolSupport.Scope.JVM) {
            throw new IllegalArgumentException(
                    "this tool supports primary JVM classes only; Android/DEX classes "
                            + "must be edited through patch_instructions or run_recaf_script"
            );
        }
        if (className.length() > MAX_CLASS_NAME_LENGTH) {
            throw new IllegalArgumentException("class_name is too long");
        }
        if (className.startsWith("[") || className.startsWith(".")) {
            throw new IllegalArgumentException("class_name must be a JVM internal name");
        }
        return new Selection(className, selection);
    }

    static JvmClassInfo lookup(Workspace workspace, String className) {
        JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
        return bundle.get(className);
    }

    static JvmClassInfo mutate(JvmClassInfo original, Consumer<ClassNode> mutation) {
        ClassReader reader = new ClassReader(original.getBytecode());
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        mutation.accept(node);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return new JvmClassInfoBuilder(writer.toByteArray()).build();
    }

    static JvmClassInfo store(Workspace workspace, JvmClassInfo replacement) {
        return workspace.getPrimaryResource()
                .getJvmClassBundle()
                .put(replacement);
    }

    static boolean isValidMemberName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_MEMBER_NAME_LENGTH) {
            return false;
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (character == '.' || character == ';' || character == '[' || character == '/') {
                return false;
            }
        }
        return !name.startsWith("<");
    }

    static boolean isValidMethodName(String name) {
        return "<init>".equals(name) || "<clinit>".equals(name) || isValidMemberName(name);
    }

    static JsonObject metadata(String className, JvmClassInfo original) {
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
        structured.addProperty("scope", "primary_jvm");
        structured.addProperty("original_sha256", sha256(original.getBytecode()));
        structured.addProperty("classfile_version", original.getVersion());
        return structured;
    }

    static void addPatchedHash(JsonObject structured, Workspace workspace, String className) {
        JvmClassInfo patched = lookup(workspace, className);
        if (patched == null) {
            structured.addProperty("patched_sha256", "");
            return;
        }
        structured.addProperty("patched_sha256", sha256(patched.getBytecode()));
        structured.addProperty(
                "dirty",
                workspace.getPrimaryResource()
                        .getJvmClassBundle()
                        .getDirtyKeys()
                        .contains(className)
        );
        structured.addProperty(
                "field_count",
                patched.getFields().size()
        );
        structured.addProperty(
                "method_count",
                patched.getMethods().size()
        );
    }

    static String requiredMemberName(JsonObject arguments, String argument) {
        String value = ToolArguments.requiredString(arguments, argument);
        if (value.length() > MAX_MEMBER_NAME_LENGTH) {
            throw new IllegalArgumentException(argument + " is too long");
        }
        return value;
    }

    static String requiredDescriptor(JsonObject arguments) {
        return requiredDescriptor(arguments, "descriptor");
    }

    static String requiredDescriptor(JsonObject arguments, String argument) {
        String value = ToolArguments.requiredString(arguments, argument);
        if (value.length() > MAX_DESCRIPTOR_LENGTH) {
            throw new IllegalArgumentException(argument + " is too long");
        }
        return value;
    }

    static String sha256(byte[] content) {
        return FileToolSupport.sha256(content);
    }

    record Selection(String className, ClassToolSupport.Selection scopeSelection) {
        Selection {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(scopeSelection, "scopeSelection");
        }
    }
}
