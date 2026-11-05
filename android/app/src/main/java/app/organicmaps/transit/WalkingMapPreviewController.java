package app.organicmaps.transit;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import app.organicmaps.R;

/** Main-thread presentation over the existing map. No native provider or I/O lives here.
 * Checks are serial/non-backlogging; a five-second unanswered check fails closed. */
public final class WalkingMapPreviewController implements AutoCloseable {
  /** Shared actual map-panel binding; ordinary Button text is its name, expanded/collapsed is its state. */
  public static void bindInstructionsToggle(android.widget.Button button,android.view.View instructions){
    WalkingMapPreviewSession.requireMain();java.util.Objects.requireNonNull(button);java.util.Objects.requireNonNull(instructions);
    java.util.function.Consumer<Boolean> state=open->androidx.core.view.ViewCompat.setStateDescription(button,
        button.getContext().getString(open?R.string.journey_details_expanded:R.string.journey_details_collapsed));
    state.accept(instructions.getVisibility()==android.view.View.VISIBLE);
    button.setOnClickListener(v->{if(!button.isEnabled())return;boolean open=instructions.getVisibility()!=android.view.View.VISIBLE;
      instructions.setVisibility(open?android.view.View.VISIBLE:android.view.View.GONE);state.accept(open);});
  }
  public interface Overlay extends AutoCloseable { boolean isCurrent();void close(); }
  public interface Host {
    boolean ready();boolean idle();Overlay show(double[] geometry);
    void status(int stringResource);void closed(int stringResource);
  }
  private final WalkingMapPreviewSession session;
  private final Host host;
  private final Handler main=new Handler(Looper.getMainLooper());
  private final long started=SystemClock.elapsedRealtime();
  private Overlay overlay;
  private boolean closed,resumed,checking;
  private long checkingSince;
  private final Runnable tick=this::tick;

  public WalkingMapPreviewController(WalkingMapPreviewSession session,Host host){
    WalkingMapPreviewSession.requireMain();this.session=java.util.Objects.requireNonNull(session);this.host=java.util.Objects.requireNonNull(host);
  }
  public void resume(){WalkingMapPreviewSession.requireMain();if(closed||resumed)return;resumed=true;session.onClose(()->finish(R.string.journey_map_unavailable));if(!closed)tick();}
  private void tick(){
    WalkingMapPreviewSession.requireMain();if(closed||!resumed)return;
    try{
      if(!session.isOpen()||!host.idle()){finish(R.string.journey_map_unavailable);return;}
      if(!host.ready()){
        if(overlay!=null||SystemClock.elapsedRealtime()-started>=10_000){finish(R.string.journey_map_unavailable);return;}
        host.status(R.string.transit_working);
      }else if(overlay!=null&&!overlay.isCurrent()){finish(R.string.journey_map_unavailable);return;}
      else if(checking){if(SystemClock.elapsedRealtime()-checkingSince>=5_000){finish(R.string.journey_map_unavailable);return;}}
      else{
        checking=true;checkingSince=SystemClock.elapsedRealtime();
        session.validate(ok->{
          if(closed||!resumed)return;checking=false;
          try{
            if(!ok||!session.isOpen()||!host.ready()||!host.idle()){finish(R.string.journey_map_unavailable);return;}
            if(overlay==null)overlay=host.show(session.geometry());
            if(overlay==null||!overlay.isCurrent()){finish(R.string.journey_map_unavailable);return;}
            host.status(R.string.journey_map_scope);
          }catch(RuntimeException|LinkageError unavailable){finish(R.string.journey_map_unavailable);}
        });
      }
      if(!closed)main.postDelayed(tick,1000);
    }catch(RuntimeException|LinkageError unavailable){finish(R.string.journey_map_unavailable);}
  }
  private void finish(int reason){
    if(closed)return;closed=true;resumed=false;main.removeCallbacks(tick);Overlay old=overlay;overlay=null;
    try{if(old!=null)old.close();}catch(RuntimeException|LinkageError ignored){/* Never retain the source after presentation failure. */}
    finally{try{session.close();}finally{host.closed(reason);}}
  }
  public void prepareReturn(java.util.function.Consumer<String> answer){
    // After the line closes, the session still owns the retained platform identities (or nothing, if invalidated).
    WalkingMapPreviewSession.requireMain();session.prepareReturn(answer);
  }
  @Override public void close(){WalkingMapPreviewSession.requireMain();finish(0);}
}
