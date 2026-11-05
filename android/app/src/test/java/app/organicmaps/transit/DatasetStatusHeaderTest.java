package app.organicmaps.transit;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.JourneyUiFixture;
import app.organicmaps.sdk.transit.TimetableRepository;
import java.time.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Real Activity/worker/repository composition, no native map registry and no new API needed for RED. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class DatasetStatusHeaderTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 private JourneyViewModel model;private ExecutorService worker;private ActivityController<Screen> screen;
 public static class Screen extends JourneyActivity {
  JourneyViewModel fixture;@Override JourneyViewModel createModel(){return fixture;}
 }
 private TextView open(boolean pack)throws Exception {
  Clock clock=Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"),ZoneOffset.UTC);
  TimetableRepository repository=pack?new JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),clock).repository:new TimetableRepository(RuntimeEnvironment.getApplication());
  worker=Executors.newSingleThreadExecutor();model=new JourneyViewModel(RuntimeEnvironment.getApplication(),repository,worker,clock);
  screen=Robolectric.buildActivity(Screen.class);screen.get().fixture=model;screen.get().setTheme(R.style.MwmTheme);screen.create().start().resume().visible();
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(model.getState().getValue().busy&&System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(model.getState().getValue().busy);
  TextView header=screen.get().getWindow().getDecorView().findViewWithTag("dmaps-dataset-status");
  assertNotNull("dataset status header must be rider visible",header);assertEquals(View.VISIBLE,header.getVisibility());return header;
 }
 @After public void close()throws Exception {if(screen!=null)screen.pause().stop().destroy();if(model!=null)model.onCleared();if(worker!=null)assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
 @Test public void noPackHasAnExplicitUsefulImportAction()throws Exception {
  String shown=open(false).getText().toString();assertTrue(shown,shown.contains("No timetable pack installed"));assertTrue(shown,shown.contains("Next: import"));
 }
 @Test public void unavailableMapRegistryIsNotClaimedAsReady()throws Exception {
  String shown=open(true).getText().toString();assertTrue(shown,shown.contains("could not be checked"));assertFalse(shown,shown.startsWith("Ready"));
 }
}
