package app.organicmaps.transit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
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
import app.organicmaps.sdk.transit.PackInfo;
import java.lang.reflect.Field;
import java.time.Instant;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * The same S1 corpus drives both consumers. Adapter tests prove Timetable and Journey derive identical status
 * from equivalent facts; the wiring tests prove each real Activity renders the shared text and still renders the
 * pre-existing trust, import-failure and expiry text unchanged. ViewModels are mocked, so no repository,
 * worker or native code runs.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, qualifiers = "en", manifest = Config.NONE)
public class DatasetStatusConsumersTest
{
  // ---- adapters -------------------------------------------------------------------------------------------------
  private static TimetableViewModel.ScreenState screen(DatasetStatusCorpus.Row row)
  {
    TimetableViewModel.ScreenState s = new TimetableViewModel.ScreenState();
    s.packInfo = row.pack;
    s.packPresent = row.pack != null || row.unavailable;
    s.status = row.unavailable ? TimetableViewModel.Status.ERROR : TimetableViewModel.Status.READY;
    s.importFailed = row.activation == DatasetStatus.Activation.REPLACE_FAILED;
    return s;
  }

  @Test public void timetableAdapterEqualsTheCorpusStatusForEveryRow() throws Exception
  {
    for (DatasetStatusCorpus.Row row : DatasetStatusCorpus.rows())
      assertEquals(row.name, row.statusFor(DatasetStatus.Scope.BOARDS),
                   DatasetStatusText.forTimetable(screen(row), row.now, row.map));
  }

  @Test public void journeyAdapterEqualsTheCorpusStatusForEveryRowWithoutActivation() throws Exception
  {
    for (DatasetStatusCorpus.Row row : DatasetStatusCorpus.rows())
    {
      if (row.activation != DatasetStatus.Activation.NONE) continue; // Journey never imports
      assertEquals(row.name, row.statusFor(DatasetStatus.Scope.JOURNEY),
                   DatasetStatusText.forJourney(row.pack, row.unavailable, null, row.now, row.map));
    }
  }

  @Test public void consumersAgreeUnlessTheFormatMakesJourneyIncompatible() throws Exception
  {
    for (DatasetStatusCorpus.Row row : DatasetStatusCorpus.rows())
    {
      DatasetStatus boards = row.statusFor(DatasetStatus.Scope.BOARDS), journey = row.statusFor(DatasetStatus.Scope.JOURNEY);
      if (row.pack != null && row.pack.formatVersion != 2) assertEquals(row.name, DatasetStatus.Kind.INCOMPATIBLE, journey.kind);
      else assertEquals(row.name, boards.kind, journey.kind);
    }
  }

  @Test public void timetableHidesTheLineUntilTheFirstPackReadAnswers()
  {
    TimetableViewModel.ScreenState s = new TimetableViewModel.ScreenState(); // IDLE
    assertNull(DatasetStatusText.forTimetable(s, Instant.parse("2026-10-28T10:00:00Z"), DatasetStatus.MapPresence.PRESENT));
    s.status = TimetableViewModel.Status.WORKING;
    assertNull(DatasetStatusText.forTimetable(s, Instant.parse("2026-10-28T10:00:00Z"), DatasetStatus.MapPresence.PRESENT));
  }

  @Test public void timetableImportErrorWithNoPackIsUnavailableNotNoPack()
  {
    TimetableViewModel.ScreenState s = new TimetableViewModel.ScreenState();
    s.status = TimetableViewModel.Status.ERROR; // PackCommitException path: packInfo null, packPresent false
    assertEquals(DatasetStatus.Kind.PACK_UNAVAILABLE,
                 DatasetStatusText.forTimetable(s, Instant.parse("2026-10-28T10:00:00Z"), DatasetStatus.MapPresence.PRESENT).kind);
  }

  @Test public void journeyKeepsAnExpiredPackAsExpiredNotNoPack() throws Exception
  {
    // JourneyViewModel drops State.info for an expired pack; the resolved snapshot must still say EXPIRED.
    for (DatasetStatusCorpus.Row row : DatasetStatusCorpus.rows())
      if (row.expected == DatasetStatus.Kind.EXPIRED)
        assertEquals(DatasetStatus.Kind.EXPIRED,
                     DatasetStatusText.forJourney(row.pack, false, null, row.now, DatasetStatus.MapPresence.PRESENT).kind);
  }

  @Test public void journeyUnresolvedLoadIsUnavailableNeverNoPack()
  {
    assertEquals(DatasetStatus.Kind.PACK_UNAVAILABLE,
                 DatasetStatusText.forJourney(null, true, null, Instant.parse("2026-10-28T10:00:00Z"), DatasetStatus.MapPresence.PRESENT).kind);
    assertEquals(DatasetStatus.Kind.NO_PACK,
                 DatasetStatusText.forJourney(null, false, null, Instant.parse("2026-10-28T10:00:00Z"), DatasetStatus.MapPresence.PRESENT).kind);
  }

  // ---- Timetable wiring -----------------------------------------------------------------------------------------
  static TimetableViewModel tModel;
  static Instant tNow;
  static DatasetStatus.MapPresence tMap;
  public static class TimetableSurface extends TimetableActivity
  {
    @Override public ViewModelProvider.Factory getDefaultViewModelProviderFactory()
    {
      return new ViewModelProvider.Factory() {
        @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) { return type.cast(tModel); }
      };
    }
    @Override Instant presentationNow() { return tNow; }
    @Override DatasetStatus.MapPresence datasetMapPresence() { return tMap; }
  }

  private ActivityController<TimetableSurface> tController;

  private TextView tView(TimetableSurface a, String field) throws Exception
  {
    Field f = TimetableActivity.class.getDeclaredField(field);
    f.setAccessible(true);
    return (TextView) f.get(a);
  }

  private String tText(TimetableSurface a, String field) throws Exception
  {
    Field f = TimetableActivity.class.getDeclaredField(field);
    f.setAccessible(true);
    return ((TextView) f.get(a)).getText().toString();
  }

  @Test public void timetableHeaderShowsTheSharedTextAndKeepsTheExistingWarnings() throws Exception
  {
    for (DatasetStatusCorpus.Row row : DatasetStatusCorpus.rows())
    {
      tModel = mock(TimetableViewModel.class);
      TimetableViewModel.ScreenState s = screen(row);
      MutableLiveData<TimetableViewModel.ScreenState> live = new MutableLiveData<>(s);
      when(tModel.getState()).thenReturn(live);
      when(tModel.getQueryText()).thenReturn("");
      tNow = row.now;
      tMap = row.map;
      tController = Robolectric.buildActivity(TimetableSurface.class);
      TimetableSurface a = tController.get();
      a.setTheme(R.style.MwmTheme);
      tController.create().start().resume().visible();
      s.revision++;
      live.setValue(s);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      DatasetStatus expected = row.statusFor(DatasetStatus.Scope.BOARDS);
      String shown = tText(a, "mDatasetLine");
      assertEquals(row.name, DatasetStatusText.of(a, expected).text(), shown);
      SilentAccessibilityAudit.check(a.findViewById(android.R.id.content),"Timetable-status/"+row.name);
      SilentAccessibilityAudit.announcesChanges(tView(a, "mDatasetLine"),"Timetable-dataset/"+row.name);
      SilentAccessibilityAudit.announcesChanges(tView(a, "mStatusLine"),"Timetable-status-line/"+row.name);
      // Pre-existing text is additive-preserved.
      if (row.pack != null && !row.pack.authenticated)
        assertEquals(row.name, a.getString(R.string.transit_unsigned_pack_disclosure), tText(a, "mTrustLine"));
      if (s.importFailed && row.pack == null)
        assertEquals(row.name, a.getString(R.string.transit_import_failed), tText(a, "mStatusLine"));
      if (row.pack != null && row.pack.isExpired(row.now))
        assertTrue(row.name, tText(a, "mPackLine").contains(a.getString(R.string.transit_pack_expired)));
      tController.pause().stop().destroy();
      tController = null;
    }
  }

  @After public void cleanup()
  {
    if (tController != null) tController.pause().stop().destroy();
    if (jController != null) jController.pause().stop().destroy();
  }

  // ---- Journey wiring -------------------------------------------------------------------------------------------
  static JourneyViewModel jModel;
  static DatasetStatus.MapPresence jMap;
  public static class JourneySurface extends JourneyActivity
  {
    @Override JourneyViewModel createModel() { return jModel; }
    @Override DatasetStatus.MapPresence datasetMapPresence() { return jMap; }
  }

  private ActivityController<JourneySurface> jController;

  @Test public void journeyHeaderShowsTheSharedTextForEveryRowWithoutActivation() throws Exception
  {
    for (DatasetStatusCorpus.Row row : DatasetStatusCorpus.rows())
    {
      if (row.activation != DatasetStatus.Activation.NONE) continue;
      jModel = mock(JourneyViewModel.class);
      MutableLiveData<JourneyViewModel.State> live = new MutableLiveData<>(
          new JourneyViewModel.State(null, null, null, null, false, false, null, R.string.journey_choose));
      when(jModel.getState()).thenReturn(live);
      when(jModel.datasetPack()).thenReturn(row.pack);
      when(jModel.datasetResolved()).thenReturn(!row.unavailable);
      when(jModel.datasetEnvelope()).thenReturn(null);
      when(jModel.now()).thenReturn(row.now);
      jMap = row.map;
      jController = Robolectric.buildActivity(JourneySurface.class);
      JourneySurface a = jController.get();
      a.setTheme(R.style.MwmTheme);
      jController.create().start().resume().visible();
      live.setValue(new JourneyViewModel.State(null, null, null, null, false, false, null, R.string.journey_choose));
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      TextView header = a.findViewById(android.R.id.content).findViewWithTag("dmaps-dataset-status");
      assertNotNull(row.name, header);
      assertEquals(row.name, View.VISIBLE, header.getVisibility());
      assertEquals(row.name, DatasetStatusText.of(a, row.statusFor(DatasetStatus.Scope.JOURNEY)).text(), header.getText().toString());
      SilentAccessibilityAudit.check(a.findViewById(android.R.id.content),"Journey-status/"+row.name);
      SilentAccessibilityAudit.announcesChanges(header,"Journey-dataset/"+row.name);
      SilentAccessibilityAudit.announcesChanges(a.findViewById(android.R.id.content).findViewWithTag("dmaps-journey-status"),"Journey-status-line/"+row.name);
      SilentAccessibilityAudit.fieldsInOrder(a.findViewById(android.R.id.content),a.getString(R.string.journey_origin),a.getString(R.string.journey_destination));
      jController.pause().stop().destroy();
      jController = null;
    }
  }

  @Test public void journeyHeaderIsHiddenWhileTheFirstLoadIsBusy()
  {
    jModel = mock(JourneyViewModel.class);
    MutableLiveData<JourneyViewModel.State> live = new MutableLiveData<>(
        new JourneyViewModel.State(null, null, null, null, false, true, null, R.string.transit_working));
    when(jModel.getState()).thenReturn(live);
    when(jModel.datasetResolved()).thenReturn(false);
    when(jModel.now()).thenReturn(Instant.parse("2026-10-28T10:00:00Z"));
    jMap = DatasetStatus.MapPresence.UNKNOWN;
    jController = Robolectric.buildActivity(JourneySurface.class);
    jController.get().setTheme(R.style.MwmTheme);
    jController.create().start().resume().visible();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    TextView header = jController.get().findViewById(android.R.id.content).findViewWithTag("dmaps-dataset-status");
    assertEquals(View.GONE, header.getVisibility());
  }
}
