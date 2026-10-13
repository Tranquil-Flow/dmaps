package app.organicmaps.sdk.transit.rt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges scheduled departures with realtime overlays into display rows.
 *
 * The output model exists to make one distinction impossible to lose: a row
 * with no live data is NOT the same as a row that live data confirms as on
 * time. With the measured VBB join rate at 62-67%, roughly a third of rows
 * will have no realtime overlay, and rendering those as ordinary live
 * departures would be a claim the data does not support.
 */
public final class RealtimeMerger
{
  private RealtimeMerger() {}

  /** How much the display row can honestly claim about a departure. */
  public enum LiveState
  {
    /** No resolvable live data. Not evidence of normal service. */
    SCHEDULED_ONLY,
    LIVE,
    DELAYED,
    CANCELED
  }

  /** A delay at or below this reads as "on time". */
  public static final int DEFAULT_DELAY_THRESHOLD_SECONDS = 90;
  /** Beyond this age the overlay is stale regardless of what it says. */
  public static final int DEFAULT_STALE_AFTER_SECONDS = 600;

  /** One line of a departure board. */
  public static final class DepartureRow
  {
    public final String tripSourceId;
    public final String line;
    public final String destination;
    public final String platform;
    public final long scheduledDeparture;
    public final LiveState state;
    public final Long liveDeparture;
    public final Long delaySeconds;
    public final Long feedTimestamp;
    public final boolean stale;

    DepartureRow(String tripSourceId, String line, String destination,
                 String platform, long scheduledDeparture, LiveState state,
                 Long liveDeparture, Long delaySeconds, Long feedTimestamp,
                 boolean stale)
    {
      this.tripSourceId = tripSourceId;
      this.line = line;
      this.destination = destination;
      this.platform = platform;
      this.scheduledDeparture = scheduledDeparture;
      this.state = state;
      this.liveDeparture = liveDeparture;
      this.delaySeconds = delaySeconds;
      this.feedTimestamp = feedTimestamp;
      this.stale = stale;
    }

    public boolean hasLiveData() { return state != LiveState.SCHEDULED_ONLY; }

    /** A cancelled trip is still listed but must never read as departable. */
    public boolean isBoardable() { return state != LiveState.CANCELED; }
  }

  /** The scheduled half of a row, before realtime is overlaid. */
  public static final class ScheduledRow
  {
    public final String tripSourceId;
    public final String line;
    public final String destination;
    public final String platform;
    public final long scheduledDeparture;
    /** The PLATFORM id, not the station — see class javadoc in the Python port. */
    public final String platformSourceId;

    public ScheduledRow(String tripSourceId, String line, String destination,
                        String platform, long scheduledDeparture,
                        String platformSourceId)
    {
      this.tripSourceId = tripSourceId;
      this.line = line;
      this.destination = destination;
      this.platform = platform;
      this.scheduledDeparture = scheduledDeparture;
      this.platformSourceId = platformSourceId;
    }
  }

  /**
   * True when a delay value could plausibly describe lateness.
   *
   * Guards against provider deviations from the GTFS-RT spec. VBB's live feed
   * puts an absolute epoch timestamp in TripUpdate.delay (measured 2026-10-13:
   * field 4 = 1791872519, an epoch, not ~1000 seconds of lateness), so
   * trusting that field unconditionally turns every departure into "late by
   * 1.79e9 s". A failing value is treated as ABSENT rather than as a huge
   * delay — the difference between "we don't know" and confidently wrong.
   */
  public static boolean isPlausibleDelay(Long delay)
  {
    return delay != null
        && Math.abs(delay) <= GtfsRealtime.MAX_PLAUSIBLE_DELAY_SECONDS;
  }

  private static GtfsRealtime.StopTimeUpdate stopUpdateFor(
      GtfsRealtime.TripUpdate u, String stopId)
  {
    if (u == null || stopId == null)
      return null;
    for (GtfsRealtime.StopTimeUpdate s : u.stopTimeUpdates)
      if (stopId.equals(s.stopId))
        return s;
    return null;
  }

  /** Best known absolute departure for a stop, or null. */
  public static Long resolveLiveDeparture(GtfsRealtime.TripUpdate update,
                                          String stopId,
                                          long scheduledDeparture)
  {
    GtfsRealtime.StopTimeUpdate stu = stopUpdateFor(update, stopId);
    if (stu != null)
    {
      // Per-stop data is authoritative: it is what describes this platform.
      if (stu.departure != null && stu.departure.time != null)
        return stu.departure.time;
      if (stu.departure != null && isPlausibleDelay(stu.departure.delay))
        return scheduledDeparture + stu.departure.delay;
    }
    // Trip-level delay is last resort and only when plausible.
    if (isPlausibleDelay(update.delay))
      return scheduledDeparture + update.delay;
    return null;
  }

  /** True when the trip runs but does not serve this stop. */
  public static boolean isStopSkipped(GtfsRealtime.TripUpdate update, String stopId)
  {
    GtfsRealtime.StopTimeUpdate stu = stopUpdateFor(update, stopId);
    return stu != null && stu.isSkipped();
  }

  /**
   * Produces a display row, or null if the trip should not appear at all.
   *
   * Returning null is correct when a matched trip explicitly skips this stop:
   * listing it would send a passenger to a platform for a train that does not
   * stop. Everything else degrades to SCHEDULED_ONLY rather than disappearing.
   */
  public static DepartureRow mergeRow(ScheduledRow row,
                                      GtfsRealtime.TripUpdate update,
                                      String stopId, Long feedTimestamp,
                                      long now, int delayThreshold,
                                      int staleAfter)
  {
    boolean isStale = feedTimestamp != null && (now - feedTimestamp) > staleAfter;

    if (update == null)
      return new DepartureRow(row.tripSourceId, row.line, row.destination,
          row.platform, row.scheduledDeparture, LiveState.SCHEDULED_ONLY,
          null, null, feedTimestamp, isStale);

    if (isStopSkipped(update, stopId))
      return null;

    if (update.isCanceled())
      return new DepartureRow(row.tripSourceId, row.line, row.destination,
          row.platform, row.scheduledDeparture, LiveState.CANCELED,
          null, null, feedTimestamp, isStale);

    Long live = resolveLiveDeparture(update, stopId, row.scheduledDeparture);
    Long delay = null;
    if (live != null)
      delay = live - row.scheduledDeparture;
    else if (isPlausibleDelay(update.delay))
      delay = update.delay;

    // Only claim LIVE/DELAYED when a plausible live time was derived. An
    // implausible trip-level delay leaves the row SCHEDULED_ONLY rather than
    // inventing a delay from a bad field.
    LiveState state;
    if (delay == null || !isPlausibleDelay(delay))
      state = LiveState.SCHEDULED_ONLY;
    else
      state = Math.abs(delay) > delayThreshold ? LiveState.DELAYED : LiveState.LIVE;

    return new DepartureRow(row.tripSourceId, row.line, row.destination,
        row.platform, row.scheduledDeparture, state, live, delay,
        feedTimestamp, isStale);
  }

  /**
   * Overlays realtime onto scheduled rows, preserving schedule order.
   *
   * Scheduled order is retained deliberately: realtime must not silently
   * reorder a board a passenger reads top-to-bottom.
   */
  public static List<DepartureRow> mergeBoard(List<ScheduledRow> scheduled,
                                              Map<String, GtfsRealtime.TripUpdate> joined,
                                              Long feedTimestamp, long now,
                                              int delayThreshold, int staleAfter)
  {
    Long ts = feedTimestamp;
    if (ts == null)
    {
      // Fallback only: newest trip-level timestamp. The FeedHeader timestamp is
      // authoritative, because a single recently-updated trip would otherwise
      // make an hour-old feed look current.
      for (ScheduledRow row : scheduled)
      {
        GtfsRealtime.TripUpdate u = joined.get(row.tripSourceId);
        if (u != null && u.timestamp != null
            && (ts == null || u.timestamp > ts))
          ts = u.timestamp;
      }
    }

    List<DepartureRow> out = new ArrayList<>();
    for (ScheduledRow row : scheduled)
    {
      DepartureRow merged = mergeRow(row, joined.get(row.tripSourceId),
          row.platformSourceId, ts, now, delayThreshold, staleAfter);
      if (merged != null)
        out.add(merged);
    }
    return out;
  }

  /**
   * Joins feed trip_ids to the pack's trip source ids, dropping unresolved.
   *
   * Unresolved updates are DROPPED, never matched heuristically: a fallback
   * join on line+time+direction attaches a live cancellation to a plausible
   * trip it does not describe, inventing an event that never happened.
   */
  public static Map<String, GtfsRealtime.TripUpdate> joinToPack(
      GtfsRealtime.FeedMessage feed, java.util.Set<String> packTripIds)
  {
    Map<String, GtfsRealtime.TripUpdate> resolved = new LinkedHashMap<>();
    for (GtfsRealtime.TripUpdate u : feed.tripUpdates)
    {
      if (u.tripId == null)
        continue;
      if (packTripIds.contains(u.tripId))
        resolved.put(u.tripId, u);
    }
    return resolved;
  }
}
