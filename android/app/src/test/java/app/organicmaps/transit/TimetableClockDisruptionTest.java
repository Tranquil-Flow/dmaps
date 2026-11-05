package app.organicmaps.transit;
import android.app.Application;
import android.os.Looper;
import app.organicmaps.sdk.transit.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Deterministic scheduled board/clock cells through real repository/SQLite, not fabricated rows. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class TimetableClockDisruptionTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 static final class Time extends Clock {Instant now=Instant.parse("2026-10-21T08:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}
 private final Time clock=new Time();private TimetableViewModel model;private ExecutorService worker;private JourneyUiFixture fixture;private Stop stop;
 private org.robolectric.android.controller.ActivityController<Screen> controller;
 public static class Screen extends TimetableActivity {
  static TimetableViewModel fixture;static Clock clock;
  @Override Instant presentationNow(){return clock.instant();}
  @Override public androidx.lifecycle.ViewModelProvider.Factory getDefaultViewModelProviderFactory(){return new androidx.lifecycle.ViewModelProvider.Factory(){@androidx.annotation.NonNull public <T extends androidx.lifecycle.ViewModel>T create(@androidx.annotation.NonNull Class<T> c){return c.cast(fixture);}};}
 }
 private Screen open(){Screen.fixture=model;Screen.clock=clock;controller=Robolectric.buildActivity(Screen.class);Screen a=controller.get();a.setTheme(app.organicmaps.R.style.MwmTheme);controller.create().start().resume().visible();return a;}
 @Before public void setup()throws Exception {
  fixture=new JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),clock);worker=Executors.newSingleThreadExecutor();
  try{var ctor=TimetableViewModel.class.getDeclaredConstructor(Application.class,TimetableRepository.class,ExecutorService.class,Clock.class);ctor.setAccessible(true);model=ctor.newInstance(RuntimeEnvironment.getApplication(),fixture.repository,worker,clock);}
  catch(NoSuchMethodException e){throw new AssertionError("timetable clock feature absent");}
  settle();long id=fixture.repository.tripStops("T1",LocalDate.parse("2026-10-21")).get(0).stopId;stop=fixture.repository.journeySelection(id).platforms.stream().filter(s->s.id==id).findFirst().orElseThrow();
 }
 private void settle()throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);do{Shadows.shadowOf(Looper.getMainLooper()).idle();if(model.getState().getValue().status!=TimetableViewModel.Status.WORKING)return;Thread.sleep(5);}while(System.nanoTime()<end);fail("bounded readiness");}
 private void tick()throws Exception {try{var m=TimetableViewModel.class.getDeclaredMethod("checkVisible");m.setAccessible(true);m.invoke(model);}catch(NoSuchMethodException e){throw new AssertionError("timetable clock feature absent");}}
 @After public void close()throws Exception {if(controller!=null)controller.pause().stop().destroy();if(model!=null)model.onCleared();else if(worker!=null)worker.shutdown();if(worker!=null)assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));Screen.fixture=null;Screen.clock=null;}
 @Test public void boardQueriesUseTheInjectedClockAndReturnActualFixtureDepartures()throws Exception {
  model.selectStop(stop);settle();assertFalse("fixed service-day board must contain real departures",model.getState().getValue().board.isEmpty());for(Departure d:model.getState().getValue().board)assertFalse(d.when.isBefore(clock.instant()));
 }
 @Test public void clockPassingTheLastDepartureRetiresRowsButKeepsTheSelectedStop()throws Exception {
  model.selectStop(stop);settle();var s=model.getState().getValue();assertFalse(s.board.isEmpty());int revision=s.revision;tick();assertEquals("unchanged clock must not rerender",revision,model.getState().getValue().revision);
  clock.now=s.board.stream().map(d->d.when).max(Comparator.naturalOrder()).orElseThrow().plusSeconds(1);tick();assertTrue("departed rows must not stay on the current board",model.getState().getValue().board.isEmpty());assertEquals(stop.id,model.getState().getValue().selectedStop.id);assertEquals(TimetableViewModel.Status.NO_RESULTS,model.getState().getValue().status);
 }
 @Test public void serviceWindowExpiryRetiresCandidatesAndBoardWithoutDroppingPackMetadata()throws Exception {
  model.searchStops("Alexanderplatz");settle();assertFalse(model.getState().getValue().candidates.isEmpty());clock.now=Instant.parse("2027-01-03T00:00:00Z");tick();var s=model.getState().getValue();assertTrue(s.candidates.isEmpty());assertTrue(s.board.isEmpty());assertNull(s.selectedStop);assertNotNull("status must explain expired, not absent",s.packInfo);assertEquals(TimetableViewModel.Status.ERROR,s.status);
 }
 @Test public void visibleBoardRetiresDepartedRowsFromTheActualAdapterOnItsClockTick()throws Exception {
  model.selectStop(stop);settle();assertFalse(model.getState().getValue().board.isEmpty());Screen a=open();settle();
  var f=TimetableActivity.class.getDeclaredField("mList");f.setAccessible(true);var list=(androidx.recyclerview.widget.RecyclerView)f.get(a);assertTrue(list.getAdapter().getItemCount()>0);
  clock.now=model.getState().getValue().board.stream().map(d->d.when).max(Comparator.naturalOrder()).orElseThrow().plusSeconds(1);
  Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertTrue(model.getState().getValue().board.isEmpty());assertEquals(0,list.getAdapter().getItemCount());
 }
 @Test public void appSwitchAndIdenticalKeyboardRecommitKeepTheRealScheduledBoard()throws Exception {
  model.searchStops("Alexanderplatz");settle();model.selectStop(stop);settle();assertFalse(model.getState().getValue().board.isEmpty());Screen a=open();settle();
  controller.pause().stop().start().resume().visible();settle();
  var f=TimetableActivity.class.getDeclaredField("mQuery");f.setAccessible(true);var query=(android.widget.EditText)f.get(a);assertEquals("Alexanderplatz",query.getText().toString());query.requestFocus();
  var connection=query.onCreateInputConnection(new android.view.inputmethod.EditorInfo());assertNotNull(connection);assertTrue(connection.setComposingRegion(0,query.length()));assertTrue(connection.setComposingText("Alexanderplatz",1));assertTrue(connection.finishComposingText());
  assertEquals(stop.id,model.getState().getValue().selectedStop.id);assertFalse(model.getState().getValue().board.isEmpty());assertEquals("Alexanderplatz",model.getQueryText());
 }
}
