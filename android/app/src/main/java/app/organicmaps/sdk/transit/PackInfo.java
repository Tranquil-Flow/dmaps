package app.organicmaps.sdk.transit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Immutable pack metadata mirror of manifest.json. No provenance authentication claim. */
public final class PackInfo
{
  public final String publisher, attribution, license, sourceUrl, sourceSha256, databaseSha256;
  public final String createdUtc, validFrom, validUntil, timezone;
  public final long databaseBytes;
  public final int maxDepartureSeconds;

  public PackInfo(String publisher, String attribution, String license, String sourceUrl,
                  String sourceSha256, String databaseSha256, String createdUtc,
                  String validFrom, String validUntil, String timezone,
                  long databaseBytes, int maxDepartureSeconds)
  {
    this.publisher = publisher;
    this.attribution = attribution;
    this.license = license;
    this.sourceUrl = sourceUrl;
    this.sourceSha256 = sourceSha256;
    this.databaseSha256 = databaseSha256;
    this.createdUtc = createdUtc;
    this.validFrom = validFrom;
    this.validUntil = validUntil;
    this.timezone = timezone;
    this.databaseBytes = databaseBytes;
    this.maxDepartureSeconds = maxDepartureSeconds;
  }

  private Instant endOfLastValidDay()
  {
    ZoneId tz = ZoneId.of(timezone);
    LocalDate lastDay = LocalDate.parse(validUntil);
    // Valid through the end of the last valid service day, including its
    // after-midnight extension (up to the pack's max departure bound).
    Instant origin = GtfsTime.serviceOrigin(lastDay, tz);
    return origin.plusSeconds((24L * 3600L) + Math.max(0, maxDepartureSeconds));
  }

  private Instant startOfFirstValidDay()
  {
    ZoneId tz = ZoneId.of(timezone);
    LocalDate firstDay = LocalDate.parse(validFrom);
    // A service day may start before local midnight of its own date when the
    // previous day's after-midnight trips are considered; the pack becomes
    // usable at the noon-origin of the first valid day minus 12h, i.e. its
    // own origin; earlier service days are not part of this pack.
    return GtfsTime.serviceOrigin(firstDay, tz);
  }

  /** True when the pack's service window (incl. after-midnight extension) ended before now. */
  public boolean isExpired(Instant now)
  {
    return now.isAfter(endOfLastValidDay());
  }

  /** True when now is before the pack's first valid service origin. */
  public boolean isNotYetValid(Instant now)
  {
    return now.isBefore(startOfFirstValidDay());
  }
}
