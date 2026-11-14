package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import me.naptila.recafmcp.mcp.McpTool;
import me.naptila.recafmcp.mcp.util.ToolArguments;
import me.naptila.recafmcp.mcp.util.ToolCancellation;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Renders raw JVM bytecode or Dalvik instructions for one exact method.
 */
public final class GetMethodBytecodeTool implements McpTool {
    private static final int MAX_TEXT_LENGTH = 400_000;
    private static final Set<String> ARGUMENTS = Set.of(
            "class_name", "name", "descriptor", "scope", "dex_name"
    );
    private static final JsonObject INPUT_SCHEMA = createInputSchema();

    private final BooleanSupplier hasWorkspace;
    private final Supplier<Workspace> workspaceSupplier;

    public GetMethodBytecodeTool(WorkspaceManager workspaceManager) {
        this(workspaceManager::hasCurrentWorkspace, workspaceManager::getCurrent);
    }

    GetMethodBytecodeTool(BooleanSupplier hasWorkspace, Supplier<Workspace> workspaceSupplier) {
        this.hasWorkspace = Objects.requireNonNull(hasWorkspace, "hasWorkspace");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public String name() {
        return "get_method_bytecode";
    }

    @Override
    public String description() {
        return "Read raw JVM bytecode or Dalvik instructions for one exact method.";
    }

    @Override
    public JsonObject inputSchema() {
        return INPUT_SCHEMA.deepCopy();
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        ToolArguments.rejectUnknown(arguments, ARGUMENTS);
        ClassToolSupport.Selection selection = ClassToolSupport.parseSelection(arguments, false);
        String className = ToolArguments.requiredString(arguments, "class_name");
        String name = ToolArguments.requiredString(arguments, "name");
        String descriptor = ToolArguments.requiredString(arguments, "descriptor");
        if (className.length() > JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH
                || name.length() > JvmClassMutationSupport.MAX_MEMBER_NAME_LENGTH
                || descriptor.length() > JvmClassMutationSupport.MAX_DESCRIPTOR_LENGTH) {
            throw new IllegalArgumentException("an argument exceeds its length limit");
        }
        if (!hasWorkspace.getAsBoolean()) {
            return error("workspace not open", className, name, descriptor);
        }

        Workspace workspace = workspaceSupplier.get();
        List<ClassToolSupport.Entry> matches = ClassToolSupport.exact(
                workspace.getPrimaryResource(),
                selection,
                className
        );
        if (matches.isEmpty()) {
            return error("class not found in requested primary scope", className, name, descriptor);
        }
        if (matches.size() > 1) {
            return error(
                    "class is ambiguous; specify dex_name",
                    className,
                    name,
                    descriptor
            );
        }

        ClassToolSupport.Entry entry = matches.getFirst();
        if (entry.scope() == ClassToolSupport.Scope.ANDROID) {
            return android(workspace, entry, name, descriptor);
        }
        return jvm(entry, name, descriptor);
    }

    private static JsonObject jvm(ClassToolSupport.Entry entry,
                                  String name,
                                  String descriptor) {
        JvmClassInfo classInfo = entry.classInfo().asJvmClass();
        MethodNode method = findMethod(classInfo, name, descriptor);
        if (method == null) {
            return error(
                    "method with exact name and descriptor not found",
                    classInfo.getName(),
                    name,
                    descriptor
            );
        }

        String text;
        try {
            ToolCancellation.check();
            Textifier textifier = new Textifier();
            method.accept(new TraceMethodVisitor(textifier));
            StringWriter writer = new StringWriter();
            textifier.print(new PrintWriter(writer));
            text = writer.toString();
        } catch (RuntimeException ex) {
            ToolCancellation.propagateIfRequested(ex);
            return error(
                    "bytecode rendering failed: " + ex.getMessage(),
                    classInfo.getName(),
                    name,
                    descriptor
            );
        }
        boolean truncated = text.length() > MAX_TEXT_LENGTH;
        if (truncated) {
            text = text.substring(0, MAX_TEXT_LENGTH);
        }

        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", classInfo.getName());
        ClassToolSupport.addIdentity(structured, entry);
        structured.addProperty("name", name);
        structured.addProperty("descriptor", descriptor);
        structured.addProperty("access", method.access);
        structured.add("modifiers", MemberToolSupport.memberModifiers(method.access, true));
        structured.addProperty("classfile_version", classInfo.getVersion());
        structured.add("dex_version", JsonNull.INSTANCE);
        structured.addProperty("bytecode_format", "jvm_textifier");
        structured.addProperty("instruction_node_count", method.instructions.size());
        structured.addProperty("try_catch_block_count", method.tryCatchBlocks.size());
        structured.addProperty("max_stack", method.maxStack);
        structured.addProperty("max_locals", method.maxLocals);
        structured.addProperty("text", text);
        structured.addProperty("text_truncated", truncated);
        structured.addProperty("text_length", text.length());
        structured.addProperty("assembly_format", "recaf_jasm_method");
        structured.addProperty("assembly_tool", "get_method_assembly");
        return McpTool.successResult("rendered JVM bytecode for " + name, structured);
    }

    private static JsonObject android(Workspace workspace,
                                      ClassToolSupport.Entry entry,
                                      String name,
                                      String descriptor) {
        AndroidInstructionSupport.MethodCode method = AndroidInstructionSupport.exact(
                entry,
                name,
                descriptor
        );
        if (method == null) {
            return error(
                    "method with exact name and descriptor not found",
                    entry.className(),
                    name,
                    descriptor
            );
        }
        List<AndroidInstructionSupport.InstructionLine> lines =
                AndroidInstructionSupport.instructions(method);
        JsonArray instructions = new JsonArray();
        for (AndroidInstructionSupport.InstructionLine line : lines) {
            ToolCancellation.check();
            JsonObject instruction = new JsonObject();
            instruction.addProperty("index", line.index());
            instruction.addProperty("offset", line.offset());
            instruction.addProperty("text", line.text());
            instructions.add(instruction);
        }

        int dexVersion = workspace.getPrimaryResource()
                .getAndroidClassBundles()
                .get(entry.dexName())
                .getVersion();
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", entry.className());
        ClassToolSupport.addIdentity(structured, entry);
        structured.addProperty("name", name);
        structured.addProperty("descriptor", descriptor);
        structured.addProperty("access", method.access());
        structured.add("modifiers", MemberToolSupport.memberModifiers(method.access(), true));
        structured.add("classfile_version", JsonNull.INSTANCE);
        structured.addProperty("dex_version", dexVersion);
        structured.addProperty("bytecode_format", "dalvik_instruction_tree");
        structured.add("instructions", instructions);
        structured.addProperty("instruction_count", instructions.size());
        structured.addProperty("assembly_format", "recaf_jasm_dalvik_method");
        structured.addProperty("assembly_tool", "get_method_assembly");
        if (method.code() == null) {
            structured.add("registers", JsonNull.INSTANCE);
            structured.add("incoming_registers", JsonNull.INSTANCE);
            structured.add("outgoing_registers", JsonNull.INSTANCE);
        } else {
            structured.addProperty("registers", method.code().getRegisters());
            structured.addProperty("incoming_registers", method.code().getIn());
            structured.addProperty("outgoing_registers", method.code().getOut());
        }
        return McpTool.successResult(
                "rendered Dalvik instructions for " + name,
                structured
        );
    }

    private static MethodNode findMethod(JvmClassInfo classInfo,
                                         String name,
                                         String descriptor) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(classInfo.getBytecode()).accept(node, 0);
            return node.methods.stream()
                    .filter(method -> method.name.equals(name)
                            && method.desc.equals(descriptor))
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static JsonObject error(String message,
                                    String className,
                                    String name,
                                    String descriptor) {
        JsonObject structured = new JsonObject();
        structured.addProperty("class_name", className);
        structured.addProperty("name", name);
        structured.addProperty("descriptor", descriptor);
        structured.addProperty("error", message);
        return McpTool.errorResult(message, structured);
    }

    private static JsonObject createInputSchema() {
        JsonObject className = new JsonObject();
        className.addProperty("type", "string");
        className.addProperty("minLength", 1);
        className.addProperty("maxLength", JvmClassMutationSupport.MAX_CLASS_NAME_LENGTH);

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("minLength", 1);
        name.addProperty("maxLength", JvmClassMutationSupport.MAX_MEMBER_NAME_LENGTH);

        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("type", "string");
        descriptor.addProperty("minLength", 1);
        descriptor.addProperty("maxLength", JvmClassMutationSupport.MAX_DESCRIPTOR_LENGTH);

        JsonObject properties = new JsonObject();
        properties.add("class_name", className);
        properties.add("name", name);
        properties.add("descriptor", descriptor);
        ClassToolSupport.addScopeProperties(properties, false);

        JsonArray required = new JsonArray();
        required.add("class_name");
        required.add("name");
        required.add("descriptor");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
