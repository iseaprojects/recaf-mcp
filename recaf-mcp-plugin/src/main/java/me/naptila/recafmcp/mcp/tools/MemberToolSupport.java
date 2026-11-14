package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import software.coley.recaf.info.annotation.AnnotationElement;
import software.coley.recaf.info.annotation.AnnotationInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared member metadata, modifier, and access-flag helpers.
 */
final class MemberToolSupport {
    static final String KIND_ARGUMENT = "kind";
    static final String FIELD = "field";
    static final String METHOD = "method";
    static final String ANY = "any";

    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_PRIVATE = 0x0002;
    private static final int ACC_PROTECTED = 0x0004;
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_FINAL = 0x0010;
    private static final int ACC_SUPER = 0x0020;
    private static final int ACC_SYNCHRONIZED = 0x0020;
    private static final int ACC_VOLATILE = 0x0040;
    private static final int ACC_BRIDGE = 0x0040;
    private static final int ACC_TRANSIENT = 0x0080;
    private static final int ACC_VARARGS = 0x0080;
    private static final int ACC_NATIVE = 0x0100;
    private static final int ACC_INTERFACE = 0x0200;
    private static final int ACC_ABSTRACT = 0x0400;
    private static final int ACC_STRICT = 0x0800;
    private static final int ACC_SYNTHETIC = 0x1000;
    private static final int ACC_ANNOTATION = 0x2000;
    private static final int ACC_ENUM = 0x4000;
    private static final int ACC_MODULE = 0x8000;
    private static final int ACC_RECORD = 0x10000;

    private static final Map<String, Integer> CLASS_FLAGS = Map.ofEntries(
            Map.entry("public", ACC_PUBLIC),
            Map.entry("final", ACC_FINAL),
            Map.entry("super", ACC_SUPER),
            Map.entry("interface", ACC_INTERFACE),
            Map.entry("abstract", ACC_ABSTRACT),
            Map.entry("synthetic", ACC_SYNTHETIC),
            Map.entry("annotation", ACC_ANNOTATION),
            Map.entry("enum", ACC_ENUM),
            Map.entry("module", ACC_MODULE),
            Map.entry("record", ACC_RECORD)
    );

    private static final Map<String, Integer> COMMON_MEMBER_FLAGS = Map.ofEntries(
            Map.entry("public", ACC_PUBLIC),
            Map.entry("private", ACC_PRIVATE),
            Map.entry("protected", ACC_PROTECTED),
            Map.entry("static", ACC_STATIC),
            Map.entry("final", ACC_FINAL),
            Map.entry("synthetic", ACC_SYNTHETIC),
            Map.entry("enum", ACC_ENUM)
    );

    private static final Map<String, Integer> FIELD_FLAGS = Map.ofEntries(
            Map.entry("volatile", ACC_VOLATILE),
            Map.entry("transient", ACC_TRANSIENT)
    );

    private static final Map<String, Integer> METHOD_FLAGS = Map.ofEntries(
            Map.entry("synchronized", ACC_SYNCHRONIZED),
            Map.entry("bridge", ACC_BRIDGE),
            Map.entry("varargs", ACC_VARARGS),
            Map.entry("native", ACC_NATIVE),
            Map.entry("abstract", ACC_ABSTRACT),
            Map.entry("strict", ACC_STRICT)
    );

    private MemberToolSupport() {
    }

    static JsonObject toJson(ClassMember member, boolean includeAnnotations) {
        JsonObject json = new JsonObject();
        boolean method = member.isMethod();
        json.addProperty(KIND_ARGUMENT, method ? METHOD : FIELD);
        json.addProperty("name", member.getName());
        json.addProperty("descriptor", member.getDescriptor());
        addNullable(json, "signature", member.getSignature());
        json.addProperty("access", member.getAccess());
        json.add("modifiers", memberModifiers(member.getAccess(), method));
        if (includeAnnotations) {
            json.add("annotations", annotations(member.getAnnotations()));
        }
        if (member instanceof MethodMember methodMember) {
            JsonArray thrown = new JsonArray();
            methodMember.getThrownTypes().forEach(thrown::add);
            json.add("thrown_types", thrown);
            json.addProperty(
                    "local_variable_count",
                    methodMember.getLocalVariables().size()
            );
        }
        if (member instanceof FieldMember fieldMember) {
            json.add("constant_value", constantValue(fieldMember.getDefaultValue()));
        }
        return json;
    }

    static JsonArray annotations(List<AnnotationInfo> annotations) {
        JsonArray array = new JsonArray();
        annotations.forEach(annotation -> {
            JsonObject json = new JsonObject();
            json.addProperty("descriptor", annotation.getDescriptor());
            json.addProperty("type", classNameFromDescriptor(annotation.getDescriptor()));
            json.addProperty("visible", annotation.isVisible());
            JsonObject elements = new JsonObject();
            annotation.getElements().forEach((name, element) ->
                    elements.add(name, annotationValue(element)));
            json.add("elements", elements);
            array.add(json);
        });
        return array;
    }

    static JsonArray classModifiers(int access) {
        JsonArray modifiers = new JsonArray();
        CLASS_FLAGS.forEach((name, flag) -> addFlag(modifiers, access, flag, name));
        return modifiers;
    }

    static JsonArray memberModifiers(int access, boolean method) {
        JsonArray modifiers = new JsonArray();
        COMMON_MEMBER_FLAGS.forEach((name, flag) -> addFlag(modifiers, access, flag, name));
        Map<String, Integer> specific = method ? METHOD_FLAGS : FIELD_FLAGS;
        specific.forEach((name, flag) -> addFlag(modifiers, access, flag, name));
        return modifiers;
    }

    static int parseMemberAccess(String specification, boolean method) {
        return parseAccess(
                specification,
                method ? METHOD_FLAGS : FIELD_FLAGS,
                COMMON_MEMBER_FLAGS
        );
    }

    static int parseClassAccess(String specification) {
        return parseAccess(specification, CLASS_FLAGS, Map.of());
    }

    private static int parseAccess(String specification,
                                   Map<String, Integer> specific,
                                   Map<String, Integer> fallback) {
        String value = specification == null ? "" : specification.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("none")) {
            return 0;
        }
        int access = 0;
        for (String token : value.split("[,|\\s]+")) {
            String name = token.toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            Integer flag = specific.get(name);
            if (flag == null) {
                flag = fallback.get(name);
            }
            if (flag == null) {
                throw new IllegalArgumentException("unsupported access modifier: " + token);
            }
            access |= flag;
        }
        return access;
    }

    static String classNameFromDescriptor(String descriptor) {
        if (descriptor != null && descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.substring(1, descriptor.length() - 1);
        }
        return descriptor;
    }

    private static JsonElement annotationValue(AnnotationElement element) {
        return valueJson(element.getElementValue());
    }

    private static JsonElement valueJson(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        if (value instanceof Character character) {
            return new JsonPrimitive(character);
        }
        if (value instanceof String text) {
            return new JsonPrimitive(text);
        }
        if (value instanceof List<?> list) {
            JsonArray array = new JsonArray();
            list.forEach(item -> array.add(valueJson(item)));
            return array;
        }
        if (value.getClass().isArray()) {
            JsonArray array = new JsonArray();
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                array.add(valueJson(java.lang.reflect.Array.get(value, index)));
            }
            return array;
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    private static JsonElement constantValue(Object value) {
        return value == null ? JsonNull.INSTANCE : valueJson(value);
    }

    private static void addFlag(JsonArray modifiers, int access, int flag, String name) {
        if ((access & flag) != 0) {
            modifiers.add(name);
        }
    }

    private static void addNullable(JsonObject target, String name, String value) {
        if (value == null) {
            target.add(name, JsonNull.INSTANCE);
        } else {
            target.addProperty(name, value);
        }
    }
}
