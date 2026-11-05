package app.organicmaps.sdk.transit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Immutable local generation/history model. Not a signer or a disk store: callers must recover and
 * serialize the directory, freshly verify the envelope/current policy and validate actual payload.
 * Local state cannot defeat rooted edits or rollback of the entire app-private backup.
 */
final class PackTrustState {
  private static final int MAX_HISTORY = 128;
  private static final int MAX_BYTES = 32768;
  private static final Set<String> ROOT_FIELDS = fields("state_version", "active", "history");
  private static final Set<String> ACTIVE_FIELDS = fields("mode", "envelope_sha256", "database_sha256", "database_bytes");
  private static final Set<String> FLOOR_FIELDS = fields("sequence", "metadata_sha256");

  static final class Active {
    final ImportMode mode;
    final String envelopeSha256, databaseSha256;
    final long databaseBytes;
    private Active(ImportMode mode, String envelope, String database, long bytes) {
      this.mode = mode; envelopeSha256 = envelope; databaseSha256 = database; databaseBytes = bytes;
    }
  }
  static final class Floor {
    final long sequence;
    final String metadataSha256;
    private Floor(long sequence, String digest) { this.sequence = sequence; metadataSha256 = digest; }
  }
  final Active active;
  final Map<String, Floor> history;

  private PackTrustState(Active active, Map<String, Floor> history) {
    this.active = active;
    this.history = Collections.unmodifiableMap(new LinkedHashMap<>(history));
  }

  /** Genesis only; a disk reader must never use this to replace missing/corrupt initialized state. */
  static PackTrustState empty() { return new PackTrustState(null, Collections.emptyMap()); }

  static PackTrustState parse(byte[] bytes) throws IOException {
    Map<String, Object> root = StrictPackJson.object(bytes);
    if (!root.keySet().equals(ROOT_FIELDS)) throw invalid();
    number(root.get("state_version"), 1, 1);
    Active active = null;
    if (root.get("active") != null) {
      Map<String, Object> value = object(root.get("active"));
      if (!value.keySet().equals(ACTIVE_FIELDS)) throw invalid();
      ImportMode mode;
      if ("OFFICIAL_REQUIRED".equals(value.get("mode"))) mode = ImportMode.OFFICIAL_REQUIRED;
      else if ("USER_UNTRUSTED".equals(value.get("mode"))) mode = ImportMode.USER_UNTRUSTED;
      else throw invalid();
      active = new Active(mode, digest(value.get("envelope_sha256")), digest(value.get("database_sha256")),
          number(value.get("database_bytes"), 0, 2147483648L));
    }
    Map<String, Object> encoded = object(root.get("history"));
    if (encoded.size() > MAX_HISTORY) throw capacity();
    Map<String, Floor> history = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : encoded.entrySet()) {
      scope(entry.getKey());
      Map<String, Object> value = object(entry.getValue());
      if (!value.keySet().equals(FLOOR_FIELDS)) throw invalid();
      history.put(entry.getKey(), new Floor(number(value.get("sequence"), 1, Long.MAX_VALUE),
          digest(value.get("metadata_sha256"))));
    }
    if (active != null && active.mode == ImportMode.OFFICIAL_REQUIRED && history.isEmpty()) throw invalid();
    return new PackTrustState(active, history);
  }

  byte[] encode() throws IOException {
    // Every emitted string is a validated identifier/scope, hexadecimal digest or fixed enum.
    StringBuilder out = new StringBuilder("{\"state_version\":1,\"active\":");
    if (active == null) out.append("null");
    else out.append("{\"mode\":\"").append(active.mode.name())
        .append("\",\"envelope_sha256\":\"").append(active.envelopeSha256)
        .append("\",\"database_sha256\":\"").append(active.databaseSha256)
        .append("\",\"database_bytes\":").append(active.databaseBytes).append('}');
    out.append(",\"history\":{");
    boolean first = true;
    for (Map.Entry<String, Floor> entry : history.entrySet()) {
      if (!first) out.append(',');
      first = false;
      out.append('"').append(entry.getKey()).append("\":{\"sequence\":").append(entry.getValue().sequence)
          .append(",\"metadata_sha256\":\"").append(entry.getValue().metadataSha256).append("\"}");
    }
    byte[] bytes = out.append("}}").toString().getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_BYTES) throw capacity();
    return bytes;
  }

  PackTrustState withPack(PackEnvelope.Result result) throws IOException {
    if (result == null || (result.mode == ImportMode.OFFICIAL_REQUIRED) != result.authenticated) throw invalid();
    Map<String, Floor> next = new LinkedHashMap<>(history);
    if (result.authenticated) {
      String key = scope(result.namespace + "/" + result.channel + "/" + result.packId);
      Floor previous = history.get(key);
      if (previous != null && (result.sequence < previous.sequence
          || (result.sequence == previous.sequence && !previous.metadataSha256.equals(result.signedMetadataSha256))))
        throw new IOException("Pack rollback or sequence conflict refused");
      if (previous == null && next.size() >= MAX_HISTORY) throw capacity();
      next.put(key, new Floor(result.sequence, result.signedMetadataSha256));
    }
    Active active = new Active(result.mode, result.envelopeSha256, (String) result.manifest.get("database_sha256"),
        (Long) result.manifest.get("database_bytes"));
    PackTrustState candidate = new PackTrustState(active, next);
    candidate.encode(); // Refuse capacity before handing a state to the publication caller; never prune.
    return candidate;
  }

  PackTrustState withoutPack() { return new PackTrustState(null, history); }

  /** A fresh verifier Result is required; stored mode is not current root/signature authority. */
  void requireActive(PackEnvelope.Result result) throws IOException {
    if (active == null || result == null || active.mode != result.mode
        || !active.envelopeSha256.equals(result.envelopeSha256)
        || !active.databaseSha256.equals(result.manifest.get("database_sha256"))
        || active.databaseBytes != (Long) result.manifest.get("database_bytes")) throw invalid();
    if (result.authenticated) {
      Floor floor = history.get(scope(result.namespace + "/" + result.channel + "/" + result.packId));
      if (floor == null || floor.sequence != result.sequence
          || !floor.metadataSha256.equals(result.signedMetadataSha256)) throw invalid();
    }
  }

  private static String scope(String value) throws IOException {
    String[] parts = value.split("/", -1);
    if (parts.length != 3) throw invalid();
    for (String part : parts) if (!PackTrustPolicy.identifier(part)) throw invalid();
    return value;
  }
  private static String digest(Object value) throws IOException {
    if (!(value instanceof String) || !((String) value).matches("[0-9a-f]{64}")) throw invalid();
    return (String) value;
  }
  private static long number(Object value, long min, long max) throws IOException {
    if (!(value instanceof Long) || (Long) value < min || (Long) value > max) throw invalid();
    return (Long) value;
  }
  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) throws IOException {
    if (!(value instanceof Map)) throw invalid();
    return (Map<String, Object>) value;
  }
  private static Set<String> fields(String... names) { return new HashSet<>(Arrays.asList(names)); }
  private static IOException invalid() { return new IOException("Invalid pack trust state"); }
  private static IOException capacity() { return new IOException("Pack history capacity exceeded"); }
}
