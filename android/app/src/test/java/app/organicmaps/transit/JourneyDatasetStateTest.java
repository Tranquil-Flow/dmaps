package app.organicmaps.transit;
import android.app.Application;
import android.os.Looper;
import app.organicmaps.sdk.transit.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Real worker/repository/SQLite checks: header metadata cannot outlive source authority. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class JourneyDatasetStateTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 private JourneyViewModel model;private JourneyUiFixture fixture;private ExecutorService worker;
 static final class Time extends Clock {Instant value=Instant.parse("2026-10-21T08:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return value;}}
 private final Time clock=new Time();
 @Before public void setup()throws Exception {fixture=new JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),clock);worker=Executors.newSingleThreadExecutor();model=new JourneyViewModel(RuntimeEnvironment.getApplication(),fixture.repository,worker,clock);model.refresh();await(()->!model.getState().getValue().busy);}
 private Object fact(String name)throws Exception {try{var m=JourneyViewModel.class.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(model);}catch(NoSuchMethodException e){throw new AssertionError("dataset facts feature absent");}}
 private void await(BooleanSupplier ready)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idle();if(ready.getAsBoolean())return;Thread.sleep(5);}fail("bounded host readiness");}
 @After public void close()throws Exception {model.onCleared();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
 @Test public void failedSourceReadRetiresThePreviouslyReadyHeader()throws Exception {
  assertNotNull(fact("datasetPack"));fixture.repository.removePack();model.search(true,"Alexanderplatz");await(()->!model.getState().getValue().busy);
  assertFalse("a failed source read must retire ready header authority",(Boolean)fact("datasetResolved"));assertNull("old pack metadata must not read as current",fact("datasetPack"));
 }
 @Test public void aSearchOnTheNewGenerationUpdatesTheHeaderIdentity()throws Exception {
  PackInfo before=(PackInfo)fact("datasetPack");fixture.replaceLabels();model.search(true,"Alexanderplatz");await(()->!model.getState().getValue().busy);
  assertNotNull(model.getState().getValue().info);assertNotEquals(before.databaseSha256,model.getState().getValue().info.databaseSha256);
  assertEquals("header must follow the same generation as the completed search",model.getState().getValue().info.databaseSha256,((PackInfo)fact("datasetPack")).databaseSha256);
 }
 @Test public void explicitExpiryRecheckKeepsExpiredMetadataInsteadOfClaimingNoPack()throws Exception {
  clock.value=Instant.parse("2027-01-03T00:00:00Z");model.refresh();await(()->!model.getState().getValue().busy);
  assertTrue("an expired but readable pack is a resolved observation",(Boolean)fact("datasetResolved"));assertNotNull("expired pack must still have header metadata",fact("datasetPack"));assertTrue(((PackInfo)fact("datasetPack")).isExpired(clock.instant()));
 }
}
