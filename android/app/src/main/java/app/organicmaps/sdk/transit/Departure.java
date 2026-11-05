package app.organicmaps.sdk.transit;

import java.time.Instant;

/**
 * Immutable scheduled departure. Always scheduled data: never tagged live or
 * on-time. When a parent station is selected, each departure keeps its actual
 * platform/stop (stopId/stopName/platform), never a synthetic merged row.
 */
public final class Departure
{
  public final String tripSourceId, line, destination, stopName, platform;
  public final int routeType, pickupType;
  public final Integer timepoint;  // nullable; unknown timing preserved, not "exact"
  public final Instant when;
  public final String timezone;
  public final long stopId;
  /** GTFS stop_id of the platform serving this departure; realtime join key. */
  public final String stopSourceId;
  public final String serviceDate; // ISO yyyy-MM-dd
  public final Integer stopSequence;
  /** Visits to this stop in the complete static trip; 0 means unknown, not unique. */
  public final int stopVisitCount;

  public Departure(String tripSourceId, String line, String destination, String stopName,
                   String platform, int routeType, int pickupType, Integer timepoint,
                   Instant when, String timezone, long stopId, String stopSourceId,
                   String serviceDate)
  {
    this(tripSourceId, line, destination, stopName, platform, routeType, pickupType, timepoint,
        when, timezone, stopId, stopSourceId, serviceDate, null);
  }

  public Departure(String tripSourceId, String line, String destination, String stopName,
                   String platform, int routeType, int pickupType, Integer timepoint,
                   Instant when, String timezone, long stopId, String stopSourceId,
                   String serviceDate, Integer stopSequence)
  {
    this(tripSourceId, line, destination, stopName, platform, routeType, pickupType, timepoint,
        when, timezone, stopId, stopSourceId, serviceDate, stopSequence, 0);
  }

  public Departure(String tripSourceId, String line, String destination, String stopName,
                   String platform, int routeType, int pickupType, Integer timepoint,
                   Instant when, String timezone, long stopId, String stopSourceId,
                   String serviceDate, Integer stopSequence, int stopVisitCount)
  {
    this.stopVisitCount = stopVisitCount;
    this.stopSequence = stopSequence;
    this.tripSourceId = tripSourceId;
    this.line = line;
    this.destination = destination;
    this.stopName = stopName;
    this.platform = platform;
    this.routeType = routeType;
    this.pickupType = pickupType;
    this.timepoint = timepoint;
    this.when = when;
    this.timezone = timezone;
    this.stopId = stopId;
    this.stopSourceId = stopSourceId;
    this.serviceDate = serviceDate;
  }

  /** Rider-facing flag: pickup types 2/3 require reservation/coordination. */
  public boolean requiresReservation()
  {
    return pickupType == 2 || pickupType == 3;
  }
}
