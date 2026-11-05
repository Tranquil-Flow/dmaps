package app.organicmaps.sdk.transit.rt;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact scheduled/live merge; absent, ambiguous or unsupported evidence stays scheduled-only. */
public final class RealtimeMerger
{
  private RealtimeMerger() {}
  public enum LiveState { SCHEDULED_ONLY, LIVE, DELAYED, CANCELED }
  public static final int DEFAULT_DELAY_THRESHOLD_SECONDS = 90;
  public static final int DEFAULT_STALE_AFTER_SECONDS = 600;

  public static final class DepartureRow
  {
    public final String tripSourceId, line, destination, platform;
    public final long scheduledDeparture;
    public final LiveState state;
    public final Long liveDeparture, delaySeconds, feedTimestamp;
    public final boolean stale;
    DepartureRow(String tripSourceId, String line, String destination, String platform,
                 long scheduledDeparture, LiveState state, Long liveDeparture, Long delay,
                 Long timestamp, boolean stale)
    {
      this.tripSourceId = tripSourceId;
      this.line = line;
      this.destination = destination;
      this.platform = platform;
      this.scheduledDeparture = scheduledDeparture;
      this.state = state;
      this.liveDeparture = liveDeparture;
      this.delaySeconds = delay;
      this.feedTimestamp = timestamp;
      this.stale = stale;
    }
    public boolean hasLiveData() { return state != LiveState.SCHEDULED_ONLY; }
    public boolean isBoardable() { return state != LiveState.CANCELED; }
  }

  public static final class ScheduledRow
  {
    public final String tripSourceId, line, destination, platform, platformSourceId, serviceDate;
    public final long scheduledDeparture;
    public final Integer stopSequence;
    public ScheduledRow(String trip, String line, String destination, String platform,
                        long departure, String stop)
    { this(trip, line, destination, platform, departure, stop, null, null); }
    public ScheduledRow(String trip, String line, String destination, String platform,
                        long departure, String stop, String date, Integer sequence)
    {
      this.tripSourceId = trip;
      this.line = line;
      this.destination = destination;
      this.platform = platform;
      this.scheduledDeparture = departure;
      this.platformSourceId = stop;
      this.serviceDate = date;
      this.stopSequence = sequence;
    }
  }

  /** Semantic guard after canonical decoding; Math.abs(Long.MIN_VALUE) is not a bound. */
  public static boolean isPlausibleDelay(Long delay)
  {
    return delay != null && delay >= -GtfsRealtime.MAX_PLAUSIBLE_DELAY_SECONDS
        && delay <= GtfsRealtime.MAX_PLAUSIBLE_DELAY_SECONDS;
  }

  static boolean matchesInstance(ScheduledRow row, GtfsRealtime.TripUpdate update)
  {
    if (update == null || update.tripId == null || !update.tripId.equals(row.tripSourceId)
        || update.startTime != null) return false; // frequency instances are unsupported
    if (update.startDate == null) return true; // board-level ambiguity checked by RealtimeBoard
    if (row.serviceDate == null || !update.startDate.matches("[0-9]{8}")) return false;
    try
    {
      return LocalDate.parse(row.serviceDate).format(DateTimeFormatter.BASIC_ISO_DATE).equals(update.startDate)
          && LocalDate.parse(update.startDate, DateTimeFormatter.BASIC_ISO_DATE) != null;
    }
    catch (java.time.DateTimeException invalid) { return false; }
  }

  /** Both feed creation and trip observation must be current; neither renews the other. */
  static Long evidenceTimestamp(GtfsRealtime.TripUpdate update, Long header, long now)
  {
    Long trip = update != null ? update.timestamp : null;
    if ((header != null && (header < 0 || header > now))
        || (trip != null && (trip < 0 || trip > now))) return null;
    if (header == null) return trip;
    if (trip == null) return header;
    return Math.min(header, trip);
  }

  private static GtfsRealtime.StopTimeUpdate stopUpdateFor(GtfsRealtime.TripUpdate u,
                                                          String stopId, Integer sequence, boolean allowStopIdOnly)
  {
    if (u == null || stopId == null) return null;
    GtfsRealtime.StopTimeUpdate found = null;
    for (GtfsRealtime.StopTimeUpdate s : u.stopTimeUpdates)
    {
      if (!allowStopIdOnly && (sequence == null || s.stopSequence == null)) continue;
      boolean sameStop = stopId.equals(s.stopId)
          || (s.stopId == null && sequence != null && sequence.equals(s.stopSequence));
      if (!sameStop || (sequence != null && s.stopSequence != null && !sequence.equals(s.stopSequence)))
        continue;
      if (found != null) return null; // repeated/ambiguous occurrences are never guessed
      found = s;
    }
    return found;
  }

  public static Long resolveLiveDeparture(GtfsRealtime.TripUpdate u, String stopId, long scheduled)
  { return resolveLiveDeparture(u, stopId, scheduled, null, true); }

  private static Long resolveLiveDeparture(GtfsRealtime.TripUpdate u, String stopId,
                                           long scheduled, Integer sequence, boolean allowStopIdOnly)
  {
    if (u == null) return null;
    GtfsRealtime.StopTimeUpdate s = stopUpdateFor(u, stopId, sequence, allowStopIdOnly);
    try
    {
      if (s != null)
      {
        if (s.hasUnsupportedStopProperties || s.scheduleRelationship != 0) return null;
        // Do not bless timing at the old platform or with unsupported pickup changes.
        if (s.departure != null && s.departure.time != null) return s.departure.time;
        if (s.departure != null && isPlausibleDelay(s.departure.delay))
          return Math.addExact(scheduled, s.departure.delay);
      }
      // With per-stop updates, an omitted stop/occurrence is not a prediction.
      // Delay propagation is outside this bounded pilot, rather than guessed.
      if (u.stopTimeUpdates.isEmpty() && isPlausibleDelay(u.delay))
        return Math.addExact(scheduled, u.delay);
    }
    catch (ArithmeticException overflow) { return null; }
    return null;
  }

  public static boolean isStopSkipped(GtfsRealtime.TripUpdate u, String stopId)
  {
    GtfsRealtime.StopTimeUpdate s = stopUpdateFor(u, stopId, null, true);
    return s != null && s.isSkipped();
  }

  /** Null suppresses only a fresh, exactly matched skipped occurrence. */
  public static DepartureRow mergeRow(ScheduledRow row, GtfsRealtime.TripUpdate update,
                                      String stopId, Long headerTimestamp, long now,
                                      int delayThreshold, int staleAfter)
  {
    return mergeRow(row, update, stopId, headerTimestamp, now, delayThreshold, staleAfter, true);
  }

  static DepartureRow mergeRow(ScheduledRow row, GtfsRealtime.TripUpdate update,
      String stopId, Long headerTimestamp, long now, int delayThreshold, int staleAfter,
      boolean allowStopIdOnly)
  {
    Long timestamp = evidenceTimestamp(update, headerTimestamp, now);
    boolean invalidAge = timestamp == null || timestamp < 0 || timestamp > now;
    boolean stale = invalidAge || now - timestamp > staleAfter;
    if (!matchesInstance(row, update) || invalidAge
        || (update.scheduleRelationship != 0 && !update.isCanceled()))
      return scheduled(row, timestamp, stale);
    if (update.isCanceled())
      return new DepartureRow(row.tripSourceId, row.line, row.destination, row.platform,
          row.scheduledDeparture, LiveState.CANCELED, null, null, timestamp, stale);
    GtfsRealtime.StopTimeUpdate stop = stopUpdateFor(update, stopId, row.stopSequence, allowStopIdOnly);
    if (stop != null && stop.isSkipped())
      return stale ? scheduled(row, timestamp, true) : null;
    Long live = resolveLiveDeparture(update, stopId, row.scheduledDeparture, row.stopSequence, allowStopIdOnly);
    Long delay = null;
    try { if (live != null) delay = Math.subtractExact(live, row.scheduledDeparture); }
    catch (ArithmeticException overflow) { live = null; }
    if (!isPlausibleDelay(delay)) return scheduled(row, timestamp, stale);
    LiveState state = Math.abs(delay) > delayThreshold ? LiveState.DELAYED : LiveState.LIVE;
    return new DepartureRow(row.tripSourceId, row.line, row.destination, row.platform,
        row.scheduledDeparture, state, live, delay, timestamp, stale);
  }

  private static DepartureRow scheduled(ScheduledRow row, Long timestamp, boolean stale)
  {
    return new DepartureRow(row.tripSourceId, row.line, row.destination, row.platform,
        row.scheduledDeparture, LiveState.SCHEDULED_ONLY, null, null, timestamp, stale);
  }

  /** Legacy diagnostic join; the app uses RealtimeBoard's instance-aware raw-feed projection. */
  public static List<DepartureRow> mergeBoard(List<ScheduledRow> scheduled,
                                              Map<String, GtfsRealtime.TripUpdate> joined,
                                              Long timestamp, long now, int threshold, int staleAfter)
  {
    List<DepartureRow> out = new ArrayList<>();
    for (ScheduledRow row : scheduled)
    {
      DepartureRow merged = mergeRow(row, joined.get(row.tripSourceId), row.platformSourceId,
          timestamp, now, threshold, staleAfter);
      if (merged != null) out.add(merged);
    }
    return out;
  }

  public static Map<String, GtfsRealtime.TripUpdate> joinToPack(GtfsRealtime.FeedMessage feed,
                                                               java.util.Set<String> ids)
  {
    Map<String, GtfsRealtime.TripUpdate> out = new LinkedHashMap<>();
    java.util.Set<String> duplicate = new java.util.HashSet<>();
    for (GtfsRealtime.TripUpdate u : feed.tripUpdates)
    {
      if (u.tripId == null || !ids.contains(u.tripId)) continue;
      if (out.put(u.tripId, u) != null) duplicate.add(u.tripId);
    }
    for (String id : duplicate) out.remove(id); // never use whichever update arrived last
    return out;
  }
}
