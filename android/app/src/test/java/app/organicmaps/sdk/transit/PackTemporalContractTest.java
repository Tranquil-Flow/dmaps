package app.organicmaps.sdk.transit;

import static org.junit.Assert.*;
import java.time.*;
import java.util.TimeZone;
import org.junit.Test;

/** Pure Java contract controls, separate from Python compiler and actual Android SQLite checks. */
public class PackTemporalContractTest
{
  private final ZoneId berlin=ZoneId.of("Europe/Berlin");
  private PackInfo info(int maximum)
  {return new PackInfo("fixture","fixture","CC BY 4.0","https://example.invalid","","","2026-10-14T00:00:00Z","2026-10-14","2026-10-14","Europe/Berlin",0,maximum);}

  @Test public void expiryUsesMaximumServiceOffsetNotAnExtraDay() {
    Instant origin=GtfsTime.serviceOrigin(LocalDate.parse("2026-10-14"),berlin);
    assertFalse(info(108000).isExpired(origin.plusSeconds(108000)));
    assertTrue(info(108000).isExpired(origin.plusSeconds(108001)));
  }
  @Test public void ordinaryExpiryIncludesWholeLastValidDay() {
    Instant origin=GtfsTime.serviceOrigin(LocalDate.parse("2026-10-14"),berlin);
    assertFalse(info(36000).isExpired(origin.plusSeconds(86399)));
    assertTrue(info(36000).isExpired(origin.plusSeconds(86401)));
  }
  @Test public void agencySemanticsDoNotFollowPhoneDefaultZone() {
    TimeZone previous=TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
      assertEquals(Instant.parse("2026-10-14T08:00:00Z"),GtfsTime.departure(LocalDate.parse("2026-10-14"),36000,berlin));
    } finally {TimeZone.setDefault(previous);}
  }
  @Test public void springAndAutumnUseNoonMinusElapsedHours() {
    assertEquals(Instant.parse("2026-03-28T22:00:00Z"),GtfsTime.serviceOrigin(LocalDate.parse("2026-03-29"),berlin));
    assertEquals(Instant.parse("2026-10-24T23:00:00Z"),GtfsTime.serviceOrigin(LocalDate.parse("2026-10-25"),berlin));
  }
  @Test public void afterMidnightBelongsToOriginalServiceDay() {
    assertEquals(Integer.valueOf(91800),GtfsTime.parseGtfs("25:30:00"));
    assertEquals(Instant.parse("2026-10-14T23:30:00Z"),GtfsTime.departure(LocalDate.parse("2026-10-14"),91800,berlin));
  }
  @Test public void notYetValidDoesNotAdvertiseCurrentService() {
    Instant origin=GtfsTime.serviceOrigin(LocalDate.parse("2026-10-14"),berlin);
    assertTrue(info(108000).isNotYetValid(origin.minusSeconds(1)));
    assertFalse(info(108000).isNotYetValid(origin));
  }
}
