package app.organicmaps.sdk.transit.rt;

import app.organicmaps.sdk.transit.Departure;
import java.util.Collections;

/** Fresh synthetic cancellation for UI cache controls; no feed endpoint or transport. */
public final class CachedLiveUiFixture {
  private CachedLiveUiFixture() { }
  public static GtfsRealtime.FeedMessage cancellation(Departure row,long now) {
    GtfsRealtime.TripUpdate trip=new GtfsRealtime.TripUpdate(row.tripSourceId,null,null,
        row.serviceDate.replace("-",""),GtfsRealtime.TRIP_CANCELED,null,now,Collections.emptyList());
    return new GtfsRealtime.FeedMessage(now,Collections.singletonList(trip),0,0);
  }
}
