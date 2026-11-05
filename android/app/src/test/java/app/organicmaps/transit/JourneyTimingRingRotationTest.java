package app.organicmaps.transit;

import android.app.Application;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** API-21 portability guard for the timing ring rotation (minSdk 21: java.nio.file.Files/StandardCopyOption need API 26).
 * Characterisation tests: they pin the rotation contract that must survive replacing the NIO calls with java.io
 * (exact retained window, no leftover temp, stale temp replaced, and a failed rotation never loses the ring).
 * They are GREEN on both the sealed v61 bytes and the replacement; the RED evidence for the API-level defect is the
 * canonical Android lint NewApi report, not this class. Host JVM only: it does not prove API-21 device behaviour. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
public class JourneyTimingRingRotationTest {
  @Rule public TemporaryFolder temp=new TemporaryFolder();

  private static void write(File dir,int i){TimingRing.write(dir,"journey_plan ok",i,10_000L+i);}
  private static List<String> lines(File dir)throws Exception {return Files.readAllLines(new File(dir,"timing.jsonl").toPath(),StandardCharsets.UTF_8);}
  private static boolean hasMs(String line,int ms){return line.contains("\"ms\":"+ms+",");}

  @Test public void rotationKeepsExactlyTheNewestWindowAndNoTemp()throws Exception {
    File dir=temp.newFolder();
    for(int i=0;i<=TimingRing.MAX_LINES;i++)write(dir,i);   // line MAX_LINES+1 triggers the first rotation
    List<String> l=lines(dir);
    assertEquals(TimingRing.KEEP_LINES,l.size());
    assertTrue("oldest kept is the first of the newest window",hasMs(l.get(0),TimingRing.MAX_LINES+1-TimingRing.KEEP_LINES));
    assertTrue("newest kept",hasMs(l.get(l.size()-1),TimingRing.MAX_LINES));
    assertFalse("no leftover temp file",new File(dir,"timing.jsonl.tmp").exists());
    byte[] raw=Files.readAllBytes(new File(dir,"timing.jsonl").toPath());
    assertEquals("ring file ends with a newline",(byte)'\n',raw[raw.length-1]);
    write(dir,TimingRing.MAX_LINES+1);                       // appends after rotation, no gap, no duplicate
    l=lines(dir);
    assertEquals(TimingRing.KEEP_LINES+1,l.size());
    assertTrue(hasMs(l.get(l.size()-1),TimingRing.MAX_LINES+1));
    assertTrue(hasMs(l.get(l.size()-2),TimingRing.MAX_LINES));
  }

  @Test public void staleTempFromACrashedRotationIsReplaced()throws Exception {
    File dir=temp.newFolder();File tmp=new File(dir,"timing.jsonl.tmp");
    Files.write(tmp.toPath(),"stale-garbage-from-a-killed-rotation\n".getBytes(StandardCharsets.UTF_8));
    for(int i=0;i<=TimingRing.MAX_LINES;i++)write(dir,i);
    assertFalse("stale temp consumed or replaced",tmp.exists());
    for(String s:lines(dir))assertFalse("stale content never reaches the ring: "+s,s.contains("stale-garbage"));
    assertEquals(TimingRing.KEEP_LINES,lines(dir).size());
  }

  @Test public void aFailedRotationNeverLosesOrCorruptsTheRing()throws Exception {
    File dir=temp.newFolder();File blocked=new File(dir,"timing.jsonl.tmp");
    assertTrue(blocked.mkdir());Files.write(new File(blocked,"keep").toPath(),new byte[]{1});   // temp path unusable: rotation must fail
    for(int i=0;i<=TimingRing.MAX_LINES;i++)write(dir,i);                                          // must not throw
    List<String> l=lines(dir);
    assertEquals("original ring left whole and unrotated",TimingRing.MAX_LINES+1,l.size());
    assertTrue(hasMs(l.get(0),0));assertTrue(hasMs(l.get(l.size()-1),TimingRing.MAX_LINES));
    assertTrue("blocker untouched",blocked.isDirectory()&&new File(blocked,"keep").isFile());
    assertTrue(new File(blocked,"keep").delete());assertTrue(blocked.delete());
    write(dir,TimingRing.MAX_LINES+1);                                                             // the next write recovers
    l=lines(dir);
    assertEquals(TimingRing.KEEP_LINES,l.size());
    assertTrue(hasMs(l.get(l.size()-1),TimingRing.MAX_LINES+1));
    assertFalse(new File(dir,"timing.jsonl.tmp").exists());
  }
}
