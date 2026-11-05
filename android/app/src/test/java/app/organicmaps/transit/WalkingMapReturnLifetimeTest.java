package app.organicmaps.transit;

import android.app.Application;
import android.os.Looper;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.JourneyPlanner;
import app.organicmaps.sdk.transit.JourneyUiFixture;
import app.organicmaps.sdk.transit.TripStop;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Owner decision 2026-10-28 (option b): the street-map detour keeps the two selected platforms until the
 * process ends or the timetable/source changes; the 5-minute preview lifetime bounds only the temporary line.
 * Real untrusted import and native SQLite; a real Walk value (private constructor, test-only reflection). */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class WalkingMapReturnLifetimeTest {
  @Rule public TemporaryFolder temp=new TemporaryFolder();
  private ExecutorService worker;private JourneyViewModel model;
  private final Clock clock=Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"),ZoneOffset.UTC);
  private JourneyUiFixture fixture;
  private JourneyViewModel.Endpoint from,to;

  private void setUp()throws Exception{
    fixture=new JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),clock);
    List<TripStop> rows=fixture.repository.tripStops("T1",LocalDate.parse("2026-10-21"));
    from=endpoint(rows.get(0).stopId);to=endpoint(rows.get(rows.size()-1).stopId);
  }
  private JourneyViewModel.Endpoint endpoint(long id)throws Exception{
    JourneyPlanner.Selection s=fixture.repository.journeySelection(id);
    for(app.organicmaps.sdk.transit.Stop p:s.platforms)if(p.id==id)return new JourneyViewModel.Endpoint(s,p);
    return new JourneyViewModel.Endpoint(s,s.platforms.get(0));
  }
  private static JourneyPlanner.Walk walk(long a,long b,String envelope)throws Exception{
    java.lang.reflect.Constructor<JourneyPlanner.Walk> c=JourneyPlanner.Walk.class.getDeclaredConstructor(long.class,long.class,int.class,String.class,
        String.class,String.class,Instant.class,double.class,double[].class,boolean.class);
    c.setAccessible(true);
    return c.newInstance(a,b,120,"path",envelope,"map",Instant.parse("2026-10-21T08:05:00Z"),74.0,new double[]{52.525,13.369,52.526,13.370},true);
  }
  private WalkingMapPreviewSession open(boolean current)throws Exception{
    WalkingMapPreviewSession s=new WalkingMapPreviewSession(walk(from.stop.id,to.stop.id,from.selection.packEnvelopeSha256),null,from,to,answer->answer.accept(current),()->{});
    assertSame(s,WalkingMapPreviewSession.claim(s.token()));return s;
  }
  private static void idle(Duration d){Shadows.shadowOf(Looper.getMainLooper()).idleFor(d);}
  private void await(BooleanSupplier ready)throws Exception{
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
    while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();if(ready.getAsBoolean())return;Thread.sleep(5);}fail("host readiness exceeded");
  }
  @After public void close()throws Exception{
    if(model!=null)model.onCleared();if(worker!=null){worker.shutdown();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  @Test public void backAfterPreviewExpiryStillReturnsBothPlatformsOnce()throws Exception{
    setUp();WalkingMapPreviewSession s=open(true);
    idle(Duration.ofSeconds(301));assertFalse("the temporary line still expires",s.isOpen());
    String[] nonce={"unset"};s.prepareReturn(n->nonce[0]=n);idle(Duration.ZERO);
    assertNotNull("expired preview must not lose the selected platforms",nonce[0]);
    idle(Duration.ofHours(2));
    WalkingMapPreviewSession.ReturnSelection kept=WalkingMapPreviewSession.takeReturn(nonce[0]);
    assertNotNull("no timer on the endpoint return",kept);assertEquals(from.stop.id,kept.fromId);assertEquals(to.stop.id,kept.toId);
    assertNull("one-shot",WalkingMapPreviewSession.takeReturn(nonce[0]));
  }
  @Test public void delayedReturnStillRestoresPlatformsIntoTheJourneyModel()throws Exception{
    setUp();WalkingMapPreviewSession s=open(true);
    String[] nonce={null};s.prepareReturn(n->nonce[0]=n);idle(Duration.ZERO);assertNotNull(nonce[0]);
    idle(Duration.ofMinutes(10));
    worker=Executors.newSingleThreadExecutor();model=new JourneyViewModel(RuntimeEnvironment.getApplication(),fixture.repository,worker,clock);
    await(()->!model.getState().getValue().busy);
    model.restoreWalkingReturn(nonce[0]);await(()->!model.getState().getValue().busy);
    JourneyViewModel.State state=model.getState().getValue();
    assertNotNull("origin platform kept",state.from);assertNotNull("destination platform kept",state.to);
    assertEquals(from.stop.id,state.from.stop.id);assertEquals(to.stop.id,state.to.stop.id);
    assertNull("never resurrects an itinerary",state.result);assertEquals(R.string.journey_return_replan,state.message);
  }
  @Test public void invalidatedSourceStillRefusesTheReturn()throws Exception{
    setUp();WalkingMapPreviewSession s=open(false);
    String[] nonce={"unset"};s.prepareReturn(n->nonce[0]=n);idle(Duration.ZERO);
    assertNull("a changed timetable/map source exports nothing",nonce[0]);assertFalse(s.isOpen());
    String[] again={"unset"};s.prepareReturn(n->again[0]=n);idle(Duration.ZERO);assertNull(again[0]);
  }
}
