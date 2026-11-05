package app.organicmaps.sdk.transit;

import java.time.Instant;
import java.time.LocalDate;

/** Immutable scheduled trip occurrence. Null timing stays unknown; restrictions are not permissions. */
public final class TripStop
{
  public final long tripId, routeId, stopId;
  public final String tripSourceId, line, destination, stopSourceId, stopName, platform, timezone;
  // destination is the effective stop-time headsign, falling back to the trip headsign.
  public final int stopSequence, pickupType, dropOffType, stopLocationType;
  public final Integer timepoint;
  public final LocalDate serviceDate;
  public final Instant arrival, departure;

  TripStop(long tripId, long routeId, String tripSourceId, String line, String destination,
           int sequence, long stopId, String stopSourceId, String stopName, String platform,
           Instant arrival, Instant departure, int pickup, int dropOff, Integer timepoint,
           int locationType, LocalDate serviceDate, String timezone)
  {
    this.tripId=tripId; this.routeId=routeId; this.tripSourceId=tripSourceId;
    this.line=line; this.destination=destination; this.stopSequence=sequence;
    this.stopId=stopId; this.stopSourceId=stopSourceId; this.stopName=stopName; this.platform=platform;
    this.arrival=arrival; this.departure=departure; this.pickupType=pickup; this.dropOffType=dropOff;
    this.timepoint=timepoint; this.stopLocationType=locationType;
    this.serviceDate=serviceDate; this.timezone=timezone;
  }
}
