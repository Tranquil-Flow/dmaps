package app.organicmaps.sdk.transit;

import java.time.Instant;

/** UI-test metadata derived through real ephemeral signatures; never a production root/flag. */
public final class VerifiedUiFixture {
  private VerifiedUiFixture() { }
  public static PackInfo official() throws Exception {
    PackEnvelopeTest.Fixture fixture = new PackEnvelopeTest.Fixture();
    return new PackInfo(PackEnvelope.decode(fixture.envelope(PackEnvelopeTest.metadata(), false),
        ImportMode.OFFICIAL_REQUIRED, fixture.policy, Instant.parse("2026-10-21T00:00:00Z")));
  }
}
