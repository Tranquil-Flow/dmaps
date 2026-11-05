package app.organicmaps.transit;

import android.app.Application;
import android.os.Looper;
import app.organicmaps.R;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real session/controller lifecycle; recording renderer boundary, NOT JNI/GPU acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,manifest=Config.NONE)
public class WalkingMapPreviewControllerTest {
  private WalkingMapPreviewSession session;private WalkingMapPreviewController controller;
  private final AtomicInteger released=new AtomicInteger();
  private final Host host=new Host();
  private Consumer<Boolean> held;
  private static final class Line implements WalkingMapPreviewController.Overlay {
    boolean current=true;int closes;
    public boolean isCurrent(){return current;}
    public void close(){closes++;current=false;}
  }
  private static final class Host implements WalkingMapPreviewController.Host {
    boolean ready=true,idle=true,refuse;int shows,closed,status;final Line line=new Line();double[] points;
    public boolean ready(){return ready;}public boolean idle(){return idle;}
    public WalkingMapPreviewController.Overlay show(double[] geometry){shows++;points=geometry;return refuse?null:line;}
    public void status(int value){status=value;}public void closed(int value){closed++;}
  }
  private void begin(WalkingMapPreviewSession.Validator validator){
    double[] geometry={52.5,13.4,52.5001,13.4001};
    session=new WalkingMapPreviewSession(geometry,validator,released::incrementAndGet);geometry[0]=0;
    String token=session.token();assertNull(WalkingMapPreviewSession.claim("invalid"));
    assertSame(session,WalkingMapPreviewSession.claim(token));assertNull(WalkingMapPreviewSession.claim(token));
    controller=new WalkingMapPreviewController(session,host);controller.resume();
  }
  @After public void end(){if(controller!=null)controller.close();if(session!=null)session.close();}
  @Test public void noPresentationUntilCurrencyReturnsAndCloseIsScopedIdempotent(){
    begin(answer->held=answer);assertEquals(0,host.shows);held.accept(true);
    assertEquals(1,host.shows);assertEquals(52.5,host.points[0],0);assertEquals(R.string.journey_map_scope,host.status);
    controller.close();controller.close();assertEquals(1,host.line.closes);assertEquals(1,released.get());assertEquals(1,host.closed);
  }
  @Test public void failedCurrencyNeverPresents(){begin(answer->answer.accept(false));assertEquals(0,host.shows);assertFalse(session.isOpen());assertEquals(1,released.get());}
  @Test public void navigationAdmissionAndLateNavigationDoNotPresent(){begin(answer->held=answer);host.idle=false;held.accept(true);assertEquals(0,host.shows);assertFalse(session.isOpen());}
  @Test public void routeStartedAfterPresentationRemovesOnlyThePreview(){begin(answer->answer.accept(true));host.idle=false;Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(1,host.line.closes);assertFalse(session.isOpen());assertFalse(host.idle);}
  @Test public void closeOrTimeoutBeforeCallbackCannotResurrect(){begin(answer->held=answer);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));assertFalse(session.isOpen());held.accept(true);assertEquals(0,host.shows);assertEquals(1,released.get());}
  @Test public void sourceInvalidationAfterPresentationRemovesLine(){AtomicInteger calls=new AtomicInteger();begin(answer->answer.accept(calls.incrementAndGet()==1));Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(1,host.line.closes);assertFalse(session.isOpen());}
  @Test public void rendererLossAndAdmissionRefusalFailClosed(){host.refuse=true;begin(answer->answer.accept(true));assertEquals(1,host.shows);assertFalse(session.isOpen());assertEquals(1,released.get());}
  @Test public void absentRendererHasBoundedReadinessWithoutDrawing(){host.ready=false;begin(answer->answer.accept(true));Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));assertEquals(0,host.shows);assertFalse(session.isOpen());}
  @Test public void expiredUnclaimedAndSupersededCapabilitiesAreNotReplayable(){
    session=new WalkingMapPreviewSession(new double[]{1,1,2,2},answer->answer.accept(true),released::incrementAndGet);String old=session.token();
    WalkingMapPreviewSession newer=new WalkingMapPreviewSession(new double[]{3,3,4,4},answer->answer.accept(true),released::incrementAndGet);
    try{assertNull(WalkingMapPreviewSession.claim(old));session.close();assertTrue(newer.isOpen());Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WalkingMapPreviewSession.LIFETIME_MILLIS));assertNull(WalkingMapPreviewSession.claim(newer.token()));assertFalse(newer.isOpen());assertEquals(2,released.get());}finally{newer.close();}
  }
}
