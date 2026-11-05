package app.organicmaps.sdk.transit;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import org.junit.Test;

/**
 * S1 table corpus. Pure host JVM: no Android, no native call, no repository. The same rows drive the text
 * and consumer tests (DatasetStatusCorpus is the shared source of rows).
 */
public class DatasetStatusTest
{
  private static final String ZERO = String.join("", Collections.nCopies(64, "0"));
  private static final DatasetStatus.MapPresence PRESENT = DatasetStatus.MapPresence.PRESENT;

  static PackInfo untrusted(String from, String until, int format) throws Exception
  {
    PackInfo p = new PackInfo("claims to be official", "fixture", "CC BY 4.0", "https://example.invalid", ZERO, ZERO,
                              "2026-10-01T00:00:00Z", from, until, "Europe/Berlin", 0, 86400);
    Field f = PackInfo.class.getDeclaredField("formatVersion");
    f.setAccessible(true);
    f.setInt(p, format);
    return p;
  }

  /** Authenticated fixture: Europe/Berlin, valid 2026-10-01..2027-01-01, authorization until 2027-01-01T00:00Z. */
  static PackInfo official() throws Exception { return VerifiedUiFixture.official(); }

  static DatasetStatus compute(DatasetStatus.Scope scope, PackInfo p, boolean unavailable, DatasetStatus.MapPresence map,
                               String now, DatasetStatus.Activation activation)
  {
    return DatasetStatus.compute(scope, p, unavailable, map, Instant.parse(now), activation, null);
  }

  private static final DatasetStatus.Scope B = DatasetStatus.Scope.BOARDS, J = DatasetStatus.Scope.JOURNEY;
  private static final DatasetStatus.Activation NONE = DatasetStatus.Activation.NONE,
      FAILED = DatasetStatus.Activation.REPLACE_FAILED;

  // ---- one row per S1 state, with its trust and its action ---------------------------------------------------------
  @Test public void readyOfficial() throws Exception
  {
    DatasetStatus s = compute(B, official(), false, PRESENT, "2026-10-28T10:00:00Z", NONE);
    assertEquals(DatasetStatus.Kind.READY, s.kind);
    assertEquals(DatasetStatus.Trust.AUTHENTICATED, s.trust);
    assertTrue(s.isReady());
    assertEquals(DatasetStatus.Action.NONE, s.action());
  }

  @Test public void readyUntrustedIsTheReachableDeviceState() throws Exception
  {
    DatasetStatus s = compute(J, untrusted("2026-10-01", "2027-01-01", 2), false, PRESENT, "2026-10-28T10:00:00Z", NONE);
    assertEquals(DatasetStatus.Kind.READY_UNTRUSTED, s.kind);
    assertEquals(DatasetStatus.Trust.UNTRUSTED, s.trust);
    assertEquals(65, s.daysLeft);
    assertTrue(s.isReady());
  }

  @Test public void publisherClaimsNeverPromoteTrust() throws Exception
  {
    DatasetStatus s = compute(B, untrusted("2026-10-01", "2027-01-01", 1), false, PRESENT, "2026-10-28T10:00:00Z", NONE);
    assertEquals(DatasetStatus.Trust.UNTRUSTED, s.trust);
    assertNotEquals(DatasetStatus.Kind.READY, s.kind);
  }

  @Test public void expiringCountsWholeLocalDaysAndKeepsTrust() throws Exception
  {
    // 2026-12-28 10:00Z = 11:00 Berlin; validUntil 2027-01-01 => 4 days.
    DatasetStatus u = compute(B, untrusted("2026-10-01", "2027-01-01", 1), false, PRESENT, "2026-12-28T10:00:00Z", NONE);
    assertEquals(DatasetStatus.Kind.EXPIRING, u.kind);
    assertEquals(4, u.daysLeft);
    assertEquals(DatasetStatus.Trust.UNTRUSTED, u.trust);
    assertEquals(DatasetStatus.Action.IMPORT_NEWER_PACK, u.action());
    DatasetStatus o = compute(B, official(), false, PRESENT, "2026-12-28T10:00:00Z", NONE);
    assertEquals(DatasetStatus.Kind.EXPIRING, o.kind);
    assertEquals(DatasetStatus.Trust.AUTHENTICATED, o.trust);
  }

  @Test public void expiringThresholdBoundary() throws Exception
  {
    PackInfo p = untrusted("2026-10-01", "2027-01-01", 1);
    // 7 days left => EXPIRING, 8 days left => READY_UNTRUSTED. Midday Berlin avoids the zone edge.
    assertEquals(DatasetStatus.Kind.EXPIRING, compute(B, p, false, PRESENT, "2026-12-25T11:00:00Z", NONE).kind);
    assertEquals(DatasetStatus.Kind.READY_UNTRUSTED, compute(B, p, false, PRESENT, "2026-12-24T11:00:00Z", NONE).kind);
  }

  @Test public void lastServiceDayIsExpiringWithZeroDaysNotExpired() throws Exception
  {
    DatasetStatus s = compute(B, untrusted("2026-10-01", "2027-01-01", 1), false, PRESENT, "2027-01-01T11:00:00Z", NONE);
    assertEquals(DatasetStatus.Kind.EXPIRING, s.kind);
    assertEquals(0, s.daysLeft);
  }

  @Test public void expiredByServiceWindowAgreesWithPackInfo() throws Exception
  {
    PackInfo p = untrusted("2026-10-01", "2027-01-01", 1);
    Instant afterWindow = Instant.parse("2027-01-02T12:00:00Z");
    assertTrue(p.isExpired(afterWindow));
    DatasetStatus s = DatasetStatus.compute(B, p, false, PRESENT, afterWindow, NONE, null);
    assertEquals(DatasetStatus.Kind.EXPIRED, s.kind);
    assertEquals(DatasetStatus.ExpiryCause.SERVICE_WINDOW, s.expiryCause);
    assertEquals(-1, s.daysLeft);
    assertEquals(DatasetStatus.Action.IMPORT_NEWER_PACK, s.action());
    assertFalse(s.isReady());
  }

  @Test public void authorizationExpiryIsExpiredWithoutTrustDowngrade() throws Exception
  {
    DatasetStatus s = compute(B, official(), false, PRESENT, "2027-01-01T12:00:00Z", NONE);
    assertEquals(DatasetStatus.Kind.EXPIRED, s.kind);
    assertEquals(DatasetStatus.ExpiryCause.AUTHORIZATION, s.expiryCause);
    assertEquals(DatasetStatus.Trust.AUTHENTICATED, s.trust);
  }

  @Test public void notYetValidIsNotExpired() throws Exception
  {
    DatasetStatus s = compute(B, untrusted("2026-11-10", "2027-01-01", 1), false, PRESENT, "2026-10-28T10:00:00Z", NONE);
    assertEquals(DatasetStatus.Kind.NOT_YET_VALID, s.kind);
    assertFalse(s.isReady());
  }

  @Test public void noPackAndUnavailablePackAreDifferent()
  {
    DatasetStatus none = compute(B, null, false, PRESENT, "2026-10-28T10:00:00Z", NONE);
    assertEquals(DatasetStatus.Kind.NO_PACK, none.kind);
    assertEquals(DatasetStatus.Trust.NONE, none.trust);
    assertEquals(DatasetStatus.Action.IMPORT_PACK, none.action());
    DatasetStatus bad = compute(B, null, true, PRESENT, "2026-10-28T10:00:00Z", NONE);
    assertEquals(DatasetStatus.Kind.PACK_UNAVAILABLE, bad.kind);
    assertEquals(DatasetStatus.Action.REIMPORT_PACK, bad.action());
  }

  @Test public void format1IsIncompatibleOnlyForJourney() throws Exception
  {
    PackInfo p = untrusted("2026-10-01", "2027-01-01", 1);
    DatasetStatus journey = compute(J, p, false, PRESENT, "2026-10-28T10:00:00Z", NONE);
    assertEquals(DatasetStatus.Kind.INCOMPATIBLE, journey.kind);
    assertEquals(DatasetStatus.Action.IMPORT_NEWER_PACK, journey.action());
    assertEquals(DatasetStatus.Kind.READY_UNTRUSTED, compute(B, p, false, PRESENT, "2026-10-28T10:00:00Z", NONE).kind);
  }

  // ---- map presence: unknown is never ready -------------------------------------------------------------------------
  @Test public void absentMapIsMapMissingAndStaysTruthfulAboutTheTimetable() throws Exception
  {
    DatasetStatus s = compute(B, untrusted("2026-10-01", "2027-01-01", 1), false, DatasetStatus.MapPresence.ABSENT,
                              "2026-10-28T10:00:00Z", NONE);
    assertEquals(DatasetStatus.Kind.MAP_MISSING, s.kind);
    assertEquals(DatasetStatus.Trust.UNTRUSTED, s.trust);
    assertFalse(s.isReady());
    assertEquals(DatasetStatus.Action.INSTALL_MAP, s.action());
  }

  @Test public void unknownMapIsNeverReadyAndNullIsUnknown() throws Exception
  {
    for (DatasetStatus.MapPresence m : new DatasetStatus.MapPresence[] {DatasetStatus.MapPresence.UNKNOWN, null})
    {
      DatasetStatus s = compute(B, untrusted("2026-10-01", "2027-01-01", 1), false, m, "2026-10-28T10:00:00Z", NONE);
      assertEquals(DatasetStatus.Kind.MAP_UNKNOWN, s.kind);
      assertEquals(DatasetStatus.MapPresence.UNKNOWN, s.map);
      assertFalse(s.isReady());
      assertEquals(DatasetStatus.Action.RECHECK, s.action());
    }
  }

  @Test public void mapProblemNeverMasksATimetableProblem() throws Exception
  {
    // Expired, no pack and incompatible stay what they are whatever the registry says.
    for (DatasetStatus.MapPresence m : DatasetStatus.MapPresence.values())
    {
      assertEquals(DatasetStatus.Kind.EXPIRED,
                   compute(B, untrusted("2025-01-01", "2025-06-01", 1), false, m, "2026-10-28T10:00:00Z", NONE).kind);
      assertEquals(DatasetStatus.Kind.NO_PACK, compute(B, null, false, m, "2026-10-28T10:00:00Z", NONE).kind);
      assertEquals(DatasetStatus.Kind.INCOMPATIBLE,
                   compute(J, untrusted("2026-10-01", "2027-01-01", 1), false, m, "2026-10-28T10:00:00Z", NONE).kind);
    }
  }

  // ---- failed replacement keeps the previous pack and its warnings --------------------------------------------------
  @Test public void failedReplaceKeepsPreviousPackTrustAndWindow() throws Exception
  {
    DatasetStatus s = compute(B, untrusted("2026-10-01", "2027-01-01", 1), false, PRESENT, "2026-10-28T10:00:00Z", FAILED);
    assertEquals(DatasetStatus.Kind.KEPT_PREVIOUS_AFTER_FAILED_REPLACE, s.kind);
    assertEquals(DatasetStatus.Trust.UNTRUSTED, s.trust);
    assertEquals("2027-01-01", s.validUntil);
    assertTrue(s.replaceFailed);
    assertEquals(DatasetStatus.Action.REIMPORT_PACK, s.action());
    assertTrue(s.isReady());
  }

  @Test public void failedReplaceNeverHidesAnExpiredPreviousPack() throws Exception
  {
    DatasetStatus s = compute(B, untrusted("2025-01-01", "2025-06-01", 1), false, PRESENT, "2026-10-28T10:00:00Z", FAILED);
    assertEquals(DatasetStatus.Kind.EXPIRED, s.kind);
    assertTrue("the failure remains visible to the presenter", s.replaceFailed);
  }

  @Test public void failedReplaceWithoutAPreviousPackIsNoPackWithTheFailureFlag()
  {
    DatasetStatus s = compute(B, null, false, PRESENT, "2026-10-28T10:00:00Z", FAILED);
    assertEquals(DatasetStatus.Kind.NO_PACK, s.kind);
    assertTrue(s.replaceFailed);
  }

  @Test public void failedReplaceStillSurfacesAMapProblem() throws Exception
  {
    DatasetStatus s = compute(B, untrusted("2026-10-01", "2027-01-01", 1), false, DatasetStatus.MapPresence.ABSENT,
                              "2026-10-28T10:00:00Z", FAILED);
    assertEquals(DatasetStatus.Kind.MAP_MISSING, s.kind);
    assertTrue(s.replaceFailed);
  }

  // ---- inputs, identity, fail-closed --------------------------------------------------------------------------------
  @Test public void generationPrefersEnvelopeThenDatabaseDigest() throws Exception
  {
    PackInfo p = untrusted("2026-10-01", "2027-01-01", 1);
    assertEquals(ZERO, DatasetStatus.compute(B, p, false, PRESENT, Instant.parse("2026-10-28T10:00:00Z"), NONE, null).generation);
    assertEquals("e", DatasetStatus.compute(B, p, false, PRESENT, Instant.parse("2026-10-28T10:00:00Z"), NONE, "e").generation);
  }

  @Test public void malformedWindowFailsClosedNotReady() throws Exception
  {
    PackInfo p = untrusted("2026-10-01", "not-a-date", 1);
    DatasetStatus s = DatasetStatus.compute(B, p, false, PRESENT, Instant.parse("2026-10-28T10:00:00Z"), NONE, null);
    assertEquals(DatasetStatus.Kind.PACK_UNAVAILABLE, s.kind);
    assertFalse(s.isReady());
  }

  @Test public void sameInputsGiveEqualImmutableValues() throws Exception
  {
    PackInfo p = untrusted("2026-10-01", "2027-01-01", 1);
    assertEquals(compute(B, p, false, PRESENT, "2026-10-28T10:00:00Z", NONE),
                 compute(B, p, false, PRESENT, "2026-10-28T10:00:00Z", NONE));
    assertNotEquals(compute(B, p, false, PRESENT, "2026-10-28T10:00:00Z", NONE),
                    compute(J, p, false, PRESENT, "2026-10-28T10:00:00Z", NONE));
  }

  @Test(expected = NullPointerException.class) public void clockIsRequired()
  { DatasetStatus.compute(B, null, false, PRESENT, null, NONE, null); }

  // ---- registry conversion: incomplete is unknown, never empty ------------------------------------------------------
  private static RegisteredWalkingMaps.Inventory inventory(boolean complete, int maps) throws Exception
  {
    Constructor<RegisteredWalkingMaps.Candidate> cc = RegisteredWalkingMaps.Candidate.class.getDeclaredConstructor(
        String.class, String.class, long.class, long.class, double.class, double.class, double.class, double.class);
    cc.setAccessible(true);
    RegisteredWalkingMaps.Candidate[] list = new RegisteredWalkingMaps.Candidate[maps];
    for (int i = 0; i < maps; i++) list[i] = cc.newInstance("/maps/" + i, "Germany_Berlin", 1L, 1L, 52.0, 13.0, 53.0, 14.0);
    Constructor<RegisteredWalkingMaps.Inventory> ic =
        RegisteredWalkingMaps.Inventory.class.getDeclaredConstructor(boolean.class, RegisteredWalkingMaps.Candidate[].class);
    ic.setAccessible(true);
    return ic.newInstance(complete, list);
  }

  @Test public void registryConversionIsTruthful() throws Exception
  {
    assertEquals(DatasetStatus.MapPresence.UNKNOWN, DatasetStatus.MapPresence.of(null));
    assertEquals(DatasetStatus.MapPresence.UNKNOWN, DatasetStatus.MapPresence.of(inventory(false, 0)));
    assertEquals(DatasetStatus.MapPresence.ABSENT, DatasetStatus.MapPresence.of(inventory(true, 0)));
    assertEquals(DatasetStatus.MapPresence.PRESENT, DatasetStatus.MapPresence.of(inventory(true, 1)));
  }

  // ---- exhaustiveness: every kind is reachable, and has an action ---------------------------------------------------
  @Test public void everyKindIsReachableFromTheCorpus() throws Exception
  {
    EnumSet<DatasetStatus.Kind> seen = EnumSet.noneOf(DatasetStatus.Kind.class);
    for (DatasetStatusCorpus.Row row : DatasetStatusCorpus.rows()) seen.add(row.status().kind);
    assertEquals(EnumSet.allOf(DatasetStatus.Kind.class), seen);
  }

  @Test public void corpusRowsMatchTheirDeclaredKind() throws Exception
  {
    List<String> wrong = new ArrayList<>();
    for (DatasetStatusCorpus.Row row : DatasetStatusCorpus.rows())
      if (row.expected != row.status().kind) wrong.add(row.name + ": " + row.status());
    assertTrue(wrong.toString(), wrong.isEmpty());
  }
}
