package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import me.darknet.dex.tree.definitions.constant.Constant;
import me.darknet.dex.tree.definitions.constant.DoubleConstant;
import me.darknet.dex.tree.definitions.constant.FloatConstant;
import me.darknet.dex.tree.definitions.constant.IntConstant;
import me.darknet.dex.tree.definitions.constant.LongConstant;
import me.darknet.dex.tree.definitions.constant.StringConstant;
import org.objectweb.asm.tree.AnnotationNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared annotation descriptor, target, and value conversion helpers.
 */
final class AnnotationToolSupport {
    static final String CLASS_TARGET = "class";
    static final String FIELD_TARGET = "field";
    static final String METHOD_TARGET = "method";
    static final Set<String> TARGETS = Set.of(CLASS_TARGET, FIELD_TARGET, METHOD_TARGET);

    private AnnotationToolSupport() {
    }

    static String descriptor(String value) {
        String descriptor = value.trim();
        if (descriptor.startsWith("L") && descriptor.endsWith(";") && descriptor.length() > 2) {
            String internal = descriptor.substring(1, descriptor.length() - 1);
            if (internal.indexOf(';') < 0 && internal.indexOf('[') < 0) {
                return descriptor;
            }
        }
        if (descriptor.startsWith("@")) {
            return descriptor(descriptor.substring(1));
        }
        if (descriptor.indexOf('/') >= 0 || descriptor.indexOf('.') >= 0) {
            return "L" + descriptor.replace('.', '/') + ";";
        }
        throw new IllegalArgumentException(
                "annotation must be a descriptor such as Lkotlin/Metadata; or a class name"
        );
    }

    static String internalName(String descriptor) {
        return descriptor.substring(1, descriptor.length() - 1);
    }

    static AnnotationNode node(String descriptor, JsonObject elements) {
        AnnotationNode node = new AnnotationNode(descriptor);
        if (elements != null && !elements.isEmpty()) {
            List<Object> values = new ArrayList<>();
            for (Map.Entry<String, JsonElement> element : elements.entrySet()) {
                String name = element.getKey();
                if (name.isBlank()) {
                    throw new IllegalArgumentException("annotation element names must be nonblank");
                }
                values.add(name);
                values.add(asmValue(element.getValue()));
            }
            node.values = values;
        }
        return node;
    }

    static Map<String, Constant> dexElements(JsonObject elements) {
        if (elements == null || elements.isEmpty()) {
            return Map.of();
        }
        Map<String, Constant> values = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> element : elements.entrySet()) {
            values.put(element.getKey(), dexValue(element.getValue()));
        }
        return values;
    }

    static byte dexVisibility(boolean visible) {
        return (byte) (visible ? 1 : 0);
    }

    private static Object asmValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("annotation element values must not be null");
        }
        if (element.isJsonArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                values.add(asmValue(item));
            }
            return values;
        }
        if (element.isJsonObject()) {
            throw new IllegalArgumentException(
                    "annotation element values must be primitives, strings, or arrays"
            );
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isString()) {
            return primitive.getAsString();
        }
        String raw = primitive.getAsString();
        if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0 || raw.indexOf('E') >= 0) {
            return Double.parseDouble(raw);
        }
        java.math.BigInteger integer = new java.math.BigInteger(raw);
        if (integer.bitLength() < 32) {
            return integer.intValue();
        }
        return integer.longValueExact();
    }

    private static Constant dexValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("annotation element values must not be null");
        }
        if (element.isJsonArray() || element.isJsonObject()) {
            throw new IllegalArgumentException(
                    "DEX annotation element values must be primitives or strings"
            );
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return new IntConstant(primitive.getAsBoolean() ? 1 : 0);
        }
        if (primitive.isString()) {
            return new StringConstant(primitive.getAsString());
        }
        String raw = primitive.getAsString().toLowerCase(Locale.ROOT);
        if (raw.indexOf('.') >= 0 || raw.indexOf('e') >= 0) {
            String suffix = raw.endsWith("f") ? raw.substring(0, raw.length() - 1) : raw;
            if (raw.endsWith("f")) {
                return new FloatConstant(Float.parseFloat(suffix));
            }
            return new DoubleConstant(Double.parseDouble(raw));
        }
        java.math.BigInteger integer = new java.math.BigInteger(raw);
        if (integer.bitLength() < 32) {
            return new IntConstant(integer.intValue());
        }
        return new LongConstant(integer.longValueExact());
    }

    static JsonArray elementsJson(Map<String, ?> elements, boolean dex) {
        JsonArray array = new JsonArray();
        elements.forEach((name, value) -> {
            JsonObject element = new JsonObject();
            element.addProperty("name", name);
            element.add("value", valueJson(value, dex));
            array.add(element);
        });
        return array;
    }

    private static JsonElement valueJson(Object value, boolean dex) {
        if (value == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        if (value instanceof Number || value instanceof Boolean
                || value instanceof String || value instanceof Character) {
            return new JsonPrimitive((value instanceof Character character)
                    ? String.valueOf(character) : value.toString());
        }
        if (dex && value instanceof Constant constant) {
            return new JsonPrimitive(constant.toString());
        }
        if (value instanceof List<?> list) {
            JsonArray array = new JsonArray();
            list.forEach(item -> array.add(valueJson(item, dex)));
            return array;
        }
        return new JsonPrimitive(String.valueOf(value));
    }
}
