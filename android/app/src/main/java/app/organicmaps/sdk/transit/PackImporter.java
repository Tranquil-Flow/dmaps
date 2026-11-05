package app.organicmaps.sdk.transit;

import android.content.Context;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Bounded secure importer for .ctpack archives per PACK_CONTRACT.md:
 * literal entry names only, size caps, hash verification, schema pinning and
 * read-only integrity check before activation; atomic POSIX rename swap with
 * staged private files only. Caller holds the directory guard over staging, validation and commit;
 * PackTrustStore owns recovery, history-preserving replacement and committed-failure reporting.
 */
final class PackImporter
{
  static final long MAX_MANIFEST_BYTES = 32768L;
  static final long MAX_DATABASE_BYTES = 2L * 1024L * 1024L * 1024L;
  static final long MAX_STREAM_BYTES = 512L * 1024L * 1024L;
  private static final String MANIFEST_NAME = "manifest.json";
  private static final String DATABASE_NAME = "timetable.sqlite";

  private final File mStagingDb;
  private final File mStagingManifest;
  private final File mActiveDb;
  private final File mActiveManifest;

  PackImporter(Context context)
  {
    File dir = new File(context.getFilesDir(), "transit_pack");
    mStagingDb = new File(dir, "staging.timetable.sqlite");
    mStagingManifest = new File(dir, "staging.manifest.json");
    mActiveDb = new File(dir, "timetable.sqlite");
    mActiveManifest = new File(dir, "manifest.json");
  }

  File activeDb() { return mActiveDb; }
  String stagedDatabasePathForValidation() { return mStagingDb.getAbsolutePath(); }
  File activeManifest() { return mActiveManifest; }

  void deleteQuietly(File f)
  {
    if (f != null && f.exists() && !f.delete())
      f.deleteOnExit();
  }

  private static String hex(byte[] digest)
  {
    StringBuilder sb = new StringBuilder(digest.length * 2);
    for (byte b : digest)
      sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    return sb.toString();
  }

  /** NFKD + remove combining marks + lowercase + ß->ss, aligned with the Python normalizer. */
  static String normalize(String s)
  {
    if (s == null)
      return "";
    StringBuilder out = new StringBuilder(s.length());
    s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD);
    for (int i = 0; i < s.length(); i++)
    {
      int cp = s.codePointAt(i);
      if (cp >= 0x10000) { i++; continue; }
      char c = s.charAt(i);
      if (Character.getType(c) == Character.NON_SPACING_MARK
          || Character.getType(c) == Character.COMBINING_SPACING_MARK)
        continue;
      out.append(c);
    }
    String t = out.toString().toLowerCase(Locale.ROOT).replace("ß", "ss");
    return t;
  }

  /**
   * Validate envelope + copy exact entries to staging. Returns the caller-mode verifier result.
   * Envelope rules: exactly the two literal root names, no dirs/traversal/
   * duplicates/symlinks/encryption; per-entry and total stream caps.
   */
  PackEnvelope.Result stageAndValidateEnvelope(InputStream input, ImportMode mode,
      PackTrustPolicy policy, java.time.Clock clock) throws IOException
  {
    if (input == null)
      throw new IllegalArgumentException("input stream is required");
    deleteQuietly(mStagingDb);
    deleteQuietly(mStagingManifest);
    File tempArchive = File.createTempFile("ctpack", ".zip", mStagingDb.getParentFile());
    long total = 0;
    try
    {
      // Snapshot the supplied stream into a private bounded temp file first:
      // ZipFile gives us duplicate-entry detection and literal name checks
      // that streaming ZipInputStream cannot do reliably.
      OutputStream os = new FileOutputStream(tempArchive);
      try
      {
        byte[] buf = new byte[65536];
        int n;
        BufferedInputStream bis = new BufferedInputStream(input);
        while ((n = bis.read(buf)) != -1)
        {
          total += n;
          if (total > MAX_STREAM_BYTES)
            throw new IOException("pack stream exceeds 512 MiB limit");
          os.write(buf, 0, n);
        }
      }
      finally
      {
        os.close();
      }

      ZipFile zf = new ZipFile(tempArchive);
      try
      {
        HashSet<String> seen = new HashSet<>();
        List<ZipEntry> entries = new ArrayList<>();
        Enumeration<? extends ZipEntry> e = zf.entries();
        while (e.hasMoreElements())
        {
          ZipEntry ze = e.nextElement();
          String name = ze.getName();
          if (!name.equals(MANIFEST_NAME) && !name.equals(DATABASE_NAME))
            throw new IOException("unexpected archive entry");
          if (!seen.add(name))
            throw new IOException("duplicate archive entry");
          if (ze.isDirectory())
            throw new IOException("unexpected directory entry");
          if (ze.getMethod() != ZipEntry.DEFLATED && ze.getMethod() != ZipEntry.STORED)
            throw new IOException("unsupported compression method");
          entries.add(ze);
        }
        if (entries.size() != 2)
          throw new IOException("archive must contain exactly manifest.json and timetable.sqlite");
        for (ZipEntry ze : entries)
        {
          long size = ze.getSize();
          String name = ze.getName();
          if (name.equals(MANIFEST_NAME))
          {
            if (size > MAX_MANIFEST_BYTES)
              throw new IOException("manifest exceeds 32 KiB");
            copyBounded(zf, ze, mStagingManifest, MAX_MANIFEST_BYTES);
          }
          else
          {
            if (size > MAX_DATABASE_BYTES)
              throw new IOException("database exceeds 2 GiB limit");
            copyBounded(zf, ze, mStagingDb, MAX_DATABASE_BYTES);
          }
        }
      }
      finally
      {
        zf.close();
      }
      byte[] raw = readAll(new FileInputStream(mStagingManifest), MAX_MANIFEST_BYTES + 1);
      PackEnvelope.Result envelope = PackEnvelope.decode(raw, mode, policy, clock.instant());
      JSONObject manifest = new JSONObject(envelope.manifest);
      validateManifestShape(manifest);
      // Verify database hash/length against manifest.
      String declaredSha = manifest.getString("database_sha256");
      long declaredLen = manifest.getLong("database_bytes");
      File dbFile = mStagingDb;
      if (dbFile.length() != declaredLen)
        throw new IOException("database size mismatch");
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      countAndDigest(dbFile, md);
      if (!hex(md.digest()).equals(declaredSha))
        throw new IOException("database hash mismatch");
      return envelope;
    }
    catch (java.security.NoSuchAlgorithmException | org.json.JSONException ex)
    {
      throw new IOException("invalid pack: " + ex.getClass().getSimpleName());
    }
    finally
    {
      deleteQuietly(tempArchive);
    }
  }

  private static void copyBounded(ZipFile zf, ZipEntry ze, File target, long cap) throws IOException
  {
    InputStream in = zf.getInputStream(ze);
    OutputStream os = new FileOutputStream(target);
    try
    {
      byte[] buf = new byte[65536];
      long written = 0;
      int n;
      while ((n = in.read(buf)) != -1)
      {
        written += n;
        if (written > cap)
          throw new IOException("entry exceeds size limit");
        os.write(buf, 0, n);
      }
    }
    finally
    {
      os.close();
      in.close();
    }
  }

  static byte[] readAll(InputStream in, long cap) throws IOException
  {
    try
    {
      java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) != -1)
      {
        bos.write(buf, 0, n);
        if (bos.size() > cap)
          throw new IOException("content exceeds limit");
      }
      return bos.toByteArray();
    }
    finally
    {
      in.close();
    }
  }

  static void countAndDigest(File f, MessageDigest md) throws IOException
  {
    InputStream in = new BufferedInputStream(new FileInputStream(f));
    try
    {
      byte[] buf = new byte[65536];
      int n;
      long total = 0;
      while ((n = in.read(buf)) != -1)
      {
        md.update(buf, 0, n);
        total += n;
      }
      if (total != f.length())
        throw new IOException("file changed during read");
    }
    finally
    {
      in.close();
    }
  }

  /** Manifest required-field and range validation per PACK_CONTRACT.md. */
  static void validateManifestShape(JSONObject m) throws IOException
  {
    try
    {
      int format = m.getInt("format_version");
      if (format != 1 && format != 2)
        throw new IOException("unsupported format_version");
      if (!"vbb".equals(m.getString("publisher_id")))
        throw new IOException("unsupported publisher_id");
      if (!"CC BY 4.0".equals(m.getString("license")))
        throw new IOException("unsupported license");
      String lu = m.getString("license_url");
      if (lu == null || !lu.startsWith("https://"))
        throw new IOException("license_url must be HTTPS");
      String sha = m.getString("database_sha256");
      if (!sha.matches("[0-9a-f]{64}"))
        throw new IOException("database_sha256 must be lowercase hex sha256");
      String ssha = m.getString("source_sha256");
      if (!ssha.matches("[0-9a-f]{64}"))
        throw new IOException("source_sha256 must be lowercase hex sha256");
      long bytes = m.getLong("database_bytes");
      if (bytes < 0 || bytes > MAX_DATABASE_BYTES)
        throw new IOException("database_bytes out of range");
      int maxDep = m.getInt("max_departure_seconds");
      if (maxDep < 0 || maxDep > 604800)
        throw new IOException("max_departure_seconds out of range");
      if (format == 2)
      {
        Object arrival = m.get("max_arrival_seconds");
        if (!(arrival instanceof Number) || arrival instanceof Double || arrival instanceof Float
            || ((Number) arrival).longValue() < 0 || ((Number) arrival).longValue() > 604800)
          throw new IOException("max_arrival_seconds out of range");
      }
      String tz = m.getString("timezone");
      java.time.ZoneId.of(tz); // rejects invalid IDs safely
      for (String k : new String[]{"publisher", "source_url", "attribution", "created_utc",
          "valid_from", "valid_until"})
      {
        String v = m.getString(k);
        if (v == null || v.isEmpty())
          throw new IOException("manifest field missing: bounded");
      }
      String vf = m.getString("valid_from"), vu = m.getString("valid_until");
      java.time.LocalDate.parse(vf);
      java.time.LocalDate.parse(vu);
      if (java.time.LocalDate.parse(vu).isBefore(java.time.LocalDate.parse(vf)))
        throw new IOException("valid_until before valid_from");
    }
    catch (org.json.JSONException ex)
    {
      throw new IOException("invalid manifest");
    }
  }
  /** Clean staged artifacts after a failed/validated import. */
  void cleanupStaging()
  {
    deleteQuietly(mStagingDb);
    deleteQuietly(mStagingManifest);
    deleteQuietly(new File(mStagingDb.getParentFile(), "staging.trust-state.json"));
  }
}
