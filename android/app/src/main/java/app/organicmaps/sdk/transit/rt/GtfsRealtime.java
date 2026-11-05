package app.organicmaps.sdk.transit.rt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded FULL_DATASET GTFS-RT TripUpdates parser. Unknown fields are skipped, not guessed. */
public final class GtfsRealtime
{
  private GtfsRealtime() {}

  // Canonical google/transit schema: TripUpdate vehicle=3, timestamp=4, delay=5.
  static final int TRIP_CANCELED = 3;
  static final int STOP_SKIPPED = 1;
  static final int STOP_NO_DATA = 2;
  public static final int MAX_ENTITIES = 200_000;
  public static final int MAX_STOP_UPDATES_PER_TRIP = 20_000;
  public static final long MAX_PLAUSIBLE_DELAY_SECONDS = 24L * 3600L;

  public static final class StopTimeEvent
  {
    public final Long time, delay;
    StopTimeEvent(Long time, Long delay) { this.time = time; this.delay = delay; }
  }

  public static final class StopTimeUpdate
  {
    public final String stopId;
    public final Integer stopSequence;
    public final StopTimeEvent arrival, departure;
    public final int scheduleRelationship;
    /** Pilot cannot yet display changed platform/pickup/headsign properties honestly. */
    public final boolean hasUnsupportedStopProperties;
    StopTimeUpdate(String stopId, Integer stopSequence, StopTimeEvent arrival,
                   StopTimeEvent departure, int scheduleRelationship)
    { this(stopId, stopSequence, arrival, departure, scheduleRelationship, false); }
    StopTimeUpdate(String stopId, Integer stopSequence, StopTimeEvent arrival,
                   StopTimeEvent departure, int scheduleRelationship, boolean unsupportedProperties)
    {
      this.hasUnsupportedStopProperties = unsupportedProperties;
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
    public final String tripId, routeId, startDate, startTime;
    public final Integer directionId;
    public final int scheduleRelationship;
    public final Long delay, timestamp;
    public final List<StopTimeUpdate> stopTimeUpdates;
    TripUpdate(String tripId, String routeId, Integer directionId, String startDate,
               int relationship, Long delay, Long timestamp, List<StopTimeUpdate> stops)
    { this(tripId, routeId, directionId, startDate, null, relationship, delay, timestamp, stops); }
    TripUpdate(String tripId, String routeId, Integer directionId, String startDate,
               String startTime, int relationship, Long delay, Long timestamp,
               List<StopTimeUpdate> stops)
    {
      this.tripId = tripId;
      this.routeId = routeId;
      this.directionId = directionId;
      this.startDate = startDate;
      this.startTime = startTime;
      this.scheduleRelationship = relationship;
      this.delay = delay;
      this.timestamp = timestamp;
      this.stopTimeUpdates = Collections.unmodifiableList(new ArrayList<>(stops));
    }
    public boolean isCanceled() { return scheduleRelationship == TRIP_CANCELED; }
  }

  public static final class FeedMessage
  {
    public final Long timestamp;
    public final List<TripUpdate> tripUpdates;
    public final int vehicleEntities, alertEntities;
    FeedMessage(Long timestamp, List<TripUpdate> updates, int vehicles, int alerts)
    {
      this.timestamp = timestamp;
      this.tripUpdates = Collections.unmodifiableList(new ArrayList<>(updates));
      this.vehicleEntities = vehicles;
      this.alertEntities = alerts;
    }
  }

  /** Retain complete updates only for local board trip IDs; do not collapse instances. */
  public static FeedMessage forTripIds(FeedMessage feed, java.util.Set<String> ids)
  {
    List<TripUpdate> updates = new ArrayList<>();
    for (TripUpdate update : feed.tripUpdates)
      if (update.tripId != null && ids.contains(update.tripId)) updates.add(update);
    return new FeedMessage(feed.timestamp, updates, 0, 0);
  }

  private static void wire(WireReader.OutInt actual, int expected)
  {
    if (actual.value != expected) throw new WireReader.WireException("wrong wire type for known field");
  }
  private static byte[] bytes(WireReader r, WireReader.OutInt wt)
  { wire(wt, WireReader.LEN); return r.readBytes(); }
  private static String text(WireReader r, WireReader.OutInt wt)
  {
    byte[] raw = bytes(r, wt);
    if (raw.length > 2048) throw new WireReader.WireException("identifier exceeds bound");
    return WireReader.utf8(raw);
  }
  private static long number(WireReader r, WireReader.OutInt wt)
  { wire(wt, WireReader.VARINT); return r.readVarint(); }
  private static long nonnegative(WireReader r, WireReader.OutInt wt)
  {
    long value = number(r, wt);
    if (value < 0) throw new WireReader.WireException("unsigned value exceeds supported range");
    return value;
  }
  private static int small(WireReader r, WireReader.OutInt wt)
  {
    long value = nonnegative(r, wt);
    if (value > Integer.MAX_VALUE) throw new WireReader.WireException("integer exceeds supported range");
    return (int) value;
  }
  private static long delay(WireReader r, WireReader.OutInt wt)
  {
    long value = number(r, wt);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
      throw new WireReader.WireException("delay exceeds int32 range");
    return value;
  }

  /** No partial result is published for malformed input or unsupported differential feeds. */
  public static FeedMessage parse(byte[] raw)
  {
    if (raw == null || raw.length == 0) throw new WireReader.WireException("empty feed payload");
    WireReader r = new WireReader(raw);
    WireReader.OutInt wt = new WireReader.OutInt();
    Long timestamp = null;
    boolean header = false;
    int entities = 0, vehicles = 0, alerts = 0;
    List<TripUpdate> updates = new ArrayList<>();
    while (r.hasRemaining())
    {
      int f = r.readTag(wt);
      if (f == 1)
      {
        if (header) throw new WireReader.WireException("duplicate feed header");
        header = true;
        WireReader h = new WireReader(bytes(r, wt));
        String version = null;
        while (h.hasRemaining())
        {
          int hf = h.readTag(wt);
          if (hf == 1) version = text(h, wt);
          else if (hf == 2)
          {
            if (small(h, wt) != 0) throw new WireReader.WireException("differential feed unsupported");
          }
          else if (hf == 3) timestamp = nonnegative(h, wt);
          else h.skip(wt.value);
        }
        if (!"1.0".equals(version) && !"2.0".equals(version))
          throw new WireReader.WireException("missing or unsupported feed version");
      }
      else if (f == 2)
      {
        if (++entities > MAX_ENTITIES) throw new WireReader.WireException("too many feed entities");
        WireReader e = new WireReader(bytes(r, wt));
        String id = null;
        boolean deleted = false, vehicle = false, alert = false;
        TripUpdate update = null;
        while (e.hasRemaining())
        {
          int ef = e.readTag(wt);
          if (ef == 1) id = text(e, wt);
          else if (ef == 2)
          {
            int value = small(e, wt);
            if (value > 1) throw new WireReader.WireException("invalid deletion flag");
            deleted = value == 1;
          }
          else if (ef == 3) update = parseTrip(bytes(e, wt));
          else if (ef == 4) { bytes(e, wt); vehicle = true; }
          else if (ef == 5) { bytes(e, wt); alert = true; }
          else e.skip(wt.value);
        }
        if (id == null || id.isEmpty()) throw new WireReader.WireException("entity missing id");
        if (!deleted)
        {
          if (update != null) updates.add(update);
          else if (vehicle) vehicles++;
          else if (alert) alerts++;
        }
      }
      else r.skip(wt.value);
    }
    if (!header) throw new WireReader.WireException("feed has no header");
    return new FeedMessage(timestamp, updates, vehicles, alerts);
  }

  private static TripUpdate parseTrip(byte[] raw)
  {
    WireReader r = new WireReader(raw);
    WireReader.OutInt wt = new WireReader.OutInt();
    String trip = null, route = null, date = null, start = null;
    Integer direction = null;
    int relationship = 0;
    boolean descriptor = false;
    Long delay = null, timestamp = null;
    List<StopTimeUpdate> stops = new ArrayList<>();
    while (r.hasRemaining())
    {
      int f = r.readTag(wt);
      if (f == 1)
      {
        descriptor = true;
        WireReader t = new WireReader(bytes(r, wt));
        while (t.hasRemaining())
        {
          int tf = t.readTag(wt);
          if (tf == 1) trip = text(t, wt);
          else if (tf == 2) start = text(t, wt);
          else if (tf == 3) date = text(t, wt);
          else if (tf == 4) relationship = small(t, wt);
          else if (tf == 5) route = text(t, wt);
          else if (tf == 6) direction = small(t, wt);
          else t.skip(wt.value);
        }
      }
      else if (f == 2)
      {
        if (stops.size() >= MAX_STOP_UPDATES_PER_TRIP)
          throw new WireReader.WireException("too many stop updates");
        stops.add(parseStop(bytes(r, wt)));
      }
      else if (f == 3) bytes(r, wt); // VehicleDescriptor: intentionally unused.
      else if (f == 4) timestamp = nonnegative(r, wt);
      else if (f == 5) delay = delay(r, wt);
      else r.skip(wt.value);
    }
    if (!descriptor) throw new WireReader.WireException("trip update missing descriptor");
    return new TripUpdate(trip, route, direction, date, start, relationship, delay, timestamp, stops);
  }

  private static StopTimeUpdate parseStop(byte[] raw)
  {
    WireReader r = new WireReader(raw);
    WireReader.OutInt wt = new WireReader.OutInt();
    String stop = null;
    Integer sequence = null;
    StopTimeEvent arrival = null, departure = null;
    int relationship = 0;
    boolean unsupportedProperties = false;
    while (r.hasRemaining())
    {
      int f = r.readTag(wt);
      if (f == 1) sequence = small(r, wt);
      else if (f == 2) arrival = parseEvent(bytes(r, wt));
      else if (f == 3) departure = parseEvent(bytes(r, wt));
      else if (f == 4) stop = text(r, wt);
      else if (f == 5) relationship = small(r, wt);
      else if (f == 6)
      {
        bytes(r, wt); // Canonical StopTimeProperties: bounded framing, no invented support.
        unsupportedProperties = true;
      }
      else r.skip(wt.value);
    }
    return new StopTimeUpdate(stop, sequence, arrival, departure, relationship, unsupportedProperties);
  }

  private static StopTimeEvent parseEvent(byte[] raw)
  {
    WireReader r = new WireReader(raw);
    WireReader.OutInt wt = new WireReader.OutInt();
    Long time = null, delay = null;
    while (r.hasRemaining())
    {
      int f = r.readTag(wt);
      if (f == 1) delay = delay(r, wt);
      else if (f == 2) time = number(r, wt);
      else r.skip(wt.value);
    }
    return new StopTimeEvent(time, delay);
  }
}
