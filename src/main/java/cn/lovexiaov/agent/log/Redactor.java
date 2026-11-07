package cn.lovexiaov.agent.log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sensitive-data redactor.
 *
 * <p>Behavior (language-neutral contract):
 * <ul>
 *   <li>Default sensitive keys can NEVER be removed; {@link #addPaths} only appends.</li>
 *   <li>A whole value is masked when its key matches a sensitive key
 *       (case-insensitive, ignoring {@code _} and {@code -}; path-tail also matched).</li>
 *   <li>Free text is regex-redacted for {@code key=value} and
 *       {@code Authorization: Bearer x} patterns.</li>
 *   <li>String values inside nested objects/arrays are redacted recursively.</li>
 * </ul>
 */
public final class Redactor {

    public static final String MASK = "***REDACTED***";

    /** Case-insensitive, normalized (underscores/dashes removed). */
    public static final List<String> DEFAULT_SENSITIVE_KEYS = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(
                    "password", "passwd", "pwd", "token", "authorization", "auth", "cookie",
                    "secret", "apikey", "api_key", "accesstoken", "access_token",
                    "refreshtoken", "refresh_token", "credential", "privatekey",
                    "private_key", "ssn", "bearer"));

    private final java.util.Set<String> keys = new java.util.LinkedHashSet<>();

    public Redactor() {
        for (String k : DEFAULT_SENSITIVE_KEYS) {
            keys.add(norm(k));
        }
    }

    /** Append-only: add more sensitive paths/keys. Defaults are never removed. */
    public void addPaths(List<String> paths) {
        if (paths == null) {
            return;
        }
        for (String p : paths) {
            if (p != null && !p.isEmpty()) {
                keys.add(norm(p));
            }
        }
    }

    /** Current (immutable view) set of normalized sensitive keys. */
    public java.util.Set<String> keys() {
        return java.util.Collections.unmodifiableSet(keys);
    }

    /** Normalize: lowercase, ignore `_` and `-`. */
    private static String norm(String key) {
        return key.toLowerCase(java.util.Locale.ROOT).replace("-", "").replace("_", "");
    }

    /** True if the key (or its path tail) matches a sensitive key. */
    public boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        if (keys.contains(norm(key))) {
            return true;
        }
        int dot = key.lastIndexOf('.');
        if (dot >= 0) {
            return keys.contains(norm(key.substring(dot + 1)));
        }
        return false;
    }

    // ============================================================ free text

    private static final Pattern AUTH_BEARER =
            Pattern.compile("(?i)authorization\\s*:\\s*bearer\\s+\\S+");
    private static final Pattern KV =
            Pattern.compile("(?i)\\b([a-z0-9_\\-]+)\\s*=\\s*(\"?)([^\\s\"]*)\\2");

    /** Redact sensitive values inside free text (key=value / Authorization: Bearer). */
    public String redactString(String text) {
        if (text == null) {
            return null;
        }
        String t = AUTH_BEARER.matcher(text)
                .replaceAll(Matcher.quoteReplacement("Authorization: Bearer " + MASK));
        Matcher m = KV.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            if (isSensitiveKey(key)) {
                String replacement = key + "=" + MASK;
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ============================================================ structured

    /** Redact a payload object recursively (returns a new map). */
    public Map<String, Object> redactMap(Map<String, Object> in) {
        if (in == null) {
            return null;
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            if (isSensitiveKey(e.getKey())) {
                out.put(e.getKey(), MASK);
            } else {
                out.put(e.getKey(), redactValue(e.getValue()));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object redactValue(Object v) {
        if (v == null || v instanceof Boolean || v instanceof Number) {
            return v;
        }
        if (v instanceof String) {
            return redactString((String) v);
        }
        if (v instanceof Map) {
            return redactMap((Map<String, Object>) v);
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<Object>) v) {
                out.add(redactValue(item));
            }
            return out;
        }
        // Fallback: treat as string.
        return redactString(v.toString());
    }
}
