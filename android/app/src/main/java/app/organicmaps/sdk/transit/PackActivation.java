package app.organicmaps.sdk.transit;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Single-writer, process-crash-recoverable pack replacement, including exact manifest and opaque
 * trust/history state. Legacy pair operations are refused once trust state is present. A synced
 * journal precedes destructive renames; removing it commits the whole generation. Recovery is
 * idempotent even if interrupted. This is not arbitrary power-loss durability on every filesystem.
 */
final class PackActivation
{
  private final File db, manifest, stagedDb, stagedManifest, backupDb, backupManifest, pending, preparing;
  private final File trust, stagedTrust, backupTrust, initialized;
  interface FaultInjector { void at(String checkpoint) throws IOException; }
  private final FaultInjector faults;

  PackActivation(File directory) { this(directory, checkpoint -> {}); }

  /** Package-private fault seam; never selected by pack data or an application intent. */
  PackActivation(File directory, FaultInjector faults)
  {
    if (faults == null) throw new IllegalArgumentException("fault injector is required");
    this.faults = faults;
    db = new File(directory, "timetable.sqlite"); manifest = new File(directory, "manifest.json");
    stagedDb = new File(directory, "staging.timetable.sqlite"); stagedManifest = new File(directory, "staging.manifest.json");
    backupDb = new File(directory, "backup.timetable.sqlite"); backupManifest = new File(directory, "backup.manifest.json");
    trust = new File(directory, "trust-state.json");
    stagedTrust = new File(directory, "staging.trust-state.json");
    backupTrust = new File(directory, "backup.trust-state.json");
    initialized = new File(directory, "trust.initialized");
    pending = new File(directory, "activation.pending"); preparing = new File(directory, "activation.preparing");
  }
  private static void remove(File file) throws IOException
  { if (file.exists() && !file.delete()) throw new IOException("pack transaction cleanup failed"); }
  private static void move(File from, File to) throws IOException
  { if (!from.renameTo(to)) throw new IOException("pack transaction rename failed"); }

  synchronized void recover() throws IOException
  {
    if (!pending.exists()) { remove(preparing); return; }
    if (!pending.isFile() || pending.length() > 16) throw new IOException("invalid pack transaction journal");
    String state;
    try (InputStream input = new FileInputStream(pending))
    {
      byte[] bytes = new byte[17];
      int length = 0, count;
      while (length < bytes.length && (count = input.read(bytes, length, bytes.length - length)) != -1)
        length += count;
      if (length > 16) throw new IOException("invalid pack transaction journal");
      state = new String(bytes, 0, length, StandardCharsets.US_ASCII);
    }
    if (state.startsWith("state1:"))
    {
      recoverGeneration(state);
      return;
    }
    requireLegacy(); // A legacy journal must never mutate an initialized three-member generation.
    if ("previous\n".equals(state))
    {
      restore(backupDb, db); restore(backupManifest, manifest);
      if (!db.isFile() || !manifest.isFile()) throw new IOException("incomplete previous pack transaction");
    }
    else if ("empty\n".equals(state))
    { remove(db); remove(manifest); remove(backupDb); remove(backupManifest); }
    else throw new IOException("invalid pack transaction journal");
    remove(stagedDb); remove(stagedManifest); remove(preparing);
    remove(pending); // Last: an interrupted rollback can always be retried.
  }
  private static void restore(File backup, File active) throws IOException
  {
    if (backup.exists()) { remove(active); move(backup, active); }
    // No backup means this file was not moved yet, or was already restored.
  }

  private File[] activeGeneration() { return new File[]{db, manifest, trust}; }
  private File[] stagedGeneration() { return new File[]{stagedDb, stagedManifest, stagedTrust}; }
  private File[] backupGeneration() { return new File[]{backupDb, backupManifest, backupTrust}; }

  private void recoverGeneration(String journal) throws IOException
  {
    if (!journal.matches("state1:(00|11)[01]\\n"))
      throw new IOException("invalid pack transaction journal");
    File[] active = activeGeneration(), backup = backupGeneration();
    // Validate all available old authorities before the first destructive rollback step.
    for (int i = 0; i < active.length; i++)
    {
      if ((active[i].exists() && !active[i].isFile()) || (backup[i].exists() && !backup[i].isFile()))
        throw new IOException("invalid pack generation files");
      if (journal.charAt(7 + i) == '1' && !backup[i].isFile() && !active[i].isFile())
        throw new IOException("incomplete previous pack generation");
    }
    for (int i = 0; i < active.length; i++)
    {
      if (journal.charAt(7 + i) == '1') restore(backup[i], active[i]);
      else { remove(active[i]); remove(backup[i]); }
      faults.at("restored-" + i);
    }
    for (File staged : stagedGeneration()) remove(staged);
    remove(preparing);
    remove(pending); // Last: every interrupted rollback remains retryable.
  }

  /**
   * Caller validates payload/envelope/history and serializes writers for the directory.
   * This layer treats state as opaque bytes; it cannot grant trust or advance a floor itself.
   */
  interface CommitCheck { void check() throws IOException; }
  synchronized void activateWithState() throws IOException { activateWithState(() -> {}); }
  synchronized void activateWithState(CommitCheck check) throws IOException { replaceGeneration(true, check); }

  /** Commit history-only state while removing the payload; never discard history implicitly. */
  synchronized void removeWithState() throws IOException { replaceGeneration(false, () -> {}); }

  private static void sync(File file) throws IOException
  {
    if (!file.isFile()) throw new IOException("missing staged pack generation");
    try (FileOutputStream output = new FileOutputStream(file, true)) { output.getFD().sync(); }
  }

  private void replaceGeneration(boolean installPayload, CommitCheck check) throws IOException
  {
    if (check == null) throw new IllegalArgumentException("commit check is required");
    recover();
    File[] active = activeGeneration(), staged = stagedGeneration(), backup = backupGeneration();
    if (db.exists() != manifest.exists()) throw new IOException("incomplete active pack pair");
    StringBuilder journal = new StringBuilder("state1:");
    for (File file : active)
    {
      if (file.exists() && !file.isFile()) throw new IOException("invalid active pack files");
      journal.append(file.isFile() ? '1' : '0');
    }
    journal.append('\n');
    sync(stagedTrust);
    if (installPayload) { sync(stagedDb); sync(stagedManifest); }
    else { remove(stagedDb); remove(stagedManifest); }
    for (File file : backup) remove(file);
    try (FileOutputStream output = new FileOutputStream(preparing))
    {
      output.write(journal.toString().getBytes(StandardCharsets.US_ASCII));
      output.getFD().sync();
    }
    move(preparing, pending);
    try
    {
      faults.at("journal-published");
      for (int i = 0; i < active.length; i++)
      {
        if (active[i].exists()) move(active[i], backup[i]);
        faults.at("backed-up-" + i);
      }
      if (installPayload)
      {
        move(stagedManifest, manifest); faults.at("installed-manifest");
        move(stagedDb, db); faults.at("installed-database");
      }
      move(stagedTrust, trust); faults.at("installed-state");
      faults.at("before-commit");
      check.check();
      remove(pending); // One commit point for payload, exact envelope and trust/history state.
    }
    catch (IOException failure)
    {
      try { recover(); } catch (IOException recovery) { failure.addSuppressed(recovery); }
      throw failure;
    }
    // Cleanup after commit cannot report a rollback. Backups are no longer authoritative.
    for (File file : backup) if (file.exists()) file.delete();
  }

  private void requireLegacy() throws IOException
  {
    if (trust.exists() || stagedTrust.exists() || backupTrust.exists() || initialized.exists())
      throw new IOException("trust state requires generation activation");
  }

  synchronized void activate() throws IOException
  {
    recover();
    requireLegacy();
    if (!stagedDb.isFile() || !stagedManifest.isFile()) throw new IOException("missing staged pack");
    if (db.exists() != manifest.exists()) throw new IOException("incomplete active pack pair");
    boolean previous = db.isFile() && manifest.isFile();
    if ((!previous && (db.exists() || manifest.exists()))) throw new IOException("invalid active pack files");
    remove(backupDb); remove(backupManifest);
    try (FileOutputStream output = new FileOutputStream(preparing))
    {
      output.write((previous ? "previous\n" : "empty\n").getBytes(StandardCharsets.US_ASCII));
      output.getFD().sync();
    }
    move(preparing, pending);
    try
    {
      if (previous) { move(db, backupDb); move(manifest, backupManifest); }
      move(stagedManifest, manifest); move(stagedDb, db);
      remove(pending); // Commit point; backups are no longer recovery authority.
    }
    catch (IOException failure)
    {
      try { recover(); } catch (IOException recovery) { failure.addSuppressed(recovery); }
      throw failure;
    }
    // A cleanup problem after commit must not claim that activation was rolled back.
    // Obsolete backups can safely be removed at the next activation.
    if (backupDb.exists()) backupDb.delete();
    if (backupManifest.exists()) backupManifest.delete();
  }

  synchronized void removeJournal() throws IOException
  { requireLegacy(); remove(pending); remove(preparing); }
}
