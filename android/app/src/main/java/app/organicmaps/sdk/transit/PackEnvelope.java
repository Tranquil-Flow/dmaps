package app.organicmaps.sdk.transit;

import com.google.common.io.BaseEncoding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Bounded wire/authority check only, not payload, storage or rollback validation. */
final class PackEnvelope {
  static final int READER_VERSION = 2;
  private static final byte[] DOMAIN =
      "DMaps timetable-pack envelope v2\0".getBytes(StandardCharsets.UTF_8);
  private static final Set<String> OUTER = new HashSet<String>(Arrays.asList(
      "envelope_version", "metadata", "signature"));
  private static final Set<String> BODY = new HashSet<String>(Arrays.asList(
      "pack", "namespace", "channel", "pack_id", "sequence", "key_id", "suite",
      "reader_min", "reader_max", "issued_utc", "expires_utc"));
  private PackEnvelope() {}

  static final class Result {
    final Map<String, Object> manifest;
    final ImportMode mode;
    final boolean authenticated;
    final String namespace, channel, packId, keyId, signedMetadataSha256, envelopeSha256;
    final long sequence;
    final Instant authorizationExpires;
    private final byte[] envelope, metadata;

    private Result(Map<String, Object> manifest, ImportMode mode, boolean authenticated,
                   String namespace, String channel, String packId, String keyId,
                   long sequence, Instant expires, byte[] envelope, byte[] metadata)
        throws IOException {
      // StrictPackJson recursively owns and freezes every map/list it returns.
      this.manifest = manifest;
      this.mode = mode;
      this.authenticated = authenticated;
      this.namespace = namespace;
      this.channel = channel;
      this.packId = packId;
      this.keyId = keyId;
      this.sequence = sequence;
      this.authorizationExpires = expires;
      this.envelope = envelope.clone();
      this.metadata = metadata == null ? null : metadata.clone();
      this.envelopeSha256 = sha256(envelope);
      this.signedMetadataSha256 = metadata == null ? null : sha256(metadata);
    }
    byte[] rawEnvelope() { return envelope.clone(); }
    byte[] signedMetadata() { return metadata == null ? null : metadata.clone(); }
  }

  static Result decode(byte[] raw, ImportMode mode, PackTrustPolicy policy, Instant now)
      throws IOException {
    if (raw == null || mode == null || policy == null || now == null || raw.length > 32768)
      throw invalid();
    byte[] owned = raw.clone();
    Map<String, Object> envelope = StrictPackJson.object(owned);
    if (!envelope.containsKey("envelope_version")) {
      if (mode != ImportMode.USER_UNTRUSTED)
        throw new IOException("Official signature required");
      pack(envelope);
      return new Result(envelope, mode, false, null, null, null, null,
          0, null, owned, null);
    }
    if (!envelope.keySet().equals(OUTER)) throw invalid();
    number(envelope.get("envelope_version"), 2, 2);
    byte[] metadata = base64(envelope.get("metadata"));
    byte[] signature = base64(envelope.get("signature"));
    if (signature.length != 64) throw invalid();
    Map<String, Object> body = StrictPackJson.object(metadata);
    if (!body.keySet().equals(BODY)) throw invalid();
    Map<String, Object> manifest = object(body.get("pack"));
    pack(manifest);
    String namespace = id(body.get("namespace"));
    String channel = id(body.get("channel"));
    String packId = id(body.get("pack_id"));
    String keyId = id(body.get("key_id"));
    long sequence = number(body.get("sequence"), 1, Long.MAX_VALUE);
    if (!"Ed25519".equals(body.get("suite"))) throw invalid();
    long minReader = number(body.get("reader_min"), 1, Integer.MAX_VALUE);
    long maxReader = number(body.get("reader_max"), 1, Integer.MAX_VALUE);
    if (minReader > maxReader || READER_VERSION < minReader || READER_VERSION > maxReader)
      throw new IOException("Unsupported pack reader range");
    Instant issued = instant(body.get("issued_utc"));
    Instant expires = instant(body.get("expires_utc"));
    if (!issued.isBefore(expires)) throw invalid();
    boolean authenticated = mode == ImportMode.OFFICIAL_REQUIRED;
    if (authenticated) {
      if (now.isBefore(issued) || !now.isBefore(expires))
        throw new IOException("Pack authorization outside validity window");
      if (!policy.authenticate(keyId, namespace, channel, packId,
          signingBytes(metadata), signature))
        throw new IOException("Pack signature refused");
    }
    return new Result(manifest, mode, authenticated, namespace, channel, packId, keyId,
        sequence, expires, owned, metadata);
  }

  static byte[] signingBytes(byte[] rawMetadata) {
    byte[] result = Arrays.copyOf(DOMAIN, DOMAIN.length + rawMetadata.length);
    System.arraycopy(rawMetadata, 0, result, DOMAIN.length, rawMetadata.length);
    return result;
  }

  private static byte[] base64(Object value) throws IOException {
    if (!(value instanceof String)) throw invalid();
    String text = (String) value;
    try {
      byte[] decoded = BaseEncoding.base64().decode(text);
      if (!BaseEncoding.base64().encode(decoded).equals(text)) throw invalid();
      return decoded;
    } catch (IllegalArgumentException failure) { throw invalid(); }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) throws IOException {
    if (!(value instanceof Map)) throw invalid();
    return (Map<String, Object>) value;
  }

  private static String text(Object value) throws IOException {
    if (!(value instanceof String) || ((String) value).isEmpty()) throw invalid();
    return (String) value;
  }

  private static String id(Object value) throws IOException {
    String result = text(value);
    if (!PackTrustPolicy.identifier(result)) throw invalid();
    return result;
  }

  private static long number(Object value, long min, long max) throws IOException {
    if (!(value instanceof Long)) throw invalid();
    long n = (Long) value;
    if (n < min || n > max) throw invalid();
    return n;
  }

  private static Instant instant(Object value) throws IOException {
    String spelling = text(value);
    try {
      Instant parsed = Instant.parse(spelling);
      if (!parsed.toString().equals(spelling)) throw invalid();
      return parsed;
    } catch (DateTimeParseException failure) { throw invalid(); }
  }

  private static void pack(Map<String, Object> value) throws IOException {
    long format = number(value.get("format_version"), 1, 2);
    if (!"vbb".equals(value.get("publisher_id")) || !"CC BY 4.0".equals(value.get("license")))
      throw invalid();
    for (String name : Arrays.asList("publisher", "source_url", "license_url", "attribution",
        "created_utc", "timezone", "valid_from", "valid_until")) text(value.get(name));
    for (String name : Arrays.asList("source_sha256", "database_sha256"))
      if (!text(value.get(name)).matches("[0-9a-f]{64}")) throw invalid();
    number(value.get("database_bytes"), 0, 2147483648L);
    number(value.get("max_departure_seconds"), 0, 604800);
    if (format == 2) number(value.get("max_arrival_seconds"), 0, 604800);
    object(value.get("counts"));
  }

  private static String sha256(byte[] bytes) throws IOException {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
      char[] result = new char[hash.length * 2];
      final char[] hex = "0123456789abcdef".toCharArray();
      for (int i = 0; i < hash.length; i++) {
        result[i * 2] = hex[(hash[i] & 255) >>> 4];
        result[i * 2 + 1] = hex[hash[i] & 15];
      }
      return new String(result);
    } catch (NoSuchAlgorithmException failure) { throw new IOException("Digest unavailable"); }
  }
  private static IOException invalid() { return new IOException("Invalid envelope"); }
}
