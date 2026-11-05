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
import java.util.function.ObjLongConsumer;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** STAGED, UNRUN. Real model/worker/fixture SQLite; native map/provider are controlled mocks.
 * New phase absence on v62 is a telemetry FEATURE_ABSENT RED, not a planner-bug RED.
 * No claim about native/phone latency follows from these host contract tests. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class JourneyWalkingPhaseTimingTest {
  @Rule public TemporaryFolder temp=new TemporaryFolder();
  static final class Time extends Clock {
    public ZoneId getZone(){return ZoneOffset.UTC;}
    public Clock withZone(ZoneId zone){return this;}
    public Instant instant(){return Instant.parse("2026-10-21T08:00:00Z");}
  }
  private final Time clock=new Time();
  private final List<String> events=Collections.synchronizedList(new ArrayList<>());
  private final List<Long> durations=Collections.synchronizedList(new ArrayList<>());
  private ObjLongConsumer<String> previous;
  private JourneyViewModel model;private ExecutorService worker;private JourneyUiFixture fixture;
  private RegisteredWalkingMaps.Candidate candidate;private MappedWalking provider;
  private static MappedWalking.Result status(MappedWalking.Status status)throws Exception {
    var method=MappedWalking.Result.class.getDeclaredMethod("failure",MappedWalking.Status.class);
    method.setAccessible(true);return (MappedWalking.Result)method.invoke(null,status);
  }
  @Before public void setup()throws Exception {
    previous=JourneyViewModel.timing;
    JourneyViewModel.timing=(event,ms)->{events.add(event);durations.add(ms);};
    fixture=new JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),clock);
    candidate=mock(RegisteredWalkingMaps.Candidate.class);provider=mock(MappedWalking.class);
    var id=MappedWalking.class.getDeclaredField("mapSnapshotId");id.setAccessible(true);id.set(provider,"a".repeat(64));
    when(provider.isCurrent()).thenReturn(true);
    when(provider.query(anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyLong(),anyString())).thenReturn(status(MappedWalking.Status.NO_PATH));
    when(candidate.open(any(File.class),any(),anyLong())).thenReturn(provider);
    var ctor=RegisteredWalkingMaps.Inventory.class.getDeclaredConstructor(boolean.class,RegisteredWalkingMaps.Candidate[].class);ctor.setAccessible(true);
    RegisteredWalkingMaps.Inventory inventory=spy(ctor.newInstance(true,new RegisteredWalkingMaps.Candidate[]{candidate}));
    doReturn(Collections.singletonList(candidate)).when(inventory).candidates(anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyLong());
    worker=Executors.newSingleThreadExecutor();model=new JourneyViewModel(RuntimeEnvironment.getApplication(),fixture.repository,worker,clock,()->inventory);
    model.refresh();await(()->!model.getState().getValue().busy);
    List<TripStop> rows=fixture.repository.tripStops("T1",LocalDate.parse("2026-10-21"));
    choose(true,rows.get(0));choose(false,rows.get(rows.size()-1));events.clear();durations.clear();
  }
  @After public void close()throws Exception {
    try{if(model!=null)model.onCleared();if(worker!=null)assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
    finally{JourneyViewModel.timing=previous;Shadows.shadowOf(Looper.getMainLooper()).idle();}
  }
  private void await(BooleanSupplier ready)throws Exception {
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
    while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();if(ready.getAsBoolean())return;Thread.sleep(5);}
    fail("bounded host readiness, not a latency measurement");
  }
  private void choose(boolean origin,TripStop row)throws Exception {
    model.search(origin,row.stopName);await(()->!model.getState().getValue().busy);var c=model.getState().getValue().candidates;
    model.select(c,row.stopId,origin);await(()->!model.getState().getValue().busy);
    assertEquals(row.stopId,(origin?model.getState().getValue().from:model.getState().getValue().to).stop.id);
  }
  private void plan()throws Exception {
    assertTrue(model.canPlanWithWalking(model.getState().getValue()));model.planWithWalking();await(()->!model.getState().getValue().busy);
  }
  private void successfulResult(){
    var s=model.getState().getValue();assertNotNull(s.result);assertTrue(s.result.found());
    assertNotNull(s.from);assertNotNull(s.to);assertEquals(R.string.journey_found,s.message);
  }
  private void successfulPhaseSequence(){
    assertEquals(Arrays.asList("journey_walk_snapshot ok","journey_walk_direct ok","journey_walk_links ok","journey_walk_route ok","journey_plan_walking ok"),events);
    assertEquals(events.size(),durations.size());
    for(long ms:durations)assertTrue("non-negative elapsed milliseconds",ms>=0);
    for(String event:events)assertTrue(event.matches("journey_[a-z_]+ (ok|failed)"));
  }
  @Test public void firstAndRepeatedPlansExposeTwoFixedPhasesWithoutChangingTheResult()throws Exception {
    plan();successfulResult();successfulPhaseSequence();events.clear();durations.clear();
    plan();successfulResult();successfulPhaseSequence();
    verify(provider,atLeast(2)).query(anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyLong(),anyString());
  }
  @Test public void aNativeCancellationStillFailsAndNeverEntersTheLinksPhase()throws Exception {
    when(provider.query(anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyLong(),anyString())).thenReturn(status(MappedWalking.Status.CANCELLED));
    plan();assertNull(model.getState().getValue().result);assertEquals(R.string.journey_walk_action_unavailable,model.getState().getValue().message);
    assertEquals(Arrays.asList("journey_walk_snapshot ok","journey_walk_direct failed","journey_plan_walking failed"),events);
  }
  @Test public void directLimitStillAllowsTheScheduledResultWithItsWarning()throws Exception {
    when(provider.query(anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyLong(),anyString())).thenReturn(status(MappedWalking.Status.LIMIT_REACHED));
    plan();successfulResult();assertTrue(model.getState().getValue().result.issues.contains(JourneyPlanner.Issue.WALK_QUERY_LIMIT));
    successfulPhaseSequence();
  }
  @Test public void changedMapCurrencyStillRefusesPublication()throws Exception {
    when(provider.isCurrent()).thenReturn(false);plan();assertNull(model.getState().getValue().result);
    assertTrue(events.contains("journey_plan_walking failed"));assertFalse(events.contains("journey_plan_walking ok"));
  }
  @Test public void queuedCancellationStartsNeitherMeasuredPhase()throws Exception {
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    worker.submit(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
    assertTrue(entered.await(10,TimeUnit.SECONDS));
    try{model.planWithWalking();assertTrue(model.getState().getValue().busy);model.invalidate(true);model.invalidate(false);}
    finally{release.countDown();}
    worker.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertNull(model.getState().getValue().result);assertNull(model.getState().getValue().from);assertNull(model.getState().getValue().to);
    assertFalse(events.stream().anyMatch(x->x.startsWith("journey_walk_direct ")||x.startsWith("journey_walk_links ")));
    verify(provider,never()).query(anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyLong(),anyString());
  }
  @Test public void aThrowingTimingSinkDoesNotChangeSuccessfulPublication()throws Exception {
    JourneyViewModel.timing=(event,ms)->{throw new IllegalStateException("controlled timing sink failure");};
    plan();successfulResult();
  }
  @Test public void existingRingAcceptsNewFixedLabelsButNotAnIdentifierBearingLine()throws Exception {
    File dir=temp.newFolder();TimingRing.write(dir,"journey_walk_direct ok",13,1000);
    TimingRing.write(dir,"journey_walk_links failed",17,1001);
    TimingRing.write(dir,"journey_walk_direct Alexanderplatz ok",19,1002);
    List<String> lines=java.nio.file.Files.readAllLines(new File(dir,"timing.jsonl").toPath(),java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(Arrays.asList("{\"event\":\"journey_walk_direct\",\"outcome\":\"ok\",\"ms\":13,\"uptimeMs\":1000}",
        "{\"event\":\"journey_walk_links\",\"outcome\":\"failed\",\"ms\":17,\"uptimeMs\":1001}"),lines);
  }
}
