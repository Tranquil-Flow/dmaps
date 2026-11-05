package app.organicmaps.sdk.transit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Arrays;

/**
 * App-private disk trust lifecycle. Every method serializes recovery as well as writes. Callers must
 * additionally hold acquire() over the entire staging/SQLite-validation/install sequence. SQLite
 * schema validity is the repository's responsibility; this store verifies bytes, current authority
 * and persisted history, never a publisher label. No network or production signing capability.
 */
final class PackTrustStore {
  private static final int LIMIT = 32768;
  private static final byte[] MAGIC = "state-v1\n".getBytes(StandardCharsets.US_ASCII);
  private final File directory, state, marker, manifest, database;
  private final PackTrustPolicy policy;
  private final Clock clock;
  private final PackActivation activation;
  private final PackActivation.FaultInjector faults;

  PackTrustStore(File directory, PackTrustPolicy policy, Clock clock) {
    this(directory, policy, clock, point -> {});
  }
  /** Test-only clock/root/fault composition; never selected by metadata, intents or user files. */
  PackTrustStore(File directory, PackTrustPolicy policy, Clock clock, PackActivation.FaultInjector faults) {
    if (directory == null || policy == null || clock == null || faults == null)
      throw new IllegalArgumentException("Trust store dependencies are required");
    this.directory = directory; this.policy = policy; this.clock = clock; this.faults = faults;
    state = file("trust-state.json"); marker = file("trust.initialized");
    manifest = file("manifest.json"); database = file("timetable.sqlite");
    activation = new PackActivation(directory, faults);
  }
  private File file(String name) { return new File(directory, name); }
  PackDirectory.Guard acquire() throws IOException { return new PackDirectory(directory).acquire(); }

  /** Load history even when the active pack's signer has expired/been removed. Never reset it. */
  PackTrustState load() throws IOException {
    try (PackDirectory.Guard ignored = acquire()) { return loadLocked(); }
  }
  private PackTrustState loadLocked() throws IOException {
    activation.recover();
    if (marker.exists()) {
      if (!Arrays.equals(MAGIC, read(marker, 32))) throw unavailable();
      return PackTrustState.parse(read(state, LIMIT)); // Missing/corrupt is unavailable, not genesis.
    }
    PackTrustState baseline;
    if (state.exists()) {
      baseline = PackTrustState.parse(read(state, LIMIT));
      // Only the known pre-marker bootstrap generation can resume, never authenticated history.
      if (!baseline.history.isEmpty()) throw unavailable();
      PackTrustState expected = legacyBaseline();
      if (expected.active == null) { if (baseline.active != null) throw unavailable(); }
      else {
        if (baseline.active == null || baseline.active.mode != ImportMode.USER_UNTRUSTED) throw unavailable();
        baseline.requireActive(decode(read(manifest, LIMIT), ImportMode.USER_UNTRUSTED));
      }
    } else {
      if (file("backup.trust-state.json").exists() || file("staging.trust-state.json").exists())
        throw unavailable();
      baseline = legacyBaseline();
      publish(file("trust-state.initializing"), state, baseline.encode());
      faults.at("bootstrap-state-published");
    }
    // Publishing state first makes every interrupted prefix retryable without resetting a floor.
    publish(file("trust.initializing"), marker, MAGIC);
    return baseline;
  }
  private PackTrustState legacyBaseline() throws IOException {
    if (database.exists() != manifest.exists()) throw unavailable();
    if (!database.exists()) return PackTrustState.empty();
    byte[] raw = read(manifest, LIMIT);
    if (StrictPackJson.object(raw).containsKey("envelope_version")) throw unavailable();
    PackEnvelope.Result legacy = decode(raw, ImportMode.USER_UNTRUSTED);
    verifyPayload(database, legacy);
    return PackTrustState.empty().withPack(legacy);
  }
  private PackEnvelope.Result decode(byte[] raw, ImportMode mode) throws IOException {
    return PackEnvelope.decode(raw, mode, policy, clock.instant());
  }

  /** Recheck authority/binding on every use; hash bytes on connection open or generation change. */
  PackEnvelope.Result verifiedActive(boolean hashPayload) throws IOException {
    try (PackDirectory.Guard ignored = acquire()) {
      PackTrustState current = loadLocked();
      if (current.active == null) throw unavailable();
      PackEnvelope.Result result = decode(read(manifest, LIMIT), current.active.mode);
      current.requireActive(result);
      if (!database.isFile() || database.length() != current.active.databaseBytes) throw unavailable();
      if (hashPayload) verifyPayload(database, result);
      return result;
    }
  }

  /** Caller has validated staged SQLite/schema while holding acquire() over the whole operation. */
  void install(PackEnvelope.Result admitted) throws IOException {
    boolean committed = false;
    try (PackDirectory.Guard ignored = acquire()) {
      PackTrustState current = loadLocked();
      if (admitted == null) throw unavailable();
      PackEnvelope.Result fresh = decode(read(file("staging.manifest.json"), LIMIT), admitted.mode);
      if (!fresh.envelopeSha256.equals(admitted.envelopeSha256)) throw unavailable();
      verifyPayload(file("staging.timetable.sqlite"), fresh);
      PackTrustState next = current.withPack(fresh);
      write(file("staging.trust-state.json"), next.encode());
      activation.activateWithState(() -> {
        // Last fallible check before the journal-removal commit point, not before a long wait/copy.
        next.requireActive(decode(read(manifest, LIMIT), fresh.mode));
      });
      committed = true;
      faults.at("store-import-committed");
    } catch (IOException failure) {
      if (committed) throw new PackCommitException(PackCommitException.Operation.IMPORT);
      throw failure;
    }
  }
  void remove() throws IOException {
    boolean committed = false;
    try (PackDirectory.Guard ignored = acquire()) {
      PackTrustState current = loadLocked();
      write(file("staging.trust-state.json"), current.withoutPack().encode());
      activation.removeWithState();
      committed = true;
      faults.at("store-remove-committed");
    } catch (IOException failure) {
      if (committed) throw new PackCommitException(PackCommitException.Operation.REMOVE);
      throw failure;
    }
  }

  private static byte[] read(File file, int limit) throws IOException {
    if (!file.isFile() || file.length() > limit) throw unavailable();
    try (InputStream input = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] block = new byte[4096]; int n;
      while ((n = input.read(block)) != -1) {
        if (n > limit - out.size()) throw unavailable();
        out.write(block, 0, n);
      }
      return out.toByteArray();
    } catch (IOException failure) { throw unavailable(); }
  }
  private static void write(File file, byte[] bytes) throws IOException {
    if (file.exists() && !file.isFile()) throw unavailable();
    try (FileOutputStream output = new FileOutputStream(file)) {
      output.write(bytes); output.getFD().sync();
    } catch (IOException failure) { throw unavailable(); }
  }
  private static void publish(File temporary, File target, byte[] bytes) throws IOException {
    if (target.exists()) throw unavailable();
    write(temporary, bytes);
    if (!temporary.renameTo(target)) throw unavailable();
  }
  private static void verifyPayload(File file, PackEnvelope.Result result) throws IOException {
    long expected = (Long) result.manifest.get("database_bytes");
    if (!file.isFile() || file.length() != expected) throw unavailable();
    MessageDigest digest;
    try { digest = MessageDigest.getInstance("SHA-256"); }
    catch (NoSuchAlgorithmException impossible) { throw unavailable(); }
    long total = 0;
    try (InputStream input = new FileInputStream(file)) {
      byte[] block = new byte[32768]; int n;
      while ((n = input.read(block)) != -1) {
        total += n;
        if (total > expected) throw unavailable();
        digest.update(block, 0, n);
      }
    } catch (IOException failure) { throw unavailable(); }
    StringBuilder hex = new StringBuilder(64);
    for (byte b : digest.digest()) {
      hex.append("0123456789abcdef".charAt((b & 255) >>> 4));
      hex.append("0123456789abcdef".charAt(b & 15));
    }
    if (total != expected || !hex.toString().equals(result.manifest.get("database_sha256"))) throw unavailable();
  }
  private static IOException unavailable() { return new IOException("Pack trust storage unavailable"); }
}
