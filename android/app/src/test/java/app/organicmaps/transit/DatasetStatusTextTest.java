package app.organicmaps.transit;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import app.organicmaps.sdk.transit.DatasetStatus;
import app.organicmaps.sdk.transit.DatasetStatusCorpus;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Compiled English and German text for every S1 state of the shared corpus. Host only; no native code. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, qualifiers = "en", manifest = Config.NONE)
public class DatasetStatusTextTest
{
  private Context context(String locale)
  {
    RuntimeEnvironment.setQualifiers(locale);
    return RuntimeEnvironment.getApplication();
  }

  @Test public void everyCorpusRowHasCompleteText() throws Exception
  {
    for (String locale : new String[] {"en", "de"})
      for (DatasetStatusCorpus.Row row : DatasetStatusCorpus.rows())
      {
        DatasetStatusText.Presentation p = DatasetStatusText.of(context(locale), row.status());
        String where = locale + "/" + row.name;
        assertEquals(where, row.expected, p.kind);
        assertFalse(where, p.headline.trim().isEmpty());
        assertFalse(where + " unformatted placeholder: " + p.text(), p.text().contains("%"));
        assertFalse(where + " null leaked: " + p.text(), p.text().contains("null"));
        boolean needsAction = row.status().action() != DatasetStatus.Action.NONE;
        assertEquals(where, needsAction, p.action != null);
      }
  }

  @Test public void englishAndGermanDiffer() throws Exception
  {
    for (DatasetStatusCorpus.Row row : DatasetStatusCorpus.rows())
      assertNotEquals(row.name, DatasetStatusText.of(context("en"), row.status()).text(),
                      DatasetStatusText.of(context("de"), row.status()).text());
  }

  @Test public void distinctStatesNeverShareAHeadline() throws Exception
  {
    Set<String> seen = new HashSet<>();
    for (DatasetStatusCorpus.Row row : DatasetStatusCorpus.rows())
      assertTrue(row.name + " duplicates another state's text", seen.add(DatasetStatusText.of(context("en"), row.status()).headline));
  }

  @Test public void onlyStatesWithAPresentMapAndCurrentTimetableMaySayReady() throws Exception
  {
    for (DatasetStatusCorpus.Row row : DatasetStatusCorpus.rows())
    {
      String en = DatasetStatusText.of(context("en"), row.status()).headline.toLowerCase(java.util.Locale.ROOT);
      String de = DatasetStatusText.of(context("de"), row.status()).headline.toLowerCase(java.util.Locale.ROOT);
      boolean mayBeReady = row.status().kind == DatasetStatus.Kind.READY
          || row.status().kind == DatasetStatus.Kind.READY_UNTRUSTED;
      if (!mayBeReady)
      {
        assertFalse(row.name + " en", en.startsWith("ready"));
        assertFalse(row.name + " de", de.startsWith("bereit"));
      }
    }
  }

  @Test public void untrustedStatesNeverReadAsOfficial() throws Exception
  {
    for (DatasetStatusCorpus.Row row : DatasetStatusCorpus.rows())
    {
      DatasetStatus s = row.status();
      if (s.trust != DatasetStatus.Trust.UNTRUSTED
          || !(s.kind == DatasetStatus.Kind.READY_UNTRUSTED || s.kind == DatasetStatus.Kind.EXPIRING
               || s.kind == DatasetStatus.Kind.KEPT_PREVIOUS_AFTER_FAILED_REPLACE)) continue;
      assertTrue(row.name, DatasetStatusText.of(context("en"), s).headline.toLowerCase(java.util.Locale.ROOT).contains("untrusted"));
      assertTrue(row.name, DatasetStatusText.of(context("de"), s).headline.toLowerCase(java.util.Locale.ROOT).contains("nicht vertrauenswürdig"));
    }
  }

  @Test public void pluralDays() throws Exception
  {
    java.util.List<DatasetStatusCorpus.Row> rows = DatasetStatusCorpus.rows();
    DatasetStatus four = null;
    for (DatasetStatusCorpus.Row r : rows) if (r.name.equals("expiring-4-days")) four = r.status();
    assertNotNull(four);
    assertTrue(DatasetStatusText.of(context("en"), four).headline.contains("4 days"));
    assertTrue(DatasetStatusText.of(context("de"), four).headline.contains("4 Tagen"));
  }

  @Test public void aFailedReplaceNoteAccompaniesAnyOtherState() throws Exception
  {
    for (DatasetStatusCorpus.Row row : DatasetStatusCorpus.rows())
    {
      DatasetStatus s = row.status();
      if (!s.replaceFailed || s.kind == DatasetStatus.Kind.KEPT_PREVIOUS_AFTER_FAILED_REPLACE) continue;
      assertTrue(row.name, DatasetStatusText.of(context("en"), s).headline.contains("last import failed"));
    }
  }

  @Test public void productionMapReadIsUnknownOnAPlainApplicationWithoutTouchingNative()
  {
    // Robolectric's Application is not MwmApplication: the guard answers before any registry/JNI access.
    assertEquals(DatasetStatus.MapPresence.UNKNOWN, DatasetMapPresence.capture(context("en")));
  }
}
