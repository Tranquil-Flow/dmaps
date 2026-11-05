package app.organicmaps.sdk.transit;

/** Immutable published rule, NOT an inferred walkable edge or resolved transfer permission.
 * Null selectors retain wildcard/applicability semantics for the later routing consumer. */
public final class TransferRule
{
  public final long id;
  public final Long fromStopId, toStopId, fromRouteId, toRouteId, fromTripId, toTripId;
  public final int transferType;
  public final Integer minTransferSeconds;

  TransferRule(long id, Long fromStopId, Long toStopId, Long fromRouteId, Long toRouteId,
               Long fromTripId, Long toTripId, int transferType, Integer minimum)
  {
    this.id=id; this.fromStopId=fromStopId; this.toStopId=toStopId;
    this.fromRouteId=fromRouteId; this.toRouteId=toRouteId;
    this.fromTripId=fromTripId; this.toTripId=toTripId;
    this.transferType=transferType; this.minTransferSeconds=minimum;
  }
}
