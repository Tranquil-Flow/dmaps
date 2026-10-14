package app.organicmaps.sdk.transit;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local read-only timetable-pack repository. No network, location, telemetry
 * or user-history persistence. All methods are intended for the parent UI's
 * single background worker; sequential use only. import/remove/close
 * invalidate any previously open read-only connection.
 */
public final class TimetableRepository implements AutoCloseable
{
  private static final int APP_ID = 0x434D5450; // 'CMTP'
  private final Context mContext;
  private final PackImporter mImporter;
  private SQLiteDatabase mDb;
  private PackInfo mInfo;
  private ZoneId mZone;

  public TimetableRepository(android.content.Context context)
  {
    if (context == null)
      throw new IllegalArgumentException("context is required");
    mContext = context.getApplicationContext();
    mImporter = new PackImporter(mContext);
  }

  /** Cheap filesystem state only; no DB/hash work. */
  public boolean hasPack()
  {
    return mImporter.hasActive();
  }

  /** Manifest metadata of the active pack. */
  public PackInfo info() throws IOException
  {
    ensureOpen();
    return mInfo;
  }

  private static IOException pack(String reason)
  {
    // Bounded, no echo of arbitrary SQLite/input content.
    return new IOException("timetable pack unavailable: " + reason);
  }

  private synchronized void ensureOpen() throws IOException
  {
    if (mDb != null)
      return;
    if (!mImporter.hasActive())
      throw pack("no pack imported");
    File db = mImporter.activeDb();
    try
    {
      mDb = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null,
          SQLiteDatabase.OPEN_READONLY);
      validateOpenDatabase(mDb);
      byte[] raw = PackImporter.readAll(new FileInputStream(mImporter.activeManifest()),
          PackImporter.MAX_MANIFEST_BYTES + 1);
      PackImporter.validateManifestShape(new JSONObject(new String(
          raw, java.nio.charset.StandardCharsets.UTF_8)));
      JSONObject m = new JSONObject(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
      String tz = m.getString("timezone");
      String metaTz = metaValue(mDb, "timezone");
      if (metaTz != null && !metaTz.equals(tz))
        throw pack("timezone mismatch");
      mZone = ZoneId.of(tz);
      mInfo = new PackInfo(m.getString("publisher"), m.getString("attribution"),
          m.getString("license"), m.getString("source_url"), m.getString("source_sha256"),
          m.getString("database_sha256"), m.getString("created_utc"), m.getString("valid_from"),
          m.getString("valid_until"), tz, m.getLong("database_bytes"),
          m.getInt("max_departure_seconds"));
    }
    catch (IOException ex)
    {
      closeQuietly();
      throw ex;
    }
    catch (Exception ex) // JSON, ZoneId, SQLite, IO on manifest
    {
      closeQuietly();
      throw pack("invalid pack");
    }
  }

  private static void validateOpenDatabase(SQLiteDatabase db) throws IOException
  {
    try
    {
      long appId = longPragma(db, "PRAGMA application_id");
      if (appId != APP_ID)
        throw pack("unknown database format");
      long userVersion = longPragma(db, "PRAGMA user_version");
      if (userVersion != 1)
        throw pack("unsupported schema version");
      // Reject executable definitions outright (defence in depth; read-only
      // flag already prevents most execution).
      Cursor c = db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type IN " +
          "('view','trigger') OR sql LIKE '%VIRTUAL TABLE%'", null);
      int bad = 0;
      if (c.moveToFirst())
        bad = c.getInt(0);
      c.close();
      if (bad > 0)
        throw pack("unsupported database objects");
      for (String t : new String[]{"meta", "agencies", "routes", "stops", "services",
          "service_days", "trips", "stop_times"})
      {
        c = db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?",
            new String[]{t});
        boolean ok = c.moveToFirst() && c.getInt(0) == 1;
        c.close();
        if (!ok)
          throw pack("missing schema table");
      }
      SchemaValidator.validate(db);
      c = db.rawQuery("PRAGMA integrity_check", null);
      String integrity = c.moveToFirst() ? c.getString(0) : "unknown";
      c.close();
      if (!"ok".equals(integrity))
        throw pack("integrity check failed");
    }
    catch (SQLiteException ex)
    {
      throw pack("corrupt database");
    }
  }

  private static long longPragma(SQLiteDatabase db, String pragma) throws IOException
  {
    try
    {
      Cursor c = db.rawQuery(pragma, null);
      long v = c.moveToFirst() ? c.getLong(0) : -1;
      c.close();
      return v;
    }
    catch (SQLiteException ex)
    {
      throw pack("unreadable database");
    }
  }

  private static String metaValue(SQLiteDatabase db, String key)
  {
    Cursor c = null;
    try
    {
      c = db.rawQuery("SELECT value FROM meta WHERE key=?", new String[]{key});
      return c.moveToFirst() ? c.getString(0) : null;
    }
    catch (SQLiteException ex)
    {
      return null;
    }
    finally
    {
      if (c != null)
        c.close();
    }
  }

  /**
   * Validate and atomically install a pack from the supplied stream. The
   * previous pack is preserved on any failure or interruption.
   */
  public synchronized PackInfo importPack(InputStream input) throws IOException
  {
    if (input == null)
      throw new IllegalArgumentException("input stream is required");
    closeQuietly(); // invalidate current connection before replacement
    JSONObject manifest;
    try
    {
      manifest = mImporter.stageAndValidateManifest(input);
    }
    catch (IOException ex)
    {
      mImporter.cleanupStaging();
      throw ex;
    }
    // Validate the staged database itself before activation.
    try
    {
      SQLiteDatabase staged = SQLiteDatabase.openDatabase(
          mImporter.stagedDatabasePathForValidation(), null, SQLiteDatabase.OPEN_READONLY);
      try
      {
        validateOpenDatabase(staged);
        String tz = manifest.getString("timezone");
        String metaTz = metaValue(staged, "timezone");
        if (metaTz == null || !metaTz.equals(tz))
          throw pack("timezone metadata mismatch");
      }
      finally
      {
        staged.close();
      }
    }
    catch (IOException ex)
    {
      mImporter.cleanupStaging();
      throw ex;
    }
    catch (Exception ex)
    {
      mImporter.cleanupStaging();
      throw pack("staged database invalid");
    }
    try
    {
      mImporter.activate();
    }
    catch (IOException ex)
    {
      mImporter.cleanupStaging();
      throw ex;
    }
    mImporter.cleanupStaging();
    ensureOpen();
    return mInfo;
  }

  public synchronized void removePack() throws IOException
  {
    closeQuietly();
    mImporter.removePack();
    mInfo = null;
    mZone = null;
  }

  // ------------------------------------------------------------------ search

  private static String escapeLike(String s)
  {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /** Normalized prefix/substring station search. Limit 1..50. */
  public synchronized List<Stop> searchStops(String text, int limit) throws IOException
  {
    if (text == null)
      throw new IllegalArgumentException("text is required");
    if (limit < 1 || limit > 50)
      throw new IllegalArgumentException("limit must be 1..50");
    ensureOpen();
    String q = PackImporter.normalize(text).trim();
    if (q.isEmpty())
      return Collections.emptyList();
    try
    {
      Cursor c = mDb.rawQuery("SELECT id, source_id, name, platform, parent_id, lat, lon," +
              " location_type FROM stops WHERE location_type IN (0,1) AND search_name LIKE ? ESCAPE '\\'" +
              " ORDER BY length(search_name), search_name, id LIMIT ?",
          new String[]{"%" + escapeLike(q) + "%", String.valueOf(limit)});
      List<Stop> out = new ArrayList<>();
      while (c.moveToNext())
        out.add(stopOf(c, Double.NaN));
      c.close();
      return out;
    }
    catch (SQLiteException ex)
    {
      throw pack("query failed");
    }
  }

  private static Stop stopOf(Cursor c, double distance)
  {
    return new Stop(c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
        c.isNull(4) ? null : c.getLong(4), c.isNull(5) ? null : c.getDouble(5),
        c.isNull(6) ? null : c.getDouble(6), c.getInt(7), distance);
  }

  /** True local (haversine) distance candidates within radius. Radius (0,5000]m, limit 1..50. */
  public synchronized List<Stop> nearbyStops(double lat, double lon, double radiusMetres,
                                             int limit) throws IOException
  {
    if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90
        || lon < -180 || lon > 180)
      throw new IllegalArgumentException("coordinates out of range");
    if (!(radiusMetres > 0) || radiusMetres > 5000)
      throw new IllegalArgumentException("radius must be (0, 5000] metres");
    if (limit < 1 || limit > 50)
      throw new IllegalArgumentException("limit must be 1..50");
    ensureOpen();
    // Bounding box prefilter then exact haversine; EPSG:3859-style local radius.
    final double R = 6371008.8;
    double dLat = Math.toDegrees(radiusMetres / R);
    double cosLat = Math.max(1e-9, Math.cos(Math.toRadians(lat)));
    double dLon = Math.toDegrees(radiusMetres / (R * cosLat));
    try
    {
      Cursor c = mDb.rawQuery("SELECT id, source_id, name, platform, parent_id, lat, lon," +
              " location_type FROM stops WHERE lat IS NOT NULL AND lon IS NOT NULL" +
              " AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
          new String[]{String.valueOf(lat - dLat), String.valueOf(lat + dLat),
              String.valueOf(lon - dLon), String.valueOf(lon + dLon)});
      List<Stop> all = new ArrayList<>();
      List<Double> dists = new ArrayList<>();
      while (c.moveToNext())
      {
        double d = haversine(lat, lon, c.getDouble(5), c.getDouble(6));
        if (d <= radiusMetres)
        {
          all.add(stopOf(c, d));
          dists.add(d);
        }
      }
      c.close();
      // Sort by distance then id for determinism.
      Integer[] idx = new Integer[all.size()];
      for (int i = 0; i < idx.length; i++)
        idx[i] = i;
      final List<Double> dd = dists;
      java.util.Arrays.sort(idx, (a, b) -> {
        int cmp = Double.compare(dd.get(a), dd.get(b));
        return cmp != 0 ? cmp : Long.compare(all.get(a).id, all.get(b).id);
      });
      List<Stop> out = new ArrayList<>(Math.min(limit, all.size()));
      for (int i = 0; i < idx.length && out.size() < limit; i++)
        out.add(all.get(idx[i]));
      return out;
    }
    catch (SQLiteException ex)
    {
      throw pack("query failed");
    }
  }

  private static double haversine(double lat1, double lon1, double lat2, double lon2)
  {
    final double R = 6371008.8;
    double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
    double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lon2 - lon1);
    double a = Math.sin(dp / 2) * Math.sin(dp / 2)
        + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
    return 2 * R * Math.asin(Math.min(1.0, Math.sqrt(a)));
  }

  // -------------------------------------------------------------- departures

  /**
   * Scheduled departures at a stop (or, when the stop is a selected parent,
   * across its child platforms — each departure keeps its actual platform).
   * After-midnight journeys from previous service days are included via the
   * noon-minus-12h service-origin semantics; both DST transitions handled by
   * construction. pickup_type 1 suppressed; 2/3 retain reservation flag;
   * stop_headsign overrides trip headsign; timepoint preserved (nullable).
   * Never tagged live/on-time.
   */
  public synchronized List<Departure> departures(long stopId, Instant now, int limit,
                                                 int horizonMinutes) throws IOException
  {
    if (now == null)
      throw new IllegalArgumentException("now is required");
    if (limit < 1 || limit > 100)
      throw new IllegalArgumentException("limit must be 1..100");
    if (horizonMinutes < 1 || horizonMinutes > 1440)
      throw new IllegalArgumentException("horizon must be 1..1440 minutes");
    ensureOpen();
    int maxDep = Math.min(Math.max(mInfo.maxDepartureSeconds, 0), 604800);

    // Selected stop row (also decides parent aggregation).
    long selectedLocationType;
    try
    {
      Cursor c = mDb.rawQuery("SELECT location_type FROM stops WHERE id=?",
          new String[]{String.valueOf(stopId)});
      if (!c.moveToFirst())
      {
        c.close();
        return Collections.emptyList();
      }
      selectedLocationType = c.getLong(0);
      c.close();
    }
    catch (SQLiteException ex)
    {
      throw pack("query failed");
    }
    // Aggregation only when the selected stop is itself a parent.
    String stopFilter = selectedLocationType != 0
        ? "(st.stop_id=? OR st.stop_id IN (SELECT id FROM stops WHERE parent_id=?))"
        : "st.stop_id=?";
    String[] filterArgs = selectedLocationType != 0
        ? new String[]{String.valueOf(stopId), String.valueOf(stopId)}
        : new String[]{String.valueOf(stopId)};

    Instant windowEnd = now.plus(Duration.ofMinutes(horizonMinutes));
    // Candidate service dates: bounded lookback derived from the pack's
    // max_departure_seconds (a service day D contributes while
    // origin(D) + maxDep >= now). Date range only prunes; the elapsed-seconds
    // SQL filter enforces exactness.
    long lookbackDays = (maxDep / 86400L) + 2;
    LocalDate firstDate = LocalDate.ofInstant(now, mZone).minusDays(lookbackDays);
    LocalDate lastDate = LocalDate.ofInstant(windowEnd, mZone).plusDays(1);

    List<Departure> merged = new ArrayList<>();
    try
    {
      for (LocalDate d = firstDate; !d.isAfter(lastDate); d = d.plusDays(1))
      {
        Instant origin = GtfsTime.serviceOrigin(d, mZone);
        Instant dayStart = origin; // service elapsed time 0
        Instant dayEnd = origin.plusSeconds((long) maxDep + 24L * 3600L + 1);
        if (dayStart.isAfter(windowEnd) || dayEnd.isBefore(now))
          continue;
        int dateKey = d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
        long lo = Math.max(0, Duration.between(origin, now).getSeconds());
        long hi = Duration.between(origin, windowEnd).getSeconds();
        if (hi < lo)
          continue;
        Cursor c = mDb.rawQuery(
            "SELECT t.source_id, r.short_name," +
            " COALESCE(NULLIF(st.stop_headsign,''), t.headsign)," +
            " sp.name, sp.platform, r.route_type, st.pickup_type, st.timepoint," +
            " st.departure_seconds, sd.service_date, st.stop_id, sp.source_id" +
            " FROM stop_times st" +
            " JOIN trips t ON t.id = st.trip_id" +
            " JOIN routes r ON r.id = t.route_id" +
            " JOIN service_days sd ON sd.service_id = t.service_id AND sd.service_date = ?" +
            " JOIN stops sp ON sp.id = st.stop_id" +
            " WHERE " + stopFilter +
            " AND st.pickup_type <> 1 AND st.departure_seconds IS NOT NULL" +
            " AND st.departure_seconds BETWEEN ? AND ?" +
            " ORDER BY st.departure_seconds, st.trip_id LIMIT ?",
            concat(new String[]{String.valueOf(dateKey)}, concat(filterArgs,
                new String[]{String.valueOf(lo), String.valueOf(hi), String.valueOf(limit)})));
        while (c.moveToNext())
        {
          int secs = c.getInt(8);
          int y = c.getInt(9) / 10000, mo = (c.getInt(9) / 100) % 100, da = c.getInt(9) % 100;
          Instant when = GtfsTime.departure(LocalDate.of(y, mo, da), secs, mZone);
          merged.add(new Departure(c.getString(0), c.getString(1), c.getString(2),
              c.getString(3), c.getString(4), c.getInt(5), c.getInt(6),
              c.isNull(7) ? null : c.getInt(7), when, mZone.getId(),
              c.getLong(10), c.getString(11), LocalDate.of(y, mo, da).toString()));
        }
        c.close();
      }
    }
    catch (SQLiteException ex)
    {
      throw pack("query failed");
    }
    merged.sort((a, b) -> {
      int cmp = a.when.compareTo(b.when);
      if (cmp != 0)
        return cmp;
      return a.tripSourceId.compareTo(b.tripSourceId);
    });
    return merged.size() > limit ? new ArrayList<>(merged.subList(0, limit)) : merged;
  }

  private static String[] concat(String[] a, String[] b)
  {
    String[] out = new String[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  @Override
  public synchronized void close()
  {
    closeQuietly();
  }

  private void closeQuietly()
  {
    if (mDb != null)
    {
      try
      {
        mDb.close();
      }
      catch (SQLiteException ignored)
      {
      }
      mDb = null;
    }
  }
}
