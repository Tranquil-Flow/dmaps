package app.organicmaps.sdk.transit.rt;

import java.util.ArrayList;
import java.util.List;

/**
 * GTFS-RT FeedMessage parsing — TripUpdates subset.
 *
 * Parses only what a departure board needs: trip identity, per-stop arrival and
 * departure times, delays and cancellation state. VehiclePositions and alerts
 * are counted, not decoded — a board showing scheduled-vs-live does not need
 * them, and decoding less keeps the trust boundary small.
 *
 * Field numbers follow the published gtfs-realtime.proto. Structural
 * counterpart of timetable_rt/gtfsrt.py.
 */
public final class GtfsRealtime
{
  private GtfsRealtime() {}

  // --- gtfs-realtime.proto field numbers ---
  static final int FEEDHEADER_TIMESTAMP = 3;
  static final int FEEDENTITY_ID = 1;
  static final int FEEDENTITY_IS_DELETED = 2;
  static final int FEEDENTITY_TRIP_UPDATE = 3;
  static final int FEEDENTITY_VEHICLE = 4;
  static final int FEEDENTITY_ALERT = 5;
  static final int TRIPUPDATE_TRIP = 1;
  static final int TRIPUPDATE_STOP_TIME_UPDATE = 2;
  static final int TRIPUPDATE_TIMESTAMP = 3;
  static final int TRIPUPDATE_DELAY = 4;
  static final int TRIPDESC_TRIP_ID = 1;
  static final int TRIPDESC_START_DATE = 3;
  static final int TRIPDESC_SCHEDULE_RELATIONSHIP = 4;
  static final int TRIPDESC_ROUTE_ID = 5;
  static final int TRIPDESC_DIRECTION_ID = 6;
  static final int STU_ARRIVAL = 2;
  static final int STU_DEPARTURE = 3;
  static final int STU_STOP_ID = 4;
  static final int STU_SCHEDULE_RELATIONSHIP = 5;
  static final int STE_DELAY = 1;
  static final int STE_TIME = 2;

  static final int TRIP_CANCELED = 3;
  static final int STOP_SKIPPED = 1;

  /** Refuse feeds claiming implausibly many entities before materialising them. */
  public static final int MAX_ENTITIES = 200_000;

  /** Beyond this a delay cannot describe lateness. See RealtimeMerger. */
  public static final long MAX_PLAUSIBLE_DELAY_SECONDS = 24L * 3600L;

  public static final class StopTimeEvent
  {
    public final Long time;          // absolute epoch seconds, or null
    public final Long delay;         // seconds vs schedule, or null
    StopTimeEvent(Long time, Long delay) { this.time = time; this.delay = delay; }
  }

  public static final class StopTimeUpdate
  {
    public final String stopId;
    public final Integer stopSequence;
    public final StopTimeEvent arrival;
    public final StopTimeEvent departure;
    public final int scheduleRelationship;

    StopTimeUpdate(String stopId, Integer stopSequence, StopTimeEvent arrival,
                   StopTimeEvent departure, int scheduleRelationship)
    {
      this.stopId = stopId;
      this.stopSequence = stopSequence;
      this.arrival = arrival;
      this.departure = departure;
      this.scheduleRelationship = scheduleRelationship;
    }

    public boolean isSkipped() { return scheduleRelationship == STOP_SKIPPED; }
  }

  public static final class TripUpdate
  {
    public final String tripId;
    public final String routeId;
    public final Integer directionId;
    public final String startDate;
    public final int scheduleRelationship;
    public final Long delay;
    public final Long timestamp;
    public final List<StopTimeUpdate> stopTimeUpdates;

    TripUpdate(String tripId, String routeId, Integer directionId,
               String startDate, int scheduleRelationship, Long delay,
               Long timestamp, List<StopTimeUpdate> stopTimeUpdates)
    {
      this.tripId = tripId;
      this.routeId = routeId;
      this.directionId = directionId;
      this.startDate = startDate;
      this.scheduleRelationship = scheduleRelationship;
      this.delay = delay;
      this.timestamp = timestamp;
      this.stopTimeUpdates = stopTimeUpdates;
    }

    public boolean isCanceled() { return scheduleRelationship == TRIP_CANCELED; }
  }

  public static final class FeedMessage
  {
    public final Long timestamp;
    public final List<TripUpdate> tripUpdates;
    public final int vehicleEntities;
    public final int alertEntities;

    FeedMessage(Long timestamp, List<TripUpdate> tripUpdates,
                int vehicleEntities, int alertEntities)
    {
      this.timestamp = timestamp;
      this.tripUpdates = tripUpdates;
      this.vehicleEntities = vehicleEntities;
      this.alertEntities = alertEntities;
    }
  }

  /**
   * Parses a complete FeedMessage. Throws WireException on malformed input.
   *
   * A header is mandatory. A zero-length or headerless body throws rather than
   * yielding an empty feed, because "no updates" and "no response" must never
   * look alike to the caller — conflating them turns a failed fetch into a
   * false claim that a service is running normally.
   */
  public static FeedMessage parse(byte[] raw)
  {
    if (raw == null || raw.length == 0)
      throw new WireReader.WireException("empty feed payload");

    Long timestamp = null;
    boolean sawHeader = false;
    List<TripUpdate> updates = new ArrayList<>();
    int vehicles = 0, alerts = 0, entityCount = 0;

    WireReader r = new WireReader(raw);
    WireReader.OutInt wt = new WireReader.OutInt();
    while (r.hasRemaining())
    {
      int field = r.readTag(wt);
      if (field == 1)
      {
        sawHeader = true;
        byte[] hdr = r.readBytes();
        WireReader h = new WireReader(hdr);
        while (h.hasRemaining())
        {
          int hf = h.readTag(wt);
          if (hf == FEEDHEADER_TIMESTAMP)
            timestamp = h.readVarint();
          else
            h.skip(wt.value);
        }
      }
      else if (field == 2)
      {
        if (++entityCount > MAX_ENTITIES)
          throw new WireReader.WireException(
              "feed claims more than " + MAX_ENTITIES + " entities; refusing");
        byte[] ent = r.readBytes();
        TripUpdate u = parseEntity(ent, wt);
        if (u != null)
          updates.add(u);
        else if (entityHas(ent, FEEDENTITY_VEHICLE))
          vehicles++;
        else if (entityHas(ent, FEEDENTITY_ALERT))
          alerts++;
      }
      else
      {
        r.skip(wt.value);
      }
    }

    if (!sawHeader)
      throw new WireReader.WireException(
          "feed has no header; refusing to treat a headerless payload as empty");

    return new FeedMessage(timestamp, updates, vehicles, alerts);
  }

  private static boolean entityHas(byte[] ent, int wantField)
  {
    WireReader r = new WireReader(ent);
    WireReader.OutInt wt = new WireReader.OutInt();
    while (r.hasRemaining())
      if (r.readTag(wt) == wantField)
        return true;
      else
        r.skip(wt.value);
    return false;
  }

  private static TripUpdate parseEntity(byte[] ent, WireReader.OutInt wt)
  {
    WireReader r = new WireReader(ent);
    while (r.hasRemaining())
    {
      int f = r.readTag(wt);
      if (f == FEEDENTITY_TRIP_UPDATE)
        return parseTripUpdate(r.readBytes(), wt);
      r.skip(wt.value);
    }
    return null;
  }

  private static TripUpdate parseTripUpdate(byte[] raw, WireReader.OutInt wt)
  {
    String tripId = null, routeId = null, startDate = null;
    Integer directionId = null;
    int relationship = 0;
    Long delay = null, timestamp = null;
    List<StopTimeUpdate> stops = new ArrayList<>();

    WireReader r = new WireReader(raw);
    while (r.hasRemaining())
    {
      int f = r.readTag(wt);
      if (f == TRIPUPDATE_TRIP)
      {
        WireReader t = new WireReader(r.readBytes());
        while (t.hasRemaining())
        {
          int tf = t.readTag(wt);
          if (tf == TRIPDESC_TRIP_ID)
            tripId = WireReader.utf8(t.readBytes());
          else if (tf == TRIPDESC_ROUTE_ID)
            routeId = WireReader.utf8(t.readBytes());
          else if (tf == TRIPDESC_START_DATE)
            startDate = WireReader.utf8(t.readBytes());
          else if (tf == TRIPDESC_DIRECTION_ID)
            directionId = (int) t.readVarint();
          else if (tf == TRIPDESC_SCHEDULE_RELATIONSHIP)
            relationship = (int) t.readVarint();
          else
            t.skip(wt.value);
        }
      }
      else if (f == TRIPUPDATE_STOP_TIME_UPDATE)
      {
        stops.add(parseStopTimeUpdate(r.readBytes(), wt));
      }
      else if (f == TRIPUPDATE_TIMESTAMP)
      {
        timestamp = r.readVarint();
      }
      else if (f == TRIPUPDATE_DELAY)
      {
        // Protobuf encodes negative int32 as a full 64-bit two's-complement
        // varint; Java's signed long already yields the correct negative value
        // with no reinterpretation step.
        delay = r.readVarint();
      }
      else
      {
        r.skip(wt.value);
      }
    }
    return new TripUpdate(tripId, routeId, directionId, startDate,
                          relationship, delay, timestamp, stops);
  }

  private static StopTimeUpdate parseStopTimeUpdate(byte[] raw, WireReader.OutInt wt)
  {
    String stopId = null;
    Integer stopSeq = null;
    StopTimeEvent arrival = null, departure = null;
    int relationship = 0;

    WireReader r = new WireReader(raw);
    while (r.hasRemaining())
    {
      int f = r.readTag(wt);
      if (f == STU_STOP_ID)
        stopId = WireReader.utf8(r.readBytes());
      else if (f == 1)                       // stop_sequence
        stopSeq = (int) r.readVarint();
      else if (f == STU_ARRIVAL)
        arrival = parseEvent(r.readBytes(), wt);
      else if (f == STU_DEPARTURE)
        departure = parseEvent(r.readBytes(), wt);
      else if (f == STU_SCHEDULE_RELATIONSHIP)
        relationship = (int) r.readVarint();
      else
        r.skip(wt.value);
    }
    return new StopTimeUpdate(stopId, stopSeq, arrival, departure, relationship);
  }

  private static StopTimeEvent parseEvent(byte[] raw, WireReader.OutInt wt)
  {
    Long time = null, delay = null;
    WireReader r = new WireReader(raw);
    while (r.hasRemaining())
    {
      int f = r.readTag(wt);
      if (f == STE_TIME)
        time = r.readVarint();
      else if (f == STE_DELAY)
        delay = r.readVarint();
      else
        r.skip(wt.value);
    }
    return new StopTimeEvent(time, delay);
  }
}
