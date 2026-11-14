package me.naptila.recafmcp.mcp.tools;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.util.List;

/**
 * Minimal JVM class and workspace fixtures for tool tests.
 */
final class ToolTestFixtures {
    private ToolTestFixtures() {
    }

    static JvmClassInfo simpleClass(String internalName, String superName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                internalName,
                null,
                superName,
                null
        );
        writer.visitField(Opcodes.ACC_PRIVATE, "count", "I", null, 3).visitEnd();
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                "<init>",
                "()V",
                null,
                null
        );
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                superName,
                "<init>",
                "()V",
                false
        );
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor value = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                "value",
                "()I",
                null,
                null
        );
        value.visitCode();
        value.visitInsn(Opcodes.ICONST_0);
        value.visitInsn(Opcodes.IRETURN);
        value.visitMaxs(0, 0);
        value.visitEnd();
        writer.visitEnd();
        return new JvmClassInfoBuilder(writer.toByteArray()).build();
    }

    static Workspace workspace(JvmClassInfo... classes) {
        BasicJvmClassBundle bundle = new BasicJvmClassBundle();
        for (JvmClassInfo classInfo : classes) {
            bundle.put(classInfo);
        }
        WorkspaceResource primary = new WorkspaceResourceBuilder()
                .withJvmClassBundle(bundle)
                .build();
        return new BasicWorkspace(primary, List.of(), false);
    }

    static Class<?> define(JvmClassInfo classInfo) {
        byte[] bytes = classInfo.getBytecode();
        return new ClassLoader(null) {
            Class<?> load() {
                return defineClass(null, bytes, 0, bytes.length);
            }
        }.load();
    }
}
