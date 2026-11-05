package app.organicmaps.sdk.transit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Small real filesystem/thread/kernel-lock controls. No Android, network or performance claim. */
public class PackDirectoryTest {
  @Rule public final TemporaryFolder temporary=new TemporaryFolder();

  private static String probe(File directory) throws Exception {
    String classes=Paths.get(PackDirectoryProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    Process child=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),
      "-XX:ActiveProcessorCount=1","-Xmx32m","-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),
      "-cp",classes,PackDirectoryProbe.class.getName(),directory.getAbsolutePath()).redirectErrorStream(true).start();
    try {
      assertTrue("probe did not finish",child.waitFor(20,TimeUnit.SECONDS));
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[256];int count;
      try(InputStream input=child.getInputStream()){while((count=input.read(buffer))!=-1){bytes.write(buffer,0,count);assertTrue("probe output exceeded cap",bytes.size()<=4096);}}
      assertEquals("probe child failed",0,child.exitValue());
      String output=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
      // JAVA_TOOL_OPTIONS can prepend a JVM notice; only fixed observer tokens are acceptance.
      boolean acquired=output.lines().anyMatch("ACQUIRED"::equals),blocked=output.lines().anyMatch("BLOCKED"::equals);
      assertTrue("missing or ambiguous probe token",acquired!=blocked);return acquired?"ACQUIRED":"BLOCKED";
    } finally {if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));}}
  }

  @Test public void kernelLockIsHeldAndReleasedAcrossRealProcesses() throws Exception {
    File dir=temporary.newFolder();PackDirectory directory=new PackDirectory(dir);
    assertEquals("ACQUIRED",probe(dir));
    try(PackDirectory.Guard guard=directory.acquire()){assertEquals("BLOCKED",probe(dir));}
    assertEquals("ACQUIRED",probe(dir));
  }

  @Test public void twoInstancesSerializeSameProcessWriters() throws Exception {
    File dir=temporary.newFolder();PackDirectory first=new PackDirectory(dir),second=new PackDirectory(dir);
    CountDownLatch attempted=new CountDownLatch(1),entered=new CountDownLatch(1);
    ExecutorService executor=Executors.newSingleThreadExecutor();Future<Boolean> waiter;
    try {
      try(PackDirectory.Guard guard=first.acquire()){
        waiter=executor.submit(()->{attempted.countDown();try(PackDirectory.Guard other=second.acquire()){entered.countDown();return true;}});
        assertTrue(attempted.await(5,TimeUnit.SECONDS));assertFalse("writer entered before release",entered.await(150,TimeUnit.MILLISECONDS));
      }
      assertTrue("waiting writer failed after release",waiter.get(5,TimeUnit.SECONDS));
    } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
  }

  @Test public void canonicalAliasesAllowReentrantUseAcrossInstances() throws Exception {
    File dir=temporary.newFolder();
    try(PackDirectory.Guard outer=new PackDirectory(dir).acquire()){
      try(PackDirectory.Guard inner=new PackDirectory(new File(dir,".")).acquire()){
        assertEquals("BLOCKED",probe(dir));
      }
      assertEquals("BLOCKED",probe(dir));
    }
    assertEquals("ACQUIRED",probe(dir));
  }

  @Test public void outOfOrderCloseCannotReleaseOuterKernelAuthority() throws Exception {
    File dir=temporary.newFolder();PackDirectory directory=new PackDirectory(dir);
    PackDirectory.Guard outer=directory.acquire(),inner=directory.acquire();
    try {
      try{outer.close();fail("out-of-order close admitted");}catch(IllegalStateException expected){}
      assertEquals("BLOCKED",probe(dir));
    } finally {inner.close();outer.close();}
    outer.close(); // Owner-thread repeat close is harmless, never a second unlock.
    assertEquals("ACQUIRED",probe(dir));
  }

  @Test public void wrongThreadCannotCloseAnotherWritersGuard() throws Exception {
    File dir=temporary.newFolder();ExecutorService executor=Executors.newSingleThreadExecutor();
    try(PackDirectory.Guard guard=new PackDirectory(dir).acquire()){
      Future<Boolean> attempt=executor.submit(()->{try{guard.close();return false;}catch(IllegalStateException expected){return true;}});
      assertTrue("wrong thread released guard",attempt.get(5,TimeUnit.SECONDS));assertEquals("BLOCKED",probe(dir));
    } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    assertEquals("ACQUIRED",probe(dir));
  }

  @Test public void interruptedWaiterCannotStealOrPoisonGuard() throws Exception {
    File dir=temporary.newFolder();PackDirectory first=new PackDirectory(dir),second=new PackDirectory(dir);
    CountDownLatch attempted=new CountDownLatch(1),finished=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();
    Thread waiter=new Thread(()->{attempted.countDown();try(PackDirectory.Guard guard=second.acquire()){error.set(new AssertionError("interrupted waiter entered"));}catch(IOException expected){if(!Thread.currentThread().isInterrupted())error.set(new AssertionError("interrupt flag lost"));}finally{finished.countDown();}},"dmaps-lock-cancel-test");
    try(PackDirectory.Guard guard=first.acquire()){
      waiter.start();assertTrue(attempted.await(5,TimeUnit.SECONDS));waiter.interrupt();assertTrue("waiter did not cancel",finished.await(5,TimeUnit.SECONDS));assertNull(error.get());assertEquals("BLOCKED",probe(dir));
    } finally {waiter.interrupt();waiter.join(5000);assertFalse("waiter leaked",waiter.isAlive());}
    try(PackDirectory.Guard guard=second.acquire()){assertEquals("BLOCKED",probe(dir));}
    assertEquals("ACQUIRED",probe(dir));
  }

  @Test public void invalidStorageRefusesAndFailedAcquireDoesNotPoisonMutex() throws Exception {
    File parent=temporary.newFolder();File dir=new File(parent,"store");Files.write(dir.toPath(),new byte[]{1});PackDirectory directory=new PackDirectory(dir);
    for(int i=0;i<2;i++){try(PackDirectory.Guard guard=directory.acquire()){fail("file admitted as directory");}catch(IOException expected){}}
    assertTrue(dir.delete());assertTrue(dir.mkdir());
    try(PackDirectory.Guard guard=directory.acquire()){assertEquals("BLOCKED",probe(dir));}
  }

  @Test public void invalidLockSlotRefusesAndCanRetryAfterRepair() throws Exception {
    File dir=temporary.newFolder();File lock=new File(dir,"pack-writer.lock");assertTrue(lock.mkdir());PackDirectory directory=new PackDirectory(dir);
    for(int i=0;i<2;i++){try(PackDirectory.Guard guard=directory.acquire()){fail("directory admitted as lock file");}catch(IOException expected){}}
    assertTrue(lock.delete());try(PackDirectory.Guard guard=directory.acquire()){assertEquals("BLOCKED",probe(dir));}
  }
}
