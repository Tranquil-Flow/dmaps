package app.organicmaps.sdk.transit.rt;

import static org.junit.Assert.*;
import java.io.InputStream;
import org.junit.Test;

/** Independent canonical fixtures; unsupported boarding/location properties must not bless timing. */
public class UnsupportedStopPropertiesTest
{
  private RealtimeMerger.DepartureRow merge(String file) throws Exception
  {
    GtfsRealtime.FeedMessage feed;
    try (InputStream input = getClass().getResourceAsStream("/transit/" + file))
    {
      assertNotNull(input);
      feed = GtfsRealtime.parse(input.readAllBytes());
    }
    RealtimeMerger.ScheduledRow row = new RealtimeMerger.ScheduledRow(
        "T", "fixture", "fixture destination", "scheduled platform", 1100L, "S", null, 1);
    return RealtimeMerger.mergeRow(row, feed.tripUpdates.get(0), "S", feed.timestamp, 1000, 90, 600);
  }

  @Test public void reassignedPlatformCannotReadAsLiveAtScheduledPlatform() throws Exception
  {
    assertEquals(RealtimeMerger.LiveState.SCHEDULED_ONLY,
        merge("canonical-assigned-stop.pb").state);
  }

  @Test public void unsupportedPickupChangeCannotBlessBoardableTiming() throws Exception
  {
    assertEquals(RealtimeMerger.LiveState.SCHEDULED_ONLY,
        merge("canonical-no-pickup.pb").state);
  }

  @Test public void ordinaryMatchingPredictionStillWorks() throws Exception
  {
    assertEquals(RealtimeMerger.LiveState.LIVE,
        merge("canonical-ordinary-stop.pb").state);
  }
}
