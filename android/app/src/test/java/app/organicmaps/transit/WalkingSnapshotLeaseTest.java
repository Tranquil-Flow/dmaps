package app.organicmaps.transit;

import app.organicmaps.sdk.transit.MappedWalking;
import app.organicmaps.sdk.transit.RegisteredWalkingMaps;
import java.io.File;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Worker ownership/refcount/mode tests. Native map safety is NOT mocked into physical proof. */
public class WalkingSnapshotLeaseTest {
  private Class<?> type;private RegisteredWalkingMaps.Candidate candidate;private MappedWalking provider;
  private final java.util.List<Object> leases=new java.util.ArrayList<>();
  @Before public void setup() throws Exception {
    try {type=Class.forName("app.organicmaps.transit.WalkingSnapshotLease");}
    catch(ClassNotFoundException e){fail("walking snapshot lease feature absent");}
    candidate=mock(RegisteredWalkingMaps.Candidate.class);provider=mock(MappedWalking.class);
    when(provider.isCurrent()).thenReturn(true);
    when(candidate.open(any(),any(),anyLong())).thenReturn(provider);
  }
  private Object acquire(RegisteredWalkingMaps.Candidate c)throws Exception {
    Method m=type.getDeclaredMethod("acquire",RegisteredWalkingMaps.Candidate.class,File.class,long.class);m.setAccessible(true);
    Object l=m.invoke(null,c,new File("/owned-test-cache"),128L*1024*1024);if(l!=null)leases.add(l);return l;
  }
  private Object call(Object l,String name)throws Exception {Method m=type.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(l);}
  @After public void cleanup()throws Exception {for(Object l:leases)call(l,"close");}
  @Test public void opensAnInodeAliasRatherThanCopyingTheMap()throws Exception {
    assertNotNull(acquire(candidate));verify(candidate).open(any(),eq(MappedWalking.SnapshotMode.HARD_LINK),anyLong());
    verify(candidate,never()).open(any(),eq(MappedWalking.SnapshotMode.PRIVATE_COPY),anyLong());
  }
  @Test public void sameWorkerSharesOneSnapshotUntilTheLastLeaseEnds()throws Exception {
    Object a=acquire(candidate),b=acquire(candidate);assertNotNull(a);assertNotNull(b);
    verify(candidate,times(1)).open(any(),any(),anyLong());call(a,"close");verify(provider,never()).close();
    assertSame(provider,call(b,"provider"));call(b,"close");verify(provider,times(1)).close();
    call(b,"close");verify(provider,times(1)).close();
  }
  @Test public void aChangedMapCannotBeBorrowedWhileAnOldLeaseLives()throws Exception {
    Object a=acquire(candidate);when(provider.isCurrent()).thenReturn(false);
    assertNull("stale generation is never reused",acquire(candidate));verify(provider,never()).close();
    call(a,"close");verify(provider).close();assertNotNull(acquire(candidate));verify(candidate,times(2)).open(any(),any(),anyLong());
  }
  @Test public void aDifferentWorkerCannotBorrowOrCloseTheOwnerSnapshot()throws Exception {
    Object a=acquire(candidate);AtomicReference<Object> answer=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
    Thread t=new Thread(()->{try{answer.set(acquire(candidate));call(a,"close");}catch(Throwable e){error.set(e);}});t.start();t.join(5000);assertFalse(t.isAlive());
    assertNull(answer.get());assertNotNull("foreign close must refuse",error.get());verify(provider,never()).close();
  }
  @Test public void equivalentFreshInventoryDescriptorsShareTheCurrentSnapshot()throws Exception {
    var ctor=RegisteredWalkingMaps.Candidate.class.getDeclaredConstructor(String.class,String.class,long.class,long.class,double.class,double.class,double.class,double.class);ctor.setAccessible(true);
    RegisteredWalkingMaps.Candidate a=spy(ctor.newInstance("/owned-map","Berlin",1L,4096L,52.0,13.0,53.0,14.0));
    RegisteredWalkingMaps.Candidate b=ctor.newInstance("/owned-map","Berlin",1L,4096L,52.0,13.0,53.0,14.0);
    doReturn(provider).when(a).open(any(),any(),anyLong());
    assertNotNull(acquire(a));assertNotNull(acquire(b));verify(a,times(1)).open(any(),any(),anyLong());
    RegisteredWalkingMaps.Candidate changed=ctor.newInstance("/owned-map","Berlin",2L,4096L,52.0,13.0,53.0,14.0);
    assertNull("a new map generation cannot borrow the old provider",acquire(changed));
    RegisteredWalkingMaps.Candidate relocated=ctor.newInstance("/another-map","Berlin",1L,4096L,52.0,13.0,53.0,14.0);
    assertNull("same metadata from another source path is not the same generation",acquire(relocated));
  }
  @Test public void refusedOpenLeavesNoProcessSlotBehind()throws Exception {
    when(candidate.open(any(),any(),anyLong())).thenReturn(null);assertNull(acquire(candidate));
    when(candidate.open(any(),any(),anyLong())).thenReturn(provider);assertNotNull(acquire(candidate));
  }
  @Test public void closedLeaseDoesNotExposeItsProvider()throws Exception {
    Object a=acquire(candidate);call(a,"close");
    try{call(a,"provider");fail("closed lease must refuse");}catch(InvocationTargetException e){assertTrue(e.getCause() instanceof IllegalStateException);}
  }
  @Test public void hardLinkRefusalHasAnExplicitBoundedPrivateCopyFallback()throws Exception {
    when(candidate.open(any(),eq(MappedWalking.SnapshotMode.HARD_LINK),anyLong())).thenReturn(null);
    assertNotNull(acquire(candidate));verify(candidate).open(any(),eq(MappedWalking.SnapshotMode.PRIVATE_COPY),eq(128L*1024*1024));
  }
}
