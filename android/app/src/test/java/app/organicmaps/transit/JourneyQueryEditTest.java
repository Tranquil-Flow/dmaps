package app.organicmaps.transit;

import android.app.Application;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.*;
import app.organicmaps.R;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Found on the Pixel 2026-10-28: after switching to another app and back, the keyboard reconnected and
 * re-committed the identical composing word in the focused destination field; the screen treated that as an
 * edit and silently dropped the chosen platform. Only a real content change may retire an endpoint. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class JourneyQueryEditTest {
  @Rule public org.junit.rules.TemporaryFolder temp=new org.junit.rules.TemporaryFolder();
  private ActivityController<JourneyScreenTest.FixtureActivity> journey;private JourneyViewModel injected;private java.util.concurrent.ExecutorService worker;
  private Object field(Object object,String name)throws Exception {
    java.lang.reflect.Field f=JourneyActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(object);
  }
  private void await(java.util.function.BooleanSupplier ready)throws Exception {
    long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
    while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();if(ready.getAsBoolean())return;Thread.sleep(5);}fail("host screen readiness exceeded");
  }
  @After public void close()throws Exception {
    if(journey!=null)journey.pause().stop().destroy();
    if(worker!=null){worker.shutdown();assertTrue(worker.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
  }
  private JourneyScreenTest.FixtureActivity selectedEndpoints()throws Exception {
    java.time.Clock clock=java.time.Clock.fixed(java.time.Instant.parse("2026-10-21T08:00:00Z"),java.time.ZoneOffset.UTC);
    app.organicmaps.sdk.transit.JourneyUiFixture fixture=new app.organicmaps.sdk.transit.JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),clock);
    worker=java.util.concurrent.Executors.newSingleThreadExecutor();injected=new JourneyViewModel(RuntimeEnvironment.getApplication(),fixture.repository,worker,clock);
    journey=Robolectric.buildActivity(JourneyScreenTest.FixtureActivity.class);JourneyScreenTest.FixtureActivity a=journey.get();a.fixture=injected;a.setTheme(R.style.MwmTheme);
    journey.create().start().resume().visible();await(()->!injected.getState().getValue().busy);
    java.util.List<app.organicmaps.sdk.transit.TripStop> rows=fixture.repository.tripStops("T1",java.time.LocalDate.parse("2026-10-21"));
    long[] targets={rows.get(0).stopId,rows.get(rows.size()-1).stopId};String[] queries={rows.get(0).stopName,rows.get(rows.size()-1).stopName};
    for(int which=0;which<2;which++){
      ((EditText)field(a,which==0?"originQuery":"destinationQuery")).setText(queries[which]);
      ((Button)field(a,which==0?"originSearch":"destinationSearch")).performClick();await(()->!injected.getState().getValue().busy);
      app.organicmaps.sdk.transit.JourneyPlanner.Candidates hits=injected.getState().getValue().candidates;assertNotNull(hits);
      int index=-1;for(int i=0;i<hits.stops.size();i++)if(hits.stops.get(i).id==targets[which])index=i;assertTrue(index>=0);
      ((LinearLayout)field(a,"candidates")).getChildAt(index).performClick();await(()->!injected.getState().getValue().busy);
    }
    assertNotNull(injected.getState().getValue().from);assertNotNull(injected.getState().getValue().to);assertTrue(((Button)field(a,"plan")).isEnabled());
    return a;
  }
  @Test public void keyboardRecommittingTheSameWordKeepsTheChosenPlatform()throws Exception {
    JourneyScreenTest.FixtureActivity a=selectedEndpoints();EditText destination=(EditText)field(a,"destinationQuery");
    destination.requestFocus();String text=destination.getText().toString();int space=text.lastIndexOf(' ');int start=space<0?0:space+1;
    InputConnection ic=destination.onCreateInputConnection(new EditorInfo());assertNotNull(ic);
    // What the reconnecting keyboard does: mark the last word as composing, re-commit it unchanged, finish.
    assertTrue(ic.setComposingRegion(start,text.length()));assertTrue(ic.setComposingText(text.substring(start),1));assertTrue(ic.finishComposingText());
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertEquals("the field content did not change",text,destination.getText().toString());
    assertNotNull("an unchanged field must not drop the chosen destination platform",injected.getState().getValue().to);
    assertTrue(((Button)field(a,"plan")).isEnabled());
  }
  @Test public void realEditStillRetiresTheEndpoint()throws Exception {
    JourneyScreenTest.FixtureActivity a=selectedEndpoints();EditText destination=(EditText)field(a,"destinationQuery");
    InputConnection ic=destination.onCreateInputConnection(new EditorInfo());destination.setSelection(destination.length());
    assertTrue(ic.commitText("x",1));Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertNull("a real edit retires the old destination",injected.getState().getValue().to);assertFalse(((Button)field(a,"plan")).isEnabled());
  }
}
