package app.organicmaps.transit;

import static app.organicmaps.sdk.transit.DatasetMapFactsFixture.BERLIN;
import static app.organicmaps.sdk.transit.DatasetMapFactsFixture.versions;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.DatasetStatus;
import app.organicmaps.sdk.transit.DatasetStatusCorpus;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * UX06 manual setup guide on the Timetable screen: the steps use the real labels of the existing Download Maps
 * entry and the existing import chooser, say what storage depends on without inventing a number, and appear
 * exactly while S1 has an action to offer. Real Timetable/Journey activities with mocked view models; no
 * repository, worker, network or native code runs.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, qualifiers = "en", manifest = Config.NONE)
public class TimetableSetupGuideTest
{
  private Context context(String locale)
  {
    RuntimeEnvironment.setQualifiers(locale);
    return RuntimeEnvironment.getApplication();
  }

  // ---- the words ------------------------------------------------------------------------------------------------
  @Test public void theStepsUseTheRealLabelsOfTheExistingEntries()
  {
    for (String locale : new String[] {"en", "de"})
    {
      Context c = context(locale);
      String guide = DatasetStatusText.setupGuide(c);
      for (int label : new int[] {R.string.download_maps, R.string.transit_import_pack, R.string.transit_import_untrusted,
                                  R.string.transit_import_official})
        assertTrue(locale + " must quote the real label: " + c.getString(label), guide.contains(c.getString(label)));
    }
  }

  @Test public void theGuideSaysWhatStorageDependsOnAndInventsNoNumber()
  {
    String en = DatasetStatusText.setupGuide(context("en")), de = DatasetStatusText.setupGuide(context("de"));
    for (String must : new String[] {"depends on the files", "archive", "previous pack", "real size", "check your free space"})
      assertTrue(must, en.toLowerCase(Locale.ROOT).contains(must));
    for (String must : new String[] {"hängt von den gewählten dateien ab", "archiv", "vorherige paket", "tatsächliche größe",
                                     "freien speicher"})
      assertTrue(must, de.toLowerCase(Locale.ROOT).contains(must));
    Pattern sizeOrProgress = Pattern.compile("(?i)\\d\\s*(%|[kmgt]i?b)|percent|prozent|progress|fortschritt");
    for (String guide : new String[] {en, de})
    {
      assertFalse(guide, sizeOrProgress.matcher(guide).find());
      String withoutStepNumbers = guide.replaceAll("(?m)^\\d\\. ", "");
      assertFalse("no figure of any kind: " + guide, Pattern.compile("\\d").matcher(withoutStepNumbers).find());
      assertFalse(guide, guide.contains("%") || guide.contains("null") || guide.contains("http"));
    }
  }

  @Test public void theGuideIsCompactAndNamesTheCheckAndTheCoverageLimit()
  {
    String en = DatasetStatusText.setupGuide(context("en")), de = DatasetStatusText.setupGuide(context("de"));
    assertNotEquals(en, de);
    assertTrue(en, en.split("\n").length <= 6);
    assertTrue(en, en.contains("does not prove it covers Berlin"));
    assertTrue(en, en.contains("untrusted"));
    assertTrue(de, de.contains("beweist nicht"));
  }

  // ---- when it shows ----------------------------------------------------------------------------------------------
  static TimetableViewModel tModel;
  static Instant tNow;
  static DatasetStatus.MapPresence tMap;
  static DatasetStatus.MapFacts tFacts; // null: use the legacy presence seam
  static int tPresenceReads;
  public static class Surface extends TimetableActivity
  {
    @Override public ViewModelProvider.Factory getDefaultViewModelProviderFactory()
    {
      return new ViewModelProvider.Factory() {
        @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) { return type.cast(tModel); }
      };
    }
    @Override Instant presentationNow() { return tNow; }
    @Override DatasetStatus.MapPresence datasetMapPresence() { tPresenceReads++; return tMap; }
    @Override DatasetStatus.MapFacts datasetMapFacts()
    { return tFacts != null ? tFacts : super.datasetMapFacts(); }
  }

  private ActivityController<Surface> controller;

  @After public void cleanup()
  {
    if (controller != null) controller.pause().stop().destroy();
    if (jController != null) jController.pause().stop().destroy();
    tFacts = null;
  }

  private static TimetableViewModel.ScreenState screen(DatasetStatusCorpus.Row row)
  {
    TimetableViewModel.ScreenState s = new TimetableViewModel.ScreenState();
    s.packInfo = row.pack;
    s.packPresent = row.pack != null || row.unavailable;
    s.status = row.unavailable ? TimetableViewModel.Status.ERROR : TimetableViewModel.Status.READY;
    s.importFailed = row.activation == DatasetStatus.Activation.REPLACE_FAILED;
    return s;
  }

  private Surface open(DatasetStatusCorpus.Row row, MutableLiveData<TimetableViewModel.ScreenState> live)
  {
    tModel = mock(TimetableViewModel.class);
    when(tModel.getState()).thenReturn(live);
    when(tModel.getQueryText()).thenReturn("");
    tNow = row.now;
    tMap = row.map;
    controller = Robolectric.buildActivity(Surface.class);
    Surface a = controller.get();
    a.setTheme(R.style.MwmTheme);
    controller.create().start().resume().visible();
    TimetableViewModel.ScreenState s = live.getValue();
    s.revision++;
    live.setValue(s);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    return a;
  }

  private static TextView guide(Surface a) throws Exception
  {
    Field f = TimetableActivity.class.getDeclaredField("mSetupGuide");
    f.setAccessible(true);
    return (TextView) f.get(a);
  }

  private static String field(TimetableActivity a, String name) throws Exception
  {
    Field f = TimetableActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    return ((TextView) f.get(a)).getText().toString();
  }

  @Test public void theGuideShowsExactlyWhileS1OffersAnAction() throws Exception
  {
    for (DatasetStatusCorpus.Row row : DatasetStatusCorpus.rows())
    {
      Surface a = open(row, new MutableLiveData<>(screen(row)));
      DatasetStatus expected = row.statusFor(DatasetStatus.Scope.BOARDS);
      boolean shows = expected.action() != DatasetStatus.Action.NONE;
      TextView g = guide(a);
      assertNotNull(row.name, g);
      assertEquals(row.name, "dmaps-setup-guide", g.getTag());
      assertEquals(row.name, shows ? View.VISIBLE : View.GONE, g.getVisibility());
      if (shows) assertEquals(row.name, DatasetStatusText.setupGuide(a), g.getText().toString());
      // The pre-existing import control and its labels are untouched.
      assertEquals(row.name, a.getString(R.string.transit_import_pack), field(a, "mImportBtn"));
      controller.pause().stop().destroy();
      controller = null;
    }
  }

  @Test public void theGuideStaysHiddenBeforeTheFirstPackReadAnswers() throws Exception
  {
    DatasetStatusCorpus.Row row = DatasetStatusCorpus.rows().get(0);
    TimetableViewModel.ScreenState idle = new TimetableViewModel.ScreenState(); // IDLE
    Surface a = open(row, new MutableLiveData<>(idle));
    assertEquals(View.GONE, guide(a).getVisibility());
  }

  // ---- one registry capture per resume ------------------------------------------------------------------------------
  @Test public void exactlyOneRegistryReadHappensPerResume() throws Exception
  {
    DatasetStatusCorpus.Row row = DatasetStatusCorpus.rows().get(1); // ready-untrusted
    tPresenceReads = 0;
    Surface a = open(row, new MutableLiveData<>(screen(row)));
    assertEquals("one capture for the first resume, shared by the header and the guide", 1, tPresenceReads);
    controller.pause();
    controller.resume();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertEquals("a new resume is a new capture", 2, tPresenceReads);
  }

  @Test public void theHeaderCarriesTheCapturedVersionsFromTheOneFactsSeam() throws Exception
  {
    DatasetStatusCorpus.Row row = DatasetStatusCorpus.rows().get(1); // ready-untrusted
    tFacts = DatasetStatus.MapFacts.of(versions(BERLIN, 261020L, 261104L));
    Surface a = open(row, new MutableLiveData<>(screen(row)));
    String shown = field(a, "mDatasetLine");
    assertTrue(shown, shown.contains("261020, 261104"));
    assertEquals(DatasetStatusText.of(a, DatasetStatus.computeWith(DatasetStatus.Scope.BOARDS, row.pack, false, tFacts, row.now,
                                                                   DatasetStatus.Activation.NONE, null)).text(), shown);
  }

  @Test public void aProductionStyleResumeOnAPlainApplicationReadsUnknownNotMissing() throws Exception
  {
    // No seam overridden: the real default path. A plain Application never reaches the registry.
    DatasetStatusCorpus.Row row = DatasetStatusCorpus.rows().get(1);
    tModel = mock(TimetableViewModel.class);
    MutableLiveData<TimetableViewModel.ScreenState> live = new MutableLiveData<>(screen(row));
    when(tModel.getState()).thenReturn(live);
    when(tModel.getQueryText()).thenReturn("");
    tNow = row.now;
    ActivityController<Plain> c = Robolectric.buildActivity(Plain.class);
    try
    {
      c.get().setTheme(R.style.MwmTheme);
      c.create().start().resume().visible();
      TimetableViewModel.ScreenState s = live.getValue();
      s.revision++;
      live.setValue(s);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      String shown = field(c.get(), "mDatasetLine");
      assertTrue(shown, shown.contains("could not be checked"));
      assertFalse(shown, shown.contains("No offline map is registered"));
    }
    finally { c.pause().stop().destroy(); }
  }

  public static class Plain extends TimetableActivity
  {
    @Override public ViewModelProvider.Factory getDefaultViewModelProviderFactory()
    {
      return new ViewModelProvider.Factory() {
        @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) { return type.cast(tModel); }
      };
    }
    @Override Instant presentationNow() { return tNow; }
  }

  // ---- Journey header: same facts, same text -----------------------------------------------------------------------
  static JourneyViewModel jModel;
  static DatasetStatus.MapFacts jFacts;
  public static class JourneySurface extends JourneyActivity
  {
    @Override JourneyViewModel createModel() { return jModel; }
    @Override DatasetStatus.MapFacts datasetMapFacts() { return jFacts; }
  }

  private ActivityController<JourneySurface> jController;

  @Test public void theJourneyHeaderShowsTheSameVersionsThroughTheSharedText() throws Exception
  {
    DatasetStatusCorpus.Row row = DatasetStatusCorpus.rows().get(1); // ready-untrusted, Journey scope
    jFacts = DatasetStatus.MapFacts.of(versions(BERLIN, 261020L));
    jModel = mock(JourneyViewModel.class);
    MutableLiveData<JourneyViewModel.State> live = new MutableLiveData<>(
        new JourneyViewModel.State(null, null, null, null, false, false, null, R.string.journey_choose));
    when(jModel.getState()).thenReturn(live);
    when(jModel.datasetPack()).thenReturn(row.pack);
    when(jModel.datasetResolved()).thenReturn(true);
    when(jModel.datasetEnvelope()).thenReturn(null);
    when(jModel.now()).thenReturn(row.now);
    jController = Robolectric.buildActivity(JourneySurface.class);
    JourneySurface a = jController.get();
    a.setTheme(R.style.MwmTheme);
    jController.create().start().resume().visible();
    live.setValue(new JourneyViewModel.State(null, null, null, null, false, false, null, R.string.journey_choose));
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    TextView header = a.findViewById(android.R.id.content).findViewWithTag("dmaps-dataset-status");
    assertNotNull(header);
    String shown = header.getText().toString();
    assertTrue(shown, shown.contains("data version: 261020"));
    assertEquals(DatasetStatusText.of(a, DatasetStatus.computeWith(DatasetStatus.Scope.JOURNEY, row.pack, false, jFacts, row.now,
                                                                   DatasetStatus.Activation.NONE, null)).text(), shown);
    // Journey cannot import, so it carries no setup guide.
    assertNull(a.findViewById(android.R.id.content).findViewWithTag("dmaps-setup-guide"));
  }
}
