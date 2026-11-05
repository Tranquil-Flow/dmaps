package app.organicmaps.sdk.transit;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import org.json.JSONObject;

import java.io.File;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Local read-only timetable-pack repository. No network, location, telemetry
 * or user-history persistence. All methods are intended for the parent UI's
 * single background worker; sequential use per instance. Directory-wide guards serialize multiple
 * instances/processes, including recovery and reads. Generation changes invalidate cached read-only
 * connections; current authority is checked even while the connection is cached.
 */
public final class TimetableRepository implements AutoCloseable
{
  /** Shared bound for the pilot UI and read-only repository. */
  public static final int MAX_BOARD_LIMIT = 300;
  public static final int MAX_TRIP_STOPS = 4096;
  public static final int MAX_TRANSFER_RULES = 100000;
  private static final int APP_ID = 0x434D5450; // 'CMTP'
  private final Context mContext;
  private final PackImporter mImporter;
  private final PackTrustStore mTrust;
  private final PackTrustPolicy mTrustPolicy;
  private final java.time.Clock mClock;
  private String mEnvelopeSha256;
  private SQLiteDatabase mDb;
  private PackInfo mInfo;
  private ZoneId mZone;

  public TimetableRepository(android.content.Context context)
  {
    this(context, PackTrustPolicy.production(), java.time.Clock.systemUTC(), point -> {});
  }

  /** Package-private fixture composition; never selectable through intents or pack metadata. */
  TimetableRepository(Context context, PackTrustPolicy policy, java.time.Clock clock,
                      PackActivation.FaultInjector faults)
  {
    if (context == null || policy == null || clock == null || faults == null)
      throw new IllegalArgumentException("repository dependencies are required");
    mContext = context.getApplicationContext();
    mImporter = new PackImporter(mContext);
    mTrustPolicy = policy; mClock = clock;
    mTrust = new PackTrustStore(new File(mContext.getFilesDir(), "transit_pack"), policy, clock, faults);
  }

  /** Checked registered-pointer lookup: distinguish unavailable history from an absent pack. */
  public synchronized boolean hasPackChecked() throws IOException
  {
    try (PackDirectory.Guard ignored = mTrust.acquire()) { return mTrust.load().active != null; }
  }

  /** Registered pack pointer, not a current-signature/readiness claim. No network or DB open. */
  public synchronized boolean hasPack()
  {
    try (PackDirectory.Guard ignored = mTrust.acquire()) { return mTrust.load().active != null; }
    catch (IOException unavailable) { closeQuietly(); return false; }
  }

  /** Freshly derived metadata/trust of the active generation. */
  public synchronized PackInfo info() throws IOException
  {
    try (PackDirectory.Guard ignored = mTrust.acquire()) { ensureOpen(); return mInfo; }
  }

  private static IOException pack(String reason)
  {
    // Bounded, no echo of arbitrary SQLite/input content.
    return new IOException("timetable pack unavailable: " + reason);
  }

  /** Caller holds the directory guard through the entire read/query, not just connection setup. */
  private synchronized void ensureOpen() throws IOException
  {
    try
    {
      PackEnvelope.Result verified = mTrust.verifiedActive(false);
      if (mDb != null && verified.envelopeSha256.equals(mEnvelopeSha256))
      {
        mInfo = new PackInfo(verified); // Authority is fresh even for a cached connection.
        return;
      }
      closeQuietly();
      verified = mTrust.verifiedActive(true); // Rehash on reopen/generation change, incl. valid SQLite substitution.
      JSONObject m = new JSONObject(verified.manifest);
      PackImporter.validateManifestShape(m);
      mDb = SQLiteDatabase.openDatabase(mImporter.activeDb().getAbsolutePath(), null,
          SQLiteDatabase.OPEN_READONLY);
      PackInfo candidateInfo = new PackInfo(verified);
      validateOpenDatabase(mDb, candidateInfo);
      String tz = m.getString("timezone");
      String metaTz = metaValue(mDb, "timezone");
      if (metaTz == null || !metaTz.equals(tz)) throw pack("timezone mismatch");
      mZone = ZoneId.of(tz);
      mInfo = candidateInfo;
      mEnvelopeSha256 = verified.envelopeSha256;
    }
    catch (IOException ex)
    {
      closeQuietly(); throw ex;
    }
    catch (Exception ex)
    {
      closeQuietly(); throw pack("invalid pack");
    }
  }

  private static void validateOpenDatabase(SQLiteDatabase db, PackInfo info) throws IOException
  {
    try
    {
      long appId = longPragma(db, "PRAGMA application_id");
      if (appId != APP_ID)
        throw pack("unknown database format");
      long userVersion = longPragma(db, "PRAGMA user_version");
      if (userVersion != info.formatVersion || (userVersion != 1 && userVersion != 2))
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
      SchemaValidator.validate(db, info.formatVersion);
      if (info.formatVersion == 2)
      {
        if (!"2".equals(metaValue(db, "format_version"))) throw pack("format metadata mismatch");
        SchemaValidator.validateJourneyRows(db, info.maxDepartureSeconds, info.maxArrivalSeconds);
      }
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
   * previous pack is preserved on precommit refusal/interruption. PackCommitException explicitly
   * reports a committed replacement whose later cleanup/reopen could not complete.
   */
  public synchronized PackInfo importPack(InputStream input) throws IOException
  {
    return importPack(input, ImportMode.USER_UNTRUSTED);
  }

  /** Caller mode is immutable for this operation. Official refusal never retries as untrusted. */
  public synchronized PackInfo importPack(InputStream input, ImportMode mode) throws IOException
  {
    if (input == null || mode == null) throw new IllegalArgumentException("input and mode are required");
    boolean committed = false;
    try (PackDirectory.Guard ignored = mTrust.acquire())
    {
      mTrust.load(); // Recover/bootstrap before staging; missing initialized history cannot be reset.
      closeQuietly();
      try
      {
        PackEnvelope.Result envelope = mImporter.stageAndValidateEnvelope(input, mode, mTrustPolicy, mClock);
        JSONObject manifest = new JSONObject(envelope.manifest);
        SQLiteDatabase staged = SQLiteDatabase.openDatabase(
            mImporter.stagedDatabasePathForValidation(), null, SQLiteDatabase.OPEN_READONLY);
        try
        {
          validateOpenDatabase(staged, new PackInfo(envelope));
          String metaTz = metaValue(staged, "timezone");
          if (metaTz == null || !metaTz.equals(manifest.getString("timezone")))
            throw pack("timezone metadata mismatch");
        }
        finally { staged.close(); }
        mTrust.install(envelope);
        committed = true;
        ensureOpen();
        return mInfo;
      }
      finally { mImporter.cleanupStaging(); }
    }
    catch (PackCommitException ex) { closeQuietly(); throw ex; }
    catch (IOException ex)
    {
      closeQuietly();
      if (committed) throw new PackCommitException(PackCommitException.Operation.IMPORT);
      throw ex;
    }
    catch (Exception ex)
    {
      closeQuietly();
      if (committed) throw new PackCommitException(PackCommitException.Operation.IMPORT);
      throw pack("staged database invalid");
    }
  }

  public synchronized void removePack() throws IOException
  {
    boolean committed = false;
    try (PackDirectory.Guard ignored = mTrust.acquire())
    {
      closeQuietly();
      mTrust.remove();
      committed = true;
      mImporter.cleanupStaging();
    }
    catch (PackCommitException ex) { throw ex; }
    catch (IOException ex)
    {
      if (committed) throw new PackCommitException(PackCommitException.Operation.REMOVE);
      throw ex;
    }
  }

  // ------------------------------------------------------------------ search

  static final int MAX_SEARCH_WORDS = 8;

  private static String escapeLike(String s)
  {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /** Normalized prefix/substring station search. Limit 1..50. */
  public synchronized List<Stop> searchStops(String text, int limit) throws IOException
  {
    try (PackDirectory.Guard ignored = mTrust.acquire()) { return searchStopsLocked(text, limit); }
  }

  private static final String FOLDED_NAME = "replace(replace(replace(replace(search_name,'\u00e4','a'),'\u00f6','o'),'\u00fc','u'),'\u00df','ss')";
  private static final String DIGRAPH_NAME = "replace(replace(replace(replace(search_name,'\u00e4','ae'),'\u00f6','oe'),'\u00fc','ue'),'\u00df','ss')";

  private List<Stop> searchStopsLocked(String text, int limit) throws IOException
  {
    if (text == null)
      throw new IllegalArgumentException("text is required");
    if (limit < 1 || limit > 50)
      throw new IllegalArgumentException("limit must be 1..50");
    ensureOpen();
    // The query is folded here, but packs keep marks in search_name, so the same folding is applied to both sides below.
    String q = PackImporter.normalize(text).trim();
    if (q.isEmpty())
      return Collections.emptyList();
    // Every word must occur (any order), so "Hauptbahnhof Berlin" finds "S+U Berlin Hauptbahnhof".
    // Whole-phrase matches rank first; a single word keeps the previous match and order exactly.
    // Bounded: at most MAX_SEARCH_WORDS distinct words; never broadens beyond the typed words.
    String phrase = q.replaceAll("\\s+", " ");
    LinkedHashSet<String> words = new LinkedHashSet<>(Arrays.asList(phrase.split(" ")));
    if (words.size() > MAX_SEARCH_WORDS)
      return Collections.emptyList();
    StringBuilder where = new StringBuilder();
    List<String> args = new ArrayList<>();
    for (String w : words)
    {
      // Bare letters (sudkreuz) or a German digraph (suedkreuz) both reach a stored umlaut (s\u00fcdkreuz).
      where.append(" AND (").append(FOLDED_NAME).append(" LIKE ? ESCAPE '\\' OR ").append(DIGRAPH_NAME).append(" LIKE ? ESCAPE '\\')");
      args.add("%" + escapeLike(w) + "%");
      args.add("%" + escapeLike(w) + "%");
    }
    args.add("%" + escapeLike(phrase) + "%");
    args.add(String.valueOf(limit));
    try
    {
      Cursor c = mDb.rawQuery("SELECT id, source_id, name, platform, parent_id, lat, lon," +
              " location_type FROM stops WHERE location_type IN (0,1)" + where +
              " ORDER BY (" + FOLDED_NAME + " LIKE ? ESCAPE '\\') DESC, length(search_name), search_name, id LIMIT ?",
          args.toArray(new String[0]));
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
    try (PackDirectory.Guard ignored = mTrust.acquire()) { return nearbyStopsLocked(lat, lon, radiusMetres, limit); }
  }

  private List<Stop> nearbyStopsLocked(double lat, double lon, double radiusMetres,
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
    try (PackDirectory.Guard ignored = mTrust.acquire()) { return departuresLocked(stopId, now, limit, horizonMinutes); }
  }

  /** A restored numeric ID is meaningful only in the exact payload that selected it.
   * Metadata, identity and board are read under one directory guard; saved trust is never reused. */
  public static final class SelectedBoard {
    public final Stop stop;
    public final List<Departure> departures;
    public final PackInfo info;
    private SelectedBoard(Stop stop,List<Departure> departures,PackInfo info){
      this.stop=stop;this.departures=Collections.unmodifiableList(new ArrayList<>(departures));this.info=info;
    }
  }
  public synchronized SelectedBoard restoreBoard(long stopId,String expectedDatabaseSha256,Instant now,int limit,int horizonMinutes)throws IOException {
    if(expectedDatabaseSha256==null||!expectedDatabaseSha256.matches("[0-9a-f]{64}"))throw pack("saved selection has no data identity");
    try(PackDirectory.Guard ignored=mTrust.acquire()){
      ensureOpen();
      if(!expectedDatabaseSha256.equals(mInfo.databaseSha256))throw pack("saved selection data changed");
      Stop stop=journeyStopLocked(stopId);
      if(stop==null||(stop.locationType!=0&&stop.locationType!=1))throw pack("saved stop unavailable");
      return new SelectedBoard(stop,departuresLocked(stopId,now,limit,horizonMinutes),mInfo);
    }
  }

  /** Offsets come from elapsed service time, and import validates the pack's maximum departure offset against its rows. */
  static boolean serviceDayCanDepart(long firstOffset, long lastOffset, long maxDepartureOffset)
  {
    return lastOffset >= firstOffset && lastOffset >= 0 && firstOffset <= maxDepartureOffset;
  }

  private List<Departure> departuresLocked(long stopId, Instant now, int limit,
                                          int horizonMinutes) throws IOException
  {
    if (now == null)
      throw new IllegalArgumentException("now is required");
    if (limit < 1 || limit > MAX_BOARD_LIMIT)
      throw new IllegalArgumentException("limit must be 1.." + MAX_BOARD_LIMIT);
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
        if (!serviceDayCanDepart(lo, hi, maxDep))
          continue;
        Cursor c = mDb.rawQuery(
            "SELECT t.source_id, r.short_name," +
            " COALESCE(NULLIF(st.stop_headsign,''), t.headsign)," +
            " sp.name, sp.platform, r.route_type, st.pickup_type, st.timepoint," +
            " st.departure_seconds, sd.service_date, st.stop_id, sp.source_id, st.stop_sequence," +
            " (SELECT COUNT(*) FROM stop_times visits WHERE visits.trip_id=st.trip_id AND +visits.stop_id=st.stop_id)" +
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
              c.getLong(10), c.getString(11), LocalDate.of(y, mo, da).toString(), c.getInt(12), c.getInt(13)));
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

  /** Complete scheduled occurrence of one trip; v1 cannot manufacture missing alighting rules.
   * Caller runs off the UI thread. Service date, not device date/timezone, selects the instance. */
  public synchronized List<TripStop> tripStops(String tripSourceId, LocalDate serviceDate) throws IOException
  {
    if (tripSourceId == null || tripSourceId.isEmpty() || tripSourceId.length() > 4096
        || serviceDate == null || serviceDate.getYear() < 1900 || serviceDate.getYear() > 2200)
      throw new IllegalArgumentException("bounded trip ID and service date are required");
    try (PackDirectory.Guard ignored = mTrust.acquire())
    {
      ensureOpen();
      if (mInfo.formatVersion != 2) throw pack("journey data requires format 2");
      if (serviceDate.isBefore(LocalDate.parse(mInfo.validFrom))
          || serviceDate.isAfter(LocalDate.parse(mInfo.validUntil))) return Collections.emptyList();
      int key = serviceDate.getYear() * 10000 + serviceDate.getMonthValue() * 100 + serviceDate.getDayOfMonth();
      List<TripStop> out = new ArrayList<>();
      long previous = -1;
      Instant origin = GtfsTime.serviceOrigin(serviceDate, mZone);
      try (Cursor c = mDb.rawQuery("SELECT t.id,t.route_id,r.short_name,COALESCE(NULLIF(st.stop_headsign,''),t.headsign),st.stop_sequence,"
          + "st.stop_id,s.source_id,s.name,s.platform,st.arrival_seconds,st.departure_seconds,"
          + "st.pickup_type,st.drop_off_type,st.timepoint,s.location_type FROM trips t"
          + " JOIN service_days sd ON sd.service_id=t.service_id AND sd.service_date=?"
          + " JOIN routes r ON r.id=t.route_id JOIN stop_times st ON st.trip_id=t.id"
          + " JOIN stops s ON s.id=st.stop_id WHERE t.source_id=? ORDER BY st.stop_sequence LIMIT ?",
          new String[]{String.valueOf(key), tripSourceId, String.valueOf(MAX_TRIP_STOPS + 1)}))
      {
        while (c.moveToNext())
        {
          if (out.size() == MAX_TRIP_STOPS) throw pack("trip stop bound exceeded");
          Long arrival = nullableLong(c, 9), departure = nullableLong(c, 10);
          for (Long supplied : new Long[]{arrival, departure})
          {
            if (supplied == null) continue;
            if (supplied < 0 || supplied > 604800 || supplied < previous)
              throw pack("invalid trip chronology");
            previous = supplied;
          }
          out.add(new TripStop(c.getLong(0), c.getLong(1), tripSourceId, c.getString(2),
              c.getString(3), c.getInt(4), c.getLong(5), c.getString(6), c.getString(7), c.getString(8),
              arrival == null ? null : origin.plusSeconds(arrival),
              departure == null ? null : origin.plusSeconds(departure), c.getInt(11), c.getInt(12),
              c.isNull(13) ? null : c.getInt(13), c.getInt(14), serviceDate, mZone.getId()));
        }
      }
      catch (SQLiteException ex) { throw pack("journey query failed"); }
      return Collections.unmodifiableList(out);
    }
  }

  /** Bounded published rules, retaining nullable selectors and linked-trip types.
   * This does not resolve precedence, authorize a transfer or compute pedestrian paths. */
  public synchronized List<TransferRule> transferRules() throws IOException
  {
    try (PackDirectory.Guard ignored = mTrust.acquire())
    {
      ensureOpen();
      if (mInfo.formatVersion != 2) throw pack("journey data requires format 2");
      List<TransferRule> out = new ArrayList<>();
      try (Cursor c = mDb.rawQuery("SELECT id,from_stop_id,to_stop_id,from_route_id,to_route_id,"
          + "from_trip_id,to_trip_id,transfer_type,min_transfer_time FROM transfers ORDER BY id LIMIT ?",
          new String[]{String.valueOf(MAX_TRANSFER_RULES + 1)}))
      {
        while (c.moveToNext())
        {
          if (out.size() == MAX_TRANSFER_RULES) throw pack("transfer rule bound exceeded");
          out.add(new TransferRule(c.getLong(0), nullableLong(c, 1), nullableLong(c, 2),
              nullableLong(c, 3), nullableLong(c, 4), nullableLong(c, 5), nullableLong(c, 6),
              c.getInt(7), c.isNull(8) ? null : c.getInt(8)));
        }
      }
      catch (SQLiteException ex) { throw pack("transfer query failed"); }
      return Collections.unmodifiableList(out);
    }
  }

  /** Explicit station/platform selection for a platform-ready scheduled query, not access walking. */
  public synchronized List<Stop> boardingPlatforms(long selectedId) throws IOException
  {
    if (selectedId < 1) throw new IllegalArgumentException("positive stop ID required");
    try (PackDirectory.Guard ignored = mTrust.acquire())
    {
      ensureOpen();
      if (mInfo.formatVersion != 2) throw pack("journey data requires format 2");
      Stop selected = journeyStopLocked(selectedId);
      if (selected == null) return Collections.emptyList();
      if (selected.locationType == 0) return Collections.singletonList(selected);
      if (selected.locationType != 1) throw pack("select a boarding stop or station");
      List<Stop> platforms = new ArrayList<>();
      try (Cursor c = mDb.rawQuery("SELECT id FROM stops WHERE parent_id=? AND location_type=0 ORDER BY id LIMIT 33",new String[]{String.valueOf(selectedId)}))
      {
        while(c.moveToNext())
        {
          if(platforms.size()==32) throw pack("station platform bound exceeded");
          platforms.add(journeyStopLocked(c.getLong(0)));
        }
      }
      return Collections.unmodifiableList(platforms);
    }
    catch(SQLiteException invalid){throw pack("journey platform query failed");}
  }

  /** Bind search hits while reading them, never by attaching a later digest to stale IDs. */
  public synchronized JourneyPlanner.Candidates searchJourneyStops(String query,int limit)throws IOException
  {
    try(PackDirectory.Guard ignored=mTrust.acquire()){
      ensureOpen();
      if(mInfo.formatVersion!=2)throw pack("journey data requires format 2");
      List<Stop> stops=new ArrayList<>();
      for(Stop s:searchStops(query,limit))if(s.locationType==0||s.locationType==1)stops.add(s);
      return new JourneyPlanner.Candidates(stops,mEnvelopeSha256,mInfo);
    }
  }

  /** Reject a stale search hit before interpreting its ID in the current data. */
  public synchronized JourneyPlanner.Selection journeySelection(long selectedId,String expectedEnvelope)throws IOException
  {
    try(PackDirectory.Guard ignored=mTrust.acquire()){
      ensureOpen();
      if(!mEnvelopeSha256.equals(expectedEnvelope))throw pack("journey search generation changed; search again");
      return journeySelection(selectedId);
    }
  }

  /** Worker-side presentation refresh; no fallback, root installation or trust-mode mutation. */
  public synchronized boolean isJourneyCurrent(String envelope)throws IOException
  {
    try(PackDirectory.Guard ignored=mTrust.acquire()){
      ensureOpen();
      return mInfo.formatVersion==2&&mEnvelopeSha256.equals(envelope)
          &&!mInfo.isExpired(mClock.instant())&&!mInfo.isNotYetValid(mClock.instant());
    }
  }

  /** Atomically bind selected platform IDs/coordinates to the generation used to select them. */
  public synchronized JourneyPlanner.Selection journeySelection(long selectedId)throws IOException
  {
    try(PackDirectory.Guard ignored=mTrust.acquire()){
      List<Stop> platforms=boardingPlatforms(selectedId);
      return new JourneyPlanner.Selection(platforms,mEnvelopeSha256,mInfo);
    }
  }

  /** Entire lazy search holds one generation guard. Walking paths are trusted local caller input,
   * never inferred from an archive's transfer minima. Optional final-hop routing is local only. */
  public synchronized JourneyPlanner.Result journeys(JourneyPlanner.Request request) throws IOException {return journeys(request,null);}

  /** Owner-worker mapped search; caller retains/closes the provider in its own finally.
   * Only immutable results escape. Currency observations are cooperative, not screen leases. */
  public synchronized JourneyPlanner.Result journeys(JourneyPlanner.Request request,MappedWalking provider) throws IOException {
    return planJourneys(request,provider,false,false);
  }
  /** Opt-in bounded egress: at most4 native queries from reached transit platforms to selected
   * destinations nominated within800m geodesic radius. No location, proximity-made path or network.
   * Provider stays on the caller's serial worker; original transit/map publication guards apply. */
  public synchronized JourneyPlanner.Result journeysWithMappedEgress(JourneyPlanner.Request request,MappedWalking provider)throws IOException {
    if(provider==null||request==null||request.destinations.size()!=1)throw new IllegalArgumentException("mapped egress needs one selected destination and provider");
    return planJourneys(request,provider,true,false);
  }
  /** One initial native attempt to the first eligible same-station boarding sibling when the
   * selected origin has no ordinary boarding departure. Shares the4-query egress budget. */
  public synchronized JourneyPlanner.Result journeysWithMappedAccessAndEgress(JourneyPlanner.Request request,MappedWalking provider)throws IOException {
    if(provider==null||request==null||request.origins.size()!=1||request.destinations.size()!=1)throw new IllegalArgumentException("mapped links need one selected platform pair and provider");
    return planJourneys(request,provider,true,true);
  }
  private JourneyPlanner.Result planJourneys(JourneyPlanner.Request request,MappedWalking provider,boolean egress,boolean access)throws IOException
  {
    if(request==null) throw new IllegalArgumentException("journey request required");
    try (PackDirectory.Guard ignored = mTrust.acquire())
    {
      ensureOpen();
      if(mInfo.formatVersion!=2) throw pack("journey data requires format 2");
      if(!mEnvelopeSha256.equals(request.expectedEnvelopeSha256)) throw pack("journey input generation changed; select endpoints again");
      for(JourneyPlanner.Walk walk:request.walks){
        if(!mEnvelopeSha256.equals(walk.packEnvelopeSha256)) throw pack("journey walking generation changed; map connections again");
        if(provider==null&&walk.mapSnapshotId!=null)throw pack("mapped walking provider required for publication");
        if(provider!=null&&(!provider.mapSnapshotId.equals(walk.mapSnapshotId)||walk.routedAt==null))throw pack("mapped walking source identity mismatch");
      }
      if(provider!=null&&!provider.isCurrent())throw pack("mapped walking source changed");
      if(mInfo.isExpired(mClock.instant())) throw pack("journey timetable expired");
      if(mInfo.isNotYetValid(request.earliest)||mInfo.isExpired(request.earliest)) throw pack("journey outside timetable coverage");
      final long walkingStart=System.nanoTime();
      JourneyPlanner.Result result=JourneyPlanner.solve(new JourneyPlanner.Network(){
        int queried;boolean limited;final java.util.Map<String,JourneyPlanner.Walk> cached=new java.util.HashMap<>();
        @Override public boolean walkLimitReached(){return limited;}
        private JourneyPlanner.Walk mapped(Stop from,Stop to,Instant at)throws IOException {
          String key=from.id+"|"+to.id+"|"+at;
          if(!cached.containsKey(key)){
            if(queried==4){limited=true;return null;}queried++;
            JourneyPlanner.Selection a=new JourneyPlanner.Selection(java.util.Collections.singletonList(from),mEnvelopeSha256,mInfo);
            JourneyPlanner.Selection b=new JourneyPlanner.Selection(java.util.Collections.singletonList(to),mEnvelopeSha256,mInfo);
            cached.put(key,JourneyPlanner.Walk.routeIfAvailable(provider,a,from.id,b,to.id,at));
          }
          return cached.get(key);
        }
        private boolean ordinaryBoarding(long id,Instant at)throws IOException {
          List<Departure> board=departuresLocked(id,at,MAX_BOARD_LIMIT,request.horizonMinutes);
          for(Departure d:board)if(d.pickupType==0&&d.when!=null&&!d.when.isBefore(at)&&!d.when.isAfter(request.earliest.plusSeconds(request.horizonMinutes*60L)))return true;
          if(board.size()==MAX_BOARD_LIMIT)limited=true;
          return false;
        }
        @Override public List<JourneyPlanner.Walk> initialWalks(long id,Instant at)throws IOException {
          if(!access)return java.util.Collections.emptyList();Stop origin=journeyStopLocked(id);
          if(origin==null||origin.parentId==null||ordinaryBoarding(id,at))return java.util.Collections.emptyList();
          Stop parent=journeyStopLocked(origin.parentId);if(parent==null||parent.locationType!=1)return java.util.Collections.emptyList();
          List<Long> siblings=new ArrayList<>();
          try(Cursor c=mDb.rawQuery("SELECT id FROM stops WHERE parent_id=? AND location_type=0 AND id<>? ORDER BY id LIMIT 32",new String[]{String.valueOf(origin.parentId),String.valueOf(id)})){
            while(c.moveToNext())siblings.add(c.getLong(0));
          }catch(SQLiteException invalid){throw pack("journey sibling query failed");}
          if(siblings.size()>=32){limited=true;return java.util.Collections.emptyList();}
          for(long sibling:siblings){
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("journey search cancelled");
            if(System.nanoTime()-walkingStart>=10_000_000_000L){limited=true;return java.util.Collections.emptyList();}
            if(request.destinations.contains(sibling))continue;Stop candidate=journeyStopLocked(sibling);
            if(!nearFinalWalk(origin,candidate)||!ordinaryBoarding(sibling,at))continue;
            if(System.nanoTime()-walkingStart>=10_000_000_000L){limited=true;return java.util.Collections.emptyList();}
            if(queried==4){limited=true;return java.util.Collections.emptyList();}
            JourneyPlanner.Walk walk=mapped(origin,candidate,at);if(walk==null)continue;
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("journey search cancelled");
            if(System.nanoTime()-walkingStart>=10_000_000_000L){limited=true;return java.util.Collections.emptyList();}
            // Nomination at the origin time is not enough: the vehicle must still be boardable
            // after this genuine path. A missed/absent first path must not suppress later siblings.
            boolean catchable=ordinaryBoarding(sibling,at.plusSeconds(walk.seconds));
            if(System.nanoTime()-walkingStart>=10_000_000_000L){limited=true;return java.util.Collections.emptyList();}
            if(catchable)return java.util.Collections.singletonList(walk);
          }
          return java.util.Collections.emptyList();
        }
        @Override public List<JourneyPlanner.Walk> finalWalks(long id,Instant at)throws IOException {
          if(!egress)return java.util.Collections.emptyList();
          List<JourneyPlanner.Walk> out=new ArrayList<>();Stop from=journeyStopLocked(id);
          for(long destination:request.destinations){
            if(id==destination)continue;Stop to=journeyStopLocked(destination);if(!nearFinalWalk(from,to))continue;
            JourneyPlanner.Walk walk=mapped(from,to,at);if(walk!=null)out.add(walk);
          }
          return out;
        }
        @Override public Stop stop(long id)throws IOException{return journeyStopLocked(id);}
        @Override public List<Departure> departures(long id,Instant at,int limit,int minutes)throws IOException{return departuresLocked(id,at,limit,minutes);}
        @Override public List<TripStop> trip(String id,LocalDate date)throws IOException{return TimetableRepository.this.tripStops(id,date);}
        @Override public List<TransferRule> rules()throws IOException{return TimetableRepository.this.transferRules();}
      },request);
      // Revalidate even when the final expansion used cached trip/stop data only.
      ensureOpen();
      if(mInfo.isExpired(mClock.instant())) throw pack("journey timetable expired");
      java.util.EnumSet<JourneyPlanner.Issue> issues=java.util.EnumSet.noneOf(JourneyPlanner.Issue.class);issues.addAll(result.issues);
      if(mInfo.isExpired(request.earliest.plusSeconds(request.horizonMinutes*60L))) issues.add(JourneyPlanner.Issue.DATA_WINDOW_END);
      JourneyPlanner.Result ready=new JourneyPlanner.Result(result.legs,result.arrival,issues,mInfo,mEnvelopeSha256,provider==null?null:provider.mapSnapshotId);
      if(provider!=null&&!provider.isCurrent())throw pack("mapped walking source changed");
      Instant now=mClock.instant();
      if(mInfo.isExpired(now)||(mInfo.authenticated&&(mInfo.authorizationExpires==null||!now.isBefore(mInfo.authorizationExpires))))throw pack("journey timetable authorization expired");
      if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("journey search cancelled");
      return ready;
    }
  }

  private static boolean nearFinalWalk(Stop a,Stop b){
    if(a==null||b==null||a.locationType!=0||b.locationType!=0||a.lat==null||a.lon==null||b.lat==null||b.lon==null)return false;
    if(a.lat.doubleValue()==b.lat.doubleValue()&&a.lon.doubleValue()==b.lon.doubleValue())return false;
    for(double x:new double[]{a.lat,a.lon,b.lat,b.lon})if(!Double.isFinite(x))return false;
    if(Math.abs(a.lat)>=90||Math.abs(b.lat)>=90||Math.abs(a.lon)>180||Math.abs(b.lon)>180)return false;
    double latitude=Math.sin(Math.toRadians(b.lat-a.lat)/2),longitude=Math.sin(Math.toRadians(b.lon-a.lon)/2);
    double h=latitude*latitude+Math.cos(Math.toRadians(a.lat))*Math.cos(Math.toRadians(b.lat))*longitude*longitude;
    return 12742000.0*Math.asin(Math.sqrt(Math.min(1,Math.max(0,h))))<=800.0;
  }
  private Stop journeyStopLocked(long id)throws IOException
  {
    try(Cursor c=mDb.rawQuery("SELECT id,source_id,name,platform,parent_id,lat,lon,location_type FROM stops WHERE id=?",new String[]{String.valueOf(id)}))
    {
      if(!c.moveToFirst())return null;
      return new Stop(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.isNull(4)?null:c.getLong(4),
          c.isNull(5)?null:c.getDouble(5),c.isNull(6)?null:c.getDouble(6),c.getInt(7),Double.NaN);
    }
    catch(SQLiteException invalid){throw pack("journey stop query failed");}
  }

  private static Long nullableLong(Cursor cursor, int column) throws IOException
  {
    if (cursor.isNull(column)) return null;
    if (cursor.getType(column) != Cursor.FIELD_TYPE_INTEGER) throw pack("invalid journey integer");
    return cursor.getLong(column);
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
    mInfo = null;
    mZone = null;
    mEnvelopeSha256 = null;
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
