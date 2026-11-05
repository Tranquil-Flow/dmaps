package app.organicmaps.sdk.transit;
import static org.junit.Assert.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import android.os.Looper;

/** Managed lease/validation controls; recording native boundary, NOT JNI/GPU proof. */
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35)
public class WalkingMapOverlayTest {
 static double[] points(){return new double[]{52.5,13.4,52.501,13.402,52.502,13.401};}
 static final class Native implements WalkingMapOverlay.NativeOps {
   long next=1,active;int shows,removes;boolean admit=true;double[] received;
   public long show(double[] p){shows++;received=p;return admit?(active=next++):0;}
   public boolean current(long token){return token==active;}
   public void remove(long token){removes++;if(active==token)active=0;}
 }
 @Test public void actualLeaseClonesInputAndClosesIdempotently(){
  Native n=new Native();double[] path=points();try(WalkingMapOverlay p=WalkingMapOverlay.show(path,n)){
   assertNotNull(p);path[0]=0;assertEquals(52.5,n.received[0],0);assertTrue(p.isCurrent());p.close();p.close();assertFalse(p.isCurrent());
  }assertEquals(1,n.removes);assertEquals(0,n.active);
 }
 @Test public void nativeRefusalReturnsNoLeaseAndSchedulesNoCleanup(){
  Native n=new Native();n.admit=false;assertNull(WalkingMapOverlay.show(points(),n));Shadows.shadowOf(Looper.getMainLooper()).idleFor(6,TimeUnit.MINUTES);assertEquals(0,n.removes);
 }
 @Test public void malformedAndOversizedGeometryNeverReachesNative(){
  Native n=new Native();
  double[][] malformed={null,new double[0],new double[]{1,2},new double[]{1,2,3},new double[]{Double.NaN,1,1,2},new double[]{1,Double.POSITIVE_INFINITY,1,2},new double[]{89,1,1,2},new double[]{1,181,1,2},new double[]{1,1,1,1},new double[]{1,-179,1,179},new double[65538]};
  for(double[] p:malformed)try{WalkingMapOverlay.show(p,n);fail();}catch(IllegalArgumentException expected){}
  assertEquals(0,n.shows);assertEquals(0,n.removes);
 }
 @Test public void foreignThreadCannotOpenOrClose(){
  Native n=new Native();WalkingMapOverlay p=WalkingMapOverlay.show(points(),n);AtomicReference<Throwable> error=new AtomicReference<>();
  Thread t=new Thread(()->{try{WalkingMapOverlay.show(points(),n);error.set(new AssertionError());}catch(IllegalStateException expected){}try{p.close();error.set(new AssertionError());}catch(IllegalStateException expected){}});
  t.start();try{t.join();}catch(InterruptedException e){throw new AssertionError(e);}assertNull(error.get());assertEquals(1,n.shows);assertEquals(0,n.removes);p.close();
 }
 @Test public void supersededOwnerAndExpiryCannotRemoveNewLease(){
  Native n=new Native();WalkingMapOverlay old=WalkingMapOverlay.show(points(),n);WalkingMapOverlay newer=WalkingMapOverlay.show(points(),n);long active=n.active;
  assertFalse(old.isCurrent());assertEquals(active,n.active);assertTrue(newer.isCurrent());old.close();assertEquals(active,n.active);newer.close();assertEquals(0,n.active);
 }
 @Test public void deadlineRetiresNativeLeaseWithoutPolling(){
  Native n=new Native();WalkingMapOverlay p=WalkingMapOverlay.show(points(),n);Shadows.shadowOf(Looper.getMainLooper()).idleFor(5,TimeUnit.MINUTES);
  assertEquals(0,n.active);assertEquals(1,n.removes);assertFalse(p.isCurrent());p.close();assertEquals(1,n.removes);
 }
 @Test public void expiredOldCallbackDoesNotRetireLaterOwner(){
  Native n=new Native();WalkingMapOverlay old=WalkingMapOverlay.show(points(),n);Shadows.shadowOf(Looper.getMainLooper()).idleFor(4,TimeUnit.MINUTES);WalkingMapOverlay next=WalkingMapOverlay.show(points(),n);long active=n.active;
  Shadows.shadowOf(Looper.getMainLooper()).idleFor(1,TimeUnit.MINUTES);assertEquals(active,n.active);assertFalse(old.isCurrent());assertTrue(next.isCurrent());next.close();
 }
}
