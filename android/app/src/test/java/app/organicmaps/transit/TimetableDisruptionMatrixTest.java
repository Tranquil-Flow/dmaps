package app.organicmaps.transit;

import android.app.Application;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import java.lang.reflect.Field;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** WI-07 S6 host cells for the Timetable screen (T0..T7 per S7). Real TimetableActivity (where stated), real
 * TimetableViewModel, single worker, real repository over the JourneyUiFixture pack (actual producer-derived SQLite,
 * untrusted import). The ViewModel reads the WALL clock (Instant.now()) for boards, so no assertion here depends on the
 * board having rows; only on selection identity, candidates, query text, pack identity and status. Not physical
 * Android process death and not realtime (live updates are unavailable in the shipping capability). */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class TimetableDisruptionMatrixTest {
  @Rule public TemporaryFolder temp=new TemporaryFolder();
  /** Same seam as TimetableTrustUiTest.Surface, with the REAL ViewModel (its store is cleared on destroy = process end). */
  public static class Host extends TimetableActivity {
    static volatile TimetableViewModel model;
    @Override public ViewModelProvider.Factory getDefaultViewModelProviderFactory(){
      return new ViewModelProvider.Factory(){@NonNull public <T extends ViewModel>T create(@NonNull Class<T> type){return type.cast(model);}};
    }
  }
  private JourneyUiFixture fixture;private String hash;private Stop stop;
  private final List<TimetableViewModel> models=new ArrayList<>();private final List<ExecutorService> workers=new ArrayList<>();
  private ActivityController<Host> controller;

  @Before public void setup()throws Exception {
    fixture=new JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"),ZoneOffset.UTC));
    hash=fixture.repository.info().databaseSha256;
    stop=fixture.repository.searchStops("Alexanderplatz",50).stream().filter(s->s.locationType==0).findFirst().orElseThrow();
  }
  @After public void close()throws Exception {
    if(controller!=null)controller.pause().stop().destroy();
    for(TimetableViewModel m:models)m.onCleared();
    for(ExecutorService w:workers)assertTrue(w.awaitTermination(10,TimeUnit.SECONDS));
    Host.model=null;Shadows.shadowOf(Looper.getMainLooper()).idle();
  }
  private ExecutorService lastWorker;
  private TimetableViewModel newModel(){
    lastWorker=Executors.newSingleThreadExecutor(r->new Thread(r,"dmaps-timetable-matrix-worker"));workers.add(lastWorker);
    TimetableViewModel m=new TimetableViewModel(RuntimeEnvironment.getApplication(),fixture.repository,lastWorker);models.add(m);return m;
  }
  private void settle(TimetableViewModel m)throws Exception {
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
    do{Shadows.shadowOf(Looper.getMainLooper()).idle();if(m.getState().getValue().status!=TimetableViewModel.Status.WORKING){
      if(controller!=null&&Host.model==m)SilentAccessibilityAudit.check(controller.get().findViewById(android.R.id.content),"Timetable/"+id(m));
      return;}Thread.sleep(5);}while(System.nanoTime()<end);
    fail("worker did not retire the operation");
  }
  private static String id(TimetableViewModel m){return ScreenStateCatalogue.timetable(m.getState().getValue());}
  private Object field(Object owner,String name)throws Exception {Field f=TimetableActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
  private Bundle parceled(Bundle live)throws Exception {
    // Only our own keys cross the parcel: this is what the OS would hand back after killing the process.
    Bundle ours=new Bundle();
    for(String key:new String[]{"transit.saved_state","transit.saved_database","transit.picker_next","transit.picker_code","transit.picker_mode"}){
      Object v=live.get(key);if(v instanceof android.os.Parcelable)ours.putParcelable(key,(android.os.Parcelable)v);else if(v instanceof String)ours.putString(key,(String)v);else if(v instanceof Integer)ours.putInt(key,(Integer)v);
    }
    Parcel p=Parcel.obtain();try{ours.writeToParcel(p,0);p.setDataPosition(0);Bundle back=Bundle.CREATOR.createFromParcel(p);back.setClassLoader(TimetableViewModel.SavedState.class.getClassLoader());return back;}finally{p.recycle();}
  }

  // ---- S7: catalogue names follow the real model ----
  @Test public void catalogueIdsFollowTheTimetableViewModelThroughItsReachableStates()throws Exception {
    TimetableViewModel m=newModel();settle(m);
    m.refreshPackState(null);settle(m);assertEquals("pack present, nothing chosen","T3",id(m));
    m.searchStops("Alexanderplatz");settle(m);assertEquals("T2",id(m));
    m.searchStops("zzzz-no-such-station");settle(m);assertEquals("T4",id(m));
    m.searchStops("Alexanderplatz");settle(m);
    m.selectStop(stop);assertEquals("T5",id(m));settle(m);
    assertTrue("T1 with rows, or T4 when the wall clock leaves the board empty: "+id(m),id(m).equals("T1")||id(m).equals("T4"));
    assertEquals(stop.id,m.getState().getValue().selectedStop.id);
    fixture.repository.removePack();m.refreshPackState(null);settle(m);assertEquals("T0",id(m));
  }

  // ---- recreate / process death: Activity -> Bundle (parceled) -> new ViewModel + new Activity ----
  @Test public void processDeathRoundTripRestoresSelectionAndQueryButRetiresCandidates()throws Exception {
    TimetableViewModel m1=newModel();Host.model=m1;ExecutorService w1=lastWorker;
    controller=Robolectric.buildActivity(Host.class);Host a1=controller.get();a1.setTheme(R.style.MwmTheme);controller.create().start().resume().visible();settle(m1);
    m1.searchStops("Alexanderplatz");settle(m1);assertFalse(m1.getState().getValue().candidates.isEmpty());
    m1.selectStop(stop);settle(m1);assertNotNull(m1.getState().getValue().selectedStop);
    Bundle live=new Bundle();controller.saveInstanceState(live);Bundle saved=parceled(live);
    assertNotNull("the Activity must have written the saved selection",saved.getParcelable("transit.saved_state"));
    assertEquals("pack identity travels beside the numeric stop id",hash,saved.getString("transit.saved_database"));
    controller.pause().stop().destroy();controller=null;assertTrue("old process worker ended",w1.awaitTermination(10,TimeUnit.SECONDS));
    TimetableViewModel m2=newModel();Host.model=m2;
    controller=Robolectric.buildActivity(Host.class);Host a2=controller.get();a2.setTheme(R.style.MwmTheme);controller.create(saved).start().resume().visible();settle(m2);
    TimetableViewModel.ScreenState s=m2.getState().getValue();
    assertNotNull("selection re-validated against the repository",s.selectedStop);assertEquals(stop.sourceId,s.selectedStop.sourceId);assertEquals(stop.id,s.selectedStop.id);
    assertTrue("the old candidate list is retired, not restored",s.candidates.isEmpty());
    assertEquals("Alexanderplatz",m2.getQueryText());assertEquals("Alexanderplatz",((EditText)field(a2,"mQuery")).getText().toString());
    assertFalse(s.packInfo.authenticated);assertEquals(hash,s.packInfo.databaseSha256);
  }

  // ---- pack generation changed ----
  @Test public void unchangedPackKeepsTheSelectionAndAReplacedPackRetiresItWithItsBoardAndCandidates()throws Exception {
    TimetableViewModel m=newModel();settle(m);m.refreshPackState(null);settle(m);
    m.searchStops("Alexanderplatz");settle(m);m.selectStop(stop);settle(m);assertEquals(stop.id,m.getState().getValue().selectedStop.id);
    m.refreshPackState(null);settle(m);
    assertNotNull("same payload: a resume-style refresh keeps the selection",m.getState().getValue().selectedStop);assertEquals(stop.id,m.getState().getValue().selectedStop.id);
    fixture.replaceLabels();m.refreshPackState(null);settle(m);
    TimetableViewModel.ScreenState s=m.getState().getValue();
    assertNull("a replaced payload must not keep a numeric stop id from the old one",s.selectedStop);assertTrue(s.board.isEmpty());assertTrue(s.candidates.isEmpty());
    assertNotEquals(hash,s.packInfo.databaseSha256);assertEquals("T3",id(m));
  }

  // ---- worker cancelled ----
  @Test public void cancelPendingResultsDiscardsTheInFlightSearchAndLeavesAUsableScreen()throws Exception {
    TimetableViewModel m=newModel();ExecutorService w=lastWorker;settle(m);m.refreshPackState(null);settle(m);
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    w.submit(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(entered.await(10,TimeUnit.SECONDS));
    try{
      m.searchStops("Alexanderplatz");assertEquals("T5",id(m));
      m.cancelPendingResults();assertNotEquals("the screen is no longer 'working' after the cancel",TimetableViewModel.Status.WORKING,m.getState().getValue().status);
    }finally{release.countDown();}
    w.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
    TimetableViewModel.ScreenState s=m.getState().getValue();
    assertTrue("the cancelled search must not publish its candidates",s.candidates.isEmpty());assertNull(s.selectedStop);assertTrue(s.board.isEmpty());
    assertEquals("T3",id(m));
    m.searchStops("Alexanderplatz");settle(m);assertEquals("a search after the cancel works","T2",id(m));
  }
}
