package app.organicmaps.transit;

import android.app.Application;
import android.os.Looper;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.ObjLongConsumer;
import static org.junit.Assert.*;

/** Slice C: the journey screen reports privacy-neutral latency for its real worker round trips
 * (event name + outcome + elapsed milliseconds only), so phone planning latency can be measured
 * from the app rather than from ADB driver wall time. Real repository/SQLite composition. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class JourneyTimingTest {
  @Rule public TemporaryFolder temp=new TemporaryFolder();
  private JourneyViewModel model;private JourneyUiFixture fixture;private ExecutorService worker;
  private final List<String> events=Collections.synchronizedList(new ArrayList<>());private final List<Long> millis=Collections.synchronizedList(new ArrayList<>());
  private java.lang.reflect.Field sinkField;private Object previous;
  static final class Time extends Clock {
    Instant now=Instant.parse("2026-10-21T08:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}
    public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
  }
  private final Time clock=new Time();
  @Before public void setup()throws Exception {
    try{sinkField=JourneyViewModel.class.getDeclaredField("timing");}catch(NoSuchFieldException missing){sinkField=null;}
    if(sinkField!=null){sinkField.setAccessible(true);previous=sinkField.get(null);
      sinkField.set(null,(ObjLongConsumer<String>)(event,ms)->{events.add(event);millis.add(ms);});}
    fixture=new JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),clock);
    worker=Executors.newSingleThreadExecutor(r->new Thread(r,"dmaps-journey-timing-test-worker"));
    model=new JourneyViewModel(RuntimeEnvironment.getApplication(),fixture.repository,worker,clock);
    model.refresh();await(()->!model.getState().getValue().busy);assertEquals(R.string.journey_choose,model.getState().getValue().message);
  }
  @After public void close()throws Exception {
    if(model!=null)model.onCleared();if(worker!=null)assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));
    if(sinkField!=null)sinkField.set(null,previous);
  }
  private void await(BooleanSupplier ready)throws Exception {
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
    while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();if(ready.getAsBoolean())return;Thread.sleep(5);}
    fail("bounded host readiness exceeded, not a latency measurement");
  }
  @Test public void searchSelectAndPlanReportOnlyEventOutcomeAndElapsedMillis()throws Exception {
    assertNotNull("journey screen has no privacy-neutral timing sink for phone latency measurement",sinkField);
    assertEquals("pack load on screen entry is the first useful journey content",List.of("journey_refresh ok"),events);
    List<TripStop> rows=fixture.repository.tripStops("T1",LocalDate.parse("2026-10-21"));
    model.search(true,"Alexanderplatz");await(()->!model.getState().getValue().busy);
    JourneyPlanner.Candidates c=model.getState().getValue().candidates;Stop origin=null;
    for(Stop s:c.stops)if(s.sourceId.equals(rows.get(0).stopSourceId))origin=s;assertNotNull(origin);
    model.select(c,origin.id,true);await(()->!model.getState().getValue().busy);
    model.search(false,rows.get(rows.size()-1).stopName);await(()->!model.getState().getValue().busy);
    c=model.getState().getValue().candidates;Stop destination=null;
    for(Stop s:c.stops)if(s.id==rows.get(rows.size()-1).stopId)destination=s;assertNotNull(destination);
    model.select(c,destination.id,false);await(()->!model.getState().getValue().busy);
    model.plan();await(()->!model.getState().getValue().busy);
    assertNotNull("fixture journey must actually plan",model.getState().getValue().result);
    assertEquals(List.of("journey_refresh ok","journey_search ok","journey_select ok","journey_search ok","journey_select ok","journey_plan ok"),events);
    for(long ms:millis)assertTrue("elapsed is a non-negative duration",ms>=0&&ms<60_000);
    for(String e:events){
      assertTrue("only fixed event vocabulary leaves the model: "+e,e.matches("journey_[a-z_]+ (ok|failed)"));
      assertFalse("no stop names in timing output",e.contains("Alexanderplatz")||e.contains(rows.get(rows.size()-1).stopName));
    }
  }
  @Test public void failedWorkReportsFailedWithoutDetail()throws Exception {
    assertNotNull("journey screen has no privacy-neutral timing sink for phone latency measurement",sinkField);
    model.search(true,"Alexanderplatz");await(()->!model.getState().getValue().busy);
    JourneyPlanner.Candidates c=model.getState().getValue().candidates;Stop origin=c.stops.get(0);
    fixture.replaceLabels(); // the next worker step sees a changed pack and refuses
    events.clear();model.select(c,origin.id,true);await(()->!model.getState().getValue().busy);
    assertEquals(R.string.journey_changed,model.getState().getValue().message);
    assertEquals(List.of("journey_select failed"),events);
  }
}
