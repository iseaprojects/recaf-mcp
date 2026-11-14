package me.naptila.recafmcp.mcp.tools;

import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.FieldMember;
import me.darknet.dex.tree.definitions.MemberIdentifier;
import me.darknet.dex.tree.definitions.MethodMember;
import me.darknet.dex.tree.definitions.code.CodeBuilder;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.definitions.constant.DoubleConstant;
import me.darknet.dex.tree.definitions.constant.FloatConstant;
import me.darknet.dex.tree.definitions.constant.IntConstant;
import me.darknet.dex.tree.definitions.constant.LongConstant;
import me.darknet.dex.tree.definitions.constant.StringConstant;
import me.darknet.dex.tree.definitions.instructions.ConstInstruction;
import me.darknet.dex.tree.definitions.instructions.ConstWideInstruction;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import me.darknet.dex.tree.type.ClassType;
import me.darknet.dex.tree.type.MethodType;
import me.darknet.dex.tree.type.Types;
import org.objectweb.asm.Opcodes;

/**
 * Native DEX class-definition member helpers.
 */
final class AndroidMemberSupport {
    private static final int MAX_PARAMETER_WORDS = 255;

    private AndroidMemberSupport() {
    }

    static ClassType parseFieldType(String descriptor) {
        if (descriptor.startsWith("(")) {
            throw new IllegalArgumentException("descriptor must be a field descriptor");
        }
        try {
            ClassType type = (ClassType) Types.typeFromDescriptor(descriptor);
            if (type == null) {
                throw new IllegalArgumentException("descriptor must be a field descriptor");
            }
            return type;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("descriptor is not a valid field descriptor");
        }
    }

    static MethodType parseMethodType(String descriptor) {
        if (!descriptor.startsWith("(")) {
            throw new IllegalArgumentException("descriptor must be a method descriptor");
        }
        try {
            return Types.methodTypeFromDescriptor(descriptor);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("descriptor is not a valid method descriptor");
        }
    }

    static FieldMember addField(ClassDefinition definition,
                                String name,
                                ClassType type,
                                int access,
                                Constant staticValue) {
        MemberIdentifier identifier = new MemberIdentifier(name, type.descriptor());
        if (definition.getField(identifier) != null) {
            throw new IllegalStateException("field with the same name and descriptor already exists");
        }
        FieldMember field = new FieldMember(name, type, access);
        if (staticValue != null) {
            field.setStaticValue(staticValue);
        }
        definition.putField(field);
        return field;
    }

    static MethodMember addMethod(ClassDefinition definition,
                                  String name,
                                  MethodType type,
                                  int access) {
        MemberIdentifier identifier = new MemberIdentifier(name, type.descriptor());
        if (definition.getMethod(identifier) != null) {
            throw new IllegalStateException("method with the same name and descriptor already exists");
        }
        MethodMember method = new MethodMember(name, type, access);
        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
            method.setCode(stubBody(type, (access & Opcodes.ACC_STATIC) != 0));
        }
        definition.putMethod(method);
        return method;
    }

    static boolean removeField(ClassDefinition definition,
                               String name,
                               String descriptor) {
        return definition.getFields().remove(new MemberIdentifier(name, descriptor)) != null;
    }

    static boolean removeMethod(ClassDefinition definition,
                                String name,
                                String descriptor) {
        return definition.getMethods().remove(new MemberIdentifier(name, descriptor)) != null;
    }

    static FieldMember replaceFieldAccess(ClassDefinition definition,
                                          FieldMember existing,
                                          int access) {
        FieldMember replacement = new FieldMember(
                existing.getName(),
                existing.getType(),
                access
        );
        replacement.setStaticValue(existing.getStaticValue());
        replacement.setSignature(existing.getSignature());
        replacement.addAnnotations(existing.getAnnotations());
        definition.putField(replacement);
        return replacement;
    }

    static MethodMember replaceMethodAccess(ClassDefinition definition,
                                            MethodMember existing,
                                            int access) {
        MethodMember replacement = new MethodMember(
                existing.getName(),
                existing.getType(),
                access
        );
        replacement.setCode(existing.getCode());
        replacement.setSignature(existing.getSignature());
        replacement.addAnnotations(existing.getAnnotations());
        replacement.setThrownTypes(existing.getThrownTypes());
        replacement.setParameterNames(existing.getParameterNames());
        replacement.setParameterAccessFlags(existing.getParameterAccessFlags());
        replacement.setParameterAnnotations(existing.getParameterAnnotations());
        replacement.setDefaultValue(existing.getDefaultValue());
        definition.putMethod(replacement);
        return replacement;
    }

    static Constant constant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return new IntConstant(bool ? 1 : 0);
        }
        if (value instanceof Character character) {
            return new IntConstant(character);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return new IntConstant(((Number) value).intValue());
        }
        if (value instanceof Long longValue) {
            return new LongConstant(longValue);
        }
        if (value instanceof Float floatValue) {
            return new FloatConstant(floatValue);
        }
        if (value instanceof Double doubleValue) {
            return new DoubleConstant(doubleValue);
        }
        if (value instanceof String text) {
            return new StringConstant(text);
        }
        throw new IllegalArgumentException(
                "static values must be primitives or strings"
        );
    }

    private static me.darknet.dex.tree.definitions.code.Code stubBody(MethodType type,
                                                                     boolean isStatic) {
        int incoming = parameterWords(type, isStatic);
        if (incoming > MAX_PARAMETER_WORDS) {
            throw new IllegalArgumentException("method has too many parameter words");
        }
        ClassType returnType = type.returnType();
        String descriptor = returnType.descriptor();
        if ("V".equals(descriptor)) {
            return new CodeBuilder()
                    .registers(Math.max(incoming, 1))
                    .arguments(incoming, 0)
                    .return_void()
                    .build();
        }
        if ("J".equals(descriptor) || "D".equals(descriptor)) {
            return new CodeBuilder()
                    .registers(incoming + 2)
                    .arguments(incoming, 0)
                    .add(new ConstWideInstruction(0, 0L))
                    .add(new ReturnInstruction(0, 1))
                    .build();
        }
        if (isReference(descriptor)) {
            return new CodeBuilder()
                    .registers(incoming + 1)
                    .arguments(incoming, 0)
                    .add(new ConstInstruction(0, 0))
                    .add(new ReturnInstruction(0, 2))
                    .build();
        }
        return new CodeBuilder()
                .registers(incoming + 1)
                .arguments(incoming, 0)
                .add(new ConstInstruction(0, 0))
                .add(new ReturnInstruction(0, 0))
                .build();
    }

    static int parameterWords(MethodType type, boolean isStatic) {
        int words = isStatic ? 0 : 1;
        for (ClassType parameter : type.parameterTypes()) {
            String descriptor = parameter.descriptor();
            words += "J".equals(descriptor) || "D".equals(descriptor) ? 2 : 1;
        }
        return words;
    }

    private static boolean isReference(String descriptor) {
        return descriptor.startsWith("L") || descriptor.startsWith("[");
    }
}
