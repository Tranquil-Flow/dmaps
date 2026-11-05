package app.organicmaps.transit;

import android.app.Application;
import android.os.Looper;
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
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real ViewModel/worker/repository/SQLite. Native routing/open alone are controlled. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class JourneyWalkingLeaseTest {
  @Rule public TemporaryFolder temp=new TemporaryFolder();
  private JourneyViewModel model;private ExecutorService worker;private JourneyUiFixture fixture;
  private RegisteredWalkingMaps.Candidate candidate;private MappedWalking provider;
  private final Clock clock=Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"),ZoneOffset.UTC);
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
  private void choose(boolean origin,TripStop row)throws Exception {
    model.search(origin,row.stopName);await(()->!model.getState().getValue().busy);var c=model.getState().getValue().candidates;
    model.select(c,row.stopId,origin);await(()->!model.getState().getValue().busy);
    assertEquals(row.stopId,(origin?model.getState().getValue().from:model.getState().getValue().to).stop.id);
  }
  private void await(BooleanSupplier ready)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();if(ready.getAsBoolean())return;Thread.sleep(5);}fail("bounded host readiness");}
  private void plan()throws Exception {assertTrue(model.canPlanWithWalking(model.getState().getValue()));model.planWithWalking();await(()->!model.getState().getValue().busy);assertNotNull("scheduled result survives optional native no-path",model.getState().getValue().result);}
  private void drained()throws Exception {worker.submit(()->{}).get(10,TimeUnit.SECONDS);}
  @After public void close()throws Exception {if(model!=null)model.onCleared();if(worker!=null)assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
  @Test public void replanningUsesOneSnapshotAndOrdinaryPauseReleasesIt()throws Exception {
    plan();plan();verify(candidate,times(1)).open(any(),any(),anyLong());verify(provider,never()).close();
    model.suspend();drained();verify(provider,times(1)).close();assertNull(model.getState().getValue().result);
  }
  @Test public void firstPlanRequestsTheVerifiedDownloaderSafeHardLinkMode()throws Exception {
    plan();verify(candidate).open(any(),eq(MappedWalking.SnapshotMode.HARD_LINK),anyLong());
  }
  @Test public void changedMapRetiresOldSnapshotBeforeNextPlan()throws Exception {
    plan();when(provider.isCurrent()).thenReturn(false);model.checkVisible();await(()->model.getState().getValue().info==null);drained();
    verify(provider,times(1)).close();assertNull(model.getState().getValue().result);assertNull(model.getState().getValue().from);
  }
}
