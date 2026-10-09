package app.organicmaps.sdk.transit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Pure GTFS temporal semantics, binding per PACK_CONTRACT.md:
 * the service origin of a service date D in agency zone Z is the instant of
 * local noon of D converted to an instant, minus exactly 12 elapsed hours.
 * This is NOT wall-clock midnight plus local hours; both DST transitions are
 * handled because noon-plus-12-elapsed-hours shifts with the offset in effect
 * near midnight (or does not, exactly as elapsed-time semantics require).
 *
 * Pure Java: no Android classes, exercisable on the host JVM.
 */
public final class GtfsTime
{
  /** Pilot bound: maximum 168 hours of elapsed seconds. */
  public static final int MAX_SECONDS = 604800;

  private GtfsTime() {}

  /**
   * Service origin instant: local noon of serviceDate in agencyZone as an
   * instant, minus exactly 12 elapsed hours. Noon is resolved with the
   * "prefer earlier offset" rule for the (for real zones nonexistent) case
   * where noon itself is in a gap/overlap.
   */
  public static Instant serviceOrigin(LocalDate serviceDate, ZoneId agencyZone)
  {
    if (serviceDate == null || agencyZone == null)
      throw new IllegalArgumentException("serviceDate and agencyZone are required");
    // atZone uses the "prefer earlier offset" rule for gap/overlap edge cases.
    ZonedDateTime noon = serviceDate.atTime(12, 0).atZone(agencyZone);
    return noon.toInstant().minusSeconds(12L * 3600L);
  }

  /** Departure instant for elapsed seconds on a service date. */
  public static Instant departure(LocalDate serviceDate, int seconds, ZoneId agencyZone)
  {
    if (seconds < 0)
      throw new IllegalArgumentException("seconds must be nonnegative");
    if (seconds > MAX_SECONDS)
      throw new IllegalArgumentException("GTFS seconds above 168h pilot bound: " + seconds);
    return serviceOrigin(serviceDate, agencyZone).plusSeconds(seconds);
  }

  /**
   * Parse a GTFS time string. Accepts H:MM:SS and HH:MM:SS (hours may exceed
   * 23; hour field 1..3 digits). Returns null for blank/null input.
   * @throws IllegalArgumentException on malformed values or seconds above the
   *         168-hour pilot bound (refuse, never silently truncate).
   */
  public static Integer parseGtfs(String value)
  {
    if (value == null)
      return null;
    String v = value.trim();
    if (v.isEmpty())
      return null;
    String[] parts = v.split(":");
    if (parts.length != 3)
      throw new IllegalArgumentException("malformed GTFS time");
    if (parts[0].isEmpty() || parts[0].length() > 3 || parts[1].length() != 2 || parts[2].length() != 2)
      throw new IllegalArgumentException("malformed GTFS time");
    for (String p : parts)
      for (int i = 0; i < p.length(); i++)
        if (!Character.isDigit(p.charAt(i)))
          throw new IllegalArgumentException("malformed GTFS time");
    int h = Integer.parseInt(parts[0]);
    int m = Integer.parseInt(parts[1]);
    int s = Integer.parseInt(parts[2]);
    if (m > 59 || s > 59)
      throw new IllegalArgumentException("malformed GTFS time");
    int total = h * 3600 + m * 60 + s;
    if (total > MAX_SECONDS)
      throw new IllegalArgumentException("GTFS time above 168h pilot bound");
    return total;
  }

  /**
   * Lower bound (inclusive) on elapsed seconds for departures at or after
   * {@code from}, relative to the given service origin; may be negative for
   * service days already in progress. Callers clamp to >= 0.
   */
  public static long secondsAfter(Instant origin, Instant from)
  {
    return java.time.Duration.between(origin, from).getSeconds();
  }
}
