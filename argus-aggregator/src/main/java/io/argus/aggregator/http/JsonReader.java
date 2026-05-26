package io.argus.aggregator.http;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal flat-JSON-object reader for parsing POST request bodies.
 *
 * <p>Supports only single-level objects with string/number/boolean values.
 * Nested objects, arrays, and escape sequences beyond the basics are not
 * supported — POST bodies in this API contract are intentionally flat.
 */
public final class JsonReader {

    private JsonReader() {}

    /**
     * Parses a flat JSON object into a {@code Map<String,String>}. All scalar
     * values are returned as their raw text (strings without surrounding quotes,
     * numbers and booleans as their JSON text). Returns null if the input is
     * not parseable as a flat object.
     */
    public static Map<String, String> parseFlatObject(String body) {
        if (body == null) return null;
        String s = body.trim();
        if (s.isEmpty() || s.charAt(0) != '{' || s.charAt(s.length() - 1) != '}') {
            return null;
        }
        s = s.substring(1, s.length() - 1).trim();
        Map<String, String> out = new HashMap<>();
        int i = 0;
        while (i < s.length()) {
            // Skip whitespace
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= s.length()) break;

            if (s.charAt(i) != '"') return null;
            int keyEnd = findStringEnd(s, i + 1);
            if (keyEnd < 0) return null;
            String key = unescape(s.substring(i + 1, keyEnd));
            i = keyEnd + 1;

            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= s.length() || s.charAt(i) != ':') return null;
            i++;
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= s.length()) return null;

            String value;
            if (s.charAt(i) == '"') {
                int valEnd = findStringEnd(s, i + 1);
                if (valEnd < 0) return null;
                value = unescape(s.substring(i + 1, valEnd));
                i = valEnd + 1;
            } else {
                int valStart = i;
                while (i < s.length() && s.charAt(i) != ',' && !Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
                value = s.substring(valStart, i);
            }
            out.put(key, value);

            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            if (i < s.length() && s.charAt(i) == ',') i++;
        }
        return out;
    }

    private static int findStringEnd(String s, int from) {
        boolean esc = false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') return i;
        }
        return -1;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"':  sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case 'n':  sb.append('\n'); i++; break;
                    case 'r':  sb.append('\r'); i++; break;
                    case 't':  sb.append('\t'); i++; break;
                    case 'b':  sb.append('\b'); i++; break;
                    case 'f':  sb.append('\f'); i++; break;
                    default:   sb.append(c); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
