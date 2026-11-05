package app.organicmaps.sdk.transit.rt;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

/** Synthetic trip-instance and freshness regressions; not live-provider observations. */
public class RealtimeIdentityTest
{
  private RealtimeMerger.ScheduledRow row(String date, String stop, Integer sequence)
  {
    return new RealtimeMerger.ScheduledRow("T", "R", "D", "1", 1200, stop, date, sequence);
  }
  private GtfsRealtime.TripUpdate update(String date, int relationship, Long timestamp,
                                        GtfsRealtime.StopTimeUpdate... stops)
  {
    return new GtfsRealtime.TripUpdate("T", "R", null, date, relationship,
        0L, timestamp, Arrays.asList(stops));
  }
  private RealtimeMerger.DepartureRow merge(RealtimeMerger.ScheduledRow row,
                                           GtfsRealtime.TripUpdate update, Long header, long now)
  {
    return RealtimeMerger.mergeRow(row, update, row.platformSourceId, header, now, 90, 600);
  }

  @Test public void cancellationForAnotherServiceDateCannotCancelThisDay()
  {
    assertEquals(RealtimeMerger.LiveState.SCHEDULED_ONLY,
        merge(row("2026-10-14", "S", 1), update("20261013", 3, 1000L), 1000L, 1000).state);
  }
  @Test public void matchingServiceDateCanCancelThisDay()
  {
    assertEquals(RealtimeMerger.LiveState.CANCELED,
        merge(row("2026-10-14", "S", 1), update("20261014", 3, 1000L), 1000L, 1000).state);
  }
  @Test public void malformedExplicitDateCannotBecomeAnUndatedMatch()
  {
    assertEquals(RealtimeMerger.LiveState.SCHEDULED_ONLY,
        merge(row("2026-10-14", "S", 1), update("2026-10-14", 3, 1000L), 1000L, 1000).state);
  }
  @Test public void missingTimestampCannotClaimFreshLive()
  {
    assertEquals(RealtimeMerger.LiveState.SCHEDULED_ONLY,
        merge(row("2026-10-14", "S", 1), update(null, 0, null), null, 1000).state);
  }
  @Test public void futureTimestampCannotClaimFreshLive()
  {
    assertEquals(RealtimeMerger.LiveState.SCHEDULED_ONLY,
        merge(row("2026-10-14", "S", 1), update(null, 0, 9000L), 9000L, 1000).state);
  }
  @Test public void eachTripUsesItsOwnTimestampWithoutAHeader()
  {
    RealtimeMerger.ScheduledRow a = new RealtimeMerger.ScheduledRow("A", "R", "D", "1", 1800, "S");
    RealtimeMerger.ScheduledRow b = new RealtimeMerger.ScheduledRow("B", "R", "D", "1", 1800, "S");
    Map<String, GtfsRealtime.TripUpdate> map = new HashMap<>();
    map.put("A", new GtfsRealtime.TripUpdate("A", "R", null, null, 0, 0L, 1000L, Collections.emptyList()));
    map.put("B", new GtfsRealtime.TripUpdate("B", "R", null, null, 0, 0L, 1601L, Collections.emptyList()));
    List<RealtimeMerger.DepartureRow> result = RealtimeMerger.mergeBoard(Arrays.asList(a, b), map, null, 1601, 90, 600);
    assertTrue(result.get(0).stale);
    assertFalse(result.get(1).stale);
  }
  @Test public void repeatedStopRequiresItsActualSequence()
  {
    GtfsRealtime.StopTimeUpdate first = new GtfsRealtime.StopTimeUpdate("S", 1, null,
        new GtfsRealtime.StopTimeEvent(null, 0L), 0);
    GtfsRealtime.StopTimeUpdate second = new GtfsRealtime.StopTimeUpdate("S", 5, null,
        new GtfsRealtime.StopTimeEvent(null, 240L), 0);
    assertEquals(Long.valueOf(240), merge(row("2026-10-14", "S", 5),
        update(null, 0, 1000L, first, second), 1000L, 1000).delaySeconds);
  }
}
