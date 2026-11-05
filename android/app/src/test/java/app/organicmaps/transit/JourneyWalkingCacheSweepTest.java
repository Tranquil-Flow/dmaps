package app.organicmaps.transit;

import android.app.Application;
import android.os.Looper;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.JourneyUiFixture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.File;
import java.nio.file.Files;
import java.time.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Found on the Pixel 2026-10-28: a force-stop while a mapped-walking plan held its private map copy left a
 * ~106 MB snapshot in cache/dmaps-journey-walking that no later process ever removed. No snapshot can outlive
 * its process, so the first journey model of a process removes leftovers once, before this process opens any
 * snapshot; it never touches a snapshot opened later in the same process and never follows symlinks. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class JourneyWalkingCacheSweepTest {
  @Rule public TemporaryFolder temp=new TemporaryFolder();
  private JourneyUiFixture fixture;private final java.util.List<JourneyViewModel> models=new java.util.ArrayList<>();
  private final java.util.List<ExecutorService> workers=new java.util.ArrayList<>();
  static final class Time extends Clock {
    Instant now=Instant.parse("2026-10-21T08:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}
    public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
  }
  private final Time clock=new Time();
  private File cache(){return new File(RuntimeEnvironment.getApplication().getCacheDir(),"dmaps-journey-walking");}
  /** Simulate a new process: the once-per-process latch starts unset. Absent on builds without the sweep. */
  private static void newProcess()throws Exception {
    try{java.lang.reflect.Method m=JourneyViewModel.class.getDeclaredMethod("resetWalkingCacheSweepForTest");m.setAccessible(true);m.invoke(null);}
    catch(NoSuchMethodException absent){/* nothing to reset: the behaviour below decides */}
  }
  @Before public void setup()throws Exception {fixture=new JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),clock);newProcess();}
  @After public void close()throws Exception {
    for(JourneyViewModel m:models)m.onCleared();for(ExecutorService w:workers)assertTrue(w.awaitTermination(10,TimeUnit.SECONDS));
  }
  private JourneyViewModel open()throws Exception {
    ExecutorService w=Executors.newSingleThreadExecutor(r->new Thread(r,"dmaps-journey-sweep-test-worker"));workers.add(w);
    JourneyViewModel m=new JourneyViewModel(RuntimeEnvironment.getApplication(),fixture.repository,w,clock);models.add(m);
    m.refresh();await(()->!m.getState().getValue().busy);assertEquals(R.string.journey_choose,m.getState().getValue().message);return m;
  }
  private void await(BooleanSupplier ready)throws Exception {
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
    while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();if(ready.getAsBoolean())return;Thread.sleep(5);}
    fail("bounded host readiness exceeded");
  }
  private static File snapshot(File cache,String name)throws Exception {
    File d=new File(cache,name);assertTrue(d.mkdirs());Files.write(new File(d,"map.mwm").toPath(),new byte[4096]);return d;
  }
  @Test public void snapshotLeftByADeadProcessIsRemovedWhenTheJourneyScreenOpens()throws Exception {
    File orphan=snapshot(cache(),"dead-process-snapshot");
    open();
    assertFalse("a private walking map copy left by a killed process must not leak in cache forever",orphan.exists());
    assertTrue("the cache directory itself stays usable",cache().isDirectory()||!cache().exists());
  }
  @Test public void laterModelsInTheSameProcessNeverTouchALiveSnapshot()throws Exception {
    open();
    File live=snapshot(cache(),"live-snapshot-of-first-model");
    open();
    assertTrue("a snapshot opened in this process belongs to a live owner",new File(live,"map.mwm").exists());
  }
  @Test public void sweepDoesNotFollowSymlinksOutOfTheCache()throws Exception {
    File outside=temp.newFolder("outside");File precious=new File(outside,"owner-map.mwm");Files.write(precious.toPath(),new byte[16]);
    assertTrue(cache().isDirectory()||cache().mkdirs());
    Files.createSymbolicLink(new File(cache(),"link").toPath(),outside.toPath());
    File orphan=snapshot(cache(),"dead-process-snapshot");
    open();
    assertFalse(orphan.exists());
    assertTrue("files reached through a link are not ours to delete",precious.exists());
  }
}
