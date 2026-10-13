package sml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 编解码（零第三方依赖）。
 *
 * 本绑定只用它做「宿主对象 ⇄ JSON 文本」的桥接（值经 JSON 与 SML 互转）；
 * 不追求完整 JSON 特性，覆盖 SML 产出 JSON 的形态即可：对象 / 数组 / 字符串 /
 * 数字（整数→Long，含小数或指数→Double）/ 布尔 / null。
 */
final class Json {

    private Json() {}

    // ---------------------------------------------------------------- parse
    static Object parse(String s) {
        return new Parser(s).parseValue();
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object parseValue() {
            skipWs();
            if (i >= s.length()) throw new RuntimeException("json: unexpected end of input");
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't') { expect("true"); return Boolean.TRUE; }
            if (c == 'f') { expect("false"); return Boolean.FALSE; }
            if (c == 'n') { expect("null"); return null; }
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { i++; return m; }
            while (true) {
                skipWs();
                String k = parseString();
                skipWs();
                expect(':');
                m.put(k, parseValue());
                skipWs();
                char c = next();
                if (c == '}') break;
                if (c != ',') throw new RuntimeException("json: expected ',' or '}'");
            }
            return m;
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> a = new ArrayList<>();
            skipWs();
            if (peek() == ']') { i++; return a; }
            while (true) {
                a.add(parseValue());
                skipWs();
                char c = next();
                if (c == ']') break;
                if (c != ',') throw new RuntimeException("json: expected ',' or ']'");
            }
            return a;
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new RuntimeException("json: unterminated string");
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            String hex = s.substring(i, i + 4);
                            i += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default: throw new RuntimeException("json: bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Object parseNumber() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) i++;
                else break;
            }
            String num = s.substring(start, i);
            if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
                return Double.parseDouble(num);
            }
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException ex) {
                return Double.parseDouble(num);
            }
        }

        private void expect(char c) {
            if (next() != c) throw new RuntimeException("json: expected '" + c + "'");
        }

        private void expect(String w) {
            for (char c : w.toCharArray()) expect(c);
        }

        private char next() {
            if (i >= s.length()) throw new RuntimeException("json: unexpected end of input");
            return s.charAt(i++);
        }

        private char peek() {
            if (i >= s.length()) throw new RuntimeException("json: unexpected end of input");
            return s.charAt(i);
        }

        private void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }
    }

    // ------------------------------------------------------------- stringify
    static String stringify(Object o) {
        StringBuilder sb = new StringBuilder();
        write(o, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object o, StringBuilder sb) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!first) sb.append(',');
                writeString(e.getKey(), sb);
                sb.append(':');
                write(e.getValue(), sb);
                first = false;
            }
            sb.append('}');
        } else if (o instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object v : (List<Object>) o) {
                if (!first) sb.append(',');
                write(v, sb);
                first = false;
            }
            sb.append(']');
        } else if (o instanceof String) {
            writeString((String) o, sb);
        } else if (o instanceof Boolean) {
            sb.append(((Boolean) o) ? "true" : "false");
        } else if (o instanceof Number) {
            if (o instanceof Double || o instanceof Float) {
                double d = ((Number) o).doubleValue();
                if (!Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d)) {
                    sb.append(Long.toString((long) d));
                } else {
                    sb.append(o.toString());
                }
            } else {
                sb.append(o.toString());
            }
        } else {
            throw new RuntimeException("json: cannot serialize " + o.getClass());
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
