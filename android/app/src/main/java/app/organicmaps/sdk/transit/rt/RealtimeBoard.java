package app.organicmaps.sdk.transit.rt;

import app.organicmaps.sdk.transit.Departure;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure, clock-driven projection of an immutable scheduled board and a complete raw feed. */
public final class RealtimeBoard
{
  private RealtimeBoard() {}

  public static final class Projection
  {
    public final List<Departure> departures;
    /** The scheduled occurrence object is the key, never the trip id alone. */
    public final Map<Departure, RealtimeMerger.DepartureRow> rows;
    public final int coverage, skipped;
    public final Long nextExpiryEpochSecond;
    private Projection(List<Departure> departures, Map<Departure, RealtimeMerger.DepartureRow> rows,
                       int coverage, int skipped, Long expiry)
    {
      this.departures = Collections.unmodifiableList(departures);
      this.rows = Collections.unmodifiableMap(rows);
      this.coverage = coverage;
      this.skipped = skipped;
      this.nextExpiryEpochSecond = expiry;
    }
  }

  public static Projection project(List<Departure> scheduled, GtfsRealtime.FeedMessage feed, long now)
  {
    Map<String, Set<String>> dates = new HashMap<>();
    for (Departure d : scheduled)
      dates.computeIfAbsent(d.tripSourceId, ignored -> new HashSet<>()).add(d.serviceDate);
    Map<String, List<GtfsRealtime.TripUpdate>> index = new HashMap<>();
    if (feed != null)
      for (GtfsRealtime.TripUpdate update : feed.tripUpdates)
        if (update.tripId != null && dates.containsKey(update.tripId))
          index.computeIfAbsent(update.tripId, ignored -> new ArrayList<>()).add(update);

    List<Departure> visible = new ArrayList<>();
    Map<Departure, RealtimeMerger.DepartureRow> rows = new LinkedHashMap<>();
    int coverage = 0, skipped = 0;
    Long expiry = null;
    for (Departure d : scheduled)
    {
      RealtimeMerger.ScheduledRow row = new RealtimeMerger.ScheduledRow(d.tripSourceId, d.line,
          d.destination, d.platform, d.when.getEpochSecond(), d.stopSourceId, d.serviceDate, d.stopSequence);
      GtfsRealtime.TripUpdate update = choose(row, index.get(d.tripSourceId), dates.get(d.tripSourceId));
      Long timestamp = RealtimeMerger.evidenceTimestamp(update, feed != null ? feed.timestamp : null, now);
      if (update != null && timestamp != null && timestamp >= 0 && timestamp <= now
          && now - timestamp <= RealtimeMerger.DEFAULT_STALE_AFTER_SECONDS)
      {
        long deadline = timestamp + RealtimeMerger.DEFAULT_STALE_AFTER_SECONDS + 1L;
        if (expiry == null || deadline < expiry) expiry = deadline;
      }
      RealtimeMerger.DepartureRow merged = RealtimeMerger.mergeRow(row, update, d.stopSourceId,
          feed != null ? feed.timestamp : null, now, RealtimeMerger.DEFAULT_DELAY_THRESHOLD_SECONDS,
          RealtimeMerger.DEFAULT_STALE_AFTER_SECONDS, uniqueStopVisit(d, scheduled));
      if (merged == null) { skipped++; continue; }
      visible.add(d);
      rows.put(d, merged);
      if (merged.hasLiveData()) coverage++;
    }
    return new Projection(visible, rows, coverage, skipped, expiry);
  }

  private static boolean uniqueStopVisit(Departure row, List<Departure> scheduled)
  {
    if (row.stopVisitCount != 1) return false; // Whole-trip identity, not just this time window.
    for (Departure other : scheduled)
      if (other != row && row.tripSourceId.equals(other.tripSourceId)
          && java.util.Objects.equals(row.serviceDate, other.serviceDate)
          && java.util.Objects.equals(row.stopSourceId, other.stopSourceId)
          && (!java.util.Objects.equals(row.stopSequence, other.stopSequence)
              || !row.when.equals(other.when))) return false;
    return true;
  }

  private static GtfsRealtime.TripUpdate choose(RealtimeMerger.ScheduledRow row,
      List<GtfsRealtime.TripUpdate> candidates, Set<String> dates)
  {
    if (candidates == null) return null;
    List<GtfsRealtime.TripUpdate> explicit = new ArrayList<>(), undated = new ArrayList<>();
    for (GtfsRealtime.TripUpdate update : candidates)
    {
      if (!RealtimeMerger.matchesInstance(row, update)) continue;
      if (update.startDate != null) explicit.add(update);
      else if (dates.size() == 1) undated.add(update);
    }
    List<GtfsRealtime.TripUpdate> matches = explicit.isEmpty() ? undated : explicit;
    return matches.size() == 1 ? matches.get(0) : null;
  }
}
