package app.organicmaps.sdk.transit;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.UiThread;

/** Temporary street-map presentation, NOT route calculation or station-access proof.
 * The consumer must revalidate the originating map/pack before opening and while visible.
 * Uses no location, network, history, saved marks, or global navigation preparation.
 * Only the Framework/UI owner may use this lease. Always close on pause/supersession.
 * A five-minute UI-loop expiry is a backstop, not hard preemption or a continuing data lease.
 */
public final class WalkingMapOverlay implements AutoCloseable {
  static final long MAX_LIFETIME_MILLIS=300_000;
  interface NativeOps {long show(double[] points);boolean current(long token);void remove(long token);}
  private static final NativeOps NATIVE=new NativeOps(){
    public long show(double[] points){Library.ensureLoaded();return nativeShow(points);}
    public boolean current(long token){return nativeCurrent(token);}
    public void remove(long token){nativeRemove(token);}
  };
  private final NativeOps ops;
  private final Handler main=new Handler(Looper.getMainLooper());
  private final long expiresAt=SystemClock.elapsedRealtime()+MAX_LIFETIME_MILLIS;
  private final Runnable expire=this::close;
  private long handle;
  private WalkingMapOverlay(long token,NativeOps ops){
    this.ops=ops;handle=token;
    if(!main.postDelayed(expire,MAX_LIFETIME_MILLIS)){
      handle=0;ops.remove(token);throw new IllegalStateException("walking preview UI unavailable");
    }
  }
  /** Null means renderer unavailable or a route is already present; no route is replaced.
   * Input is [latitude,longitude,...], never inferred/simplified station links. */
  @UiThread public static WalkingMapOverlay show(double[] points){return show(points,NATIVE);}
  static WalkingMapOverlay show(double[] points,NativeOps ops){
    requireMain();validate(points);double[] copy=points.clone();
    long token=ops.show(copy);return token<=0?null:new WalkingMapOverlay(token,ops);
  }
  @UiThread public boolean isCurrent(){
    requireMain();if(handle==0)return false;
    if(SystemClock.elapsedRealtime()>=expiresAt||!ops.current(handle)){close();return false;}
    return true;
  }
  @Override @UiThread public void close(){
    requireMain();long token=handle;if(token==0)return;
    handle=0;main.removeCallbacks(expire);ops.remove(token);
  }
  private static void requireMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("walking preview requires Framework owner");}
  private static void validate(double[] points){
    if(points==null||points.length<4||points.length>65536||(points.length&1)!=0)throw new IllegalArgumentException("invalid walking preview geometry");
    boolean distinct=false;
    for(int i=0;i<points.length;i+=2){
      double lat=points[i],lon=points[i+1];
      if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85.0511287798066||lon< -180||lon>180||(i>0&&Math.abs(lon-points[i-1])>180))throw new IllegalArgumentException("invalid walking preview geometry");
      if(i>0&&(lat!=points[0]||lon!=points[1]))distinct=true;
    }
    if(!distinct)throw new IllegalArgumentException("invalid walking preview geometry");
  }
  private static final class Library {static {System.loadLibrary("organicmaps");}static void ensureLoaded(){}}
  private static native long nativeShow(double[] points);
  private static native boolean nativeCurrent(long token);
  private static native void nativeRemove(long token);
}
