package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import me.darknet.dex.tree.definitions.ClassDefinition;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.List;
import java.util.Objects;

/**
 * Shared exact-class resolution and DEX definition storage for mutation tools.
 */
final class ClassMutationSupport {
    private ClassMutationSupport() {
    }

    static Resolution resolve(WorkspaceResource resource,
                              JsonObject arguments,
                              String className,
                              boolean allowAll) {
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(
                arguments,
                allowAll
        );
        if (className.length() > JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH) {
            throw new IllegalArgumentException("class_name is too long");
        }
        List<ClassToolSupport.Entry> matches = ClassToolSupport.exact(
                resource,
                selection,
                className
        );
        if (matches.isEmpty()) {
            return new Resolution(null, List.of(), "class not found in requested primary scope");
        }
        if (matches.size() > 1) {
            return new Resolution(null, matches, "class is ambiguous; specify dex_name");
        }
        return new Resolution(matches.getFirst(), List.of(), null);
    }

    static ClassDefinition definition(ClassToolSupport.Entry entry) {
        AndroidClassInfo classInfo = entry.classInfo().asAndroidClass();
        ClassDefinition definition = classInfo.getBackingDefinition();
        if (definition == null) {
            throw new IllegalStateException("Android class has no backing DEX definition");
        }
        return definition;
    }

    static AndroidClassBundle androidBundle(Workspace workspace, ClassToolSupport.Entry entry) {
        AndroidClassBundle bundle = workspace.getPrimaryResource()
                .getAndroidClassBundles()
                .get(entry.dexName());
        if (bundle == null) {
            throw new IllegalStateException("Android DEX bundle is not available: "
                    + entry.dexName());
        }
        return bundle;
    }

    static AndroidClassInfo storeAndroid(Workspace workspace,
                                         ClassToolSupport.Entry entry,
                                         ClassDefinition definition) {
        AndroidClassInfo updated = new AndroidClassInfoBuilder(definition).build();
        AndroidClassBundle bundle = androidBundle(workspace, entry);
        bundle.put(updated);
        return updated;
    }

    static JsonObject errorMetadata(String className) {
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
        return structured;
    }

    record Resolution(ClassToolSupport.Entry entry,
                      List<ClassToolSupport.Entry> candidates,
                      String error) {
        Resolution {
            candidates = List.copyOf(candidates);
        }

        boolean found() {
            return entry != null;
        }

        JsonObject errorResult(String className) {
            JsonObject structured = errorMetadata(className);
            if (!candidates.isEmpty()) {
                structured.add("candidates", ClassToolSupport.candidates(candidates));
            }
            structured.addProperty("error", Objects.requireNonNullElse(error, "class not found"));
            return structured;
        }
    }
}
