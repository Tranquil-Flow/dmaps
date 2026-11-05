package app.organicmaps.sdk.transit.rt;

import static org.junit.Assert.*;
import app.organicmaps.sdk.transit.Departure;
import java.time.Instant;
import java.util.*;
import org.junit.Test;

/** Synthetic board projections; these are the same projection used by the Activity adapter. */
public class RealtimeBoardTest
{
  private Departure departure(String date, String stop, int sequence)
  {
    return new Departure("T", "R", "D", "Station", "1", 2, 0, 1,
        Instant.ofEpochSecond(1200), "Europe/Berlin", sequence, stop, date, sequence);
  }
  private GtfsRealtime.StopTimeUpdate stop(String id, int sequence, int relationship, long delay)
  {
    return new GtfsRealtime.StopTimeUpdate(id, sequence, null,
        new GtfsRealtime.StopTimeEvent(null, delay), relationship);
  }
  private GtfsRealtime.TripUpdate trip(String date, int relationship, GtfsRealtime.StopTimeUpdate... stops)
  {
    return new GtfsRealtime.TripUpdate("T", "R", null, date, relationship, 0L, 1000L, Arrays.asList(stops));
  }
  private GtfsRealtime.FeedMessage feed(GtfsRealtime.TripUpdate... trips)
  { return new GtfsRealtime.FeedMessage(1000L, Arrays.asList(trips), 0, 0); }

  @Test public void skippedOccurrenceDisappearsButOtherPlatformIsRetained()
  {
    Departure a = departure("2026-10-14", "A", 1), b = departure("2026-10-14", "B", 2);
    RealtimeBoard.Projection p = RealtimeBoard.project(Arrays.asList(a, b),
        feed(trip("20261014", 0, stop("A", 1, 1, 0), stop("B", 2, 0, 240))), 1000);
    assertEquals(Collections.singletonList(b), p.departures);
    assertEquals(1, p.skipped);
    assertEquals(RealtimeMerger.LiveState.DELAYED, p.rows.get(b).state);
  }
  @Test public void missingFeedRestoresScheduledRows()
  {
    Departure a = departure("2026-10-14", "A", 1);
    RealtimeBoard.Projection p = RealtimeBoard.project(Collections.singletonList(a), null, 1000);
    assertEquals(Collections.singletonList(a), p.departures);
    assertEquals(RealtimeMerger.LiveState.SCHEDULED_ONLY, p.rows.get(a).state);
  }
  @Test public void platformOccurrencesDoNotOverwriteOneAnother()
  {
    Departure a = departure("2026-10-14", "A", 1), b = departure("2026-10-14", "B", 2);
    RealtimeBoard.Projection p = RealtimeBoard.project(Arrays.asList(a, b),
        feed(trip("20261014", 0, stop("A", 1, 0, 0), stop("B", 2, 0, 240))), 1000);
    assertEquals(2, p.rows.size());
    assertEquals(RealtimeMerger.LiveState.LIVE, p.rows.get(a).state);
    assertEquals(RealtimeMerger.LiveState.DELAYED, p.rows.get(b).state);
  }
  @Test public void explicitDateOnlyCancelsMatchingInstance()
  {
    Departure a = departure("2026-10-14", "A", 1), b = departure("2026-10-15", "A", 1);
    RealtimeBoard.Projection p = RealtimeBoard.project(Arrays.asList(a, b), feed(trip("20261014", 3)), 1000);
    assertEquals(RealtimeMerger.LiveState.CANCELED, p.rows.get(a).state);
    assertEquals(RealtimeMerger.LiveState.SCHEDULED_ONLY, p.rows.get(b).state);
  }
  @Test public void undatedAmbiguousTripCannotCancelEitherDay()
  {
    Departure a = departure("2026-10-14", "A", 1), b = departure("2026-10-15", "A", 1);
    RealtimeBoard.Projection p = RealtimeBoard.project(Arrays.asList(a, b), feed(trip(null, 3)), 1000);
    assertEquals(0, p.coverage);
    assertEquals(RealtimeMerger.LiveState.SCHEDULED_ONLY, p.rows.get(a).state);
    assertEquals(RealtimeMerger.LiveState.SCHEDULED_ONLY, p.rows.get(b).state);
  }
  @Test public void duplicateUpdateOrderCannotChooseAResult()
  {
    Departure a = departure("2026-10-14", "A", 1);
    GtfsRealtime.TripUpdate x = trip("20261014", 3), y = trip("20261014", 0);
    assertEquals(0, RealtimeBoard.project(Collections.singletonList(a), feed(x, y), 1000).coverage);
    assertEquals(0, RealtimeBoard.project(Collections.singletonList(a), feed(y, x), 1000).coverage);
  }
  @Test public void rawFeedReprojectsAnExpandedBoardOnUnchangedContent()
  {
    Departure a = departure("2026-10-14", "A", 1), b = departure("2026-10-14", "B", 2);
    GtfsRealtime.FeedMessage raw = feed(trip("20261014", 0, stop("A", 1, 0, 0), stop("B", 2, 0, 240)));
    assertEquals(1, RealtimeBoard.project(Collections.singletonList(a), raw, 1000).coverage);
    assertEquals(2, RealtimeBoard.project(Arrays.asList(a, b), raw, 1000).coverage);
  }
  @Test public void expiryRecomputesFreshnessWithoutRefetch()
  {
    Departure a = departure("2026-10-14", "A", 1);
    GtfsRealtime.FeedMessage raw = feed(trip("20261014", 0));
    assertFalse(RealtimeBoard.project(Collections.singletonList(a), raw, 1600).rows.get(a).stale);
    assertTrue(RealtimeBoard.project(Collections.singletonList(a), raw, 1601).rows.get(a).stale);
  }
  @Test public void skippedStopReturnsWhenEvidenceExpires()
  {
    Departure a = departure("2026-10-14", "A", 1);
    GtfsRealtime.FeedMessage raw = feed(trip("20261014", 0, stop("A", 1, 1, 0)));
    assertTrue(RealtimeBoard.project(Collections.singletonList(a), raw, 1000).departures.isEmpty());
    RealtimeBoard.Projection expired = RealtimeBoard.project(Collections.singletonList(a), raw, 1601);
    assertEquals(Collections.singletonList(a), expired.departures);
    assertEquals(RealtimeMerger.LiveState.SCHEDULED_ONLY, expired.rows.get(a).state);
  }
}
