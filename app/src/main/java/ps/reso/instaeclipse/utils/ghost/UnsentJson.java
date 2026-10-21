package ps.reso.instaeclipse.utils.ghost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for the message copies Instagram's own codec writes (UTF-8 JSON text).
 * Objects become maps, arrays lists, numbers doubles. Free of org.json, which plain unit tests
 * lack. Malformed or too deeply nested input throws IllegalArgumentException.
 */
final class UnsentJson {
    private static final int MAX_DEPTH = 128;

    private final String s;
    private int i;

    private UnsentJson(String s) { this.s = s; }

    static Object parse(String json) {
        if (json == null) throw new IllegalArgumentException("null");
        UnsentJson reader = new UnsentJson(json);
        Object value = reader.value(0);
        reader.space();
        if (reader.i != json.length()) throw reader.error("trailing data");
        return value;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw error("too deep");
        space();
        if (i >= s.length()) throw error("end of input");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': return word("true", Boolean.TRUE);
            case 'f': return word("false", Boolean.FALSE);
            case 'n': return word("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("unexpected '" + c + "'");
        }
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        i++; // {
        space();
        if (peek() == '}') { i++; return map; }
        while (true) {
            space();
            if (peek() != '"') throw error("key expected");
            String key = string();
            space();
            if (peek() != ':') throw error("':' expected");
            i++;
            map.put(key, value(depth + 1));
            space();
            char c = peek();
            i++;
            if (c == '}') return map;
            if (c != ',') throw error("',' or '}' expected");
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        i++; // [
        space();
        if (peek() == ']') { i++; return list; }
        while (true) {
            list.add(value(depth + 1));
            space();
            char c = peek();
            i++;
            if (c == ']') return list;
            if (c != ',') throw error("',' or ']' expected");
        }
    }

    private String string() {
        i++; // opening quote
        StringBuilder b = null;
        int start = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                String out = b == null ? s.substring(start, i) : b.append(s, start, i).toString();
                i++;
                return out;
            }
            if (c != '\\') { i++; continue; }
            if (b == null) b = new StringBuilder();
            b.append(s, start, i);
            if (++i >= s.length()) break;
            char e = s.charAt(i++);
            switch (e) {
                case '"': case '\\': case '/': b.append(e); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw error("bad \\u escape");
                    try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); }
                    catch (NumberFormatException ex) { throw error("bad \\u escape"); }
                    i += 4;
                    break;
                default: throw error("bad escape");
            }
            start = i;
        }
        throw error("unterminated string");
    }

    private Double number() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        try { return Double.valueOf(s.substring(start, i)); }
        catch (NumberFormatException e) { throw error("bad number"); }
    }

    private Object word(String word, Object value) {
        if (!s.startsWith(word, i)) throw error("unexpected token");
        i += word.length();
        return value;
    }

    private void space() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return;
            i++;
        }
    }

    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at " + i);
    }
}
