package app.organicmaps.transit;

import android.app.Application;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** API-21 portability guard for the walking-cache sweep's no-follow rule (java.nio.file.Files.isSymbolicLink and
 * File.toPath need API 26; minSdk is 21). Calls the private once-per-process sweep directly on a prepared tree.
 * Characterisation tests: GREEN on the sealed v61 bytes and on the java.io replacement; the RED evidence for the
 * API-level defect is the canonical lint NewApi report. Host JVM only: it does not prove API-21 device behaviour. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
public class JourneyWalkingCacheSweepLinkTest {
  @Rule public TemporaryFolder temp=new TemporaryFolder();

  private static void latch(String name)throws Exception {Method m=JourneyViewModel.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(null);}
  private static void sweep(File cache)throws Exception {
    latch("resetWalkingCacheSweepForTest");
    Method m=JourneyViewModel.class.getDeclaredMethod("sweepWalkingCacheOnce",File.class);m.setAccessible(true);m.invoke(null,cache);
  }
  @Before public void fresh()throws Exception {latch("resetWalkingCacheSweepForTest");}
  @After public void restore()throws Exception {latch("resetWalkingCacheSweepForTest");}   // the latch starts unset in a fresh process
  private File outside()throws Exception {
    File outside=temp.newFolder("outside");File sub=new File(outside,"sub");assertTrue(sub.mkdirs());
    Files.write(new File(outside,"owner-map.mwm").toPath(),new byte[16]);Files.write(new File(sub,"deep.mwm").toPath(),new byte[16]);return outside;
  }
  private static void assertOutsideIntact(File outside){
    assertTrue("files reached through a link are not ours to delete",new File(outside,"owner-map.mwm").isFile());
    assertTrue(new File(new File(outside,"sub"),"deep.mwm").isFile());
  }
  private static boolean exists(File f){return Files.exists(f.toPath(),LinkOption.NOFOLLOW_LINKS);}

  @Test public void aLinkToADirectoryIsRemovedNotFollowed()throws Exception {
    File outside=outside(),cache=temp.newFolder("cache");File link=new File(cache,"link");
    Files.createSymbolicLink(link.toPath(),outside.toPath());
    sweep(cache);
    assertFalse("the link itself is removed",exists(link));
    assertOutsideIntact(outside);
  }
  @Test public void aLinkNestedInARealSnapshotDirectoryIsNotFollowedAndTheSnapshotIsRemoved()throws Exception {
    File outside=outside(),cache=temp.newFolder("cache");File snap=new File(cache,"dead-snapshot"),inner=new File(snap,"inner");
    assertTrue(inner.mkdirs());Files.write(new File(inner,"map.mwm").toPath(),new byte[64]);
    Files.createSymbolicLink(new File(inner,"link").toPath(),outside.toPath());
    sweep(cache);
    assertFalse("real nested directories are recursed into and removed",snap.exists());
    assertOutsideIntact(outside);
  }
  @Test public void fileAndDanglingLinksAreRemovedWithoutTouchingTargets()throws Exception {
    File outside=outside(),cache=temp.newFolder("cache");
    File toFile=new File(cache,"to-file"),dangling=new File(cache,"dangling");
    Files.createSymbolicLink(toFile.toPath(),new File(outside,"owner-map.mwm").toPath());
    Files.createSymbolicLink(dangling.toPath(),new File(outside,"does-not-exist").toPath());
    sweep(cache);
    assertFalse(exists(toFile));assertFalse(exists(dangling));
    assertOutsideIntact(outside);
  }
  @Test public void aCacheReachedThroughALinkedAncestorStillRemovesItsRealSnapshots()throws Exception {
    // Android reaches app storage through /data/user/0 -> /data/data: real entries below a linked ancestor are not links.
    File real=temp.newFolder("real-parent");File cache=new File(real,"dmaps-journey-walking");File snap=new File(cache,"dead-snapshot");
    assertTrue(snap.mkdirs());Files.write(new File(snap,"map.mwm").toPath(),new byte[64]);
    File alias=new File(temp.getRoot(),"alias-parent");Files.createSymbolicLink(alias.toPath(),real.toPath());
    sweep(new File(alias,"dmaps-journey-walking"));
    assertFalse("real snapshot below the linked ancestor is removed",snap.exists());
  }
}
