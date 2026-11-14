package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import software.coley.recaf.info.properties.builtin.InputFilePathProperty;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Shared supporting-resource identity and summary helpers.
 */
final class SupportingResourceSupport {
    private SupportingResourceSupport() {
    }

    static String displayName(WorkspaceResource resource) {
        if (resource instanceof WorkspaceFileResource fileResource) {
            return fileResource.getFileInfo().getName();
        }
        return null;
    }

    static Path inputPath(WorkspaceResource resource) {
        if (!(resource instanceof WorkspaceFileResource fileResource)) {
            return null;
        }
        return InputFilePathProperty.get(fileResource.getFileInfo());
    }

    static JsonObject describe(WorkspaceResource resource, int index, boolean internal) {
        JsonObject json = new JsonObject();
        if (index >= 0) {
            json.addProperty("index", index);
        } else {
            json.add("index", JsonNull.INSTANCE);
        }
        json.addProperty("internal", internal);
        String name = displayName(resource);
        if (name == null) {
            json.add("name", JsonNull.INSTANCE);
        } else {
            json.addProperty("name", name);
        }
        Path path = inputPath(resource);
        if (path == null) {
            json.add("path", JsonNull.INSTANCE);
        } else {
            json.addProperty("path", canonicalize(path).toString());
        }
        int jvmClasses = resource.getJvmClassBundle().size();
        int androidClasses = resource.getAndroidClassBundles().values().stream()
                .mapToInt(Map::size)
                .sum();
        json.addProperty("jvm_class_count", jvmClasses);
        json.addProperty("android_class_count", androidClasses);
        json.addProperty("file_count", resource.getFileBundle().size());
        json.addProperty(
                "embedded_resource_count",
                resource.getEmbeddedResources().size()
        );
        return json;
    }

    static WorkspaceResource match(Workspace workspace, Path path, String name, int index) {
        List<WorkspaceResource> resources = workspace.getSupportingResources();
        if (index >= 0) {
            if (index >= resources.size()) {
                return null;
            }
            return resources.get(index);
        }
        Path canonical = path == null ? null : canonicalize(path);
        for (WorkspaceResource resource : resources) {
            if (canonical != null) {
                Path candidate = inputPath(resource);
                if (candidate != null && canonical.equals(canonicalize(candidate))) {
                    return resource;
                }
            }
            if (name != null && name.equals(displayName(resource))) {
                return resource;
            }
        }
        return null;
    }

    static boolean containsPath(Workspace workspace, Path path) {
        Path canonical = canonicalize(path);
        return workspace.getSupportingResources().stream()
                .map(SupportingResourceSupport::inputPath)
                .filter(java.util.Objects::nonNull)
                .map(SupportingResourceSupport::canonicalize)
                .anyMatch(canonical::equals);
    }

    static Path canonicalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ex) {
            return path.toAbsolutePath().normalize();
        }
    }
}
