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
import java.io.File;
import java.lang.reflect.Constructor;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DF-04: a mapped departure-now plan whose first leg is a WALK must not be retired by the clock advancing past
 * Request.earliest (which is all a walk's departure is). Real ViewModel, worker, repository, SQLite and planner;
 * only the native map candidate/provider are mocks (same harness as JourneyWalkingDisruptionTest), but here the
 * provider ANSWERS an OK path for exactly one coordinate pair, so a real walk leg is planned. Controls: a real first
 * train (no walk) keeps its exact boundary, and the feasible-start deadline of walk-then-train is exact (no grace).
 * Not native routing, not a device. Fixture pack: synthetic, Europe/Berlin, 2026-10-21 trip T1 (CEST = UTC+2):
 * P1a dep 08:00:30Z, P1b dep 08:05:30Z, H1a 08:10:30Z, S1 arr 08:20:00Z. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class JourneyWalkingDepartureTest {
  @Rule public TemporaryFolder temp=new TemporaryFolder();
  static final class Time extends Clock {
    volatile Instant now=Instant.parse("2026-10-21T08:00:00Z");
    public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return now;}
  }
  private final Time clock=new Time();
  private JourneyViewModel model;private ExecutorService worker;private JourneyUiFixture fixture;private MappedWalking provider;
  /** Native pair the provider answers OK for: [fromLat,fromLon,toLat,toLon]; null = every query is NO_PATH. */
  private volatile double[] okPair;private volatile int okSeconds=1800;private volatile double okMeters=2400;

  @Before public void setup()throws Exception {
    fixture=new JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),clock);
    RegisteredWalkingMaps.Candidate candidate=mock(RegisteredWalkingMaps.Candidate.class);provider=mock(MappedWalking.class);
    var id=MappedWalking.class.getDeclaredField("mapSnapshotId");id.setAccessible(true);id.set(provider,"a".repeat(64));
    when(provider.isCurrent()).thenReturn(true);
    when(provider.query(anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyLong(),anyString())).thenAnswer(call->{
      double[] pair=okPair;
      if(pair!=null&&near(call.getArgument(0),pair[0])&&near(call.getArgument(1),pair[1])&&near(call.getArgument(2),pair[2])&&near(call.getArgument(3),pair[3]))
        return okResult(pair,okSeconds,okMeters);
      return failure(MappedWalking.Status.NO_PATH);
    });
    when(candidate.open(any(File.class),any(),anyLong())).thenReturn(provider);
    var ctor=RegisteredWalkingMaps.Inventory.class.getDeclaredConstructor(boolean.class,RegisteredWalkingMaps.Candidate[].class);ctor.setAccessible(true);
    RegisteredWalkingMaps.Inventory inventory=spy(ctor.newInstance(true,new RegisteredWalkingMaps.Candidate[]{candidate}));
    doReturn(Collections.singletonList(candidate)).when(inventory).candidates(anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyLong());
    worker=Executors.newSingleThreadExecutor();model=new JourneyViewModel(RuntimeEnvironment.getApplication(),fixture.repository,worker,clock,()->inventory);
  }
  @After public void close()throws Exception {if(model!=null)model.onCleared();if(worker!=null)assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();}
  private static boolean near(double a,double b){return Math.abs(a-b)<1e-9;}
  private static MappedWalking.Result failure(MappedWalking.Status status)throws Exception {
    var m=MappedWalking.Result.class.getDeclaredMethod("failure",MappedWalking.Status.class);m.setAccessible(true);return (MappedWalking.Result)m.invoke(null,status);
  }
  private static MappedWalking.Result okResult(double[] pair,int seconds,double meters)throws Exception {
    Constructor<MappedWalking.Result> c=MappedWalking.Result.class.getDeclaredConstructor(int.class,String.class,String.class,double[].class,int.class,double.class,boolean.class);c.setAccessible(true);
    return c.newInstance(MappedWalking.Status.OK.ordinal(),"a".repeat(64),"b".repeat(64),new double[]{pair[0],pair[1],pair[2],pair[3]},seconds,meters,false);
  }
  private void await(BooleanSupplier ready)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();if(ready.getAsBoolean())return;Thread.sleep(5);}fail("bounded host readiness");}
  private void choose(boolean origin,TripStop row)throws Exception {
    model.search(origin,row.stopName);await(()->!model.getState().getValue().busy);var c=model.getState().getValue().candidates;
    model.select(c,row.stopId,origin);await(()->!model.getState().getValue().busy);
    assertEquals(row.stopId,(origin?model.getState().getValue().from:model.getState().getValue().to).stop.id);
  }
  /** Origin = first stop of T1 (P1a), destination = last (S1), after any fixture change. */
  private JourneyPlanner.Result planMapped()throws Exception {
    model.refresh();await(()->!model.getState().getValue().busy);
    List<TripStop> rows=fixture.repository.tripStops("T1",LocalDate.parse("2026-10-21"));
    choose(true,rows.get(0));choose(false,rows.get(rows.size()-1));
    assertTrue(model.canPlanWithWalking(model.getState().getValue()));
    model.planWithWalking();await(()->!model.getState().getValue().busy);
    JourneyPlanner.Result result=model.getState().getValue().result;
    assertNotNull("the mapped plan is published",result);assertTrue(result.found());assertEquals(R.string.journey_found,model.getState().getValue().message);return result;
  }
  private void tick(Instant at){clock.now=at;model.checkVisible();Shadows.shadowOf(Looper.getMainLooper()).idle();}

  /** v61 RED: first leg is the direct walk, whose departure IS Request.earliest, so any later tick retires it
   * (observed on device: Working, then "planned departure time has passed" within 52 s). */
  @Test public void walkOnlyPlanHasNoScheduledDepartureAndIsNotRetiredByTheClock()throws Exception {
    clock.now=Instant.parse("2026-10-21T09:00:00Z"); // after the last fixture train and beyond the 180 min horizon of the next one
    okPair=new double[]{52.52,13.405,52.475,13.365}; // P1a -> S1, the DIRECT query only; every access/egress query is NO_PATH
    JourneyPlanner.Result result=planMapped();
    assertEquals(1,result.legs.size());assertTrue("walk-only: no scheduled leg exists",result.legs.get(0).isWalking());
    assertEquals("a walk's departure is only the request time",clock.now,result.legs.get(0).departure);
    assertFalse(model.departed(result));
    final Instant planned=clock.now;
    for(long seconds:new long[]{1,52,300,3600}){
      tick(planned.plusSeconds(seconds));
      assertSame("no schedule, nothing departed (+"+seconds+"s)",result,model.getState().getValue().result);
      assertEquals(R.string.journey_found,model.getState().getValue().message);assertFalse(model.getState().getValue().busy);
    }
    // Currency gates are untouched: an expired pack still outranks a retained walk-only plan.
    tick(Instant.parse("2027-01-03T00:00:00Z"));
    assertNull(model.getState().getValue().result);assertEquals(R.string.journey_changed,model.getState().getValue().message);
  }
  /** v61 RED: the access walk leaves at Request.earliest (08:00:00Z) but the train leaves P1b at 08:05:30Z. The plan is
   * feasible until boarding minus the 180 s walk = 08:02:30Z, exact: still current AT it, departed one second after.
   * Controls the other way: nothing is waived past the deadline. */
  @Test public void accessWalkBeforeAFixedTrainIsFeasibleUntilBoardingMinusWalkingTime()throws Exception {
    fixture.useAuthoredAccessCoordinates();
    okPair=new double[]{0,0,0,0.002};okSeconds=180;okMeters=222; // P1a -> P1b only
    JourneyPlanner.Result result=planMapped();
    assertEquals(2,result.legs.size());assertTrue(result.legs.get(0).isWalking());assertFalse(result.legs.get(1).isWalking());
    assertEquals(Instant.parse("2026-10-21T08:00:00Z"),result.legs.get(0).departure);
    assertEquals(Instant.parse("2026-10-21T08:05:30Z"),result.legs.get(1).departure);
    assertEquals(Instant.parse("2026-10-21T08:20:00Z"),result.arrival);
    tick(Instant.parse("2026-10-21T08:00:01Z")); // walk leg's own departure has passed; the train has not
    assertSame("v61 retires here", result,model.getState().getValue().result);
    tick(Instant.parse("2026-10-21T08:02:30Z"));
    assertSame("exact feasible-start boundary is still current",result,model.getState().getValue().result);
    tick(Instant.parse("2026-10-21T08:02:31Z"));
    JourneyViewModel.State s=model.getState().getValue();
    assertNull("one second later the train can no longer be caught",s.result);assertEquals(R.string.journey_departed,s.message);
    assertNotNull(s.from);assertNotNull(s.to);assertNotNull(s.info);assertFalse(s.busy);
  }
  /** Control, green on v61 and after: a real first train with no walk keeps the exact existing boundary (no walk is
   * subtracted, no grace is added). */
  @Test public void firstTrainWithoutAWalkKeepsItsExactBoundary()throws Exception {
    okPair=null; // every native query is NO_PATH: scheduled transit only
    JourneyPlanner.Result result=planMapped();
    assertFalse(result.legs.get(0).isWalking());Instant first=result.legs.get(0).departure;
    assertEquals(Instant.parse("2026-10-21T08:00:30Z"),first);
    tick(first);assertSame(result,model.getState().getValue().result);
    tick(first.plusSeconds(1));
    assertNull(model.getState().getValue().result);assertEquals(R.string.journey_departed,model.getState().getValue().message);
  }
}
