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
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Actual UI worker -> repository -> native-host SQLite composition. No real map routing/device. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class JourneyViewModelTest {
  @Rule public TemporaryFolder temp=new TemporaryFolder();
  private JourneyViewModel model;private JourneyUiFixture fixture;private ExecutorService worker;
  static final class Time extends Clock {
    Instant now=Instant.parse("2026-10-21T08:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}
    public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
  }
  private final Time clock=new Time();
  @Before public void setup()throws Exception {
    fixture=new JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),clock);
    worker=Executors.newSingleThreadExecutor(r->new Thread(r,"dmaps-journey-test-worker"));
    model=new JourneyViewModel(RuntimeEnvironment.getApplication(),fixture.repository,worker,clock);
    model.refresh();await(()->!model.getState().getValue().busy);assertEquals(R.string.journey_choose,model.getState().getValue().message);
  }
  @After public void close()throws Exception {if(model!=null)model.onCleared();if(worker!=null)assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
  private void await(BooleanSupplier ready)throws Exception {
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();if(ready.getAsBoolean())return;Thread.sleep(5);}fail("bounded host readiness exceeded, not a speed acceptance");
  }
  private void select(boolean from,String source)throws Exception {
    model.search(from,"Alexanderplatz");await(()->!model.getState().getValue().busy);
    JourneyPlanner.Candidates c=model.getState().getValue().candidates;assertNotNull(c);Stop chosen=null;
    for(Stop s:c.stops)if(s.sourceId.equals(source))chosen=s;assertNotNull(source,chosen);
    model.select(c,chosen.id,from);await(()->!model.getState().getValue().busy);
  }
  private void endpoints()throws Exception {
    java.util.List<TripStop> rows=fixture.repository.tripStops("T1",LocalDate.parse("2026-10-21"));
    select(true,rows.get(0).stopSourceId);
    // Use the actual destination name from the producer, not a fabricated search hit.
    model.search(false,rows.get(rows.size()-1).stopName);await(()->!model.getState().getValue().busy);
    JourneyPlanner.Candidates c=model.getState().getValue().candidates;Stop destination=null;
    for(Stop s:c.stops)if(s.id==rows.get(rows.size()-1).stopId)destination=s;assertNotNull(destination);
    model.select(c,destination.id,false);await(()->!model.getState().getValue().busy);
    assertNotNull(model.getState().getValue().from);assertNotNull(model.getState().getValue().to);
  }
  private void platform(JourneyPlanner.Selection group,long id,boolean origin)throws Exception {
    java.lang.reflect.Method action=null;
    try{action=JourneyViewModel.class.getMethod("selectPlatform",JourneyPlanner.Selection.class,long.class,boolean.class);}catch(NoSuchMethodException missing){}
    assertNotNull("A station choice must expose its actual child platforms beyond the capped name search",action);
    action.invoke(model,group,id,origin);
  }
  @Test public void selectedStationCanNarrowOnlyToItsActualPlatformAndRejectStaleChoice()throws Exception {
    select(true,"P1");JourneyPlanner.Selection group=model.getState().getValue().from.selection;
    assertTrue(group.platforms.size()>1);Stop child=group.platforms.get(0);
    platform(group,Long.MAX_VALUE,true);assertSame(group,model.getState().getValue().from.selection);assertFalse(model.getState().getValue().busy);
    platform(group,child.id,true);await(()->!model.getState().getValue().busy);
    assertEquals(child.id,model.getState().getValue().from.stop.id);assertEquals(1,model.getState().getValue().from.selection.platforms.size());
    JourneyPlanner.Selection narrowed=model.getState().getValue().from.selection;
    platform(group,group.platforms.get(1).id,true);assertSame("obsolete station control cannot overwrite the newer endpoint",narrowed,model.getState().getValue().from.selection);
  }
  @Test public void stationPlatformChoiceAfterPackReplacementMustNotReinterpretOldId()throws Exception {
    select(true,"P1");JourneyPlanner.Selection group=model.getState().getValue().from.selection;fixture.replaceLabels();
    platform(group,group.platforms.get(0).id,true);await(()->!model.getState().getValue().busy);
    assertNull(model.getState().getValue().from);assertNull(model.getState().getValue().info);assertEquals(R.string.journey_changed,model.getState().getValue().message);
  }
  @Test public void departedPlanRetiresAtItsFirstDepartureButKeepsEndpointsForReplanning()throws Exception {
    endpoints();model.plan();await(()->!model.getState().getValue().busy);
    JourneyPlanner.Result result=model.getState().getValue().result;assertNotNull(result);assertTrue(result.found());
    Instant first=result.legs.get(0).departure;assertNotNull(first);
    clock.now=first;model.checkVisible();Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertSame("at the departure instant the plan is still current",result,model.getState().getValue().result);
    clock.now=first.plusSeconds(1);model.checkVisible();
    var s=model.getState().getValue();assertNull(s.result);assertEquals(R.string.journey_departed,s.message);
    assertNotNull(s.from);assertNotNull(s.to);assertNotNull(s.info);assertFalse(s.busy);
    model.plan();await(()->!model.getState().getValue().busy);
    JourneyPlanner.Result again=model.getState().getValue().result;assertNotNull(again);
    if(again.found())assertFalse("replan never offers an already departed itinerary",again.legs.get(0).departure.isBefore(clock.instant()));
  }
  @Test public void packExpiryStillOutranksDepartedPlan()throws Exception {
    endpoints();model.plan();await(()->!model.getState().getValue().busy);assertNotNull(model.getState().getValue().result);
    clock.now=Instant.parse("2027-01-03T00:00:00Z");model.checkVisible();
    assertNull(model.getState().getValue().result);assertNull(model.getState().getValue().from);assertEquals(R.string.journey_changed,model.getState().getValue().message);
  }
  @Test public void explicitEndpointsPlanActualScheduledLegAndEditingRetiresIt()throws Exception {
    endpoints();model.plan();await(()->!model.getState().getValue().busy);
    JourneyPlanner.Result result=model.getState().getValue().result;assertNotNull(result);assertTrue(result.found());assertEquals(1,result.legs.size());assertFalse(result.legs.get(0).isWalking());assertFalse(result.packInfo.authenticated);
    assertEquals(Instant.parse("2026-10-21T08:20:00Z"),result.arrival);
    model.invalidate(false);assertNull(model.getState().getValue().result);assertNull(model.getState().getValue().to);assertNotNull(model.getState().getValue().from);
  }
  @Test public void removalAfterSearchCannotReinterpretHitAndCannotRetainReadyData()throws Exception {
    model.search(true,"Alexanderplatz");await(()->!model.getState().getValue().busy);
    JourneyPlanner.Candidates old=model.getState().getValue().candidates;assertFalse(old.stops.isEmpty());fixture.repository.removePack();
    model.select(old,old.stops.get(0).id,true);await(()->!model.getState().getValue().busy);
    assertEquals(R.string.journey_changed,model.getState().getValue().message);assertNull(model.getState().getValue().from);assertNull(model.getState().getValue().info);
  }
  @Test public void visibleExpiryRetiresResultWithoutWaitingForAnotherWorkerRevision()throws Exception {
    endpoints();model.plan();await(()->!model.getState().getValue().busy);assertNotNull(model.getState().getValue().result);
    clock.now=Instant.parse("2027-01-03T00:00:00Z");assertTrue(model.getState().getValue().info.isExpired(clock.instant()));model.checkVisible();
    assertNull(model.getState().getValue().result);assertNull(model.getState().getValue().info);assertEquals(R.string.journey_changed,model.getState().getValue().message);
  }
  @Test public void pauseResumeRevalidatesSelectionsButDoesNotResurrectOldItinerary()throws Exception {
    endpoints();model.plan();await(()->!model.getState().getValue().busy);assertNotNull(model.getState().getValue().result);
    model.suspend();assertNull(model.getState().getValue().result);assertNull(model.getState().getValue().info);
    model.refresh();await(()->!model.getState().getValue().busy);assertNotNull(model.getState().getValue().from);assertNull(model.getState().getValue().result);
    fixture.repository.removePack();model.checkVisible();await(()->model.getState().getValue().info==null);assertNull(model.getState().getValue().from);
  }
  @Test public void expiryRetiresDisplayEvenWhenAuthorityRefreshIsAlreadyQueued()throws Exception {
    endpoints();model.plan();await(()->!model.getState().getValue().busy);assertNotNull(model.getState().getValue().result);
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    worker.submit(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(entered.await(10,TimeUnit.SECONDS));
    try{model.checkVisible();clock.now=Instant.parse("2027-01-03T00:00:00Z");model.checkVisible();assertNull(model.getState().getValue().result);assertNull(model.getState().getValue().info);}finally{release.countDown();}
  }
  @Test public void completedWorkerCannotPublishAfterEditBeforeMainCallback()throws Exception {
    endpoints();model.plan();worker.submit(()->{}).get(10,TimeUnit.SECONDS);assertTrue(model.getState().getValue().busy);
    model.invalidate(false);Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(model.getState().getValue().result);assertNull(model.getState().getValue().to);
  }
  private void replaceBeforeNextSearch(String query)throws Exception {
    // Only the phase boundary is controlled: every repository operation delegates to real SQLite.
    TimetableRepository phased=org.mockito.Mockito.mock(TimetableRepository.class,
        org.mockito.AdditionalAnswers.delegatesTo(fixture.repository));
    org.mockito.Mockito.doAnswer(invocation->{fixture.replaceLabels();return fixture.repository.searchJourneyStops(query,(Integer)invocation.getArgument(1));})
        .when(phased).searchJourneyStops(org.mockito.ArgumentMatchers.eq(query),org.mockito.ArgumentMatchers.anyInt());
    java.lang.reflect.Field f=JourneyViewModel.class.getDeclaredField("repository");f.setAccessible(true);f.set(model,phased);
  }
  @Test public void refreshCannotPublishPackBWithRetainedSelectionsFromPackA()throws Exception {
    endpoints();String old=model.getState().getValue().from.selection.packEnvelopeSha256;
    replaceBeforeNextSearch("");model.refresh();await(()->!model.getState().getValue().busy);
    assertNotNull(model.getState().getValue().info);
    assertFalse(fixture.repository.isJourneyCurrent(old));
    assertNull("completed B snapshot must retire A origin",model.getState().getValue().from);
    assertNull("completed B snapshot must retire A destination",model.getState().getValue().to);
  }
  @Test public void searchForPackBCannotKeepOppositeEndpointFromPackA()throws Exception {
    endpoints();String old=model.getState().getValue().to.selection.packEnvelopeSha256;
    replaceBeforeNextSearch("Alexanderplatz");model.search(true,"Alexanderplatz");await(()->!model.getState().getValue().busy);
    assertNotNull(model.getState().getValue().candidates);assertNotEquals(old,model.getState().getValue().candidates.packEnvelopeSha256);
    assertNull("B search publication must retire A destination",model.getState().getValue().to);
  }
  @Test public void typingDuringResumeLoadMustNotStrandSearchWithoutPackInfo()throws Exception {
    model.suspend();assertNull(model.getState().getValue().info);
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    worker.submit(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(entered.await(10,TimeUnit.SECONDS));
    try{
      model.refresh();assertTrue(model.getState().getValue().busy);model.invalidate(true);
      release.countDown();worker.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertNotNull("typing must not cancel the only current pack-load operation",model.getState().getValue().info);
      assertFalse(model.getState().getValue().busy);assertTrue(model.displayable(model.getState().getValue()));
      model.search(true,"Alexanderplatz");await(()->!model.getState().getValue().busy);assertNotNull(model.getState().getValue().candidates);
    }finally{release.countDown();}
  }
  @Test public void supersededQueuedSearchCannotPublishAfterInputEdit()throws Exception {
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    worker.submit(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(entered.await(10,TimeUnit.SECONDS));
    model.search(true,"Alexanderplatz");assertTrue(model.getState().getValue().busy);model.invalidate(true);release.countDown();
    worker.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertNull(model.getState().getValue().candidates);assertNull(model.getState().getValue().result);assertFalse(model.getState().getValue().busy);
  }
}
