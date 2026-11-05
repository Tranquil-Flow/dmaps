package app.organicmaps.transit;

import android.app.Application;
import android.os.Looper;
import app.organicmaps.sdk.transit.JourneyUiFixture;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** SYSTEM.md I6: the phone's logcat main buffer holds about one minute, so slice C samples were lost.
 * Debug builds keep the newest timing events in an app-private ring file read with run-as. Only the fixed event
 * vocabulary, the outcome, elapsed milliseconds and device uptime may ever be written: no names, IDs, coordinates,
 * queries or wall-clock times. Looked up reflectively so the sealed bytes without the ring compile (a FEATURE_ABSENT RED). */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class JourneyTimingRingTest {
  @Rule public TemporaryFolder temp=new TemporaryFolder();
  private static Class<?> ring(){
    try{return Class.forName("app.organicmaps.transit.TimingRing");}
    catch(ClassNotFoundException missing){fail("no on-device timing ring");return null;}
  }
  private static void write(File dir,String event,long ms,long uptime)throws Exception {
    java.lang.reflect.Method m=ring().getDeclaredMethod("write",File.class,String.class,long.class,long.class);m.setAccessible(true);m.invoke(null,dir,event,ms,uptime);
  }
  private static List<String> lines(File dir)throws Exception {
    File f=new File(dir,"timing.jsonl");return f.exists()?Files.readAllLines(f.toPath(),StandardCharsets.UTF_8):Collections.emptyList();
  }
  @Test public void writesOnlyTheFixedVocabularyAsJsonLines()throws Exception {
    File dir=temp.newFolder();
    write(dir,"journey_plan ok",964,1000);
    write(dir,"journey_search Alexanderplatz ok",91,1001);          // a name must never reach the file
    write(dir,"journey_plan ok\n{\"event\":\"x\"}",1,1002);           // no line injection
    write(dir,"journey_plan_walking failed",3017,1003);
    write(dir,"journey_plan ok",-1,1004);                            // not a duration
    write(dir,"journey_plan ok",3_600_001,1005);                     // not a plausible user wait
    List<String> l=lines(dir);
    assertEquals(Arrays.asList(
        "{\"event\":\"journey_plan\",\"outcome\":\"ok\",\"ms\":964,\"uptimeMs\":1000}",
        "{\"event\":\"journey_plan_walking\",\"outcome\":\"failed\",\"ms\":3017,\"uptimeMs\":1003}"),l);
  }
  @Test public void keepsOnlyTheNewestEntries()throws Exception {
    File dir=temp.newFolder();
    for(int i=0;i<450;i++)write(dir,"journey_plan ok",i,10_000+i);
    List<String> l=lines(dir);
    assertTrue("bounded: "+l.size(),l.size()>=200&&l.size()<=400);
    assertTrue("newest kept",l.get(l.size()-1).contains("\"ms\":449,"));
    assertFalse("oldest dropped",l.get(0).contains("\"ms\":0,"));
    assertFalse("no leftover temp file",new File(dir,"timing.jsonl.tmp").exists());
  }
  static final class Time extends Clock {
    public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
    public Instant instant(){return Instant.parse("2026-10-21T08:00:00Z");}
  }
  @Test public void debugJourneyScreenRecordsIntoTheAppPrivateRing()throws Exception {
    ring();
    Application app=RuntimeEnvironment.getApplication();File dir=new File(app.getFilesDir(),"dmaps-diag");
    JourneyUiFixture fixture=new JourneyUiFixture(app,temp.newFolder(),new Time());
    ExecutorService worker=Executors.newSingleThreadExecutor();
    JourneyViewModel model=new JourneyViewModel(app,fixture.repository,worker,new Time());
    try{
      model.refresh();
      long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);boolean seen=false;
      while(System.nanoTime()<end&&!seen){
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        for(String s:lines(dir))if(s.startsWith("{\"event\":\"journey_refresh\",\"outcome\":\"ok\",\"ms\":"))seen=true;
        Thread.sleep(5);
      }
      assertTrue("journey_refresh reached files/dmaps-diag/timing.jsonl",seen);
      for(String s:lines(dir))assertTrue("only the four fields: "+s,s.matches("\\{\"event\":\"journey_[a-z_]+\",\"outcome\":\"(ok|failed)\",\"ms\":\\d+,\"uptimeMs\":\\d+\\}"));
    }finally{model.onCleared();worker.shutdown();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
  }
}
