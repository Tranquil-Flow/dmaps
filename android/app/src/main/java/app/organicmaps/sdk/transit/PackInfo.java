package app.organicmaps.sdk.transit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Immutable metadata and derived trust of a verified generation; legacy constructors are untrusted. */
public final class PackInfo
{
  public final String publisher, attribution, license, sourceUrl, sourceSha256, databaseSha256;
  public final String createdUtc, validFrom, validUntil, timezone;
  public final long databaseBytes;
  public final int maxDepartureSeconds;
  /** Data schema identity, not a journey-planner readiness or trust flag. */
  public final int formatVersion;
  /** Unknown in v1; never manufactured from its departure bound. */
  public final Integer maxArrivalSeconds;

  /** Trust is derived from the verified generation, never from publisher/provenance strings. */
  public final boolean authenticated;
  public final ImportMode importMode;
  public final String signingKeyId;
  public final long officialSequence;
  public final Instant authorizationExpires;

  PackInfo(PackEnvelope.Result verified)
  {
    java.util.Map<String, Object> m = verified.manifest;
    publisher = (String) m.get("publisher"); attribution = (String) m.get("attribution");
    license = (String) m.get("license"); sourceUrl = (String) m.get("source_url");
    sourceSha256 = (String) m.get("source_sha256"); databaseSha256 = (String) m.get("database_sha256");
    createdUtc = (String) m.get("created_utc"); validFrom = (String) m.get("valid_from");
    validUntil = (String) m.get("valid_until"); timezone = (String) m.get("timezone");
    databaseBytes = (Long) m.get("database_bytes"); maxDepartureSeconds = ((Long) m.get("max_departure_seconds")).intValue();
    formatVersion = ((Long) m.get("format_version")).intValue();
    maxArrivalSeconds = formatVersion == 2 ? ((Long) m.get("max_arrival_seconds")).intValue() : null;
    authenticated = verified.authenticated; importMode = verified.mode;
    signingKeyId = authenticated ? verified.keyId : null;
    officialSequence = authenticated ? verified.sequence : 0;
    authorizationExpires = authenticated ? verified.authorizationExpires : null;
  }

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
    formatVersion = 1; maxArrivalSeconds = null;
    authenticated = false; importMode = ImportMode.USER_UNTRUSTED;
    signingKeyId = null; officialSequence = 0; authorizationExpires = null;
  }

  private Instant endOfLastValidDay()
  {
    ZoneId tz = ZoneId.of(timezone);
    LocalDate lastDay = LocalDate.parse(validUntil);
    // Valid through the end of the last valid service day, including its
    // after-midnight extension (up to the pack's max departure bound).
    Instant origin = GtfsTime.serviceOrigin(lastDay, tz);
    // maxDepartureSeconds is already measured from this service origin;
    // adding a whole day again would advertise an expired pack as current.
    int lastOffset = maxArrivalSeconds == null ? maxDepartureSeconds
        : Math.max(maxDepartureSeconds, maxArrivalSeconds);
    return origin.plusSeconds(Math.max(24L * 3600L, lastOffset));
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
