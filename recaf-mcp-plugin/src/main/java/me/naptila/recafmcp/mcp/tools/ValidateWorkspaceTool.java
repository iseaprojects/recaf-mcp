package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Checks primary classes for structural inconsistencies without modifying them.
 */
public final class ValidateWorkspaceTool implements McpTool {
    private static final int DEFAULT_MAX_ISSUES = 100;
    private static final int MAX_MAX_ISSUES = 1_000;
    private static final Set<String> ARGUMENTS = Set.of(
            "scope", "dex_name", "max_issues"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public ValidateWorkspaceTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    ValidateWorkspaceTool(BooleanSupplier hasWorkspace,
                          Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "validate_workspace";
    }

    @Override
    public String description() {
        return "Check primary classes for parse failures, name mismatches, and broken members.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(arguments, true);
        int maxIssues = ToolArguments.integer(
                arguments,
                "max_issues",
                DEFAULT_MAX_ISSUES,
                1,
                MAX_MAX_ISSUES
        );
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", selection);
        }

        Workspace workspace = workspaceSupplier.get();
        JsonArray issues = new JsonArray();
        TreeMap<String, Integer> counts = new TreeMap<>();
        int classes = 0;
        int jvmClasses = 0;
        int androidClasses = 0;

        for (ClassToolSupport.Entry entry
                : ClassToolSupport.entries(workspace.getPrimaryResource(), selection)) {
            ToolCancellation.check();
            classes++;
            if (entry.scope() == ClassToolSupport.Scope.JVM) {
                jvmClasses++;
                validateJvm(entry, issues, counts, maxIssues);
            } else {
                androidClasses++;
                validateAndroid(workspace, entry, issues, counts, maxIssues);
            }
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("requested_scope", selection.scope().value());
        if (selection.dexName() == null) {
            structured.add("dex_name", JsonNull.INSTANCE);
        } else {
            structured.addProperty("dex_name", selection.dexName());
        }
        structured.addProperty("classes_checked", classes);
        structured.addProperty("jvm_classes_checked", jvmClasses);
        structured.addProperty("android_classes_checked", androidClasses);
        int issueTotal = counts.values().stream().mapToInt(Integer::intValue).sum();
        structured.addProperty("issue_total", issueTotal);
        structured.addProperty("valid", issueTotal == 0);
        JsonObject byCode = new JsonObject();
        counts.forEach(byCode::addProperty);
        structured.add("issues_by_code", byCode);
        structured.add("issues", issues);
        structured.addProperty("returned_issues", issues.size());
        structured.addProperty("truncated", issueTotal > issues.size());
        structured.addProperty("max_issues", maxIssues);
        return McpTool.successResult(
                issueTotal == 0
                        ? "validated " + classes + " classes without issues"
                        : "found " + issueTotal + " issues in " + classes + " classes",
                structured
        );
    }

    private static void validateJvm(ClassToolSupport.Entry entry,
                                    JsonArray issues,
                                    TreeMap<String, Integer> counts,
                                    int maxIssues) {
        JvmClassInfo classInfo = entry.classInfo().asJvmClass();
        String className = entry.className();
        if (!className.equals(classInfo.getName())) {
            record(issues, counts, maxIssues, "jvm_name_mismatch", entry,
                    "bundle key and classfile name differ: " + classInfo.getName());
        }
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(classInfo.getBytecode()).accept(node, 0);
        } catch (RuntimeException ex) {
            record(issues, counts, maxIssues, "jvm_class_unparseable", entry,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return;
        }
        if (!className.equals(node.name)) {
            record(issues, counts, maxIssues, "jvm_classfile_name_mismatch", entry,
                    "classfile declares " + node.name);
        }
        if (node.superName == null && (node.access & Opcodes.ACC_MODULE) == 0
                && !"java/lang/Object".equals(node.name)) {
            record(issues, counts, maxIssues, "jvm_missing_superclass", entry,
                    "class has no superclass");
        }
        Set<String> fieldKeys = new HashSet<>();
        for (FieldNode field : node.fields) {
            if (!fieldKeys.add(field.name + field.desc)) {
                record(issues, counts, maxIssues, "jvm_duplicate_field", entry,
                        field.name + field.desc);
            }
        }
        Set<String> methodKeys = new HashSet<>();
        for (MethodNode method : node.methods) {
            if (!methodKeys.add(method.name + method.desc)) {
                record(issues, counts, maxIssues, "jvm_duplicate_method", entry,
                        method.name + method.desc);
            }
            boolean codeless = (method.access
                    & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0;
            if (!codeless && (method.instructions == null
                    || method.instructions.size() == 0)) {
                record(issues, counts, maxIssues, "jvm_method_without_code", entry,
                        method.name + method.desc);
            }
        }
        for (ClassMember member : classInfo.fieldAndMethodStream().toList()) {
            if (member.getName().isEmpty()) {
                record(issues, counts, maxIssues, "jvm_empty_member_name", entry,
                        member.getDescriptor());
            }
        }
    }

    private static void validateAndroid(Workspace workspace,
                                        ClassToolSupport.Entry entry,
                                        JsonArray issues,
                                        TreeMap<String, Integer> counts,
                                        int maxIssues) {
        AndroidClassInfo classInfo = entry.classInfo().asAndroidClass();
        ClassDefinition definition;
        try {
            definition = ClassMutationSupport.definition(entry);
        } catch (RuntimeException ex) {
            record(issues, counts, maxIssues, "android_definition_missing", entry,
                    ex.getMessage());
            return;
        }
        String declared = definition.getType().internalName();
        if (!declared.equals(classInfo.getName())) {
            record(issues, counts, maxIssues, "android_name_mismatch", entry,
                    "definition declares " + declared);
        }
        if (definition.getSuperClass() == null
                && !"Ljava/lang/Object;".equals(definition.getType().descriptor())) {
            record(issues, counts, maxIssues, "android_missing_superclass", entry,
                    "class has no superclass");
        }
        int access = definition.getAccess();
        if ((access & Opcodes.ACC_INTERFACE) != 0
                && (access & Opcodes.ACC_ABSTRACT) == 0) {
            record(issues, counts, maxIssues, "android_interface_not_abstract", entry,
                    "interface access flags omit abstract");
        }
        AndroidClassBundle bundle = workspace.getPrimaryResource()
                .getAndroidClassBundles()
                .get(entry.dexName());
        if (bundle == null) {
            record(issues, counts, maxIssues, "android_bundle_missing", entry,
                    "DEX bundle " + entry.dexName() + " is unavailable");
            return;
        }
        int version = bundle.getVersion();
        if (version < 35 || version > 41) {
            record(issues, counts, maxIssues, "android_dex_version_unusual", entry,
                    "DEX version " + version);
        }
        for (ClassMember member : classInfo.fieldAndMethodStream().toList()) {
            boolean method = member.isMethod();
            if (method && (member.getAccess()
                    & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                var methodMember = definition.getMethod(
                        member.getName(),
                        member.getDescriptor()
                );
                if (methodMember == null) {
                    record(issues, counts, maxIssues, "android_member_missing", entry,
                            member.getName() + member.getDescriptor());
                } else if (methodMember.getCode() == null) {
                    record(issues, counts, maxIssues, "android_method_without_code",
                            entry, member.getName() + member.getDescriptor());
                }
            }
        }
    }

    private static void record(JsonArray issues,
                               TreeMap<String, Integer> counts,
                               int maxIssues,
                               String code,
                               ClassToolSupport.Entry entry,
                               String detail) {
        counts.merge(code, 1, Integer::sum);
        if (issues.size() >= maxIssues) {
            return;
        }
        JsonObject issue = new JsonObject();
        issue.addProperty("code", code);
        issue.addProperty("class_name", entry.className());
        ClassToolSupport.addHitIdentity(issue, entry);
        issue.addProperty("detail", detail == null ? "" : clip(detail));
        issues.add(issue);
    }

    private static String clip(String value) {
        String singleLine = value.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 500 ? singleLine : singleLine.substring(0, 500);
    }

    private static JsonObject error(String message, ClassToolSupport.Selection selection) {
        JsonObject structured = new JsonObject();
        structured.addProperty("requested_scope", selection.scope().value());
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject maxIssues = new JsonObject();
        maxIssues.addProperty("type", "integer");
        maxIssues.addProperty("minimum", 1);
        maxIssues.addProperty("maximum", MAX_MAX_ISSUES);
        maxIssues.addProperty("default", DEFAULT_MAX_ISSUES);
        maxIssues.addProperty("description", "Maximum detailed issues returned.");

        JsonObject properties = new JsonObject();
        properties.add("max_issues", maxIssues);
        ClassToolSupport.addScopeProperties(properties, true);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
