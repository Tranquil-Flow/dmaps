package app.organicmaps.sdk.transit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The one S1 state corpus. DatasetStatusTest, the text test and both consumer tests all iterate these rows. */
public final class DatasetStatusCorpus
{
  private DatasetStatusCorpus() {}

  public static final class Row
  {
    public final String name;
    public final DatasetStatus.Kind expected;
    public final DatasetStatus.Scope scope;
    public final PackInfo pack;
    public final boolean unavailable;
    public final DatasetStatus.MapPresence map;
    public final Instant now;
    public final DatasetStatus.Activation activation;

    Row(String name, DatasetStatus.Kind expected, DatasetStatus.Scope scope, PackInfo pack, boolean unavailable,
        DatasetStatus.MapPresence map, String now, DatasetStatus.Activation activation)
    {
      this.name = name; this.expected = expected; this.scope = scope; this.pack = pack; this.unavailable = unavailable;
      this.map = map; this.now = Instant.parse(now); this.activation = activation;
    }

    public DatasetStatus status() { return statusFor(scope); }

    public DatasetStatus statusFor(DatasetStatus.Scope s)
    { return DatasetStatus.compute(s, pack, unavailable, map, now, activation, null); }
  }

  /** Rows are written for BOARDS unless the row is about Journey format 2; a consumer re-scopes with statusFor. */
  public static List<Row> rows() throws Exception
  {
    final DatasetStatus.MapPresence P = DatasetStatus.MapPresence.PRESENT;
    final DatasetStatus.Activation N = DatasetStatus.Activation.NONE;
    final DatasetStatus.Scope B = DatasetStatus.Scope.BOARDS, J = DatasetStatus.Scope.JOURNEY;
    final String NOW = "2026-10-28T10:00:00Z";
    List<Row> rows = new ArrayList<>();
    rows.add(new Row("ready-official", DatasetStatus.Kind.READY, B, DatasetStatusTest.official(), false, P, NOW, N));
    rows.add(new Row("ready-untrusted", DatasetStatus.Kind.READY_UNTRUSTED, J,
                     DatasetStatusTest.untrusted("2026-10-01", "2027-01-01", 2), false, P, NOW, N));
    rows.add(new Row("expiring-4-days", DatasetStatus.Kind.EXPIRING, B,
                     DatasetStatusTest.untrusted("2026-10-01", "2027-01-01", 1), false, P, "2026-12-28T10:00:00Z", N));
    rows.add(new Row("expired", DatasetStatus.Kind.EXPIRED, B,
                     DatasetStatusTest.untrusted("2025-01-01", "2025-06-01", 2), false, P, NOW, N));
    rows.add(new Row("no-pack", DatasetStatus.Kind.NO_PACK, B, null, false, P, NOW, N));
    rows.add(new Row("incompatible-format1-journey", DatasetStatus.Kind.INCOMPATIBLE, J,
                     DatasetStatusTest.untrusted("2026-10-01", "2027-01-01", 1), false, P, NOW, N));
    rows.add(new Row("map-missing", DatasetStatus.Kind.MAP_MISSING, B,
                     DatasetStatusTest.untrusted("2026-10-01", "2027-01-01", 1), false,
                     DatasetStatus.MapPresence.ABSENT, NOW, N));
    rows.add(new Row("kept-previous", DatasetStatus.Kind.KEPT_PREVIOUS_AFTER_FAILED_REPLACE, B,
                     DatasetStatusTest.untrusted("2026-10-01", "2027-01-01", 1), false, P, NOW,
                     DatasetStatus.Activation.REPLACE_FAILED));
    rows.add(new Row("not-yet-valid", DatasetStatus.Kind.NOT_YET_VALID, B,
                     DatasetStatusTest.untrusted("2026-11-10", "2027-01-01", 1), false, P, NOW, N));
    rows.add(new Row("pack-unavailable", DatasetStatus.Kind.PACK_UNAVAILABLE, B, null, true, P, NOW, N));
    rows.add(new Row("map-unknown", DatasetStatus.Kind.MAP_UNKNOWN, B,
                     DatasetStatusTest.untrusted("2026-10-01", "2027-01-01", 1), false,
                     DatasetStatus.MapPresence.UNKNOWN, NOW, N));
    return rows;
  }
}
