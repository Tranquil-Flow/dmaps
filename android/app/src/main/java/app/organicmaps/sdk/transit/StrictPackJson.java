package app.organicmaps.sdk.transit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict, bounded JSON wire reader. This does not authenticate a pack or validate
 * its schema. Limits are fixture wire-engineering limits, not security policy.
 * Root counts as a container and a value node; keys are not value nodes.
 * Instances and mutable backing collections never escape the parsing call.
 */
final class StrictPackJson {
  private static final int MAX_BYTES = 32768;
  private static final int MAX_DEPTH = 8;
  private static final int MAX_NODES = 2048;
  private static final int MAX_ITEMS = 256;

  private final String text;
  private int position;
  private int nodes;

  private StrictPackJson(String text) { this.text = text; }

  static Map<String, Object> object(byte[] utf8) throws IOException {
    if (utf8 == null) throw error("Invalid JSON");
    if (utf8.length > MAX_BYTES) throw error("Input limit");
    final String decoded;
    try {
      decoded = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(utf8)).toString();
    } catch (CharacterCodingException e) {
      // Do not retain decoder exceptions or input in diagnostics.
      throw error("Invalid UTF-8");
    }
    StrictPackJson parser = new StrictPackJson(decoded);
    parser.whitespace();
    if (!parser.peek('{')) throw error("Invalid JSON");
    // Handle root explicitly: it contributes one node and depth one.
    parser.nodes = 1;
    Map<String, Object> result = parser.readObject(1);
    parser.whitespace();
    if (parser.position != decoded.length()) throw error("Invalid JSON");
    return result;
  }

  private Object value(int parentDepth) throws IOException {
    whitespace();
    if (++nodes > MAX_NODES) throw error("Node limit");
    if (position == text.length()) throw error("Invalid JSON");
    char c = text.charAt(position);
    if (c == '{' || c == '[') {
      // Check before making another container call, including empty containers.
      if (parentDepth >= MAX_DEPTH) throw error("Depth limit");
      return c == '{' ? readObject(parentDepth + 1) : readArray(parentDepth + 1);
    }
    if (c == '"') return string();
    if (c == 't') { literal("true"); return Boolean.TRUE; }
    if (c == 'f') { literal("false"); return Boolean.FALSE; }
    if (c == 'n') { literal("null"); return null; }
    if (c == '-' || digit(c)) return number();
    throw error("Invalid JSON");
  }

  private Map<String, Object> readObject(int depth) throws IOException {
    take('{');
    Map<String, Object> fields = new LinkedHashMap<String, Object>();
    whitespace();
    if (consume('}')) return Collections.unmodifiableMap(fields);
    while (true) {
      if (fields.size() >= MAX_ITEMS) throw error("Container limit");
      String key = string();
      // containsKey, not get: a previous null value is still a duplicate.
      if (fields.containsKey(key)) throw error("Duplicate key");
      whitespace();
      take(':');
      fields.put(key, value(depth));
      whitespace();
      if (consume('}')) return Collections.unmodifiableMap(fields);
      take(',');
      whitespace();
      // The next iteration requires a quoted key, so trailing commas fail.
    }
  }

  private List<Object> readArray(int depth) throws IOException {
    take('[');
    List<Object> values = new ArrayList<Object>();
    whitespace();
    if (consume(']')) return Collections.unmodifiableList(values);
    while (true) {
      if (values.size() >= MAX_ITEMS) throw error("Container limit");
      values.add(value(depth));
      whitespace();
      if (consume(']')) return Collections.unmodifiableList(values);
      take(',');
    }
  }

  private String string() throws IOException {
    take('"');
    StringBuilder out = new StringBuilder();
    while (position < text.length()) {
      char c = text.charAt(position++);
      if (c == '"') return out.toString();
      if (c < 0x20) throw error("Invalid JSON");
      if (c == '\\') {
        if (position == text.length()) throw error("Invalid JSON");
        char escaped = text.charAt(position++);
        switch (escaped) {
          case '"': case '\\': case '/': out.append(escaped); break;
          case 'b': out.append('\b'); break;
          case 'f': out.append('\f'); break;
          case 'n': out.append('\n'); break;
          case 'r': out.append('\r'); break;
          case 't': out.append('\t'); break;
          case 'u':
            char unit = hexUnit();
            if (Character.isHighSurrogate(unit)) {
              take('\\');
              take('u');
              char low = hexUnit();
              if (!Character.isLowSurrogate(low)) throw error("Invalid JSON");
              out.append(unit).append(low);
            } else {
              if (Character.isLowSurrogate(unit)) throw error("Invalid JSON");
              out.append(unit);
            }
            break;
          default: throw error("Invalid JSON");
        }
      } else if (Character.isHighSurrogate(c)) {
        // REPORT decoding admits supplementary scalars as UTF-16 pairs only.
        if (position == text.length() || !Character.isLowSurrogate(text.charAt(position)))
          throw error("Invalid JSON");
        out.append(c).append(text.charAt(position++));
      } else {
        if (Character.isLowSurrogate(c)) throw error("Invalid JSON");
        out.append(c);
      }
    }
    throw error("Invalid JSON");
  }

  private char hexUnit() throws IOException {
    if (text.length() - position < 4) throw error("Invalid JSON");
    int unit = 0;
    for (int i = 0; i < 4; i++) {
      char c = text.charAt(position++);
      int nibble;
      if (c >= '0' && c <= '9') nibble = c - '0';
      else if (c >= 'a' && c <= 'f') nibble = c - 'a' + 10;
      else if (c >= 'A' && c <= 'F') nibble = c - 'A' + 10;
      else throw error("Invalid JSON");
      unit = (unit << 4) | nibble;
    }
    return (char) unit;
  }

  private Long number() throws IOException {
    int start = position;
    consume('-');
    if (consume('0')) {
      if (position < text.length() && digit(text.charAt(position)))
        throw error("Invalid JSON");
    } else {
      if (position == text.length() || text.charAt(position) < '1' || text.charAt(position) > '9')
        throw error("Invalid JSON");
      do { position++; } while (position < text.length() && digit(text.charAt(position)));
    }
    // Fractions, exponents and every other suffix fail at the container delimiter.
    // Reject unreasonable length before allocating the numeric substring.
    if (position - start > 20) throw error("Invalid JSON");
    try { return Long.valueOf(text.substring(start, position)); }
    catch (NumberFormatException e) { throw error("Invalid JSON"); }
  }

  private void literal(String expected) throws IOException {
    if (!text.startsWith(expected, position)) throw error("Invalid JSON");
    position += expected.length();
  }

  private void whitespace() {
    while (position < text.length()) {
      char c = text.charAt(position);
      if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return;
      position++;
    }
  }

  private boolean peek(char c) { return position < text.length() && text.charAt(position) == c; }
  private boolean consume(char c) {
    if (!peek(c)) return false;
    position++;
    return true;
  }
  private void take(char c) throws IOException { if (!consume(c)) throw error("Invalid JSON"); }
  private static boolean digit(char c) { return c >= '0' && c <= '9'; }
  private static IOException error(String message) { return new IOException(message); }
}
