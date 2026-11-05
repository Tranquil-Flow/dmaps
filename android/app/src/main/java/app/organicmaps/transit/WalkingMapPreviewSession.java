package app.organicmaps.transit;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.UUID;
import java.util.function.Consumer;

/** One process-local, one-shot handoff. Neither geometry nor a native provider goes in an Intent.
 * The originating ViewModel retains its existing serial-worker provider, not a second map copy.
 * Currency is a completed observation, not a filesystem lease or a painted-frame guarantee. */
public final class WalkingMapPreviewSession implements AutoCloseable {
  public static final String EXTRA_TOKEN="app.organicmaps.transit.WALK_PREVIEW_TOKEN";
  public static final String EXTRA_RETURN_TOKEN="app.organicmaps.transit.WALK_RETURN_TOKEN";
  static final long LIFETIME_MILLIS=300_000;
  // One explicit return, IDs only. No itinerary, geometry, provider, model or Activity survives here.
  // Owner decision 2026-10-28 (option b): the platforms survive the 5-minute line lifetime and live in
  // process memory until consumed, replaced by a newer preview, or refused by a changed timetable/source.
  static final class ReturnSelection {
    final long fromId,toId;final String fromSource,toSource,envelope;
    ReturnSelection(JourneyViewModel.Endpoint from,JourneyViewModel.Endpoint to){
      fromId=from.stop.id;toId=to.stop.id;fromSource=from.stop.sourceId;toSource=to.stop.sourceId;
      envelope=from.selection.packEnvelopeSha256;
      if(!envelope.equals(to.selection.packEnvelopeSha256))throw new IllegalArgumentException("return generation mismatch");
    }
  }
  private static ReturnSelection pendingReturn;
  private static String returnToken;
  private static void clearReturn(){pendingReturn=null;returnToken=null;}
  private static String park(ReturnSelection selection){clearReturn();pendingReturn=selection;return returnToken=UUID.randomUUID().toString();}
  public static void cancelReturn(String token){requireMain();if(token!=null&&token.equals(returnToken))clearReturn();}
  static ReturnSelection takeReturn(String token){
    requireMain();
    if(token==null||!token.equals(returnToken))return null;
    ReturnSelection selection=pendingReturn;clearReturn();return selection;
  }
  interface Validator { void check(Consumer<Boolean> answer); }
  private static WalkingMapPreviewSession current;
  private final Handler main=new Handler(Looper.getMainLooper());
  private final String token=UUID.randomUUID().toString();
  private final double[] geometry;
  private final java.util.List<app.organicmaps.sdk.transit.MappedWalking.Direction> directions;
  private final app.organicmaps.sdk.transit.PackInfo pack;
  private final Validator validator;
  private final Runnable release;
  private final long expires=SystemClock.elapsedRealtime()+LIFETIME_MILLIS;
  private final Runnable expiry=this::close;
  private Runnable listener;
  private boolean closed,claimed,returning,invalidated;
  private ReturnSelection selection,retained;
  private Consumer<String> returnAnswer;

  WalkingMapPreviewSession(double[] geometry,Validator validator,Runnable release){this(geometry,java.util.Collections.emptyList(),null,validator,release);}
  WalkingMapPreviewSession(app.organicmaps.sdk.transit.JourneyPlanner.Walk walk,app.organicmaps.sdk.transit.PackInfo pack,Validator validator,Runnable release){
    this(walk.latLonGeometry(),walk.directionsAvailable?walk.directions():java.util.Collections.emptyList(),pack,validator,release);
  }
  WalkingMapPreviewSession(app.organicmaps.sdk.transit.JourneyPlanner.Walk walk,app.organicmaps.sdk.transit.PackInfo pack,
      JourneyViewModel.Endpoint from,JourneyViewModel.Endpoint to,Validator validator,Runnable release){
    this(walk,pack,validator,release);selection=new ReturnSelection(from,to);
  }
  private WalkingMapPreviewSession(double[] geometry,java.util.List<app.organicmaps.sdk.transit.MappedWalking.Direction> directions,app.organicmaps.sdk.transit.PackInfo pack,Validator validator,Runnable release){
    requireMain();this.pack=pack;this.directions=java.util.List.copyOf(directions);
    if(geometry==null||geometry.length<4||geometry.length>65_536||(geometry.length&1)!=0)
      throw new IllegalArgumentException("unsupported preview geometry");
    this.geometry=geometry.clone();
    for(int i=0;i<this.geometry.length;i++)if(!Double.isFinite(this.geometry[i])||Math.abs(this.geometry[i])>(i%2==0?90:180))
      throw new IllegalArgumentException("unsupported preview geometry");
    this.validator=java.util.Objects.requireNonNull(validator);this.release=java.util.Objects.requireNonNull(release);
    clearReturn();if(current!=null)current.close();current=this;main.postDelayed(expiry,LIFETIME_MILLIS);
  }
  static void requireMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("preview requires main thread");}
  String token(){requireMain();return token;}
  double[] geometry(){requireMain();return geometry.clone();}
  public String trust(android.content.Context context){
    requireMain();if(pack==null)return "";
    return pack.authenticated?context.getString(app.organicmaps.R.string.transit_authenticated_pack,pack.signingKeyId,pack.officialSequence,pack.authorizationExpires.toString()):context.getString(app.organicmaps.R.string.transit_unsigned_pack_disclosure);
  }
  /** Genuine native step events only; never derive turns from outline angles. */
  public String instructions(android.content.Context context){
    requireMain();if(!isOpen())return "";StringBuilder text=new StringBuilder(context.getString(app.organicmaps.R.string.journey_walk_access));
    if(pack!=null)text.append('\n').append(JourneyActivity.bounded(pack.publisher)).append(" · ").append(JourneyActivity.bounded(pack.attribution)).append(" · ").append(JourneyActivity.bounded(pack.license));
    text.append('\n').append(context.getString(app.organicmaps.R.string.journey_walk_directions_scope));int step=0;
    for(app.organicmaps.sdk.transit.MappedWalking.Direction d:directions){
      int label;switch(d.maneuver){
        case GO_STRAIGHT:label=app.organicmaps.R.string.journey_walk_direction_straight;break;
        case RIGHT:label=app.organicmaps.R.string.journey_walk_direction_right;break;
        case LEFT:label=app.organicmaps.R.string.journey_walk_direction_left;break;
        case FINISH:label=app.organicmaps.R.string.journey_walk_direction_finish;break;
        default:throw new IllegalArgumentException("unsupported pedestrian instruction");
      }
      text.append('\n').append(context.getString(app.organicmaps.R.string.journey_walk_direction_step,++step,Math.round(d.distanceFromStartMeters),context.getString(label)));
    }
    if(directions.isEmpty())text.append('\n').append(context.getString(app.organicmaps.R.string.journey_walk_directions_unavailable));return text.toString();
  }
  public boolean isOpen(){requireMain();if(!closed&&SystemClock.elapsedRealtime()>=expires)close();return !closed;}
  public static WalkingMapPreviewSession claim(String token){
    requireMain();WalkingMapPreviewSession s=current;
    if(s==null||token==null||!s.token.equals(token)||s.claimed||!s.isOpen())return null;
    s.claimed=true;return s;
  }
  public static void cancelPending(String token){
    requireMain();if(current!=null&&!current.claimed&&current.token.equals(token))current.close();
  }
  void onClose(Runnable listener){requireMain();if(!isOpen())listener.run();else this.listener=listener;}
  public void validate(Consumer<Boolean> answer){
    requireMain();if(!isOpen()){answer.accept(false);return;}
    try{validator.check(ok->{
      Runnable deliver=()->{boolean current=Boolean.TRUE.equals(ok);if(!current)invalidated=true;boolean valid=current&&isOpen();if(!valid)close();answer.accept(valid);};
      if(Looper.myLooper()==Looper.getMainLooper())deliver.run();else main.post(deliver);
    });}catch(RuntimeException unavailable){invalidated=true;close();answer.accept(false);}
  }
  /** Recheck the still-owned source before exporting only re-plannable endpoint identity. */
  void prepareReturn(Consumer<String> answer){
    requireMain();if(returning||!claimed){answer.accept(null);return;}
    // Expired line: the source is already released, so export only the retained platform identities.
    // The Journey model re-reads them under the exact pack envelope and current-pack check before use.
    if(!isOpen()){ReturnSelection kept=retained;retained=null;answer.accept(kept==null?null:park(kept));return;}
    returning=true;returnAnswer=answer;
    validate(ok->{
      if(!returning)return;returning=false;returnAnswer=null;String nonce=null;
      if(ok&&isOpen()&&selection!=null)nonce=park(selection);
      else if(!invalidated&&retained!=null)nonce=park(retained);
      close();retained=null;answer.accept(nonce);
    });
  }
  @Override public void close(){
    requireMain();if(closed)return;closed=true;main.removeCallbacks(expiry);if(current==this)current=null;
    // A changed timetable/source keeps nothing; a timeout or presentation close keeps the platforms.
    ReturnSelection kept=invalidated?null:selection;selection=null;Runnable old=listener;listener=null;
    Consumer<String> waiting=returnAnswer;returnAnswer=null;returning=false;
    if(waiting==null)retained=kept;
    try{if(old!=null)old.run();}finally{
      try{release.run();}finally{if(waiting!=null)waiting.accept(kept==null?null:park(kept));}
    }
  }
}
