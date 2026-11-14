package me.naptila.recafmcp.mcp.tools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchNumbersToolTest {
    @Test
    void pagesAndFiltersInjectedHits() {
        SearchNumbersTool tool = new SearchNumbersTool(
                () -> true,
                () -> null,
                (workspace, request) -> new SearchNumbersTool.SearchOutcome(
                        List.of(
                                hit(7, "z/Last", "run", 3),
                                hit(7, "a/First", "load", 1),
                                hit(42, "a/First", "load", 2)
                        ),
                        true
                )
        );
        JsonObject arguments = new JsonObject();
        arguments.addProperty("value", "7");
        arguments.addProperty("offset", 0);
        arguments.addProperty("limit", 1);
        arguments.addProperty("filter", "a/First");

        JsonObject result = tool.execute(arguments);
        JsonObject structured = result.getAsJsonObject("structuredContent");

        assertFalse(result.get("isError").getAsBoolean());
        assertEquals("equals", structured.get("mode").getAsString());
        assertEquals(2, structured.get("total").getAsInt());
        assertEquals(1, structured.get("returned").getAsInt());
        assertEquals(1, structured.get("next_offset").getAsInt());
        assertTrue(structured.get("collection_truncated").getAsBoolean());
        assertEquals(
                "load",
                structured.getAsJsonArray("hits").get(0).getAsJsonObject()
                        .get("containing_member_name").getAsString()
        );
    }

    @Test
    void parsesRangeAndAnyOfModes() {
        SearchNumbersTool tool = new SearchNumbersTool(
                () -> false,
                () -> null,
                (workspace, request) -> new SearchNumbersTool.SearchOutcome(List.of(), false)
        );
        JsonObject range = new JsonObject();
        range.addProperty("value", "1");
        range.addProperty("mode", "range");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(range));

        range.addProperty("value2", "9");
        JsonObject missingWorkspace = tool.execute(range);
        assertTrue(missingWorkspace.get("isError").getAsBoolean());

        JsonObject anyOf = new JsonObject();
        anyOf.addProperty("value", "1");
        anyOf.addProperty("mode", "any_of");
        anyOf.addProperty("values", "1,2,3");
        assertTrue(tool.execute(anyOf).get("isError").getAsBoolean());

        JsonObject badMode = new JsonObject();
        badMode.addProperty("value", "1");
        badMode.addProperty("mode", "approximate");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(badMode));

        JsonObject nonNumber = new JsonObject();
        nonNumber.addProperty("value", "abc");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(nonNumber));
    }

    private static SearchNumbersTool.NumberHit hit(int value,
                                                   String className,
                                                   String memberName,
                                                   int instructionIndex) {
        return new SearchNumbersTool.NumberHit(
                value,
                className,
                "jvm",
                null,
                "method",
                memberName,
                "()V",
                instructionIndex
        );
    }
}
