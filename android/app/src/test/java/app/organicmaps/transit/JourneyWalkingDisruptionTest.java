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
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** WI-07 S6 host cells for J5 (walking result). Same controlled-native harness as JourneyWalkingLeaseTest: real ViewModel,
 * worker, repository and SQLite; only the native map candidate/provider are mocks. State-level assertions ONLY (result,
 * endpoints, message, busy): this class deliberately says nothing about WHEN the walking snapshot is acquired or closed, so
 * it survives a change of the snapshot lifecycle. If JourneyWalkingLeaseTest's harness (Candidate.open signature,
 * Inventory constructor) changes, update setup() here identically. Not native routing, not a device. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class JourneyWalkingDisruptionTest {
  @Rule public TemporaryFolder temp=new TemporaryFolder();
  static final class Time extends Clock {
    volatile Instant now=Instant.parse("2026-10-21T08:00:00Z");
    public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return now;}
  }
  private final Time clock=new Time();
  private JourneyViewModel model;private ExecutorService worker;private JourneyUiFixture fixture;
  private RegisteredWalkingMaps.Candidate candidate;private MappedWalking provider;

  @Before public void setup()throws Exception {
    fixture=new JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),clock);
    candidate=mock(RegisteredWalkingMaps.Candidate.class);provider=mock(MappedWalking.class);
    var id=MappedWalking.class.getDeclaredField("mapSnapshotId");id.setAccessible(true);id.set(provider,"a".repeat(64));
    when(provider.isCurrent()).thenReturn(true);
    var failure=MappedWalking.Result.class.getDeclaredMethod("failure",MappedWalking.Status.class);failure.setAccessible(true);
    when(provider.query(anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyLong(),anyString())).thenReturn((MappedWalking.Result)failure.invoke(null,MappedWalking.Status.NO_PATH));
    when(candidate.open(any(File.class),any(),anyLong())).thenReturn(provider);
    var ctor=RegisteredWalkingMaps.Inventory.class.getDeclaredConstructor(boolean.class,RegisteredWalkingMaps.Candidate[].class);ctor.setAccessible(true);
    RegisteredWalkingMaps.Inventory inventory=spy(ctor.newInstance(true,new RegisteredWalkingMaps.Candidate[]{candidate}));
    doReturn(Collections.singletonList(candidate)).when(inventory).candidates(anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyLong());
    worker=Executors.newSingleThreadExecutor();model=new JourneyViewModel(RuntimeEnvironment.getApplication(),fixture.repository,worker,clock,()->inventory);
    model.refresh();await(()->!model.getState().getValue().busy);
    List<TripStop> rows=fixture.repository.tripStops("T1",LocalDate.parse("2026-10-21"));
    choose(true,rows.get(0));choose(false,rows.get(rows.size()-1));
  }
  @After public void close()throws Exception {if(model!=null)model.onCleared();if(worker!=null)assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();}
  private void choose(boolean origin,TripStop row)throws Exception {
    model.search(origin,row.stopName);await(()->!model.getState().getValue().busy);var c=model.getState().getValue().candidates;
    model.select(c,row.stopId,origin);await(()->!model.getState().getValue().busy);
    assertEquals(row.stopId,(origin?model.getState().getValue().from:model.getState().getValue().to).stop.id);
  }
  private void await(BooleanSupplier ready)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();if(ready.getAsBoolean())return;Thread.sleep(5);}fail("bounded host readiness");}
  private String id(){return ScreenStateCatalogue.journey(model.getState().getValue());}
  private void planWalking()throws Exception {
    assertTrue(model.canPlanWithWalking(model.getState().getValue()));model.planWithWalking();await(()->!model.getState().getValue().busy);
    assertNotNull("scheduled result survives optional native no-path",model.getState().getValue().result);assertEquals("J5",id());
  }

  @Test public void walkingResultRetiresAtItsFirstDepartureAndKeepsTheEndpoints()throws Exception {
    planWalking();JourneyPlanner.Result result=model.getState().getValue().result;assertFalse(result.legs.isEmpty());
    Instant first=result.legs.get(0).departure;assertNotNull(first);
    clock.now=first;model.checkVisible();Shadows.shadowOf(Looper.getMainLooper()).idle();assertSame("at the departure instant the plan is still current",result,model.getState().getValue().result);
    clock.now=first.plusSeconds(1);model.checkVisible();Shadows.shadowOf(Looper.getMainLooper()).idle();
    JourneyViewModel.State s=model.getState().getValue();
    assertEquals("JD",id());assertNull(s.result);assertEquals(R.string.journey_departed,s.message);assertNotNull(s.from);assertNotNull(s.to);assertFalse(s.busy);
  }
  @Test public void cancelWhileTheWalkingPlanIsQueuedLeavesNoResultAndNoEndpoints()throws Exception {
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    worker.submit(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(entered.await(10,TimeUnit.SECONDS));
    try{
      assertTrue(model.canPlanWithWalking(model.getState().getValue()));model.planWithWalking();assertEquals("JB",id());
      model.invalidate(true);model.invalidate(false); // what the screen's Cancel button does
    }finally{release.countDown();}
    worker.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
    JourneyViewModel.State s=model.getState().getValue();
    assertFalse(s.busy);assertNull("the cancelled walking plan never publishes",s.result);assertNull(s.from);assertNull(s.to);assertEquals("J0",id());
  }
  @Test public void pauseRetiresTheWalkingResultButKeepsTheEndpointsForAReplan()throws Exception {
    planWalking();
    model.suspend();assertNull(model.getState().getValue().result);assertEquals("JS",id());
    model.refresh();await(()->!model.getState().getValue().busy);
    JourneyViewModel.State s=model.getState().getValue();assertEquals("J3",id());assertNotNull(s.from);assertNotNull(s.to);assertNull("never resurrects the old walking itinerary",s.result);
  }
}
