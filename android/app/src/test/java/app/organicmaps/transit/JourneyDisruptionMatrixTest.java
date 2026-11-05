package app.organicmaps.transit;

import android.app.Application;
import android.os.Bundle;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.*;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** WI-07 S6 host cells for the Journey screen (J0..J8 per S7). Real JourneyActivity + real JourneyViewModel + worker +
 * repository + native-host SQLite via JourneyUiFixture; fixed/mutable clock. NOT physical Android process death, IME,
 * GPU or native map walking (those need the phone pass). "Process death" here means: a NEW ViewModel and a NEW Activity
 * created from the old Activity's saved-instance Bundle, i.e. nothing but the Bundle survives. Walking (J5) cells are in
 * JourneyWalkingDisruptionTest. Every test cites its (state, disruption) cells in disruption-matrix.json. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class JourneyDisruptionMatrixTest {
  @Rule public TemporaryFolder temp=new TemporaryFolder();
  static final class Time extends Clock {
    volatile Instant now=Instant.parse("2026-10-21T08:00:00Z");
    public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return now;}
  }
  /** Same injection seam as JourneyScreenTest.FixtureActivity, but static so a re-created Activity gets the model of its "process". */
  public static class HostActivity extends JourneyActivity {
    static volatile JourneyViewModel shared;
    @Override JourneyViewModel createModel(){return shared;}
  }
  private final Time clock=new Time();
  private JourneyUiFixture fixture;
  private final List<JourneyViewModel> models=new ArrayList<>();
  private final List<ExecutorService> workers=new ArrayList<>();
  private ActivityController<HostActivity> controller;

  @Before public void setup()throws Exception {
    fixture=new JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),clock);
  }
  @After public void close()throws Exception {
    if(controller!=null)controller.pause().stop().destroy();
    for(JourneyViewModel m:models)m.onCleared();
    for(ExecutorService w:workers)assertTrue(w.awaitTermination(10,TimeUnit.SECONDS));
    HostActivity.shared=null;Shadows.shadowOf(Looper.getMainLooper()).idle();
  }
  private ExecutorService lastWorker;
  private JourneyViewModel newModel(){
    lastWorker=Executors.newSingleThreadExecutor(r->new Thread(r,"dmaps-journey-matrix-worker"));workers.add(lastWorker);
    JourneyViewModel m=new JourneyViewModel(RuntimeEnvironment.getApplication(),fixture.repository,lastWorker,clock);models.add(m);return m;
  }
  private void await(BooleanSupplier ready)throws Exception {
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
    while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();if(ready.getAsBoolean())return;Thread.sleep(5);}
    fail("bounded host readiness exceeded, not a latency proof");
  }
  private void settle(JourneyViewModel m)throws Exception {
    await(()->!m.getState().getValue().busy);
    if(controller!=null&&HostActivity.shared==m){HostActivity a=controller.get();android.view.View root=a.findViewById(android.R.id.content);
      SilentAccessibilityAudit.check(root,"Journey/"+ScreenStateCatalogue.journey(m.getState().getValue()));
      SilentAccessibilityAudit.fieldsInOrder(root,a.getString(R.string.journey_origin),a.getString(R.string.journey_destination));}
  }
  private static String id(JourneyViewModel m){return ScreenStateCatalogue.journey(m.getState().getValue());}
  private HostActivity start(JourneyViewModel m,Bundle saved)throws Exception {
    HostActivity.shared=m;controller=Robolectric.buildActivity(HostActivity.class);HostActivity a=controller.get();a.setTheme(R.style.MwmTheme);
    controller.create(saved).start().resume().visible();settle(m);return a;
  }
  /** The unit of "process death": only the Bundle survives; the old model dies with its worker. */
  private Bundle dieKeepingBundle(JourneyViewModel old)throws Exception {
    // The old model is NOT cleared here: it shares the fixture repository, whose close() would end the "next process".
    // It is left idle (its Activity and refresh loop are gone) and released with all models in close().
    Bundle saved=new Bundle();controller.saveInstanceState(saved).pause().stop().destroy();controller=null;return saved;
  }
  private Object field(Object object,String name)throws Exception {
    java.lang.reflect.Field f=JourneyActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(object);
  }
  private List<TripStop> rows()throws Exception {return fixture.repository.tripStops("T1",LocalDate.parse("2026-10-21"));}
  private void choose(JourneyViewModel m,boolean origin,TripStop row)throws Exception {
    m.search(origin,row.stopName);settle(m);JourneyPlanner.Candidates c=m.getState().getValue().candidates;assertNotNull(c);
    m.select(c,row.stopId,origin);settle(m);
    assertEquals(row.stopId,(origin?m.getState().getValue().from:m.getState().getValue().to).stop.id);
  }
  private void bothPlatforms(JourneyViewModel m)throws Exception {List<TripStop> r=rows();choose(m,true,r.get(0));choose(m,false,r.get(r.size()-1));}
  /** Rider path through the real controls (as JourneyQueryEditTest): type, tap Search, tap the hit. */
  private void bothPlatformsViaControls(HostActivity a,JourneyViewModel m)throws Exception {
    List<TripStop> r=rows();long[] targets={r.get(0).stopId,r.get(r.size()-1).stopId};String[] queries={r.get(0).stopName,r.get(r.size()-1).stopName};
    for(int which=0;which<2;which++){
      ((EditText)field(a,which==0?"originQuery":"destinationQuery")).setText(queries[which]);
      ((Button)field(a,which==0?"originSearch":"destinationSearch")).performClick();settle(m);
      JourneyPlanner.Candidates hits=m.getState().getValue().candidates;assertNotNull(hits);
      int index=-1;for(int i=0;i<hits.stops.size();i++)if(hits.stops.get(i).id==targets[which])index=i;assertTrue(index>=0);
      ((LinearLayout)field(a,"candidates")).getChildAt(index).performClick();settle(m);
    }
    assertNotNull(m.getState().getValue().from);assertNotNull(m.getState().getValue().to);
  }
  private void planViaButton(HostActivity a,JourneyViewModel m)throws Exception {
    assertTrue(((Button)field(a,"plan")).isEnabled());((Button)field(a,"plan")).performClick();settle(m);
    assertNotNull(m.getState().getValue().result);assertEquals("J4",id(m));
  }
  private static void recommitSameWord(EditText field)throws Exception {
    field.requestFocus();String text=field.getText().toString();int space=text.lastIndexOf(' ');int start=space<0?0:space+1;
    InputConnection ic=field.onCreateInputConnection(new EditorInfo());assertNotNull(ic);
    assertTrue(ic.setComposingRegion(start,text.length()));assertTrue(ic.setComposingText(text.substring(start),1));assertTrue(ic.finishComposingText());
    Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("the field content did not change",text,field.getText().toString());
  }
  private CountDownLatch[] blockWorker(ExecutorService w)throws Exception {
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    w.submit(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
    assertTrue(entered.await(10,TimeUnit.SECONDS));return new CountDownLatch[]{release};
  }

  // ---- S7: the catalogue names follow the real model through every state the Journey model can be in ----
  @Test public void catalogueIdsFollowTheViewModelThroughEveryReachableState()throws Exception {
    JourneyViewModel m=newModel();m.refresh();settle(m);assertEquals("J0",id(m));
    m.search(true,"Alexanderplatz");settle(m);assertEquals("JC",id(m));
    Stop station=null;for(Stop s:m.getState().getValue().candidates.stops)if(s.sourceId.equals("P1"))station=s;assertNotNull(station);
    m.select(m.getState().getValue().candidates,station.id,true);settle(m);assertEquals("J1",id(m));
    m.invalidate(true);assertEquals("J0",id(m));
    List<TripStop> r=rows();TripStop last=r.get(r.size()-1);
    choose(m,false,last);assertEquals("J1D",id(m));
    m.invalidate(false);assertEquals("J0",id(m));
    m.search(true,"Alexanderplatz");settle(m);station=null;for(Stop s:m.getState().getValue().candidates.stops)if(s.sourceId.equals("P1"))station=s;
    m.select(m.getState().getValue().candidates,station.id,true);settle(m);choose(m,false,last);
    assertEquals("a station group is chosen: platforms not yet narrowed","J2",id(m));
    JourneyPlanner.Selection group=m.getState().getValue().from.selection;assertTrue(group.platforms.size()>1);
    m.selectPlatform(group,group.platforms.get(0).id,true);settle(m);assertEquals("J3",id(m));
    choose(m,true,r.get(0)); // the trip's own first platform, so the plan below is the known-good pair
    assertEquals("J3",id(m));
    m.plan();assertEquals("busy overlay while the worker runs","JB",id(m));settle(m);assertEquals("J4",id(m));
    JourneyPlanner.Result planned=m.getState().getValue().result;assertTrue(planned.found());assertFalse(planned.legs.isEmpty());
    clock.now=planned.legs.get(0).departure.plusSeconds(1);m.checkVisible();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("JD",id(m));
    m.suspend();assertEquals("JS",id(m));
    clock.now=Instant.parse("2026-10-21T08:00:00Z");m.refresh();settle(m);assertEquals("endpoints survive a pause",  "J3",id(m));
    fixture.replaceLabels();m.checkVisible();await(()->m.getState().getValue().info==null);assertEquals("JX",id(m));
    m.refresh();settle(m);assertEquals("J0",id(m));
    fixture.repository.removePack();m.refresh();settle(m);assertEquals("JN",id(m));
  }

  // ---- recreate (configuration change; model retained) ----
  @Test public void recreateKeepsBothPlatformsAndRetiresTheResultWithoutResurrectingIt()throws Exception {
    JourneyViewModel m=newModel();HostActivity a=start(m,null);bothPlatformsViaControls(a,m);planViaButton(a,m);
    long fromId=m.getState().getValue().from.stop.id,toId=m.getState().getValue().to.stop.id;
    Bundle saved=new Bundle();controller.saveInstanceState(saved).pause().stop().destroy();
    assertEquals("pause retires the displayed plan at once (suspended: no pack info shown)","JS",id(m));assertNull(m.getState().getValue().result);
    HostActivity b=start(m,saved);
    assertEquals("J3",id(m));assertEquals(fromId,m.getState().getValue().from.stop.id);assertEquals(toId,m.getState().getValue().to.stop.id);
    assertNull(m.getState().getValue().result);assertEquals(0,((LinearLayout)field(b,"legs")).getChildCount());
    assertTrue("the rider can replan at once",((Button)field(b,"plan")).isEnabled());
    assertTrue("origin label shows the kept platform",((TextView)field(b,"originLabel")).getText().toString().contains(rows().get(0).stopName));
  }

  // ---- process death + restore: nothing but the Bundle survives; the screen is ephemeral by design ----
  @Test public void processDeathRestoresNoSelectionNoResultAndNoTypedQueryFromJ1J3AndJ4()throws Exception {
    for(String reached:new String[]{"J1","J3","J4"}){
      JourneyViewModel m=newModel();HostActivity a=start(m,null);
      if(reached.equals("J1")){
        List<TripStop> r=rows();((EditText)field(a,"originQuery")).setText(r.get(0).stopName);((Button)field(a,"originSearch")).performClick();settle(m);
        m.select(m.getState().getValue().candidates,r.get(0).stopId,true);settle(m);
      }else{bothPlatformsViaControls(a,m);if(reached.equals("J4"))planViaButton(a,m);}
      assertEquals(reached.equals("J1")?"J1":reached,id(m));
      Bundle saved=dieKeepingBundle(m);
      JourneyViewModel fresh=newModel();HostActivity b=start(fresh,saved);
      String where="after process death from "+reached;
      assertEquals(where,"J0",id(fresh));assertNull(where,fresh.getState().getValue().from);assertNull(where,fresh.getState().getValue().to);
      assertNull(where,fresh.getState().getValue().result);assertNull(where,fresh.getState().getValue().candidates);
      assertEquals(where,"",((EditText)field(b,"originQuery")).getText().toString());assertEquals(where,"",((EditText)field(b,"destinationQuery")).getText().toString());
      assertFalse(where,((Button)field(b,"plan")).isEnabled());assertEquals(where,0,((LinearLayout)field(b,"legs")).getChildCount());
      controller.pause().stop().destroy();controller=null;
    }
  }

  // ---- app switch + IME reconnect: the exact 10-28 defect sequence (switch away, return, keyboard re-commits) ----
  @Test public void appSwitchThenKeyboardRecommitKeepsBothPlatformsInEitherField()throws Exception {
    JourneyViewModel m=newModel();HostActivity a=start(m,null);bothPlatformsViaControls(a,m);
    controller.pause().stop();controller.start().resume();settle(m);
    assertEquals("J3",id(m));
    recommitSameWord((EditText)field(a,"destinationQuery"));
    assertNotNull("destination platform survives switch + recommit",m.getState().getValue().to);
    recommitSameWord((EditText)field(a,"originQuery"));
    assertNotNull("origin platform survives switch + recommit",m.getState().getValue().from);
    assertTrue(((Button)field(a,"plan")).isEnabled());assertEquals("J3",id(m));
  }
  @Test public void keyboardRecommitKeepsTheShownResultAndTheShownCandidateList()throws Exception {
    JourneyViewModel m=newModel();HostActivity a=start(m,null);bothPlatformsViaControls(a,m);planViaButton(a,m);
    JourneyPlanner.Result shown=m.getState().getValue().result;int legRows=((LinearLayout)field(a,"legs")).getChildCount();assertTrue(legRows>0);
    recommitSameWord((EditText)field(a,"destinationQuery"));
    assertSame("J4 stays J4: same result object",shown,m.getState().getValue().result);assertEquals(legRows,((LinearLayout)field(a,"legs")).getChildCount());
    // JC: a candidate list for the origin field survives the same re-commit.
    ((EditText)field(a,"originQuery")).setText(rows().get(0).stopName);((Button)field(a,"originSearch")).performClick();settle(m);
    JourneyPlanner.Candidates list=m.getState().getValue().candidates;assertNotNull(list);assertEquals("JC",id(m));
    int buttons=((LinearLayout)field(a,"candidates")).getChildCount();assertTrue(buttons>0);
    recommitSameWord((EditText)field(a,"originQuery"));
    assertSame(list,m.getState().getValue().candidates);assertEquals(buttons,((LinearLayout)field(a,"candidates")).getChildCount());
  }

  // ---- worker cancelled: the screen's Cancel button while work is in flight ----
  @Test public void cancelButtonWhilePlanIsInFlightLeavesAnEmptyConsistentScreen()throws Exception {
    JourneyViewModel m=newModel();ExecutorService w=lastWorker;HostActivity a=start(m,null);bothPlatformsViaControls(a,m);
    CountDownLatch[] gate=blockWorker(w);
    try{
      m.plan();assertEquals("JB",id(m));
      Button cancel=JourneyScreenTest.button(a.getWindow().getDecorView(),a.getString(android.R.string.cancel));assertNotNull(cancel);cancel.performClick();
    }finally{gate[0].countDown();}
    w.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
    JourneyViewModel.State s=m.getState().getValue();
    assertFalse(s.busy);assertNull("the cancelled plan never publishes",s.result);assertNull(s.from);assertNull(s.to);assertEquals("J0",id(m));
    assertFalse(((Button)field(a,"plan")).isEnabled());assertEquals(0,((LinearLayout)field(a,"legs")).getChildCount());
    assertTrue("the search buttons are usable again",((Button)field(a,"originSearch")).isEnabled());
  }
  @Test public void cancelButtonWhileSearchIsInFlightPublishesNoCandidates()throws Exception {
    JourneyViewModel m=newModel();ExecutorService w=lastWorker;HostActivity a=start(m,null);
    CountDownLatch[] gate=blockWorker(w);
    try{
      m.search(true,"Alexanderplatz");assertEquals("JB",id(m));
      JourneyScreenTest.button(a.getWindow().getDecorView(),a.getString(android.R.string.cancel)).performClick();
    }finally{gate[0].countDown();}
    w.submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertNull(m.getState().getValue().candidates);assertFalse(m.getState().getValue().busy);assertEquals("J0",id(m));
    assertEquals(0,((LinearLayout)field(a,"candidates")).getChildCount());
    m.search(true,"Alexanderplatz");settle(m);assertNotNull("a search after the cancel works",m.getState().getValue().candidates);
  }

  @Test public void recreateRetiresCandidateListAndUnselectedQuery()throws Exception {
    JourneyViewModel m=newModel();HostActivity a=start(m,null);
    ((EditText)field(a,"originQuery")).setText("Alexanderplatz");((Button)field(a,"originSearch")).performClick();settle(m);assertEquals("JC",id(m));
    Bundle saved=new Bundle();controller.saveInstanceState(saved).pause().stop().destroy();controller=null;
    a=start(m,saved);assertNull(m.getState().getValue().candidates);assertNull(m.getState().getValue().from);assertNull(m.getState().getValue().to);
    assertEquals("unselected query is ephemeral", "",((EditText)field(a,"originQuery")).getText().toString());
  }

  // ---- clock ----
  @Test public void departedPlanIsReplacedOnScreenByTheDepartedMessageAndPlatformsStay()throws Exception {
    JourneyViewModel m=newModel();HostActivity a=start(m,null);bothPlatformsViaControls(a,m);planViaButton(a,m);
    Instant first=m.getState().getValue().result.legs.get(0).departure;
    clock.now=first.plusSeconds(1);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
    assertEquals("JD",id(m));assertNotNull(m.getState().getValue().from);assertNotNull(m.getState().getValue().to);
    assertEquals(a.getString(R.string.journey_departed),((TextView)field(a,"status")).getText().toString());
    assertEquals("no stale leg rows remain",0,((LinearLayout)field(a,"legs")).getChildCount());
    assertTrue("replan is offered at once",((Button)field(a,"plan")).isEnabled());
  }
  @Test public void packValidityEndRetiresTheKeptPlatformsEvenAfterTheDepartedState()throws Exception {
    JourneyViewModel m=newModel();m.refresh();settle(m);bothPlatforms(m);m.plan();settle(m);JourneyPlanner.Result r=m.getState().getValue().result;assertNotNull(r);
    clock.now=r.legs.get(0).departure.plusSeconds(1);m.checkVisible();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("JD",id(m));
    clock.now=Instant.parse("2027-01-03T00:00:00Z");m.checkVisible();Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertEquals("JX",id(m));assertNull(m.getState().getValue().from);assertNull(m.getState().getValue().to);assertEquals(R.string.journey_changed,m.getState().getValue().message);
  }
  @Test public void packValidityEndRetiresPlatformsWhenNoResultWasEverPlanned()throws Exception {
    JourneyViewModel m=newModel();m.refresh();settle(m);bothPlatforms(m);assertEquals("J3",id(m));
    clock.now=Instant.parse("2027-01-03T00:00:00Z");m.checkVisible();Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertEquals("JX",id(m));assertNull(m.getState().getValue().from);assertNull(m.getState().getValue().to);
  }

  // ---- pack generation changed (a replacement import while the screen exists) ----
  @Test public void replacedPackWhileAResultIsShownRetiresResultAndPlatformsWithTheChangedMessage()throws Exception {
    JourneyViewModel m=newModel();HostActivity a=start(m,null);bothPlatformsViaControls(a,m);planViaButton(a,m);
    fixture.replaceLabels();m.checkVisible();await(()->m.getState().getValue().info==null);
    assertEquals("JX",id(m));assertNull(m.getState().getValue().from);assertNull(m.getState().getValue().to);
    assertEquals(a.getString(R.string.journey_changed),((TextView)field(a,"status")).getText().toString());
    assertEquals("old itinerary rows are gone",0,((LinearLayout)field(a,"legs")).getChildCount());assertFalse(((Button)field(a,"plan")).isEnabled());
  }
  @Test public void planOnAReplacedPackRetiresTheEndpointsInsteadOfPlanningOnTheNewPack()throws Exception {
    JourneyViewModel m=newModel();m.refresh();settle(m);bothPlatforms(m);assertEquals("J3",id(m));
    fixture.replaceLabels();m.plan();settle(m);
    assertNull("no itinerary computed against pack B with pack A endpoint identities",m.getState().getValue().result);
    assertNull(m.getState().getValue().from);assertNull(m.getState().getValue().to);assertEquals(R.string.journey_changed,m.getState().getValue().message);
  }

  // ---- J8 returned: the one-shot return token, replay and generation change ----
  private static JourneyPlanner.Walk walk(long a,long b,String envelope)throws Exception {
    java.lang.reflect.Constructor<JourneyPlanner.Walk> c=JourneyPlanner.Walk.class.getDeclaredConstructor(long.class,long.class,int.class,String.class,
        String.class,String.class,Instant.class,double.class,double[].class,boolean.class);
    c.setAccessible(true);
    return c.newInstance(a,b,120,"path",envelope,"map",Instant.parse("2026-10-21T08:05:00Z"),74.0,new double[]{52.525,13.369,52.526,13.370},true);
  }
  /** Same recipe as WalkingMapReturnLifetimeTest: a real session object over two real endpoints, test-only reflection for the Walk value. */
  private String issueReturnToken(JourneyViewModel m)throws Exception {
    bothPlatforms(m);JourneyViewModel.Endpoint from=m.getState().getValue().from,to=m.getState().getValue().to;
    WalkingMapPreviewSession s=new WalkingMapPreviewSession(walk(from.stop.id,to.stop.id,from.selection.packEnvelopeSha256),null,from,to,answer->answer.accept(true),()->{});
    assertSame(s,WalkingMapPreviewSession.claim(s.token()));
    String[] nonce={null};s.prepareReturn(n->nonce[0]=n);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ZERO);assertNotNull(nonce[0]);return nonce[0];
  }
  @Test public void returnedStateIsOneShotAndAReplayOrAnUnknownTokenAsksToReselect()throws Exception {
    JourneyViewModel issuer=newModel();issuer.refresh();settle(issuer);String nonce=issueReturnToken(issuer);
    JourneyViewModel m=newModel();m.refresh();settle(m);
    m.restoreWalkingReturn(nonce);settle(m);
    assertEquals("J8",id(m));assertNotNull(m.getState().getValue().from);assertNotNull(m.getState().getValue().to);assertNull(m.getState().getValue().result);
    assertEquals(R.string.journey_return_replan,m.getState().getValue().message);
    m.restoreWalkingReturn(nonce);settle(m);
    assertEquals("replay of the consumed token",R.string.journey_return_reselect,m.getState().getValue().message);assertNull(m.getState().getValue().from);assertNull(m.getState().getValue().to);
    m.restoreWalkingReturn("no-such-token");settle(m);
    assertEquals("a token from a dead process is just an unknown token",R.string.journey_return_reselect,m.getState().getValue().message);assertNull(m.getState().getValue().from);
  }
  @Test public void returnAfterPackReplacementAsksToReselectInsteadOfRestoringOldIdentities()throws Exception {
    JourneyViewModel issuer=newModel();issuer.refresh();settle(issuer);String nonce=issueReturnToken(issuer);
    fixture.replaceLabels();
    JourneyViewModel m=newModel();m.refresh();settle(m);
    m.restoreWalkingReturn(nonce);settle(m);
    assertEquals("JX",id(m));assertEquals(R.string.journey_return_reselect,m.getState().getValue().message);
    assertNull(m.getState().getValue().from);assertNull(m.getState().getValue().to);assertNull(m.getState().getValue().result);
  }
}
