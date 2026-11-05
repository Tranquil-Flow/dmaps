package app.organicmaps.transit;

import android.app.Application;
import android.os.Looper;
import app.organicmaps.sdk.transit.*;
import java.time.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Real temporary pack/SQLite/worker restoration; not Android process-death or GPU proof. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class TimetableRestoreStateTest {
  @Rule public org.junit.rules.TemporaryFolder temp=new org.junit.rules.TemporaryFolder();
  private TimetableRepository repository;private TimetableViewModel model;private ExecutorService worker;
  private Stop stop;private String hash;
  @Before public void setup()throws Exception {
    var fixture=new JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"),ZoneOffset.UTC));
    repository=fixture.repository;hash=repository.info().databaseSha256;
    stop=repository.searchStops("Alexanderplatz",50).stream().filter(s->s.locationType==0).findFirst().orElseThrow();
    worker=Executors.newSingleThreadExecutor();model=new TimetableViewModel(RuntimeEnvironment.getApplication(),repository,worker);await();
  }
  private void await()throws Exception {
    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
    do{Shadows.shadowOf(Looper.getMainLooper()).idle();if(model.getState().getValue().status!=TimetableViewModel.Status.WORKING)return;Thread.sleep(5);}while(System.nanoTime()<end);
    fail("worker did not retire restoration");
  }
  @After public void close()throws Exception {
    if(model!=null)model.onCleared();if(worker!=null)assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }
  @Test public void boundSavedSelectionReloadsActualIdentityAndFreshUntrustedMetadata()throws Exception {
    model.refreshPackState(new TimetableViewModel.SavedState("owned query",stop.id,"not an authoritative label"),hash);await();
    var state=model.getState().getValue();assertNotNull(state.selectedStop);
    assertEquals(stop.sourceId,state.selectedStop.sourceId);assertEquals(stop.name,state.selectedStop.name);
    assertEquals(stop.platform,state.selectedStop.platform);assertEquals(stop.parentId,state.selectedStop.parentId);
    assertFalse(state.packInfo.authenticated);assertEquals(ImportMode.USER_UNTRUSTED,state.packInfo.importMode);
    assertEquals("owned query",model.getQueryText());
  }
  @Test public void differentPayloadCannotReinterpretSavedNumericId()throws Exception {
    String other=hash.charAt(0)=='0'?"1"+hash.substring(1):"0"+hash.substring(1);
    model.refreshPackState(new TimetableViewModel.SavedState("owned query",stop.id,stop.name),other);await();
    var state=model.getState().getValue();assertNull(state.selectedStop);assertTrue(state.board.isEmpty());
    assertEquals(hash,state.packInfo.databaseSha256);assertFalse(state.packInfo.authenticated);
    assertEquals("owned query",model.getQueryText());
  }
  @Test public void legacySavedSelectionWithoutPayloadIdentityIsDropped()throws Exception {
    model.refreshPackState(new TimetableViewModel.SavedState("legacy query",stop.id,stop.name));await();
    assertNull(model.getState().getValue().selectedStop);assertTrue(model.getState().getValue().board.isEmpty());
    assertEquals("legacy query",model.getQueryText());
  }
  @Test public void unavailableStopCannotBecomeAnEmptyPlaceholderAndHealthyReentryStillWorks()throws Exception {
    try{repository.restoreBoard(Long.MAX_VALUE,hash,Instant.parse("2026-10-21T08:00:00Z"),20,180);fail("missing stop restored");}
    catch(java.io.IOException expected){}
    var restored=repository.restoreBoard(stop.id,hash,Instant.parse("2026-10-21T08:00:00Z"),20,180);
    assertEquals(stop.sourceId,restored.stop.sourceId);assertEquals(stop.name,restored.stop.name);
    assertEquals(hash,restored.info.databaseSha256);assertFalse(restored.info.authenticated);
  }
}
