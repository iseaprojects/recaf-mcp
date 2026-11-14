package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.InnerClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Returns complete metadata for one exact primary class.
 */
public final class GetClassInfoTool implements McpTool {
    private static final int DEFAULT_MEMBER_LIMIT = 100;
    private static final int MAX_MEMBER_LIMIT = 1_000;
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "scope", "dex_name", "include_members", "member_limit"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public GetClassInfoTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    GetClassInfoTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "get_class_info";
    }

    @Override
    public String description() {
        return "Read structural metadata for one exact JVM or Android/DEX class.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        Request request = parseRequest(arguments);
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", request, List.of());
        }

        Workspace workspace = workspaceSupplier.get();
        List<ClassToolSupport.Entry> matches = ClassToolSupport.exact(
                workspace.getPrimaryResource(),
                request.selection(),
                request.className()
        );
        if (matches.isEmpty()) {
            return error("class not found in requested primary scope", request, List.of());
        }
        if (matches.size() > 1) {
            return error(
                    "class is ambiguous; specify dex_name",
                    request,
                    matches
            );
        }

        ClassToolSupport.Entry entry = matches.getFirst();
        ClassInfo classInfo = entry.classInfo();
        JsonObject structured = metadata(request);
        ClassToolSupport.addIdentity(structured, entry);
        structured.addProperty("class_name", classInfo.getName());
        structured.addProperty("simple_name", classInfo.getSimpleName());
        structured.addProperty("package_name", classInfo.getPackageName());
        structured.addProperty("access", classInfo.getAccess());
        structured.add("modifiers", MemberToolSupport.classModifiers(classInfo.getAccess()));
        addNullable(structured, "super_name", classInfo.getSuperName());
        JsonArray interfaces = new JsonArray();
        classInfo.getInterfaces().forEach(interfaces::add);
        structured.add("interfaces", interfaces);
        addNullable(structured, "signature", classInfo.getSignature());
        addNullable(structured, "source_file", classInfo.getSourceFileName());
        addNullable(structured, "outer_class_name", classInfo.getOuterClassName());
        addNullable(structured, "outer_method_name", classInfo.getOuterMethodName());
        addNullable(
                structured,
                "outer_method_descriptor",
                classInfo.getOuterMethodDescriptor()
        );
        JsonArray breadcrumbs = new JsonArray();
        classInfo.getOuterClassBreadcrumbs().forEach(breadcrumbs::add);
        structured.add("outer_class_breadcrumbs", breadcrumbs);
        JsonArray innerClasses = new JsonArray();
        classInfo.getInnerClasses().forEach(inner -> innerClasses.add(innerJson(inner)));
        structured.add("inner_classes", innerClasses);
        structured.add("annotations", MemberToolSupport.annotations(classInfo.getAnnotations()));
        structured.addProperty(
                "type_annotation_count",
                classInfo.getTypeAnnotations().size()
        );
        structured.addProperty("field_count", classInfo.getFields().size());
        structured.addProperty("method_count", classInfo.getMethods().size());

        if (classInfo instanceof JvmClassInfo jvmClass) {
            structured.addProperty("classfile_version", jvmClass.getVersion());
            structured.add("dex_version", JsonNull.INSTANCE);
            structured.addProperty("bytecode_size_bytes", jvmClass.getBytecode().length);
            structured.addProperty("class_sha256", JvmClassMutationSupport.sha256(
                    jvmClass.getBytecode()
            ));
        } else {
            structured.add("classfile_version", JsonNull.INSTANCE);
            int dexVersion = workspace.getPrimaryResource()
                    .getAndroidClassBundles()
                    .get(entry.dexName())
                    .getVersion();
            structured.addProperty("dex_version", dexVersion);
            structured.add("bytecode_size_bytes", JsonNull.INSTANCE);
            structured.add("class_sha256", JsonNull.INSTANCE);
        }

        if (request.includeMembers()) {
            addMembers(structured, classInfo, request.memberLimit());
        }
        return McpTool.successResult(
                "class metadata for " + classInfo.getName(),
                structured
        );
    }

    private static void addMembers(JsonObject structured,
                                   ClassInfo classInfo,
                                   int memberLimit) {
        List<ClassMember> members = classInfo.fieldAndMethodStream()
                .sorted(java.util.Comparator
                        .comparing((ClassMember member) -> member.isField() ? 0 : 1)
                        .thenComparing(ClassMember::getName)
                        .thenComparing(ClassMember::getDescriptor))
                .toList();
        JsonArray page = new JsonArray();
        members.stream()
                .limit(memberLimit)
                .forEach(member -> page.add(MemberToolSupport.toJson(member, false)));
        structured.add("members", page);
        structured.addProperty("members_total", members.size());
        structured.addProperty("members_returned", page.size());
        structured.addProperty("members_truncated", members.size() > page.size());
    }

    private static JsonObject innerJson(InnerClassInfo inner) {
        JsonObject json = new JsonObject();
        json.addProperty("inner_class_name", inner.getInnerClassName());
        addNullable(json, "outer_class_name", inner.getOuterClassName());
        addNullable(json, "inner_name", inner.getInnerName());
        json.addProperty("inner_access", inner.getInnerAccess());
        json.add("modifiers", MemberToolSupport.classModifiers(inner.getInnerAccess()));
        json.addProperty("self_reference", inner.isSelfReference());
        json.addProperty("external_reference", inner.isExternalReference());
        return json;
    }

    private static JsonObject error(String message,
                                    Request request,
                                    List<ClassToolSupport.Entry> candidates) {
        JsonObject structured = metadata(request);
        if (!candidates.isEmpty()) {
            structured.add("candidates", ClassToolSupport.candidates(candidates));
        }
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject metadata(Request request) {
        JsonObject structured = new JsonObject();
        structured.addProperty("requested_class_name", request.className());
        structured.addProperty("requested_scope", request.selection().scope().value());
        if (request.selection().dexName() == null) {
            structured.add("requested_dex_name", JsonNull.INSTANCE);
        } else {
            structured.addProperty("requested_dex_name", request.selection().dexName());
        }
        return structured;
    }

    private static Request parseRequest(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(
                arguments,
                false
        );
        String className = ToolArguments.requiredString(arguments, "class_name");
        if (className.length() > JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH) {
            throw new IllegalArgumentException("class_name is too long");
        }
        return new Request(
                className,
                selection,
                ToolArguments.bool(arguments, "include_members", false),
                ToolArguments.integer(
                        arguments,
                        "member_limit",
                        DEFAULT_MEMBER_LIMIT,
                        1,
                        MAX_MEMBER_LIMIT
                )
        );
    }

    private static void addNullable(JsonObject target, String name, String value) {
        if (value == null) {
            target.add(name, JsonNull.INSTANCE);
        } else {
            target.addProperty(name, value);
        }
    }

    private static JsonObject createInputSchema() {
        JsonObject className = new JsonObject();
        className.addProperty("type", "string");
        className.addProperty("minLength", 1);
        className.addProperty("maxLength", JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH);
        className.addProperty("description", "Exact primary JVM internal or DEX class name.");

        JsonObject includeMembers = new JsonObject();
        includeMembers.addProperty("type", "boolean");
        includeMembers.addProperty("default", false);
        includeMembers.addProperty(
                "description",
                "Include a bounded member listing; list_members offers richer filtering."
        );

        JsonObject memberLimit = new JsonObject();
        memberLimit.addProperty("type", "integer");
        memberLimit.addProperty("minimum", 1);
        memberLimit.addProperty("maximum", MAX_MEMBER_LIMIT);
        memberLimit.addProperty("default", DEFAULT_MEMBER_LIMIT);

        JsonObject properties = new JsonObject();
        properties.add("class_name", className);
        properties.add("include_members", includeMembers);
        properties.add("member_limit", memberLimit);
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

    record Request(String className,
                   ClassToolSupport.Selection selection,
                   boolean includeMembers,
                   int memberLimit) {
    }
}
