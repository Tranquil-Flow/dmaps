package app.organicmaps.transit;

import android.app.Application;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import app.organicmaps.R;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Actual host Activity entry point; not physical rendering or native map walking proof. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class JourneyScreenTest {
  private ActivityController<? extends TimetableActivity> entry;
  // AndroidViewModelFactory's default singleton can retain the first Robolectric Application
  // across same-sandbox tests. Compose a real per-Application factory, not a mocked ViewModel.
  public static class EntryActivity extends TimetableActivity {
    @Override public androidx.lifecycle.ViewModelProvider.Factory getDefaultViewModelProviderFactory(){
      return new androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(getApplication());
    }
  }
  private ActivityController<? extends JourneyActivity> journey;
  private JourneyViewModel injected; private java.util.concurrent.ExecutorService worker;
  @org.junit.Rule public org.junit.rules.TemporaryFolder temp=new org.junit.rules.TemporaryFolder();
  public static class FixtureActivity extends JourneyActivity {
    JourneyViewModel fixture; @Override JourneyViewModel createModel(){return fixture;}
  }
  private Object field(Object object,String name)throws Exception {
    java.lang.reflect.Field f=JourneyActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(object);
  }
  private void await(java.util.function.BooleanSupplier ready)throws Exception {
    long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
    while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();if(ready.getAsBoolean())return;Thread.sleep(5);}fail("host screen readiness exceeded, not a latency proof");
  }
  private String visible(View v){
    String s=v instanceof TextView?((TextView)v).getText().toString()+"\n":"";
    if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)s+=visible(((ViewGroup)v).getChildAt(i));return s;
  }
  static Button button(View v,String text){
    if(v instanceof Button&&((Button)v).getText().toString().equals(text))return (Button)v;
    if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=button(((ViewGroup)v).getChildAt(i),text);if(b!=null)return b;}
    return null;
  }
  @After public void close()throws Exception {
    if(entry!=null){
      java.lang.reflect.Field f=TimetableActivity.class.getDeclaredField("mModel");f.setAccessible(true);Object vm=f.get(entry.get());
      java.lang.reflect.Field executor=TimetableViewModel.class.getDeclaredField("mExecutor");executor.setAccessible(true);
      java.util.concurrent.ExecutorService owned=(java.util.concurrent.ExecutorService)executor.get(vm);
      entry.pause().stop().destroy();assertTrue("entry worker retired before sandbox cleanup",owned.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));
    }
    if(journey!=null)journey.pause().stop().destroy();
    if(injected!=null)injected.onCleared();if(worker!=null)assertTrue(worker.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }
  @Test public void actualScreenRendersPlannedLegsAndRetiresThemOnEditAndResume()throws Exception {
    java.time.Clock clock=java.time.Clock.fixed(java.time.Instant.parse("2026-10-21T08:00:00Z"),java.time.ZoneOffset.UTC);
    app.organicmaps.sdk.transit.JourneyUiFixture fixture=new app.organicmaps.sdk.transit.JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),clock);
    worker=java.util.concurrent.Executors.newSingleThreadExecutor();injected=new JourneyViewModel(RuntimeEnvironment.getApplication(),fixture.repository,worker,clock);
    ActivityController<FixtureActivity> c=Robolectric.buildActivity(FixtureActivity.class);FixtureActivity a=c.get();a.fixture=injected;a.setTheme(R.style.MwmTheme);c.create().start().resume().visible();journey=c;
    await(()->!injected.getState().getValue().busy);assertFalse(((Button)field(a,"plan")).isEnabled());
    java.util.List<app.organicmaps.sdk.transit.TripStop> rows=fixture.repository.tripStops("T1",java.time.LocalDate.parse("2026-10-21"));
    long[] targets={rows.get(0).stopId,rows.get(rows.size()-1).stopId};String[] queries={rows.get(0).stopName,rows.get(rows.size()-1).stopName};
    for(int which=0;which<2;which++){
      ((EditText)field(a,which==0?"originQuery":"destinationQuery")).setText(queries[which]);
      ((Button)field(a,which==0?"originSearch":"destinationSearch")).performClick();await(()->!injected.getState().getValue().busy);
      app.organicmaps.sdk.transit.JourneyPlanner.Candidates hits=injected.getState().getValue().candidates;assertNotNull(hits);
      int index=-1;for(int i=0;i<hits.stops.size();i++)if(hits.stops.get(i).id==targets[which])index=i;assertTrue(index>=0);
      View hit=((LinearLayout)field(a,"candidates")).getChildAt(index);
      if(which==0){Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));assertSame("periodic validation must not replace focused candidate controls",hit,((LinearLayout)field(a,"candidates")).getChildAt(index));}
      hit.performClick();await(()->!injected.getState().getValue().busy);
    }
    assertTrue(((Button)field(a,"plan")).isEnabled());((Button)field(a,"plan")).performClick();await(()->!injected.getState().getValue().busy);
    assertNotNull(injected.getState().getValue().result);String rendered=visible(a.getWindow().getDecorView());
    assertTrue(rendered.contains("Scheduled final arrival:"));assertTrue(rendered.contains("Untrusted user import"));assertTrue(rendered.contains("No walking links"));assertTrue(rendered.contains("Platform 1"));
    // UX01: the whole-trip summary is the first result row, ahead of scope/limitations, which all remain.
    LinearLayout legRows=(LinearLayout)field(a,"legs");View first=legRows.getChildAt(0);assertEquals("dmaps-journey-summary",first.getTag());
    String summary=((TextView)first).getText().toString();app.organicmaps.sdk.transit.JourneyPlanner.Result planned=injected.getState().getValue().result;
    assertTrue(summary,summary.startsWith("Leave "));assertTrue(summary,summary.contains(" · arrive "));assertTrue(summary,summary.contains(planned.legs.get(0).board.line));
    assertTrue(summary,summary.contains(planned.legs.size()==1?"no change":"change"));assertFalse("summary must not drop the dated detail",summary.contains("Scheduled final arrival"));
    assertTrue(((TextView)legRows.getChildAt(1)).getText().toString().startsWith("Search:"));
    // UX01: the new result is scrolled into view once; periodic revalidation must not keep re-scrolling.
    ScrollView scroller=(ScrollView)legRows.getParent().getParent();Shadows.shadowOf(Looper.getMainLooper()).idle();
    // Robolectric never draws: advance the real OverScroller frame by frame as a draw pass would.
    for(int frame=0;frame<120;frame++){Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));scroller.computeScroll();}
    assertTrue("form is taller than the viewport in this fixture",legRows.getTop()>scroller.getHeight());
    int max=Math.max(0,scroller.getChildAt(0).getHeight()-(scroller.getHeight()-scroller.getPaddingTop()-scroller.getPaddingBottom()));
    assertTrue("summary revealed",scroller.getScrollY()>0);assertEquals(Math.min(legRows.getTop(),max),scroller.getScrollY());
    scroller.scrollTo(0,0);Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3));
    assertEquals("same result is not re-revealed by periodic checks",0,scroller.getScrollY());
    c.pause().stop();assertEquals(0,((LinearLayout)field(a,"legs")).getChildCount());c.start().resume();await(()->!injected.getState().getValue().busy);
    assertNotNull(injected.getState().getValue().from);assertNull(injected.getState().getValue().result);
    ((EditText)field(a,"destinationQuery")).setText("different query");assertFalse(((Button)field(a,"plan")).isEnabled());assertEquals(0,((LinearLayout)field(a,"legs")).getChildCount());
  }
  @Test public void walkingSearchMechanicsAreBehindAnAccessibleDetailsActionWhileMaterialLimitsStayVisible()throws Exception {
    java.time.Clock clock=java.time.Clock.fixed(java.time.Instant.parse("2026-10-21T08:00:00Z"),java.time.ZoneOffset.UTC);
    app.organicmaps.sdk.transit.JourneyUiFixture fixture=new app.organicmaps.sdk.transit.JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),clock);
    worker=java.util.concurrent.Executors.newSingleThreadExecutor();injected=new JourneyViewModel(RuntimeEnvironment.getApplication(),fixture.repository,worker,clock);
    ActivityController<FixtureActivity> c=Robolectric.buildActivity(FixtureActivity.class);FixtureActivity a=c.get();a.fixture=injected;a.setTheme(R.style.MwmTheme);c.create().start().resume().visible();journey=c;
    await(()->!injected.getState().getValue().busy);View root=a.getWindow().getDecorView();
    TextView mechanics=root.findViewWithTag("dmaps-walk-action-scope");Button details=root.findViewWithTag("dmaps-walk-action-details");
    assertNotNull(mechanics);assertNotNull(details);assertEquals(View.GONE,mechanics.getVisibility());assertTrue(mechanics.getText().toString().contains("32 platforms"));
    assertEquals("Search details",details.getText().toString());assertEquals("collapsed",String.valueOf(androidx.core.view.ViewCompat.getStateDescription(details)));
    String shown=visibleOnly(root);
    for(String material:new String[]{"no interchange walking","station access remains unverified","128 MiB","no download or active-navigation change","at most one"})assertTrue(material,shown.contains(material));
    assertFalse("mechanics hidden by default",shown.contains("32 platforms"));
    details.performClick();assertEquals(View.VISIBLE,mechanics.getVisibility());assertEquals("Hide search details",details.getText().toString());
    assertEquals("expanded",String.valueOf(androidx.core.view.ViewCompat.getStateDescription(details)));assertTrue(visibleOnly(root).contains("32 platforms"));
    details.performClick();assertEquals(View.GONE,mechanics.getVisibility());
  }
  private String visibleOnly(View v){
    if(v.getVisibility()!=View.VISIBLE)return "";String s=v instanceof TextView?((TextView)v).getText().toString()+"\n":"";
    if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)s+=visibleOnly(((ViewGroup)v).getChildAt(i));return s;
  }
  @Test public void stationChoiceRendersAndConsumesAnExplicitChildPlatformControl()throws Exception {
    java.time.Clock clock=java.time.Clock.fixed(java.time.Instant.parse("2026-10-21T08:00:00Z"),java.time.ZoneOffset.UTC);
    app.organicmaps.sdk.transit.JourneyUiFixture fixture=new app.organicmaps.sdk.transit.JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),clock);
    worker=java.util.concurrent.Executors.newSingleThreadExecutor();injected=new JourneyViewModel(RuntimeEnvironment.getApplication(),fixture.repository,worker,clock);
    ActivityController<FixtureActivity> c=Robolectric.buildActivity(FixtureActivity.class);FixtureActivity a=c.get();a.fixture=injected;a.setTheme(R.style.MwmTheme);c.create().start().resume().visible();journey=c;await(()->!injected.getState().getValue().busy);
    injected.search(true,"Alexanderplatz");await(()->!injected.getState().getValue().busy);var hits=injected.getState().getValue().candidates;app.organicmaps.sdk.transit.Stop station=null;
    for(var s:hits.stops)if(s.sourceId.equals("P1"))station=s;assertNotNull(station);injected.select(hits,station.id,true);await(()->!injected.getState().getValue().busy);
    var group=injected.getState().getValue().from.selection;assertTrue(group.platforms.size()>1);long id=group.platforms.get(0).id;
    Button pick=a.getWindow().getDecorView().findViewWithTag("dmaps-origin-platform-"+id);assertNotNull("station child platforms must be rider-reachable independently of name-search cap",pick);assertTrue(pick.isEnabled());pick.performClick();await(()->!injected.getState().getValue().busy);
    assertEquals(id,injected.getState().getValue().from.stop.id);assertEquals(1,injected.getState().getValue().from.selection.platforms.size());
  }
  private FixtureActivity readyFixtureScreen()throws Exception {
    java.time.Clock clock=java.time.Clock.fixed(java.time.Instant.parse("2026-10-21T08:00:00Z"),java.time.ZoneOffset.UTC);
    app.organicmaps.sdk.transit.JourneyUiFixture fixture=new app.organicmaps.sdk.transit.JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),clock);
    worker=java.util.concurrent.Executors.newSingleThreadExecutor();injected=new JourneyViewModel(RuntimeEnvironment.getApplication(),fixture.repository,worker,clock);
    ActivityController<FixtureActivity> c=Robolectric.buildActivity(FixtureActivity.class);FixtureActivity a=c.get();a.fixture=injected;a.setTheme(R.style.MwmTheme);c.create().start().resume().visible();journey=c;
    await(()->!injected.getState().getValue().busy);return a;
  }
  @Test public void candidateControlsDistinguishStationAndBoardingPointAndPreserveSourceIds()throws Exception {
    FixtureActivity a=readyFixtureScreen();injected.search(true,"Alexanderplatz");await(()->!injected.getState().getValue().busy);
    var hits=injected.getState().getValue().candidates;assertNotNull(hits);assertTrue(hits.stops.size()>1);
    LinearLayout controls=(LinearLayout)field(a,"candidates");assertEquals(hits.stops.size(),controls.getChildCount());
    java.util.Set<String> labels=new java.util.HashSet<>();boolean station=false,boarding=false;
    for(int i=0;i<hits.stops.size();i++){
      var stop=hits.stops.get(i);Button control=(Button)controls.getChildAt(i);String label=control.getText().toString();
      assertTrue("candidate source identity must be rider-visible",label.contains("Stop ID: "+stop.sourceId));
      assertNull("all-caps transformation must not rewrite source identifiers",control.getTransformationMethod());
      assertTrue("different source IDs must not produce identical candidate labels",labels.add(label));
      if(stop.locationType==1){station=true;assertTrue(label.contains("Station — choose a platform"));}
      else{boarding=true;assertTrue(label.contains("Boarding point"));}
    }
    assertTrue("fixture must exercise both semantic kinds",station&&boarding);
  }
  @Test public void systemBarsAndKeyboardInsetTheScrollableViewportRatherThanScrollingAwayWithContent()throws Exception {
    FixtureActivity a=readyFixtureScreen();ViewGroup content=a.findViewById(android.R.id.content);
    ScrollView scroll=(ScrollView)content.getChildAt(0);View body=scroll.getChildAt(0);int bodyPadding=body.getPaddingTop();
    var bars=androidx.core.graphics.Insets.of(7,29,11,37);
    var shown=new androidx.core.view.WindowInsetsCompat.Builder().setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(),bars)
        .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(),androidx.core.graphics.Insets.of(0,0,0,480))
        .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(),true).build();
    androidx.core.view.ViewCompat.dispatchApplyWindowInsets(scroll,shown);
    assertEquals("status-bar exclusion belongs to fixed viewport",29,scroll.getPaddingTop());
    assertEquals(7,scroll.getPaddingLeft());assertEquals(11,scroll.getPaddingRight());assertEquals(480,scroll.getPaddingBottom());
    assertEquals("content keeps its ordinary padding, not duplicated system insets",bodyPadding,body.getPaddingTop());assertTrue(scroll.getClipToPadding());
    var hidden=new androidx.core.view.WindowInsetsCompat.Builder(shown).setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(),androidx.core.graphics.Insets.NONE)
        .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(),false).build();
    androidx.core.view.ViewCompat.dispatchApplyWindowInsets(scroll,hidden);assertEquals(37,scroll.getPaddingBottom());
  }
  @Test public void timetableOffersExplicitLocalJourneyEntryWithoutAlteringBoards(){
    entry=Robolectric.buildActivity(EntryActivity.class);TimetableActivity a=entry.get();a.setTheme(R.style.MwmTheme);entry.create().start().resume().visible();
    Button open=button(a.getWindow().getDecorView(),"Plan station journey");
    assertNotNull("Rider cannot reach the verified journey backend from the timetable",open);
    open.performClick();android.content.Intent intent=Shadows.shadowOf(a).getNextStartedActivity();
    assertNotNull(intent);assertEquals("app.organicmaps.transit.JourneyActivity",intent.getComponent().getClassName());assertNull(intent.getExtras());
  }
}
