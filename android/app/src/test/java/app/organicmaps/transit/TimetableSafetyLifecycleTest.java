package app.organicmaps.transit;
import android.app.Application;
import android.os.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.lifecycle.*;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Source review findings adjudicated at real rider rendering/lifecycle boundaries. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class TimetableSafetyLifecycleTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 public static class Screen extends TimetableActivity {
  static TimetableViewModel model;static Instant now=Instant.parse("2026-10-28T10:00:00Z");
  @Override Instant presentationNow(){return now;}
  @Override public ViewModelProvider.Factory getDefaultViewModelProviderFactory(){return new ViewModelProvider.Factory(){@NonNull public <T extends ViewModel>T create(@NonNull Class<T> c){return c.cast(model);}};}
 }
 private JourneyUiFixture fixture;private ActivityController<Screen> controller;
 private final List<TimetableViewModel> models=new ArrayList<>();private final List<ExecutorService> workers=new ArrayList<>();
 @Before public void setup()throws Exception {fixture=new JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"),ZoneOffset.UTC));Screen.now=Instant.parse("2026-10-28T10:00:00Z");}
 private TimetableViewModel model() {ExecutorService w=Executors.newSingleThreadExecutor();workers.add(w);TimetableViewModel m=new TimetableViewModel(RuntimeEnvironment.getApplication(),fixture.repository,w);models.add(m);return m;}
 private void settle(TimetableViewModel m)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);do{Shadows.shadowOf(Looper.getMainLooper()).idle();if(m.getState().getValue().status!=TimetableViewModel.Status.WORKING)return;Thread.sleep(5);}while(System.nanoTime()<end);fail("bounded readiness");}
 private Screen open(TimetableViewModel m,Bundle saved){Screen.model=m;controller=Robolectric.buildActivity(Screen.class);Screen a=controller.get();a.setTheme(R.style.MwmTheme);controller.create(saved).start().resume().visible();return a;}
 @After public void close()throws Exception {if(controller!=null)controller.pause().stop().destroy();for(TimetableViewModel m:models)m.onCleared();for(ExecutorService w:workers)assertTrue(w.awaitTermination(10,TimeUnit.SECONDS));Screen.model=null;}
 @Test public void replacementWhileAwayIsRecheckedBeforeCandidatesCanBeUsed()throws Exception {
  TimetableViewModel m=model();open(m,null);settle(m);m.searchStops("Alexanderplatz");settle(m);assertFalse(m.getState().getValue().candidates.isEmpty());String old=m.getState().getValue().packInfo.databaseSha256;
  controller.pause().stop();fixture.replaceLabels();controller.start().resume().visible();settle(m);
  assertNotEquals("resume must observe the replaced pack generation",old,m.getState().getValue().packInfo.databaseSha256);
  assertTrue("candidate IDs from the previous pack must be retired",m.getState().getValue().candidates.isEmpty());
 }
 @Test public void failedImportDisclosureSurvivesAnActivityRecreation()throws Exception {
  TimetableViewModel m=model();open(m,null);settle(m);TimetableViewModel.ScreenState state=m.getState().getValue();state.importFailed=true;state.revision++;((MutableLiveData<TimetableViewModel.ScreenState>)m.getState()).setValue(state);
  Bundle saved=new Bundle();controller.saveInstanceState(saved).pause().stop().destroy();controller=null;
  TimetableViewModel next=model();open(next,saved);settle(next);
  assertTrue("rotation must not silently erase the last failed-import disclosure",next.getState().getValue().importFailedFlag());
 }
 @Test public void recreatedWorkingSearchRestoresQueryButNoAbandonedResults()throws Exception {
  TimetableViewModel m=model();open(m,null);settle(m);ExecutorService w=workers.get(workers.size()-1);
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);w.submit(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(entered.await(10,TimeUnit.SECONDS));
  Bundle saved=new Bundle();try{m.searchStops("Alexanderplatz");assertEquals(TimetableViewModel.Status.WORKING,m.getState().getValue().status);controller.saveInstanceState(saved).pause().stop().destroy();controller=null;}finally{release.countDown();}
  assertTrue(w.awaitTermination(10,TimeUnit.SECONDS));TimetableViewModel next=model();open(next,saved);settle(next);assertEquals("Alexanderplatz",next.getQueryText());assertTrue(next.getState().getValue().candidates.isEmpty());assertTrue(next.getState().getValue().board.isEmpty());assertNull(next.getState().getValue().selectedStop);
 }
 @Test public void restoredFailureDoesNotAttachToAReplacementGeneration()throws Exception {
  TimetableViewModel m=model();open(m,null);settle(m);var state=m.getState().getValue();state.importFailed=true;state.revision++;((MutableLiveData<TimetableViewModel.ScreenState>)m.getState()).setValue(state);
  Bundle saved=new Bundle();controller.saveInstanceState(saved).pause().stop().destroy();controller=null;fixture.replaceLabels();TimetableViewModel next=model();open(next,saved);settle(next);assertFalse("failure for the old payload must not label its replacement",next.getState().getValue().importFailedFlag());
 }
 @Test public void authorizationExpiryAlreadyRetiresCandidateRowsAtTheRealAdapterBoundary()throws Exception {
  TimetableViewModel fake=mock(TimetableViewModel.class);TimetableViewModel.ScreenState state=new TimetableViewModel.ScreenState();state.status=TimetableViewModel.Status.READY;state.packPresent=true;state.packInfo=VerifiedUiFixture.official();state.candidates=fixture.repository.searchStops("Alexanderplatz",50);
  MutableLiveData<TimetableViewModel.ScreenState> live=new MutableLiveData<>(state);when(fake.getState()).thenReturn(live);when(fake.getQueryText()).thenReturn("");
  Screen.now=state.packInfo.authorizationExpires.minusSeconds(1);Screen a=open(fake,null);Shadows.shadowOf(Looper.getMainLooper()).idle();
  var field=TimetableActivity.class.getDeclaredField("mList");field.setAccessible(true);RecyclerView list=(RecyclerView)field.get(a);assertTrue(list.getAdapter().getItemCount()>0);
  Screen.now=state.packInfo.authorizationExpires.plusSeconds(1);var update=TimetableActivity.class.getDeclaredMethod("updatePackTrust",TimetableViewModel.ScreenState.class);update.setAccessible(true);update.invoke(a,state);
  assertEquals("candidate and board rows use the same retired adapter",0,list.getAdapter().getItemCount());
 }
}
