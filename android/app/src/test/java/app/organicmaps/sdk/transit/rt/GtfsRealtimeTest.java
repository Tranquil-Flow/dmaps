package app.organicmaps.sdk.transit.rt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for the Java realtime client.
 *
 * All inputs here are SYNTHETIC, built inline by this class. Real-feed
 * behaviour is measured separately by rt-lane/probe_feed.py, so a passing test
 * is never mistaken for a field observation.
 */
@RunWith(JUnit4.class)
public class GtfsRealtimeTest
{
  // ---- minimal protobuf writer ------------------------------------------------

  private static void varint(ByteArrayOutputStream o, long v)
  {
    // Java's long is already two's complement, so a negative value encodes to
    // the correct wide varint with no adjustment. The unsigned shift brings the
    // sign-extension bits down; the loop ends after at most 10 bytes.
    while (true)
    {
      int b = (int) (v & 0x7F);
      v >>>= 7;
      o.write(v != 0 ? b | 0x80 : b);
      if (v == 0)
        return;
    }
  }

  private static byte[] fVarint(int field, long value)
  {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    varint(o, (long) field << 3);
    varint(o, value);
    return o.toByteArray();
  }

  private static byte[] fBytes(int field, byte[] payload)
  {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    varint(o, ((long) field << 3) | 2);
    varint(o, payload.length);
    o.write(payload, 0, payload.length);
    return o.toByteArray();
  }

  private static byte[] fString(int field, String s)
  {
    return fBytes(field, s.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] cat(byte[]... parts)
  {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    for (byte[] p : parts)
      o.write(p, 0, p.length);
    return o.toByteArray();
  }

  private static void put(ByteArrayOutputStream o, byte[] b)
  {
    o.write(b, 0, b.length);
  }

  private static byte[] stopTimeEvent(long time, long delay)
  {
    return cat(fVarint(1, delay), fVarint(2, time));
  }

  private static byte[] stopTimeUpdate(String stopId, long time, long delay)
  {
    return cat(fVarint(1, 1), fBytes(2, stopTimeEvent(time, delay)),
               fBytes(3, stopTimeEvent(time, delay)), fString(4, stopId));
  }

  private static byte[] tripUpdate(String tripId, String routeId, long delay,
                                   byte[]... stops)
  {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    put(o, fBytes(1, cat(fString(1, tripId), fString(5, routeId))));
    for (byte[] s : stops)
      put(o, fBytes(2, s));
    put(o, fVarint(4, 1791880000L));
    put(o, fVarint(5, delay));
    return o.toByteArray();
  }

  private static byte[] entity(String id, byte[] update)
  {
    return cat(fString(1, id), fBytes(3, update));
  }

  private static byte[] vehicleEntity(String id)
  {
    return cat(fString(1, id), fBytes(4, new byte[0]));
  }

  private static byte[] feed(long timestamp, byte[]... entities)
  {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    put(o, fBytes(1, cat(fString(1, "2.0"), fVarint(3, timestamp))));
    for (byte[] e : entities)
      put(o, fBytes(2, e));
    return o.toByteArray();
  }

  // ---- parsing ----------------------------------------------------------------

  @Test
  public void parsesHeaderAndTrip()
  {
    byte[] raw = feed(1791880000L,
        entity("e1", tripUpdate("T100", "U5", 180,
            stopTimeUpdate("S_ALEX", 1791880400L, 180))));
    GtfsRealtime.FeedMessage m = GtfsRealtime.parse(raw);
    assertEquals(Long.valueOf(1791880000L), m.timestamp);
    assertEquals(1, m.tripUpdates.size());
    GtfsRealtime.TripUpdate u = m.tripUpdates.get(0);
    assertEquals("T100", u.tripId);
    assertEquals("U5", u.routeId);
    assertEquals(Long.valueOf(180L), u.delay);
    assertEquals("S_ALEX", u.stopTimeUpdates.get(0).stopId);
  }

  @Test
  public void negativeDelayDecodesSigned()
  {
    // Protobuf encodes negative int32 as a 64-bit two's-complement varint.
    // Java's signed long must yield -60, not ~1.8e19.
    byte[] raw = feed(1L, entity("e", tripUpdate("T", "U5", -60)));
    GtfsRealtime.TripUpdate u = GtfsRealtime.parse(raw).tripUpdates.get(0);
    assertEquals(Long.valueOf(-60L), u.delay);
  }

  @Test
  public void ordinaryScheduledTripIsNotCanceled()
  {
    byte[] raw = feed(1L, entity("e", tripUpdate("T200", "S9", 0)));
    GtfsRealtime.TripUpdate u = GtfsRealtime.parse(raw).tripUpdates.get(0);
    assertFalse(u.isCanceled());
  }

  @Test
  public void emptyPayloadThrows()
  {
    try
    {
      GtfsRealtime.parse(new byte[0]);
      fail("an empty payload must raise, not parse to an empty feed");
    }
    catch (WireReader.WireException expected) { /* required */ }
  }

  @Test
  public void headerlessPayloadThrows()
  {
    // A 200 with an empty body must not read as "no live updates".
    try
    {
      GtfsRealtime.parse(new byte[] { (byte) 0x92, 0x01, 0x00 });
      fail("a payload with no header must raise");
    }
    catch (WireReader.WireException expected) { /* required */ }
  }

  // ---- semantic magnitude bounds (synthetic; not a provider deviation) --------

  @Test
  public void epochInTripDelayIsRejectedAsImplausible()
  {
    // Synthetic invalid delay. Canonical field 4 is timestamp, field 5 is delay.
    long epoch = 1791872519L;
    assertFalse(RealtimeMerger.isPlausibleDelay(epoch));
    assertFalse(RealtimeMerger.isPlausibleDelay(-epoch));
    assertTrue(RealtimeMerger.isPlausibleDelay(180L));
    assertTrue(RealtimeMerger.isPlausibleDelay(0L));
    assertFalse(RealtimeMerger.isPlausibleDelay(null));
  }

  @Test
  public void epochDelayDoesNotInflateARow()
  {
    long epoch = 1791872519L;
    byte[] raw = feed(1L, entity("e", tripUpdate("T100", "U5", epoch)));
    Map<String, GtfsRealtime.TripUpdate> joined = RealtimeMerger.joinToPack(
        GtfsRealtime.parse(raw), ids("T100"));

    RealtimeMerger.ScheduledRow row = new RealtimeMerger.ScheduledRow(
        "T100", "U5", "U Hönow", "2", 1791818400L, "S_ALEX");
    List<RealtimeMerger.DepartureRow> rows = RealtimeMerger.mergeBoard(
        Collections.singletonList(row), joined, 1L, 1791880000L,
        RealtimeMerger.DEFAULT_DELAY_THRESHOLD_SECONDS,
        RealtimeMerger.DEFAULT_STALE_AFTER_SECONDS);

    assertEquals(1, rows.size());
    assertEquals(RealtimeMerger.LiveState.SCHEDULED_ONLY, rows.get(0).state);
    assertNull(rows.get(0).delaySeconds);
    assertNull(rows.get(0).liveDeparture);
  }

  @Test
  public void perStopTimeWinsOverBadTripDelay()
  {
    long absolute = 1791818700L;
    long epoch = 1791872519L;
    byte[] raw = feed(1L, entity("e", tripUpdate("T100", "U5", epoch,
        stopTimeUpdate("S_ALEX", absolute, 300))));
    Map<String, GtfsRealtime.TripUpdate> joined = RealtimeMerger.joinToPack(
        GtfsRealtime.parse(raw), ids("T100"));

    RealtimeMerger.ScheduledRow row = new RealtimeMerger.ScheduledRow(
        "T100", "U5", "U Hönow", "2", 1791818400L, "S_ALEX");
    List<RealtimeMerger.DepartureRow> rows = RealtimeMerger.mergeBoard(
        Collections.singletonList(row), joined, 1L, 1791880000L, 90,
        RealtimeMerger.DEFAULT_STALE_AFTER_SECONDS);

    assertEquals(Long.valueOf(absolute), rows.get(0).liveDeparture);
    assertEquals(RealtimeMerger.LiveState.DELAYED, rows.get(0).state);
  }

  // ---- the safety invariants --------------------------------------------------

  @Test
  public void unmatchedTripIsDroppedNeverHeuristicallyMatched()
  {
    byte[] raw = feed(1L, entity("e", tripUpdate("T_PHANTOM", "U5", 120)));
    Map<String, GtfsRealtime.TripUpdate> joined = RealtimeMerger.joinToPack(
        GtfsRealtime.parse(raw), ids("T100"));
    assertTrue("an unresolved trip must be dropped, not matched",
               joined.isEmpty());
  }

  @Test
  public void unresolvedRowIsScheduledOnlyNeverCanceled()
  {
    byte[] raw = feed(1L, entity("e", tripUpdate("T_PHANTOM", "U5", 120)));
    Map<String, GtfsRealtime.TripUpdate> joined = RealtimeMerger.joinToPack(
        GtfsRealtime.parse(raw), ids("T100"));
    RealtimeMerger.ScheduledRow row = new RealtimeMerger.ScheduledRow(
        "T100", "U5", "U Hönow", "2", 1791818400L, "S_ALEX");
    List<RealtimeMerger.DepartureRow> rows = RealtimeMerger.mergeBoard(
        Collections.singletonList(row), joined, 1L, 1791880000L, 90, 600);

    assertEquals(RealtimeMerger.LiveState.SCHEDULED_ONLY, rows.get(0).state);
    assertTrue(rows.get(0).isBoardable());
  }

  @Test
  public void scheduleOrderIsPreserved()
  {
    byte[] raw = feed(1L, entity("e", tripUpdate("T100", "U5", 300,
        stopTimeUpdate("S_ALEX", 1791818700L, 300))));
    Map<String, GtfsRealtime.TripUpdate> joined = RealtimeMerger.joinToPack(
        GtfsRealtime.parse(raw), ids("T100", "T200"));

    java.util.ArrayList<RealtimeMerger.ScheduledRow> board = new java.util.ArrayList<>();
    board.add(new RealtimeMerger.ScheduledRow("T100", "U5", "A", "1", 1791818400L, "S_ALEX"));
    board.add(new RealtimeMerger.ScheduledRow("T200", "U2", "B", "2", 1791819000L, "S_ALEX"));
    List<RealtimeMerger.DepartureRow> rows = RealtimeMerger.mergeBoard(
        board, joined, 1L, 1791880000L, 90, 600);

    assertEquals("T100", rows.get(0).tripSourceId);
    assertEquals("T200", rows.get(1).tripSourceId);
  }

  @Test
  public void staleFeedMarksRows()
  {
    long headerTs = 1791880000L - 3600;   // an hour old
    byte[] raw = feed(headerTs, entity("e", tripUpdate("T100", "U5", 300)));
    Map<String, GtfsRealtime.TripUpdate> joined = RealtimeMerger.joinToPack(
        GtfsRealtime.parse(raw), ids("T100"));
    RealtimeMerger.ScheduledRow row = new RealtimeMerger.ScheduledRow(
        "T100", "U5", "A", "1", 1791818400L, "S_ALEX");
    List<RealtimeMerger.DepartureRow> rows = RealtimeMerger.mergeBoard(
        Collections.singletonList(row), joined, headerTs, 1791880000L, 90, 600);
    assertTrue("an hour-old feed must be marked stale", rows.get(0).stale);
  }

  @Test
  public void vehicleEntitiesAreCountedNotDecoded()
  {
    byte[] raw = feed(1L, vehicleEntity("v"),
                      entity("e", tripUpdate("T1", "U5", 0)));
    GtfsRealtime.FeedMessage m = GtfsRealtime.parse(raw);
    assertEquals(1, m.tripUpdates.size());
    assertEquals(1, m.vehicleEntities);
  }

  private static HashSet<String> ids(String... v)
  {
    HashSet<String> s = new HashSet<>();
    Collections.addAll(s, v);
    return s;
  }
}
