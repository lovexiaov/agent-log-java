package com.scaffold.agentlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class RedactorTests {

    @Test
    public void defaultKeysMasked() {
        Redactor r = new Redactor();
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("password", "hunter2");
        in.put("token", "abc");
        in.put("access_token", "tok123");
        Map<String, Object> out = r.redactMap(in);

        assertEquals(Redactor.MASK, out.get("password"));
        assertEquals(Redactor.MASK, out.get("token"));
        assertEquals(Redactor.MASK, out.get("access_token"));
    }

    @Test
    public void nonSensitiveKeyUnchanged() {
        Redactor r = new Redactor();
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("host", "example.com");
        in.put("count", 3);
        Map<String, Object> out = r.redactMap(in);
        assertEquals("example.com", out.get("host"));
        assertEquals(3, out.get("count"));
    }

    @Test
    public void regexOnFreeText() {
        Redactor r = new Redactor();
        String t1 = r.redactString("password=hunter2 and user=bob");
        assertTrue(t1.contains(Redactor.MASK), "password value must be masked");
        assertFalse(t1.contains("hunter2"), "raw password must not remain");
        assertTrue(t1.contains("user=bob"), "non-sensitive key untouched");

        String t2 = r.redactString("Authorization: Bearer secret-token-xyz");
        assertTrue(t2.contains(Redactor.MASK), "bearer token must be masked");
        assertFalse(t2.contains("secret-token-xyz"), "raw bearer token must not remain");
    }

    @Test
    public void recursiveMaskInsideObjectsAndArrays() {
        Redactor r = new Redactor();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("secret", "top");
        Map<String, Object> deep = new LinkedHashMap<>();
        deep.put("password", "inner");
        nested.put("config", deep);
        nested.put("list", Arrays.asList(
                new LinkedHashMap<String, Object>() {{
                    put("api_key", "k1");
                }},
                "plain"));
        Map<String, Object> out = r.redactMap(nested);

        assertEquals(Redactor.MASK, out.get("secret"));
        assertTrue(out.get("config") instanceof Map);
        assertEquals(Redactor.MASK, ((Map<?, ?>) out.get("config")).get("password"));
        assertTrue(out.get("list") instanceof List);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<Object>) out.get("list")).get(0);
        assertEquals(Redactor.MASK, first.get("api_key"));
        assertEquals("plain", ((List<?>) out.get("list")).get(1));
    }

    @Test
    public void appendOnlyRedact() {
        Redactor r = new Redactor();
        int defaults = r.keys().size();
        assertTrue(defaults > 0, "must have default keys");
        assertTrue(r.keys().contains("password"), "default password must be present");

        r.addPaths(List.of("customSecret", "myKey"));
        assertEquals(defaults + 2, r.keys().size(), "addPaths must append");

        // defaults can never be removed — re-adding default has no effect on size
        int before = r.keys().size();
        r.addPaths(List.of("password"));
        assertEquals(before, r.keys().size(), "re-adding default must not change size");

        // the appended key actually masks
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("customSecret", "x");
        Map<String, Object> out = r.redactMap(in);
        assertEquals(Redactor.MASK, out.get("customSecret"));
    }

    @Test
    public void pathTailMatching() {
        Redactor r = new Redactor();
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("user.password", "x"); // tail "password" -> sensitive
        Map<String, Object> out = r.redactMap(in);
        assertEquals(Redactor.MASK, out.get("user.password"));
    }
}
