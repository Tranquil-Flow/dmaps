package app.organicmaps.transit;

import android.app.Application;
import android.net.Uri;
import android.os.Looper;
import app.organicmaps.sdk.transit.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual ViewModel/handler transitions with controlled worker queue and synthetic SAF streams.
 * Repository is mocked here; separate PackRepositoryTrustTest exercises the real archive/SQLite path. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,manifest=Config.NONE)
public class TimetableImportStateTest {
  static final class Queue extends AbstractExecutorService {
    final ArrayDeque<Runnable> tasks=new ArrayDeque<>();boolean closed;
    public void execute(Runnable r){if(closed)throw new RejectedExecutionException();tasks.add(r);}
    public void shutdown(){closed=true;}
    public List<Runnable> shutdownNow(){closed=true;List<Runnable> out=new ArrayList<>(tasks);tasks.clear();return out;}
    public boolean isShutdown(){return closed;}
    public boolean isTerminated(){return closed&&tasks.isEmpty();}
    public boolean awaitTermination(long t,TimeUnit u){return isTerminated();}
    void drain(){while(!tasks.isEmpty())tasks.remove().run();Shadows.shadowOf(Looper.getMainLooper()).idle();}
  }
  private Queue queue;
  private TimetableRepository repository;
  private TimetableViewModel model;
  @Before public void setup(){queue=new Queue();repository=mock(TimetableRepository.class);model=new TimetableViewModel(RuntimeEnvironment.getApplication(),repository,queue);queue.drain();}
  @After public void cleanup(){if(model!=null)model.onCleared();queue.drain();assertTrue(queue.isTerminated());}
  private Uri stream(String name){Uri uri=Uri.parse("content://fixture.invalid/"+name);Shadows.shadowOf(RuntimeEnvironment.getApplication().getContentResolver()).registerInputStream(uri,new ByteArrayInputStream(name.getBytes(StandardCharsets.UTF_8)));return uri;}
  private PackInfo info(){return new PackInfo("fixture","fixture","CC BY 4.0","https://example.invalid",String.join("",Collections.nCopies(64,"0")),String.join("",Collections.nCopies(64,"0")),"2026-10-01T00:00:00Z","2026-01-01","2027-01-01","Europe/Berlin",0,86400);}
  @Test public void queuedImportsKeepTheirOwnUriAndExplicitMode()throws Exception {
    List<String> seen=new ArrayList<>();List<ImportMode> modes=new ArrayList<>();
    org.mockito.stubbing.Answer<PackInfo> record=invocation->{seen.add(new String(((InputStream)invocation.getArgument(0)).readAllBytes(),StandardCharsets.UTF_8));modes.add(invocation.getArguments().length==2?invocation.getArgument(1):ImportMode.USER_UNTRUSTED);return info();};
    when(repository.importPack(any(InputStream.class))).thenAnswer(record);when(repository.importPack(any(InputStream.class),any(ImportMode.class))).thenAnswer(record);
    model.importPack(stream("first"),ImportMode.OFFICIAL_REQUIRED);model.importPack(stream("second"),ImportMode.USER_UNTRUSTED);queue.drain();
    assertEquals(Arrays.asList("first","second"),seen);assertEquals(Arrays.asList(ImportMode.OFFICIAL_REQUIRED,ImportMode.USER_UNTRUSTED),modes);assertEquals(TimetableViewModel.Status.READY,model.getState().getValue().status);
  }
  @Test public void committedFailureCannotSayPreviousPackWasKeptOrRetainOldRows()throws Exception {
    java.lang.reflect.Constructor<PackCommitException> c=PackCommitException.class.getDeclaredConstructor(PackCommitException.Operation.class);c.setAccessible(true);
    PackCommitException committed=c.newInstance(PackCommitException.Operation.IMPORT);
    when(repository.importPack(any(InputStream.class))).thenThrow(committed);when(repository.importPack(any(InputStream.class),any(ImportMode.class))).thenThrow(committed);
    TimetableViewModel.ScreenState state=model.getState().getValue();state.packPresent=true;state.packInfo=info();state.board.add(null);state.candidates.add(null);
    model.importPack(stream("committed"));queue.drain();state=model.getState().getValue();
    assertFalse(state.importFailedFlag());assertEquals(TimetableViewModel.Status.ERROR,state.status);assertNull(state.packInfo);assertFalse(state.packPresent);assertTrue(state.board.isEmpty());assertTrue(state.candidates.isEmpty());assertNotNull(state.message);
  }
  @Test public void streamCloseFailureAfterRepositorySuccessIsCommittedNotRollback()throws Exception {
    Uri uri=Uri.parse("content://fixture.invalid/close-fails");
    Shadows.shadowOf(RuntimeEnvironment.getApplication().getContentResolver()).registerInputStream(uri,new ByteArrayInputStream(new byte[]{1}){
      @Override public void close()throws IOException {throw new IOException("synthetic close failure");}
    });
    when(repository.importPack(any(InputStream.class),any(ImportMode.class))).thenReturn(info());
    model.importPack(uri);queue.drain();TimetableViewModel.ScreenState state=model.getState().getValue();assertFalse(state.importFailedFlag());assertEquals(TimetableViewModel.Status.ERROR,state.status);assertNull(state.packInfo);assertNotNull(state.message);
  }

  @Test public void unavailableAuthorityKeepsRegisteredPackRemovableButDropsReadyMetadata()throws Exception {
    when(repository.hasPack()).thenReturn(true);when(repository.hasPackChecked()).thenReturn(true);when(repository.info()).thenThrow(new IOException("synthetic unavailable authority"));
    TimetableViewModel.ScreenState old=model.getState().getValue();old.packPresent=true;old.packInfo=info();old.board.add(null);
    model.refreshPackState(null);queue.drain();TimetableViewModel.ScreenState state=model.getState().getValue();assertEquals(TimetableViewModel.Status.ERROR,state.status);assertTrue(state.packPresent);assertNull(state.packInfo);assertTrue(state.board.isEmpty());
  }
  @Test public void unreadableHistoryIsAnErrorNotNoPack()throws Exception {
    when(repository.hasPack()).thenReturn(false);when(repository.hasPackChecked()).thenThrow(new IOException("synthetic corrupt history"));model.refreshPackState(null);queue.drain();assertEquals(TimetableViewModel.Status.ERROR,model.getState().getValue().status);
  }

  @Test public void clearingSchedulesCloseAfterAdmittedWorkRatherThanBlockingMain()throws Exception {
    when(repository.importPack(any(InputStream.class),any(ImportMode.class))).thenReturn(info());model.importPack(stream("pending"));TimetableViewModel retiring=model;model=null;
    retiring.onCleared();
    try { verify(repository,never()).close(); }
    catch (AssertionError failedGuard) { queue.shutdownNow(); throw failedGuard; }
    queue.drain();org.mockito.InOrder order=inOrder(repository);order.verify(repository).importPack(any(InputStream.class),eq(ImportMode.USER_UNTRUSTED));order.verify(repository).close();
  }

  @Test public void ordinaryPrecommitFailureKeepsPreviousPackAndRetainsFailureDisclosure()throws Exception {
    when(repository.importPack(any(InputStream.class))).thenThrow(new IOException("synthetic"));when(repository.importPack(any(InputStream.class),any(ImportMode.class))).thenThrow(new IOException("synthetic"));
    TimetableViewModel.ScreenState state=model.getState().getValue();state.packPresent=true;state.packInfo=info();PackInfo old=state.packInfo;
    model.importPack(stream("refused"));queue.drain();state=model.getState().getValue();assertTrue(state.importFailedFlag());assertSame(old,state.packInfo);assertTrue(state.packPresent);
  }
}
