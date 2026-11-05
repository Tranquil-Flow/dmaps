package app.organicmaps.transit;

import static app.organicmaps.sdk.transit.DatasetMapFactsFixture.BERLIN;
import static app.organicmaps.sdk.transit.DatasetMapFactsFixture.versions;
import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import app.organicmaps.sdk.transit.DatasetStatus;
import app.organicmaps.sdk.transit.DatasetStatusCorpus;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The shared S1 text carries the actual registered map versions, honestly, in English and German. Host only,
 * no native code. The facts add one note line; they never rewrite the headline or the one action.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, qualifiers = "en", manifest = Config.NONE)
public class DatasetMapFactsTextTest
{
  private Context context(String locale)
  {
    RuntimeEnvironment.setQualifiers(locale);
    return RuntimeEnvironment.getApplication();
  }

  private static DatasetStatusCorpus.Row row(String name) throws Exception
  {
    for (DatasetStatusCorpus.Row r : DatasetStatusCorpus.rows()) if (r.name.equals(name)) return r;
    throw new AssertionError("no corpus row " + name);
  }

  private static DatasetStatus with(DatasetStatusCorpus.Row r, DatasetStatus.MapFacts facts)
  {
    return DatasetStatus.computeWith(r.scope, r.pack, r.unavailable, facts, r.now, r.activation, null);
  }

  private static DatasetStatus.MapFacts facts(long... v) throws Exception
  {
    return DatasetStatus.MapFacts.of(versions(BERLIN, v));
  }

  @Test public void theNoteNamesTheActualVersionsInBothLanguages() throws Exception
  {
    DatasetStatus s = with(row("ready-untrusted"), facts(261104L, 261020L));
    String en = DatasetStatusText.of(context("en"), s).text(), de = DatasetStatusText.of(context("de"), s).text();
    assertTrue(en, en.contains("261020, 261104"));
    assertTrue(de, de.contains("261020, 261104"));
    assertTrue(en, en.contains("versions"));
    assertTrue(de, de.contains("Versionen"));
    assertNotEquals(en, de);
  }

  @Test public void aSingleVersionUsesTheSingularForm() throws Exception
  {
    DatasetStatus s = with(row("ready-untrusted"), facts(261020L));
    String en = DatasetStatusText.of(context("en"), s).text(), de = DatasetStatusText.of(context("de"), s).text();
    assertTrue(en, en.contains("data version: 261020"));
    assertFalse(en, en.contains("versions"));
    assertTrue(de, de.contains("Version: 261020"));
  }

  @Test public void aTruncatedListSaysHowManyOlderVersionsAreNotListed() throws Exception
  {
    DatasetStatus s = with(row("ready-untrusted"), facts(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L));
    String en = DatasetStatusText.of(context("en"), s).text(), de = DatasetStatusText.of(context("de"), s).text();
    assertTrue(en, en.contains("3, 4, 5, 6, 7, 8, plus 2 older"));
    assertTrue(de, de.contains("3, 4, 5, 6, 7, 8 sowie 2 ältere"));
  }

  @Test public void theNoteNeverClaimsBerlinCoverage() throws Exception
  {
    DatasetStatus s = with(row("ready-untrusted"), facts(261020L, 261104L));
    String en = DatasetStatusText.of(context("en"), s).text().toLowerCase(Locale.ROOT);
    String de = DatasetStatusText.of(context("de"), s).text().toLowerCase(Locale.ROOT);
    assertTrue(en, en.contains("does not confirm that any of them covers berlin"));
    assertTrue(de, de.contains("bestätigt nicht"));
    assertFalse(en, en.contains("berlin map"));
    assertFalse(de, de.contains("berlin-karte") || de.contains("karte berlin"));
  }

  @Test public void noNoteWithoutRealVersions() throws Exception
  {
    for (DatasetStatusCorpus.Row r : DatasetStatusCorpus.rows())
      for (String locale : new String[] {"en", "de"})
        assertNull(r.name + "/" + locale, DatasetStatusText.of(context(locale), r.status()).mapNote);
    // Unknown and absent captures carry no versions, so no note and no claim.
    DatasetStatusCorpus.Row r = row("ready-untrusted");
    assertNull(DatasetStatusText.of(context("en"), with(r, DatasetStatus.MapFacts.UNKNOWN)).mapNote);
    assertNull(DatasetStatusText.of(context("en"), with(r, DatasetStatus.MapFacts.ABSENT)).mapNote);
  }

  @Test public void theNoteNeverChangesTheHeadlineOrTheAction() throws Exception
  {
    DatasetStatus.MapFacts f = facts(261020L, 261104L);
    for (DatasetStatusCorpus.Row r : DatasetStatusCorpus.rows())
      for (String locale : new String[] {"en", "de"})
      {
        DatasetStatusText.Presentation plain = DatasetStatusText.of(context(locale), r.status());
        DatasetStatusText.Presentation noted = DatasetStatusText.of(context(locale), with(r, r.map == DatasetStatus.MapPresence.PRESENT ? f
                                                                                           : DatasetStatus.MapFacts.ofPresence(r.map)));
        String where = r.name + "/" + locale;
        assertEquals(where, plain.kind, noted.kind);
        assertEquals(where, plain.headline, noted.headline);
        assertEquals(where, plain.action, noted.action);
        assertFalse(where, noted.text().contains("%"));
        assertFalse(where, noted.text().contains("null"));
      }
  }

  @Test public void onlyAPresentCaptureWithVersionsGetsANote() throws Exception
  {
    DatasetStatus.MapFacts f = facts(261020L);
    for (DatasetStatusCorpus.Row r : DatasetStatusCorpus.rows())
    {
      DatasetStatusText.Presentation p = DatasetStatusText.of(context("en"), with(r, r.map == DatasetStatus.MapPresence.PRESENT ? f
                                                                                  : DatasetStatus.MapFacts.ofPresence(r.map)));
      assertEquals(r.name, r.map == DatasetStatus.MapPresence.PRESENT, p.mapNote != null);
    }
  }

  @Test public void readyStillRequiresATimetableAndAPresentMapNotTheNote() throws Exception
  {
    DatasetStatus s = with(row("ready-untrusted"), facts(261020L));
    assertEquals(DatasetStatus.Kind.READY_UNTRUSTED, s.kind);
    String en = DatasetStatusText.of(context("en"), s).text();
    assertTrue(en, en.startsWith("Ready, untrusted"));
    // An unknown capture stays "could not be checked" however many versions an earlier capture had.
    String unknown = DatasetStatusText.of(context("en"), with(row("ready-untrusted"), DatasetStatus.MapFacts.UNKNOWN)).text();
    assertTrue(unknown, unknown.contains("could not be checked"));
    assertFalse(unknown, unknown.startsWith("Ready"));
  }

  @Test public void theProductionFactsReadIsUnknownOnAPlainApplicationAndAgreesWithThePresenceRead()
  {
    Context c = context("en");
    assertEquals(DatasetStatus.MapFacts.UNKNOWN, DatasetMapPresence.captureFacts(c));
    assertEquals(DatasetMapPresence.capture(c), DatasetMapPresence.captureFacts(c).presence);
  }
}
