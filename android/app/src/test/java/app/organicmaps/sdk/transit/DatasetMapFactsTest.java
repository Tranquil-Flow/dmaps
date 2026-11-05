package app.organicmaps.sdk.transit;

import static app.organicmaps.sdk.transit.DatasetMapFactsFixture.BAVARIA;
import static app.organicmaps.sdk.transit.DatasetMapFactsFixture.BERLIN;
import static app.organicmaps.sdk.transit.DatasetMapFactsFixture.candidate;
import static app.organicmaps.sdk.transit.DatasetMapFactsFixture.inventory;
import static app.organicmaps.sdk.transit.DatasetMapFactsFixture.versions;
import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Immutable map generation facts for S1: one registry capture in, presence plus the real Candidate.mapVersion
 * values out. Pure host JVM, no Android, no native. The facts feed the one S1 status; they add no freshness
 * model and never change a Kind.
 */
public class DatasetMapFactsTest
{
  private static final DatasetStatus.MapFacts UNKNOWN = DatasetStatus.MapFacts.UNKNOWN;

  private static DatasetStatus status(DatasetStatusCorpus.Row row, DatasetStatus.MapFacts facts)
  {
    return DatasetStatus.computeWith(row.scope, row.pack, row.unavailable, facts, row.now, row.activation, null);
  }

  private static DatasetStatusCorpus.Row row(String name) throws Exception
  {
    for (DatasetStatusCorpus.Row r : DatasetStatusCorpus.rows()) if (r.name.equals(name)) return r;
    throw new AssertionError("no corpus row " + name);
  }

  // ---- the facts are the actual candidate versions: sorted, distinct, bounded, immutable ---------------------------
  @Test public void versionsAreTheActualCandidateVersionsSortedAndDistinct() throws Exception
  {
    DatasetStatus.MapFacts f = DatasetStatus.MapFacts.of(versions(BERLIN, 261104L, 261020L, 261104L));
    assertEquals(DatasetStatus.MapPresence.PRESENT, f.presence);
    assertEquals(Arrays.asList(261020L, 261104L), f.versions);
    assertEquals(2, f.distinctVersions);
  }

  @Test public void theVersionListIsBoundedAndKeepsTheNewest() throws Exception
  {
    long[] ten = new long[10];
    for (int i = 0; i < ten.length; i++) ten[i] = 260000L + (9 - i); // arrives newest-first
    DatasetStatus.MapFacts f = DatasetStatus.MapFacts.of(versions(BERLIN, ten));
    assertEquals(DatasetStatus.MapFacts.MAX_VERSIONS, f.versions.size());
    assertEquals(10, f.distinctVersions);
    assertEquals(Arrays.asList(260004L, 260005L, 260006L, 260007L, 260008L, 260009L), f.versions);
  }

  @Test public void theCapturedListCannotBeMutated() throws Exception
  {
    DatasetStatus.MapFacts f = DatasetStatus.MapFacts.of(versions(BERLIN, 261020L));
    try { f.versions.add(1L); fail("versions must be unmodifiable"); }
    catch (UnsupportedOperationException expected) { /* immutable */ }
    try { DatasetStatus.MapFacts.UNKNOWN.versions.add(1L); fail("unknown versions must be unmodifiable"); }
    catch (UnsupportedOperationException expected) { /* immutable */ }
    // final fields only: no setter-like surface.
    for (Field field : DatasetStatus.MapFacts.class.getDeclaredFields())
      if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()))
        assertTrue(field.getName(), java.lang.reflect.Modifier.isFinal(field.getModifiers()));
  }

  // ---- unknown is neither missing nor ready -------------------------------------------------------------------------
  @Test public void anIncompleteOrNullCaptureIsUnknownWithNoVersions() throws Exception
  {
    assertEquals(UNKNOWN, DatasetStatus.MapFacts.of(null));
    assertEquals(UNKNOWN, DatasetStatus.MapFacts.of(inventory(false)));
    assertTrue(DatasetStatus.MapFacts.of(inventory(false)).versions.isEmpty());
    assertEquals(DatasetStatus.MapPresence.UNKNOWN, DatasetStatus.MapFacts.ofPresence(null).presence);
  }

  @Test public void aCompleteEmptyCaptureIsAbsentWithNoVersions() throws Exception
  {
    DatasetStatus.MapFacts f = DatasetStatus.MapFacts.of(inventory(true));
    assertEquals(DatasetStatus.MapPresence.ABSENT, f.presence);
    assertTrue(f.versions.isEmpty());
    assertEquals(0, f.distinctVersions);
  }

  @Test public void anUnknownCaptureIsNeitherMissingNorReadyForAnyCurrentTimetable() throws Exception
  {
    for (String name : new String[] {"ready-official", "ready-untrusted", "expiring-4-days", "map-missing", "map-unknown",
                                     "kept-previous"})
    {
      DatasetStatusCorpus.Row r = row(name);
      for (DatasetStatus.MapFacts unknown : new DatasetStatus.MapFacts[] {UNKNOWN, null})
      {
        DatasetStatus s = status(r, unknown);
        assertEquals(name, DatasetStatus.Kind.MAP_UNKNOWN, s.kind);
        assertNotEquals(name, DatasetStatus.Kind.MAP_MISSING, s.kind);
        assertFalse(name, s.isReady());
        assertEquals(name, DatasetStatus.Action.RECHECK, s.action());
      }
    }
  }

  @Test public void anAbsentCaptureIsMapMissingAndOnlyForACurrentTimetable() throws Exception
  {
    assertEquals(DatasetStatus.Kind.MAP_MISSING, status(row("ready-untrusted"), DatasetStatus.MapFacts.ABSENT).kind);
    // A timetable problem is never masked by the map answer.
    assertEquals(DatasetStatus.Kind.EXPIRED, status(row("expired"), DatasetStatus.MapFacts.ABSENT).kind);
    assertEquals(DatasetStatus.Kind.NO_PACK, status(row("no-pack"), DatasetStatus.MapFacts.ABSENT).kind);
  }

  // ---- a registered map is not Berlin coverage ----------------------------------------------------------------------
  @Test public void anInstalledMapNeverClaimsBerlinCoverage() throws Exception
  {
    DatasetStatus.MapFacts berlin = DatasetStatus.MapFacts.of(versions(BERLIN, 261020L));
    DatasetStatus.MapFacts bavaria = DatasetStatus.MapFacts.of(versions(BAVARIA, 261020L));
    assertEquals("bounds must not enter the facts", berlin, bavaria);
    DatasetStatusCorpus.Row r = row("ready-untrusted");
    assertEquals(status(r, berlin), status(r, bavaria));
    assertEquals(DatasetStatus.Kind.READY_UNTRUSTED, status(r, bavaria).kind);
    for (Field field : DatasetStatus.MapFacts.class.getDeclaredFields())
    {
      String n = field.getName().toLowerCase(java.util.Locale.ROOT);
      assertFalse(n, n.contains("berlin") || n.contains("cover") || n.contains("bound") || n.contains("country"));
    }
  }

  // ---- the facts never change a Kind, an action or a trust answer --------------------------------------------------
  @Test public void presenceOnlyFactsReproduceTheExistingStatusForEveryCorpusRow() throws Exception
  {
    for (DatasetStatusCorpus.Row r : DatasetStatusCorpus.rows())
      assertEquals(r.name, r.status(), status(r, DatasetStatus.MapFacts.ofPresence(r.map)));
  }

  @Test public void versionsNeverChangeTheTimetableAnswerOfAnyCorpusRow() throws Exception
  {
    DatasetStatus.MapFacts present = DatasetStatus.MapFacts.of(versions(BERLIN, 261020L, 261104L));
    for (DatasetStatusCorpus.Row r : DatasetStatusCorpus.rows())
    {
      if (r.map != DatasetStatus.MapPresence.PRESENT) continue;
      DatasetStatus plain = r.status(), withFacts = status(r, present);
      assertEquals(r.name, plain.kind, withFacts.kind);
      assertEquals(r.name, plain.action(), withFacts.action());
      assertEquals(r.name, plain.trust, withFacts.trust);
      assertEquals(r.name, plain.daysLeft, withFacts.daysLeft);
      assertEquals(r.name, plain.isReady(), withFacts.isReady());
      assertEquals(r.name, plain.replaceFailed, withFacts.replaceFailed);
      assertSame(r.name, present, withFacts.mapFacts);
    }
  }

  @Test public void maplessAndPacklessAnswersStayWhatTheyWereWithMapsInstalled() throws Exception
  {
    DatasetStatus.MapFacts present = DatasetStatus.MapFacts.of(versions(BERLIN, 261020L));
    DatasetStatus s = status(row("no-pack"), present);
    assertEquals(DatasetStatus.Kind.NO_PACK, s.kind);
    assertFalse(s.isReady());
  }

  // ---- equality includes the map generation -------------------------------------------------------------------------
  @Test public void equalityAndHashIncludeTheMapGeneration() throws Exception
  {
    DatasetStatusCorpus.Row r = row("ready-untrusted");
    DatasetStatus a = status(r, DatasetStatus.MapFacts.of(versions(BERLIN, 261020L)));
    DatasetStatus same = status(r, DatasetStatus.MapFacts.of(versions(BAVARIA, 261020L)));
    DatasetStatus newer = status(r, DatasetStatus.MapFacts.of(versions(BERLIN, 261104L)));
    assertEquals(a, same);
    assertEquals(a.hashCode(), same.hashCode());
    assertNotEquals(a, newer);
    assertNotEquals("a version-bearing status differs from the presence-only one", a, r.status());
  }

  @Test public void aSecondCaptureOfTheSameRegistryYieldsAnEqualImmutableFact() throws Exception
  {
    RegisteredWalkingMaps.Inventory inv = inventory(true, candidate("A", 261104L, BERLIN), candidate("B", 261020L, BAVARIA));
    DatasetStatus.MapFacts first = DatasetStatus.MapFacts.of(inv), second = DatasetStatus.MapFacts.of(inv);
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    List<Long> expected = Arrays.asList(261020L, 261104L);
    assertEquals(expected, first.versions);
  }
}
