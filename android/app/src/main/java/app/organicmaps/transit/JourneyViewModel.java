package app.organicmaps.transit;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.*;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Ephemeral, local station/platform-ready preview. No imports, network, GPS, saved query history,
 * global navigation mutation or synthetic walking. All repository work is serialized off main.
 * The visible snapshot is refreshed while resumed, not a lease against external pack writers. */
public class JourneyViewModel extends AndroidViewModel {
  public static final class Endpoint {
    public final JourneyPlanner.Selection selection; public final Stop stop;
    Endpoint(JourneyPlanner.Selection selection,Stop stop){this.selection=selection;this.stop=stop;}
  }
  public static final class State {
    public final PackInfo info; public final Endpoint from,to;
    public final JourneyPlanner.Candidates candidates; public final boolean choosingFrom,busy;
    public final JourneyPlanner.Result result; public final int message;
    State(PackInfo info,Endpoint from,Endpoint to,JourneyPlanner.Candidates candidates,boolean choosingFrom,
          boolean busy,JourneyPlanner.Result result,int message){
      this.info=info;this.from=from;this.to=to;this.candidates=candidates;this.choosingFrom=choosingFrom;
      this.busy=busy;this.result=result;this.message=message;
    }
  }
  private final MutableLiveData<State> state=new MutableLiveData<>();
  private final ExecutorService worker; private final Clock clock;
  private final java.util.function.Supplier<RegisteredWalkingMaps.Inventory> walkingMaps;
  private final Handler main=new Handler(Looper.getMainLooper());
  private TimetableRepository repository; private Future<?> pending;
  private static final long WALK_COPY_LIMIT=128L*1024*1024;
  // Main owns the ticket; only its cancel method is cross-thread. Provider never crosses a Future.
  private WalkingSession walkingSession;
  private WalkingSnapshotLease walkingSnapshot; // Worker-only anchor; never outlives the resumed model/preview.
  private void closeWalkingSnapshotOnWorker(){WalkingSnapshotLease old=walkingSnapshot;walkingSnapshot=null;if(old!=null)old.close();}
  private void releaseWalkingSnapshot(){worker.execute(this::closeWalkingSnapshotOnWorker);}
  private WalkingMapPreviewSession mapPreview;
  private boolean preparingMap;
  private void closeMapPreview(){WalkingMapPreviewSession old=mapPreview;mapPreview=null;if(old!=null)old.close();}
  private static final class WalkingSession {
    private volatile boolean cancelled;private volatile MappedWalking cancellationTarget;
    private MappedWalking owned;private WalkingSnapshotLease lease; // Serial worker only.
    void cancel(){cancelled=true;MappedWalking p=cancellationTarget;if(p!=null)p.cancel();}
    void attachOnWorker(WalkingSnapshotLease l){lease=l;MappedWalking p=l.provider();owned=p;cancellationTarget=p;if(cancelled)p.cancel();}
    boolean currentOnWorker(String expected){return !cancelled&&owned!=null&&owned.mapSnapshotId.equals(expected)&&owned.isCurrent()&&!cancelled;}
    void closeOnWorker(){WalkingSnapshotLease l=lease;lease=null;owned=null;cancellationTarget=null;if(l!=null)l.close();}
  }
  private void retireWalking(){WalkingSession old=walkingSession;walkingSession=null;if(old!=null){old.cancel();worker.execute(old::closeOnWorker);}}
  private int generation; private boolean closed,checking,loadingPack,workerRetired;
  // Ephemeral, private code-boundary diagnostic for owned verification. Never retain Throwable,
  // message, file/method names, coordinates, input paths or arbitrary external class identity.
  private String lastWorkerFailureLocation;
  private static String failureLocation(Exception error){
    String kind=error instanceof java.io.InterruptedIOException?"INTERRUPTED_IO":error instanceof IOException?"IO":error instanceof IllegalArgumentException?"ARGUMENT":error instanceof IllegalStateException?"STATE":"OTHER";
    if(error instanceof IOException)for(MappedWalking.Status status:MappedWalking.Status.values())if(("mapped walking unavailable: "+status.name()).equals(error.getMessage())){kind="WALK_"+status.name();break;}
    StringBuilder text=new StringBuilder(kind);int count=0;
    for(StackTraceElement frame:error.getStackTrace()){
      String name=frame.getClassName(),code=null;
      if(name.equals("app.organicmaps.transit.JourneyViewModel"))code="VIEWMODEL";
      else if(name.equals("app.organicmaps.sdk.transit.MappedWalking$Result"))code="WALK_RESULT";
      else for(String[] known:new String[][]{{"TimetableRepository","REPOSITORY"},{"JourneyPlanner","PLANNER"},{"MappedWalking","WALK_SDK"},{"RegisteredWalkingMaps","REGISTRY"}}){String root="app.organicmaps.sdk.transit."+known[0];if(name.equals(root)||name.startsWith(root+"$")){code=known[1];break;}}
      if(code!=null){int line=frame.getLineNumber();text.append('|').append(code).append(':').append(line>=0&&line<=1_000_000?line:-1);if(++count==8)break;}
    }
    return text.toString();
  }
  private PackInfo info; private String currentEnvelope; private Endpoint from,to;
  public JourneyViewModel(Application app){this(app,null,Executors.newSingleThreadExecutor(r->{
    Thread t=new Thread(r,"dmaps-journey-ui-worker");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;
  }),Clock.systemUTC());}
  /** Controlled package-only test composition; never selected by Intent/pack fields. */
  JourneyViewModel(Application app,TimetableRepository repository,ExecutorService worker,Clock clock){this(app,repository,worker,clock,RegisteredWalkingMaps::capture);}
  JourneyViewModel(Application app,TimetableRepository repository,ExecutorService worker,Clock clock,java.util.function.Supplier<RegisteredWalkingMaps.Inventory> walkingMaps){
    super(app);this.walkingMaps=walkingMaps;this.repository=repository;this.worker=worker;this.clock=clock;
    TimingRing.init(app); // debug builds only: the gate is inside (the parameter name 'app' shadows the package here)
    publish(false,null,false,null,R.string.journey_choose);
    final java.io.File cache=walkingCache();worker.execute(()->sweepWalkingCacheOnce(cache));
  }
  private static final Object WALK_SWEEP_LOCK=new Object();private static boolean walkingCacheSwept;
  static void resetWalkingCacheSweepForTest(){synchronized(WALK_SWEEP_LOCK){walkingCacheSwept=false;}}
  private java.io.File walkingCache(){return new java.io.File(getApplication().getCacheDir(),"dmaps-journey-walking");}
  /** A force-stop, low-memory kill or crash while a plan holds its private walking map copy (~100 MB) leaves it
   * in cache; nothing else ever removed it. No snapshot outlives its process, so the first model of a process
   * removes leftovers once, before any snapshot of this process is opened (every opener runs this first, under
   * the same lock). Later models never touch snapshots opened in this process. Links are removed, not followed. */
  private static void sweepWalkingCacheOnce(java.io.File cache){
    synchronized(WALK_SWEEP_LOCK){if(walkingCacheSwept)return;walkingCacheSwept=true;removeChildren(cache);}
  }
  /** java.nio.file.Files.isSymbolicLink and File.toPath need API 26 (minSdk 21). A link's canonical path differs from its
   * canonicalised parent plus its own name. The parent is canonicalised first so real entries below a linked ancestor
   * (Android reaches app storage through /data/user/0 -> /data/data) are not misread as links. If canonicalisation
   * fails the entry counts as a link: it is deleted, never descended into (the safe side of the no-follow rule). */
  private static boolean isSymbolicLink(java.io.File f){
    try{
      java.io.File parent=f.getParentFile();
      java.io.File entry=parent==null?f:new java.io.File(parent.getCanonicalFile(),f.getName());
      return !entry.getCanonicalFile().equals(entry.getAbsoluteFile());
    }catch(java.io.IOException unresolvable){return true;}
  }
  private static void removeChildren(java.io.File dir){
    java.io.File[] children=dir.listFiles();if(children==null)return;
    for(java.io.File f:children){
      if(f.isDirectory()&&!isSymbolicLink(f))removeChildren(f);
      //noinspection ResultOfMethodCallIgnored -- best effort; a survivor is retried by the next process
      f.delete();
    }
  }
  public LiveData<State> getState(){return state;}
  private TimetableRepository repo(){if(repository==null)repository=new TimetableRepository(getApplication());return repository;}
  private boolean valid(PackInfo p){Instant now=clock.instant();return p!=null&&!p.isExpired(now)&&!p.isNotYetValid(now)
      &&(!p.authenticated||(p.authorizationExpires!=null&&now.isBefore(p.authorizationExpires)));}
  boolean displayable(State s){return s!=null&&valid(s.info);}
  // S1 header facts. State.info is dropped (discard) when a pack is expired, so the header reads the last pack this model
  // resolved instead. Written on main by refresh()/restoreWalkingReturn() only; read on main by JourneyActivity.
  private PackInfo datasetPack;private String datasetEnvelope;private boolean datasetResolved;
  private void observeDataset(PackInfo pack,String envelope){datasetPack=pack;datasetEnvelope=envelope;datasetResolved=true;}
  PackInfo datasetPack(){return datasetPack;}String datasetEnvelope(){return datasetEnvelope;}boolean datasetResolved(){return datasetResolved;}
  Instant now(){return clock.instant();}
  /** A departure-now itinerary is no longer usable once its FIRST SCHEDULED departure cannot be reached. A walking leg's
   * departure is only the request time, not a schedule, so leading walks are not a deadline: the deadline is the first
   * scheduled boarding minus the mapped walking seconds that must precede it (exact; no slack, no grace). A walk-only
   * result has no scheduled departure, so the clock alone never retires it; pack validity, map-source currency, pause,
   * generation and the map-preview lifetime remain the only gates. With no leading walk this is the previous rule. */
  boolean departed(JourneyPlanner.Result r){
    if(r==null)return false;long walkSeconds=0;
    for(JourneyPlanner.Leg leg:r.legs){
      if(leg.isWalking()){walkSeconds+=leg.walk.seconds;continue;}
      Instant first=leg.departure;
      return first!=null&&clock.instant().isAfter(first.minusSeconds(walkSeconds));
    }
    return false;
  }
  private void publish(boolean busy,JourneyPlanner.Candidates c,boolean chooseFrom,JourneyPlanner.Result result,int message){
    if(!busy&&info!=null)observeDataset(info,currentEnvelope);
    state.setValue(new State(info,from,to,c,chooseFrom,busy,result,message));
  }
  private void discard(){closeMapPreview();retireWalking();releaseWalkingSnapshot();from=null;to=null;info=null;currentEnvelope=null;datasetPack=null;datasetEnvelope=null;datasetResolved=false;}
  private int supersede(){generation++;preparingMap=false;closeMapPreview();lastWorkerFailureLocation=null;checking=false;loadingPack=false;if(pending!=null)pending.cancel(true);retireWalking();return generation;}
  /** Slice C phone latency: a fixed event name, ok/failed and elapsed milliseconds from the user action to the
   * main-thread result. Never stop names, IDs, coordinates, queries or clock times. Debug builds only log it. */
  static java.util.function.ObjLongConsumer<String> timing=(event,ms)->{
    if(app.organicmaps.BuildConfig.DEBUG){android.util.Log.i("DMapsTiming",event+" elapsedMs="+ms);TimingRing.record(event,ms);}
  };
  private <T> void run(String event,Callable<T> task,Consumer<T> apply){run(event,task,apply,false);}
  private <T> void run(String event,Callable<T> task,Consumer<T> apply,boolean packLoad){run(event,task,apply,packLoad,R.string.journey_changed);}
  private <T> void run(String event,Callable<T> task,Consumer<T> apply,boolean packLoad,int failureMessage){
    if(closed)return;final int token=supersede();loadingPack=packLoad;publish(true,null,false,null,R.string.transit_working);
    final long started=android.os.SystemClock.elapsedRealtime();
    pending=worker.submit(()->{
      try{T value=task.call();main.post(()->{if(!closed&&token==generation){loadingPack=false;apply.accept(value);report(event+" ok",started);}});}
      catch(Exception failed){String location=failureLocation(failed);main.post(()->{if(!closed&&token==generation){loadingPack=false;lastWorkerFailureLocation=location;discard();publish(false,null,false,null,failureMessage);report(event+" failed",started);}});}
    });
  }
  private static void report(String event,long started){
    try{timing.accept(event,android.os.SystemClock.elapsedRealtime()-started);}catch(RuntimeException ignored){/* measurement never alters behaviour */}
  }
  private static final class Loaded {
    final PackInfo info;final String envelope;
    Loaded(PackInfo p,String envelope){info=p;this.envelope=envelope;}
  }
  /** Also used on resume: drop old display first and revalidate the retained ephemeral selections. */
  public void refresh(){refresh(R.string.journey_choose);}
  private void refresh(int message){
    datasetResolved=false; // a load that never answers stays unresolved: the header says unavailable, never no pack
    run("journey_refresh",()->{
      TimetableRepository r=repo();PackInfo p=r.hasPackChecked()?r.info():null;
      JourneyPlanner.Candidates snapshot=p!=null&&p.formatVersion==2?r.searchJourneyStops("",1):null;
      return new Loaded(snapshot==null?p:snapshot.packInfo,snapshot==null?null:snapshot.packEnvelopeSha256);
    },loaded->{
      datasetPack=loaded.info;datasetEnvelope=loaded.envelope;datasetResolved=true;
      info=loaded.info;currentEnvelope=loaded.envelope;reconcileSelections(currentEnvelope);
      if(info==null){discard();observeDataset(null,null);publish(false,null,false,null,R.string.transit_no_pack);}
      else if(info.formatVersion!=2){from=null;to=null;publish(false,null,false,null,R.string.journey_format_required);}
      else if(!valid(info)){discard();observeDataset(loaded.info,loaded.envelope);publish(false,null,false,null,R.string.journey_changed);}
      else publish(false,null,false,null,message);
    },true);
  }
  /** Consume once into a live model. Re-read the exact pack/platform identities on this worker;
   * never restore an old itinerary or borrow the cleared model's provider. Planning is explicit. */
  public void restoreWalkingReturn(String token){
    if(closed)return;
    WalkingMapPreviewSession.ReturnSelection selection=WalkingMapPreviewSession.takeReturn(token);
    from=null;to=null;
    if(selection==null){refresh(R.string.journey_return_reselect);return;}
    run("journey_return",()->{
      TimetableRepository r=repo();
      JourneyPlanner.Selection a=r.journeySelection(selection.fromId,selection.envelope);
      JourneyPlanner.Selection b=r.journeySelection(selection.toId,selection.envelope);
      if(a.platforms.size()!=1||b.platforms.size()!=1
          ||a.platforms.get(0).id!=selection.fromId||b.platforms.get(0).id!=selection.toId
          ||!Objects.equals(a.platforms.get(0).sourceId,selection.fromSource)
          ||!Objects.equals(b.platforms.get(0).sourceId,selection.toSource)
          ||!r.isJourneyCurrent(selection.envelope))throw new IOException("return changed");
      return new Endpoint[]{new Endpoint(a,a.platforms.get(0)),new Endpoint(b,b.platforms.get(0))};
    },endpoints->{
      if(!valid(endpoints[0].selection.packInfo)||!valid(endpoints[1].selection.packInfo)){
        discard();publish(false,null,false,null,R.string.journey_return_reselect);return;
      }
      from=endpoints[0];to=endpoints[1];info=from.selection.packInfo;currentEnvelope=selection.envelope;
      datasetPack=info;datasetEnvelope=currentEnvelope;datasetResolved=true;
      publish(false,null,false,null,R.string.journey_return_replan);
    },true,R.string.journey_return_reselect);
  }
  /** Reconcile against the identity actually returned, not earlier separate current-generation reads. */
  private void reconcileSelections(String envelope){
    if(from!=null&&!from.selection.packEnvelopeSha256.equals(envelope))from=null;
    if(to!=null&&!to.selection.packEnvelopeSha256.equals(envelope))to=null;
  }
  public void search(boolean origin,String query){
    State s=state.getValue();if(closed||s.busy||!displayable(s)||s.info.formatVersion!=2)return;
    String text=query==null?"":query.trim();if(text.isEmpty()||text.length()>256){invalidate(origin);return;}
    if(origin)from=null;else to=null;
    run("journey_search",()->repo().searchJourneyStops(text,20),c->{
      info=c.packInfo;currentEnvelope=c.packEnvelopeSha256;reconcileSelections(currentEnvelope);
      if(!valid(info)){discard();publish(false,null,false,null,R.string.journey_changed);return;}
      publish(false,c,origin,null,c.stops.isEmpty()?R.string.transit_no_results:R.string.journey_choose_hit);
    });
  }
  public void select(JourneyPlanner.Candidates candidates,long id,boolean origin){
    State s=state.getValue();if(closed||s.busy||s.candidates!=candidates||s.choosingFrom!=origin)return;
    Stop chosen=null;for(Stop stop:candidates.stops)if(stop.id==id)chosen=stop;
    if(chosen==null)return;final Stop stop=chosen;
    run("journey_select",()->{
      JourneyPlanner.Selection selection=repo().journeySelection(id,candidates.packEnvelopeSha256);
      if(selection.platforms.isEmpty()||!repo().isJourneyCurrent(selection.packEnvelopeSha256))throw new IOException("selection unavailable");
      return new Endpoint(selection,stop);
    },endpoint->{
      info=endpoint.selection.packInfo;currentEnvelope=endpoint.selection.packEnvelopeSha256;
      if(!valid(info)){discard();publish(false,null,false,null,R.string.journey_changed);return;}
      if(origin){from=endpoint;if(to!=null&&!to.selection.packEnvelopeSha256.equals(endpoint.selection.packEnvelopeSha256))to=null;}
      else{to=endpoint;if(from!=null&&!from.selection.packEnvelopeSha256.equals(endpoint.selection.packEnvelopeSha256))from=null;}
      publish(false,null,false,null,R.string.journey_choose);
    });
  }
  /** Narrow an already explicit station choice without relying on the capped name-search list.
   * A captured station control cannot reinterpret IDs after an endpoint or pack change. */
  public void selectPlatform(JourneyPlanner.Selection expected,long id,boolean origin){
    State s=state.getValue();Endpoint endpoint=origin?from:to;
    if(closed||s==null||s.busy||!displayable(s)||endpoint==null||endpoint.selection!=expected)return;
    boolean member=false;for(Stop platform:expected.platforms)if(platform.id==id)member=true;if(!member)return;
    run("journey_platform",()->{
      JourneyPlanner.Selection selection=repo().journeySelection(id,expected.packEnvelopeSha256);
      if(selection.platforms.size()!=1||selection.platforms.get(0).id!=id||!repo().isJourneyCurrent(selection.packEnvelopeSha256))throw new IOException("station platform changed");
      return new Endpoint(selection,selection.platforms.get(0));
    },selected->{
      info=selected.selection.packInfo;currentEnvelope=selected.selection.packEnvelopeSha256;
      if(!valid(info)){discard();publish(false,null,false,null,R.string.journey_changed);return;}
      reconcileSelections(currentEnvelope);if(origin)from=selected;else to=selected;
      publish(false,null,false,null,R.string.journey_choose);
    });
  }
  /** Editing retires results and any candidate list before queued callbacks can publish them. */
  public void invalidate(boolean origin){
    if(closed)return;
    // Query edits retire query work, but must not cancel the only admission/metadata load.
    boolean keepLoading=loadingPack;if(!keepLoading)supersede();if(origin)from=null;else to=null;
    publish(keepLoading,null,false,null,keepLoading?R.string.transit_working:R.string.journey_choose);
  }
  /** Bounded mapped scope: one platform pair/map; direct path and4 shared link queries, including
   * at most one initial sibling path. Every path is native, not inferred station access. */
  public boolean canPlanWithWalking(State s){
    if(closed||s==null||s.busy||!displayable(s)||s.from==null||s.to==null||s.from.selection.platforms.size()!=1||s.to.selection.platforms.size()!=1)return false;
    Stop a=s.from.selection.platforms.get(0),b=s.to.selection.platforms.get(0);
    return a.id!=b.id&&a.lat!=null&&a.lon!=null&&b.lat!=null&&b.lon!=null;
  }
  public void planWithWalking(){
    State s=state.getValue();if(!canPlanWithWalking(s))return;
    final Endpoint a=from,b=to;final RegisteredWalkingMaps.Inventory inventory;
    try{inventory=walkingMaps.get();if(inventory==null||!inventory.complete)throw new IllegalStateException();}
    catch(RuntimeException|LinkageError unavailable){supersede();publish(false,null,false,null,R.string.journey_walk_action_unavailable);return;}
    final WalkingSession ticket=new WalkingSession();
    run("journey_plan_walking",()->{
      boolean retained=false;
      try{
        if(ticket.cancelled||Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("walking cancelled");
        TimetableRepository r=repo();if(!r.isJourneyCurrent(a.selection.packEnvelopeSha256)||!a.selection.packEnvelopeSha256.equals(b.selection.packEnvelopeSha256))throw new IOException("walking selection changed");
        Stop first=a.selection.platforms.get(0),last=b.selection.platforms.get(0);
        RegisteredWalkingMaps.Candidate chosen=inventory.candidates(first.lat,first.lon,last.lat,last.lon,WALK_COPY_LIMIT).stream()
            .min(Comparator.comparingLong((RegisteredWalkingMaps.Candidate c)->c.cachedBytes).thenComparing(c->c.countryName)).orElseThrow(()->new IOException("no admitted walking map"));
        java.io.File cache=walkingCache();sweepWalkingCacheOnce(cache);
        if(!cache.isDirectory()&&!cache.mkdirs())throw new IOException("walking cache unavailable");
        final long snapshotStarted=android.os.SystemClock.elapsedRealtime();
        if(walkingSnapshot!=null&&!walkingSnapshot.matches(chosen))closeWalkingSnapshotOnWorker();
        if(walkingSnapshot==null)walkingSnapshot=WalkingSnapshotLease.acquire(chosen,cache,WALK_COPY_LIMIT);
        if(walkingSnapshot==null)throw new IOException("walking snapshot unavailable");
        WalkingSnapshotLease lease=WalkingSnapshotLease.acquire(chosen,cache,WALK_COPY_LIMIT);
        if(lease==null)throw new IOException("walking snapshot changed");ticket.attachOnWorker(lease);
        MappedWalking p=lease.provider();
        // Slice C split of journey_plan_walking: the private map copy, then direct + access/egress routing.
        report("journey_walk_snapshot ok",snapshotStarted);final long routeStarted=android.os.SystemClock.elapsedRealtime();
        if(ticket.cancelled||Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("walking cancelled");
        // Construction may copy/hash; departure-now is sampled AFTER it, not before a queued copy.
        Instant earliest=clock.instant();JourneyPlanner.Walk walk;boolean directLimited=false;
        // Coarse debug timing only: the remainder still combines GTFS and further native queries.
        final long directStarted=android.os.SystemClock.elapsedRealtime();boolean directFinished=false;
        try{
          try{walk=JourneyPlanner.Walk.routeIfAvailable(p,a.selection,first.id,b.selection,last.id,earliest);}
          catch(IOException unavailable){
            // Preserve the existing direct-only LIMIT_REACHED fallback, never cancellation/stale failures.
            if(!"mapped walking unavailable: LIMIT_REACHED".equals(unavailable.getMessage())||Thread.currentThread().isInterrupted())throw unavailable;
            walk=null;directLimited=true;
          }
          directFinished=true;
        }finally{report(directFinished?"journey_walk_direct ok":"journey_walk_direct failed",directStarted);}
        final long linksStarted=android.os.SystemClock.elapsedRealtime();boolean linksFinished=false;
        JourneyPlanner.Result result;
        try{
          result=r.journeysWithMappedAccessAndEgress(new JourneyPlanner.Request(a.selection,b.selection,earliest,180,3,walk==null?Collections.emptyList():Collections.singletonList(walk)),p);
          linksFinished=true;
        }finally{report(linksFinished?"journey_walk_links ok":"journey_walk_links failed",linksStarted);}
        if(directLimited)result=result.withWalkingQueryLimit();
        report("journey_walk_route ok",routeStarted);
        if(ticket.cancelled||Thread.currentThread().isInterrupted()||!ticket.currentOnWorker(result.mapSnapshotId))throw new IOException("walking retired");
        retained=true;return result;
      }finally{if(!retained||ticket.cancelled)ticket.closeOnWorker();}
    },result->{
      info=result.packInfo;currentEnvelope=result.packEnvelopeSha256;
      if(!valid(info)){discard();publish(false,null,false,null,R.string.journey_changed);return;}
      publish(false,null,false,result,result.found()?R.string.journey_found:R.string.journey_no_result);
    },false,R.string.journey_walk_action_unavailable);
    walkingSession=ticket;
  }
  /** Revalidate the exact displayed leg on its existing worker before issuing a one-shot nonce.
   * No provider is moved to main and no second snapshot is opened for the map handoff. */
  public void prepareWalkingMap(State expected,JourneyPlanner.Walk walk,Consumer<String> ready){
    WalkingMapPreviewSession.requireMain();State s=state.getValue();
    boolean member=false;if(s!=null&&s.result!=null)for(JourneyPlanner.Leg leg:s.result.legs)if(leg.walk==walk)member=true;
    if(closed||preparingMap||s!=expected||s==null||s.busy||!displayable(s)||!member||walk==null
        ||s.result.mapSnapshotId==null||!s.result.mapSnapshotId.equals(walk.mapSnapshotId)||walkingSession==null||departed(s.result)){ready.accept(null);return;}
    closeMapPreview();preparingMap=true;final int token=generation;
    final WalkingSession ticket=walkingSession;final JourneyPlanner.Result result=s.result;
    worker.execute(()->{
      boolean ok;try{ok=repo().isJourneyCurrent(result.packEnvelopeSha256)&&ticket.currentOnWorker(result.mapSnapshotId);}catch(Exception failed){ok=false;}
      final boolean current=ok;main.post(()->{
        if(closed||token!=generation)return;preparingMap=false;
        if(!current||state.getValue()!=expected||!valid(result.packInfo)||walkingSession!=ticket||departed(result)){ready.accept(null);return;}
        final WalkingMapPreviewSession[] holder=new WalkingMapPreviewSession[1];
        WalkingMapPreviewSession session=new WalkingMapPreviewSession(walk,result.packInfo,s.from,s.to,answer->{
          // This exact bounded session, not a live Activity, owns currency after model clear.
          if(mapPreview!=holder[0]||token!=generation||walkingSession!=ticket||!valid(result.packInfo)){answer.accept(false);return;}
          worker.execute(()->{
            boolean validSource;try{validSource=repo().isJourneyCurrent(result.packEnvelopeSha256)&&ticket.currentOnWorker(result.mapSnapshotId);}catch(Exception failed){validSource=false;}
            final boolean checked=validSource;main.post(()->answer.accept(checked&&mapPreview==holder[0]&&token==generation&&walkingSession==ticket&&valid(result.packInfo)));
          });
        },()->{
          if(mapPreview==holder[0]){mapPreview=null;retireWalking();releaseWalkingSnapshot();if(closed)retireWorker();else if(state.getValue().result!=null)publish(false,null,false,null,R.string.journey_choose);}
        });
        holder[0]=session;mapPreview=session;ready.accept(session.token());
      });
    });
  }
  public void plan(){
    State s=state.getValue();if(closed||s.busy||from==null||to==null||!displayable(s))return;
    final Endpoint a=from,b=to;final Instant earliest=clock.instant();
    run("journey_plan",()->repo().journeys(new JourneyPlanner.Request(a.selection,b.selection,earliest,180,3,Collections.emptyList())),result->{
      info=result.packInfo;currentEnvelope=result.packEnvelopeSha256;
      if(!valid(info)){discard();publish(false,null,false,null,R.string.journey_changed);return;}
      publish(false,null,false,result,result.found()?R.string.journey_found:R.string.journey_no_result);
    });
  }
  /** Periodic worker-side authority/generation refresh. No I/O on main, no concurrent poll backlog.
   * External changes are detected at the next completed refresh, not atomically with screen pixels. */
  public void checkVisible(){
    if(mapPreview!=null&&mapPreview.isOpen())return;
    State s=state.getValue();if(closed||s.info==null)return;
    if(!valid(s.info)){supersede();String envelope=currentEnvelope;discard();observeDataset(s.info,envelope);publish(false,null,false,null,R.string.journey_changed);return;}
    // Keep the selected endpoints so the rider can replan at once; never show a departed plan as current.
    if(!s.busy&&departed(s.result)){supersede();publish(false,null,false,null,R.string.journey_departed);return;}
    if(s.busy||checking)return;
    String envelope=s.result!=null?s.result.packEnvelopeSha256:(from!=null?from.selection.packEnvelopeSha256:
        (to!=null?to.selection.packEnvelopeSha256:(s.candidates!=null?s.candidates.packEnvelopeSha256:currentEnvelope)));
    if(envelope==null)return;checking=true;final int token=generation;
    final WalkingSession ticket=walkingSession;final String map=s.result==null?null:s.result.mapSnapshotId;
    pending=worker.submit(()->{
      boolean ok;try{ok=repo().isJourneyCurrent(envelope);if(map!=null)ok=ok&&ticket!=null&&ticket.currentOnWorker(map);}catch(Exception failed){ok=false;}final boolean current=ok;
      main.post(()->{if(!closed&&token==generation){checking=false;if(mapPreview!=null&&mapPreview.isOpen())return;if(!current||!valid(info)){PackInfo expired=info;discard();if(current)observeDataset(expired,envelope);publish(false,null,false,null,R.string.journey_changed);}}});
    });
  }
  /** No schedule/selection is persisted. Paused screens lose displayed results/candidates immediately. */
  public void suspend(){
    if(closed)return;
    // Explicit handoff alone retains the existing worker-owned snapshot; ordinary pauses do not.
    if(mapPreview==null||!mapPreview.isOpen()){supersede();releaseWalkingSnapshot();}
    info=null;currentEnvelope=null;publish(false,null,false,null,R.string.journey_choose);
  }
  /** Enqueue repository retirement behind provider/check work, on the same serial owner. */
  private void retireWorker(){
    if(workerRetired)return;workerRetired=true;
    worker.execute(()->{try{closeWalkingSnapshotOnWorker();if(repository!=null)repository.close();}finally{repository=null;}});
    worker.shutdown();
  }
  @Override protected void onCleared(){
    if(closed)return;closed=true;
    // MwmActivity is singleTask: the explicit handoff can destroy Journey, not just pause it.
    // Keep only an already-issued, still-open bounded session's worker/repository alive. All
    // ordinary model callbacks/actions remain fenced by closed; expiry/close releases this owner.
    if(mapPreview==null||!mapPreview.isOpen()){supersede();retireWorker();}
    super.onCleared();
  }
}
