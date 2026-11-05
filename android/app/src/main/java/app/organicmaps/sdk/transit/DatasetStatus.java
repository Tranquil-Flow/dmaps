package app.organicmaps.sdk.transit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * S1 DatasetStatus: one immutable answer to "what do I have, and how fresh is it". A pure function of
 * (consumer scope, active pack facts incl. trust and validity, registered-map presence, clock, last
 * activation outcome). No I/O, no Android, no native calls, no text: wording lives in the presenter.
 * It is display truth only; it never gates a query, never grants or removes trust, and never converts
 * an unknown (map, pack) into a ready state.
 */
public final class DatasetStatus
{
  /** The eight S1 states, then three truthful extensions the S1 text did not name (see proposal README). */
  public enum Kind
  {
    READY, READY_UNTRUSTED, EXPIRING, EXPIRED, NO_PACK, INCOMPATIBLE, MAP_MISSING,
    KEPT_PREVIOUS_AFTER_FAILED_REPLACE,
    /** Extension: valid_from is in the future; "expired" would be false. */
    NOT_YET_VALID,
    /** Extension: a pack is registered but cannot be read or validated; "no pack" would be false. */
    PACK_UNAVAILABLE,
    /** Extension: the registry could not be read; "missing" and "ready" would both be false. */
    MAP_UNKNOWN
  }

  /** What the consuming screen needs. Journey needs format 2; departure boards do not. */
  public enum Scope { BOARDS, JOURNEY }
  public enum Trust { NONE, UNTRUSTED, AUTHENTICATED }
  public enum ExpiryCause { NONE, SERVICE_WINDOW, AUTHORIZATION }
  /** The one thing that helps. A text, not a click target: Journey cannot import. */
  public enum Action { NONE, IMPORT_PACK, IMPORT_NEWER_PACK, REIMPORT_PACK, INSTALL_MAP, RECHECK }

  /** Truth about the installed-map registry. UNKNOWN is the default and is never treated as present. */
  public enum MapPresence
  {
    PRESENT, ABSENT, UNKNOWN;

    /** Complete inventory with maps: PRESENT. Complete and empty: ABSENT. Null or incomplete: UNKNOWN.
     * Presence says only that some country map is registered; it is not Berlin coverage, graph
     * availability or a version-compatibility claim. */
    public static MapPresence of(RegisteredWalkingMaps.Inventory inventory)
    {
      if (inventory == null || !inventory.complete || inventory.maps == null) return UNKNOWN;
      return inventory.maps.isEmpty() ? ABSENT : PRESENT;
    }
  }

  /**
   * Immutable map generation facts from ONE registry capture (one per screen resume). Presence plus the actual
   * registered Candidate.mapVersion values: distinct, ascending, bounded to the newest MAX_VERSIONS (the rest are
   * counted in distinctVersions). They carry no bounds, no country names and no coverage verdict: a registered map
   * is not Berlin coverage, and an unknown capture (UNKNOWN, no versions) is neither missing nor ready. Facts feed
   * the one S1 status text; they never change a Kind.
   */
  public static final class MapFacts
  {
    public static final int MAX_VERSIONS = 6;
    public static final MapFacts UNKNOWN = new MapFacts(MapPresence.UNKNOWN, 0, Collections.<Long>emptyList());
    public static final MapFacts ABSENT = new MapFacts(MapPresence.ABSENT, 0, Collections.<Long>emptyList());

    public final MapPresence presence;
    /** Count of distinct versions in the capture; 0 when presence is not PRESENT or the capture was presence-only. */
    public final int distinctVersions;
    /** Newest-bounded, ascending, unmodifiable. Empty unless presence is PRESENT and the versions were captured. */
    public final List<Long> versions;

    private MapFacts(MapPresence presence, int distinctVersions, List<Long> versions)
    { this.presence = presence; this.distinctVersions = distinctVersions; this.versions = versions; }

    /** A presence answer without versions (null is UNKNOWN). Equals what the registry conversion yields for no maps list. */
    public static MapFacts ofPresence(MapPresence presence)
    {
      if (presence == null || presence == MapPresence.UNKNOWN) return UNKNOWN;
      if (presence == MapPresence.ABSENT) return ABSENT;
      return new MapFacts(MapPresence.PRESENT, 0, Collections.<Long>emptyList());
    }

    /** The only conversion from a registry capture. Null or incomplete is UNKNOWN, complete and empty is ABSENT. */
    public static MapFacts of(RegisteredWalkingMaps.Inventory inventory)
    {
      final MapPresence presence = MapPresence.of(inventory);
      if (presence != MapPresence.PRESENT) return ofPresence(presence);
      final TreeSet<Long> distinct = new TreeSet<>();
      for (RegisteredWalkingMaps.Candidate map : inventory.maps) distinct.add(map.mapVersion);
      final List<Long> all = new ArrayList<>(distinct);
      final List<Long> newest = new ArrayList<>(all.subList(Math.max(0, all.size() - MAX_VERSIONS), all.size()));
      return new MapFacts(MapPresence.PRESENT, all.size(), Collections.unmodifiableList(newest));
    }

    @Override public boolean equals(Object o)
    {
      if (!(o instanceof MapFacts)) return false;
      MapFacts f = (MapFacts) o;
      return presence == f.presence && distinctVersions == f.distinctVersions && versions.equals(f.versions);
    }
    @Override public int hashCode() { return Objects.hash(presence, distinctVersions, versions); }
    @Override public String toString() { return "MapFacts[" + presence + " " + versions + " of " + distinctVersions + "]"; }
  }

  /** Last pack-replacement outcome known to this screen. It is ephemeral; a durable record is open (README). */
  public enum Activation { NONE, REPLACE_FAILED }

  /** A pack with fewer whole local days left than this shows EXPIRING. Agent default, owner-changeable. */
  public static final int EXPIRING_WITHIN_DAYS = 7;

  public final Kind kind;
  public final Scope scope;
  public final Trust trust;
  public final ExpiryCause expiryCause;
  /** Whole local days to validUntil while a pack is within its window, else -1. */
  public final int daysLeft;
  public final String validFrom, validUntil;
  public final MapPresence map;
  /** The same registry capture as `map`, with its actual map versions. Presence-only unless built by computeWith. */
  public final MapFacts mapFacts;
  /** True for every kind when the last replacement failed; the previous pack's own warnings still apply. */
  public final boolean replaceFailed;
  /** Identity the status was derived from: the envelope digest if supplied, else the database digest, else null. */
  public final String generation;

  private DatasetStatus(Kind kind, Scope scope, Trust trust, ExpiryCause cause, int daysLeft, String validFrom,
                        String validUntil, MapPresence map, boolean replaceFailed, String generation)
  {
    this.kind = kind; this.scope = scope; this.trust = trust; this.expiryCause = cause; this.daysLeft = daysLeft;
    this.validFrom = validFrom; this.validUntil = validUntil; this.map = map;
    this.replaceFailed = replaceFailed; this.generation = generation;
    this.mapFacts = MapFacts.ofPresence(map);
  }

  /** Same answer, carrying the richer facts of the capture it was computed from (facts.presence == base.map). */
  private DatasetStatus(DatasetStatus base, MapFacts facts)
  {
    this.kind = base.kind; this.scope = base.scope; this.trust = base.trust; this.expiryCause = base.expiryCause;
    this.daysLeft = base.daysLeft; this.validFrom = base.validFrom; this.validUntil = base.validUntil; this.map = base.map;
    this.replaceFailed = base.replaceFailed; this.generation = base.generation; this.mapFacts = facts;
  }

  /**
   * @param pack the active pack, or null when none is registered
   * @param packUnavailable true when a pack is registered but its info cannot be read (ignored if pack != null)
   * @param envelopeSha256 generation identity if the caller holds it (Journey does), else null
   */
  public static DatasetStatus compute(Scope scope, PackInfo pack, boolean packUnavailable, MapPresence map,
                                      Instant now, Activation activation, String envelopeSha256)
  {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(now, "now");
    final MapPresence presence = map == null ? MapPresence.UNKNOWN : map;
    final boolean failed = activation == Activation.REPLACE_FAILED;
    if (pack == null)
      return new DatasetStatus(packUnavailable ? Kind.PACK_UNAVAILABLE : Kind.NO_PACK, scope, Trust.NONE,
                               ExpiryCause.NONE, -1, null, null, presence, failed, envelopeSha256);
    final Trust trust = pack.authenticated ? Trust.AUTHENTICATED : Trust.UNTRUSTED;
    final String generation = envelopeSha256 != null ? envelopeSha256 : pack.databaseSha256;
    try
    {
      if (scope == Scope.JOURNEY && pack.formatVersion != 2)
        return new DatasetStatus(Kind.INCOMPATIBLE, scope, trust, ExpiryCause.NONE, -1, pack.validFrom,
                                 pack.validUntil, presence, failed, generation);
      if (pack.isNotYetValid(now))
        return new DatasetStatus(Kind.NOT_YET_VALID, scope, trust, ExpiryCause.NONE, -1, pack.validFrom,
                                 pack.validUntil, presence, failed, generation);
      if (pack.isExpired(now))
        return new DatasetStatus(Kind.EXPIRED, scope, trust, ExpiryCause.SERVICE_WINDOW, -1, pack.validFrom,
                                 pack.validUntil, presence, failed, generation);
      // Authorization ends trust use; it never downgrades the pack to untrusted (existing behaviour).
      if (pack.authenticated && (pack.authorizationExpires == null || !now.isBefore(pack.authorizationExpires)))
        return new DatasetStatus(Kind.EXPIRED, scope, trust, ExpiryCause.AUTHORIZATION, -1, pack.validFrom,
                                 pack.validUntil, presence, failed, generation);
      final LocalDate today = now.atZone(ZoneId.of(pack.timezone)).toLocalDate();
      final int days = (int) Math.max(0L, Math.min(Integer.MAX_VALUE,
          ChronoUnit.DAYS.between(today, LocalDate.parse(pack.validUntil))));
      final Kind kind;
      if (presence == MapPresence.ABSENT) kind = Kind.MAP_MISSING;
      else if (presence == MapPresence.UNKNOWN) kind = Kind.MAP_UNKNOWN;
      else if (failed) kind = Kind.KEPT_PREVIOUS_AFTER_FAILED_REPLACE;
      else if (days <= EXPIRING_WITHIN_DAYS) kind = Kind.EXPIRING;
      else kind = pack.authenticated ? Kind.READY : Kind.READY_UNTRUSTED;
      return new DatasetStatus(kind, scope, trust, ExpiryCause.NONE, days, pack.validFrom, pack.validUntil,
                               presence, failed, generation);
    }
    catch (RuntimeException malformed)
    {
      // Unparseable window or zone: fail closed to unavailable, never to ready.
      return new DatasetStatus(Kind.PACK_UNAVAILABLE, scope, Trust.NONE, ExpiryCause.NONE, -1, null, null,
                               presence, failed, generation);
    }
  }

  /**
   * compute() fed by the facts of one registry capture. The Kind, action, trust and window logic is the single
   * compute() above, driven by facts.presence; the versions are carried along for the text and never decide anything.
   * Null facts are UNKNOWN. A presence-only fact yields exactly what compute() yields.
   */
  public static DatasetStatus computeWith(Scope scope, PackInfo pack, boolean packUnavailable, MapFacts map,
                                          Instant now, Activation activation, String envelopeSha256)
  {
    final MapFacts facts = map == null ? MapFacts.UNKNOWN : map;
    final DatasetStatus base = compute(scope, pack, packUnavailable, facts.presence, now, activation, envelopeSha256);
    return facts.versions.isEmpty() ? base : new DatasetStatus(base, facts);
  }

  /** True only for the kinds that may be spoken of as ready: the timetable is current and the map is present. */
  public boolean isReady()
  {
    return kind == Kind.READY || kind == Kind.READY_UNTRUSTED || kind == Kind.EXPIRING
        || kind == Kind.KEPT_PREVIOUS_AFTER_FAILED_REPLACE;
  }

  public Action action()
  {
    switch (kind)
    {
    case READY: case READY_UNTRUSTED: return Action.NONE;
    case EXPIRING: case EXPIRED: case NOT_YET_VALID: case INCOMPATIBLE: return Action.IMPORT_NEWER_PACK;
    case NO_PACK: return Action.IMPORT_PACK;
    case PACK_UNAVAILABLE: case KEPT_PREVIOUS_AFTER_FAILED_REPLACE: return Action.REIMPORT_PACK;
    case MAP_MISSING: return Action.INSTALL_MAP;
    case MAP_UNKNOWN: return Action.RECHECK;
    default: throw new IllegalStateException("unhandled dataset kind");
    }
  }

  @Override public boolean equals(Object o)
  {
    if (!(o instanceof DatasetStatus)) return false;
    DatasetStatus s = (DatasetStatus) o;
    return kind == s.kind && scope == s.scope && trust == s.trust && expiryCause == s.expiryCause
        && daysLeft == s.daysLeft && Objects.equals(validFrom, s.validFrom) && Objects.equals(validUntil, s.validUntil)
        && map == s.map && replaceFailed == s.replaceFailed && Objects.equals(generation, s.generation)
        && mapFacts.equals(s.mapFacts);
  }
  @Override public int hashCode()
  { return Objects.hash(kind, scope, trust, expiryCause, daysLeft, validFrom, validUntil, map, replaceFailed, generation, mapFacts); }
  @Override public String toString()
  { return "DatasetStatus[" + kind + " " + trust + " days=" + daysLeft + " map=" + map + (replaceFailed ? " replaceFailed" : "") + "]"; }
}
