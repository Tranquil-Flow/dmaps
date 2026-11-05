package app.organicmaps.sdk.transit;

import android.app.*;
import android.content.Context;
import android.os.*;
import app.organicmaps.R;
import app.organicmaps.transit.JourneyViewModel;
import java.io.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

/** Real production ViewModel on ART, no Activity or rider Application/private storage.
 * Only repository location/clock/Application cache are controlled. Production registry capture,
 * serial worker, SDK provider, planner, generation/cancellation and visible polling remain real. */
public final class BerlinJourneyModelChecks {
  private BerlinJourneyModelChecks(){}
  interface Checked {void run()throws Exception;}
  interface Ready {boolean get()throws Exception;}
  private static void need(boolean ok,String stage){if(!ok)throw new AssertionError(stage);}
  private static void main(Instrumentation runner,Checked action)throws Exception{
    AtomicReference<Throwable> failed=new AtomicReference<>();runner.runOnMainSync(()->{try{action.run();}catch(Throwable e){failed.set(e);}});
    if(failed.get()!=null)throw new Exception("model main assertion",failed.get());
  }
  private static void await(Instrumentation runner,Ready ready)throws Exception{
    long end=SystemClock.uptimeMillis()+45000;
    do{AtomicBoolean ok=new AtomicBoolean();main(runner,()->ok.set(ready.get()));if(ok.get())return;SystemClock.sleep(40);}while(SystemClock.uptimeMillis()<end);
    throw new AssertionError("model state deadline");
  }
  private static void phase(Instrumentation r,String stage){Bundle b=new Bundle();b.putString("dmaps_berlin_model_phase",stage);r.sendStatus(0,b);}
  private static boolean empty(File f){String[] names=f.list();return !f.exists()||(names!=null&&names.length==0);}
  private static void drain(Instrumentation r,ExecutorService worker)throws Exception{worker.submit(()->{}).get(20,TimeUnit.SECONDS);main(r,()->{});}
  private static void choose(Instrumentation r,JourneyViewModel model,boolean from,String query,String sourceId)throws Exception{
    choose(r,model,from,query,from?"de:11000:900100003":"de:11000:900003201",sourceId);
  }
  private static void choose(Instrumentation r,JourneyViewModel model,boolean from,String query,String stationId,String sourceId)throws Exception{
    main(r,()->model.search(from,query));await(r,()->!model.getState().getValue().busy);
    main(r,()->{var s=model.getState().getValue();need(s.candidates!=null,"real search candidates");Stop hit=null;for(Stop stop:s.candidates.stops)if(stationId.equals(stop.sourceId))hit=stop;need(hit!=null,"station reachable in bounded search");model.select(s.candidates,hit.id,from);});
    await(r,()->{var s=model.getState().getValue();return !s.busy&&(from?s.from!=null:s.to!=null);});
    main(r,()->{var s=model.getState().getValue();var group=(from?s.from:s.to).selection;Stop hit=null;for(Stop stop:group.platforms)if(sourceId.equals(stop.sourceId))hit=stop;need(hit!=null,"known platform retained in actual station selection");model.selectPlatform(group,hit.id,from);});
    await(r,()->{var s=model.getState().getValue();return !s.busy&&(from?s.from:s.to).selection.platforms.size()==1;});
  }
  public static void run(Instrumentation r,Context context,File working,Clock initial,AtomicBoolean retirementSafe)throws Exception{
    File modelCache=new File(working,"model-cache");need(modelCache.mkdir(),"owned model cache");
    AtomicReference<Instant> now=new AtomicReference<>(initial.instant());Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){if(!ZoneOffset.UTC.equals(z))throw new IllegalArgumentException();return this;}public Instant instant(){return now.get();}};
    Application app=new Application(){@Override public File getCacheDir(){return modelCache;}@Override public File getFilesDir(){return context.getFilesDir();}@Override public Context getApplicationContext(){return this;}};
    ExecutorService worker=Executors.newSingleThreadExecutor(task->{Thread t=new Thread(task,"dmaps-berlin-art-model-worker");t.setPriority(Thread.MIN_PRIORITY);return t;});
    JourneyViewModel[] model={null};TimetableRepository repository=FixtureRepositories.open(context,clock);boolean attached=false;retirementSafe.set(false);
    File snapshots=new File(modelCache,"dmaps-journey-walking");
    try{
      Constructor<JourneyViewModel> ctor=JourneyViewModel.class.getDeclaredConstructor(Application.class,TimetableRepository.class,ExecutorService.class,Clock.class);ctor.setAccessible(true);
      main(r,()->model[0]=ctor.newInstance(app,repository,worker,clock));attached=true;JourneyViewModel vm=model[0];
      phase(r,"refresh-and-real-platform-search");main(r,vm::refresh);await(r,()->!vm.getState().getValue().busy&&vm.getState().getValue().info!=null);
      // Genuine distance-cap witness, not an injected timeout/status. The selected public
      // platforms exceed the unchanged direct-distance cap but share a real U5 service.
      phase(r,"real-native-direct-distance-cap-and-transit-fallback");
      choose(r,vm,true,"Alexanderplatz","de:11000:900100003::4");
      choose(r,vm,false,"Tierpark","de:11000:900161002","de:11000:900161002::4");
      RegisteredWalkingMaps.Inventory[] witnessMaps={null};Stop[] endpoints=new Stop[2];
      main(r,()->{var state=vm.getState().getValue();endpoints[0]=state.from.selection.platforms.get(0);endpoints[1]=state.to.selection.platforms.get(0);witnessMaps[0]=RegisteredWalkingMaps.capture();need(vm.canPlanWithWalking(state),"real long selected pair admitted");});
      File witnessCache=new File(modelCache,"native-limit-witness");need(witnessCache.mkdir(),"owned native witness cache");
      String witnessMap=worker.submit(()->{
        Stop a=endpoints[0],b=endpoints[1];var candidates=witnessMaps[0].candidates(a.lat,a.lon,b.lat,b.lon,128L*1024*1024);need(candidates.size()==1,"real long pair has one registered map");
        try(MappedWalking provider=candidates.get(0).open(witnessCache,MappedWalking.SnapshotMode.PRIVATE_COPY,128L*1024*1024)){
          need(provider!=null,"real native witness provider");var refusal=provider.query(a.lat,a.lon,b.lat,b.lon,clock.instant().getEpochSecond(),provider.mapSnapshotId);
          need(refusal.status==MappedWalking.Status.LIMIT_REACHED&&provider.isCurrent(),"real native direct cap, source still current");
          need(refusal.pathToken.isEmpty()&&refusal.latLonGeometry().length==0&&!refusal.directionsAvailable,"native limit returns no partial path");return provider.mapSnapshotId;
        }
      }).get(30,TimeUnit.SECONDS);
      need(empty(witnessCache),"witness native owner closed before rider worker");
      main(r,vm::planWithWalking);await(r,()->!vm.getState().getValue().busy);
      main(r,()->{
        var state=vm.getState().getValue();var plan=state.result;JSONObject metrics=new JSONObject();
        metrics.put("scope","REAL_BERLIN_ANDROID_NATIVE_DISTANCE_CAP_PRODUCTION_VIEWMODEL_NO_ACTIVITY");metrics.put("native_status","LIMIT_REACHED");metrics.put("witness_provider_current",true);metrics.put("witness_cache_retired",true);metrics.put("witness_outside_plan_query_budget",true);metrics.put("exact_plan_query_count",JSONObject.NULL);
        metrics.put("from_source_id",endpoints[0].sourceId);metrics.put("to_source_id",endpoints[1].sourceId);metrics.put("found",plan!=null&&plan.found());metrics.put("issues",plan==null?JSONObject.NULL:plan.issues.toString());
        if(plan!=null){metrics.put("legs",plan.legs.size());metrics.put("map_snapshot_id",plan.mapSnapshotId);metrics.put("pack_envelope_sha256",plan.packEnvelopeSha256);if(plan.found())metrics.put("arrival_utc",plan.arrival.toString());}
        Bundle status=new Bundle();status.putString("dmaps_berlin_model_direct_limit_metrics",metrics.toString());r.sendStatus(0,status);
        need(plan!=null&&plan.found(),"real optional native cap must retain scheduled transit");need(plan.issues.contains(JourneyPlanner.Issue.WALK_QUERY_LIMIT)&&!plan.completeWithinSuppliedConnections(),"walking cap remains explicit incomplete output");
        need(witnessMap.equals(plan.mapSnapshotId)&&state.info!=null&&!state.info.authenticated,"same native map and unsigned timetable retained");
        need(plan.legs.size()==1&&!plan.legs.get(0).isWalking()&&"U5".equals(plan.legs.get(0).board.line),"real U5 without fabricated walking");
        need(endpoints[0].sourceId.equals(plan.legs.get(0).board.stopSourceId)&&endpoints[1].sourceId.equals(plan.legs.get(0).alight.stopSourceId),"real explicit long-pair platforms");
        need(Instant.parse("2026-10-21T08:02:00Z").equals(plan.legs.get(0).departure)&&Instant.parse("2026-10-21T08:17:30Z").equals(plan.arrival),"real frozen U5 long-pair timetable");
      });
      choose(r,vm,true,"Alexanderplatz","de:11000:900100003::3");choose(r,vm,false,"Berlin Hauptbahnhof","de:11000:900003201:3:55");
      main(r,()->need(vm.canPlanWithWalking(vm.getState().getValue()),"single-platform native action enabled"));
      phase(r,"actual-default-registry-plan-worker");long started=System.nanoTime();main(r,vm::planWithWalking);await(r,()->!vm.getState().getValue().busy);
      main(r,()->{
        var s=vm.getState().getValue();JSONObject diagnostic=new JSONObject();
        diagnostic.put("plan_ms",(System.nanoTime()-started)/1e6);diagnostic.put("info_present",s.info!=null);diagnostic.put("origin_present",s.from!=null);diagnostic.put("destination_present",s.to!=null);
        diagnostic.put("message",s.message==R.string.journey_walk_action_unavailable?"WALK_ACTION_UNAVAILABLE":s.message==R.string.journey_changed?"SOURCE_CHANGED":s.message==R.string.journey_no_result?"NO_RESULT":s.message==R.string.journey_found?"FOUND":"OTHER_RESOURCE");
        diagnostic.put("result_present",s.result!=null);
        Field failure=JourneyViewModel.class.getDeclaredField("lastWorkerFailureLocation");failure.setAccessible(true);Object location=failure.get(vm);diagnostic.put("worker_failure_location",location==null?JSONObject.NULL:location);
        if(s.result!=null){diagnostic.put("found",s.result.found());diagnostic.put("issues",s.result.issues.toString());diagnostic.put("legs",s.result.legs.size());diagnostic.put("expected_map", "66700c9cde60fc3aaf48a5c98e2cd538a8b08c62f772c16e77219d9f452b7c89".equals(s.result.mapSnapshotId));if(s.result.legs.size()==2){var ride=s.result.legs.get(0);var walk=s.result.legs.get(1);diagnostic.put("ride_then_walk",!ride.isWalking()&&walk.isWalking());if(!ride.isWalking())diagnostic.put("line_is_u5","U5".equals(ride.board.line));if(walk.isWalking())diagnostic.put("walk_at_arrival",ride.arrival.equals(walk.walk.routedAt));}}
        Bundle b=new Bundle();b.putString("dmaps_berlin_model_plan_diagnostic",diagnostic.toString());r.sendStatus(0,b);
      });
      JourneyPlanner.Result[] result={null};main(r,()->{var s=vm.getState().getValue();
        need(s.result!=null&&s.result.found(),"actual worker result");
        need(s.result.mapSnapshotId!=null&&s.result.mapSnapshotId.equals("66700c9cde60fc3aaf48a5c98e2cd538a8b08c62f772c16e77219d9f452b7c89"),"native content identity");
        need(s.result.legs.size()==2&&!s.result.legs.get(0).isWalking()&&s.result.legs.get(1).isWalking(),"real U5 ride plus final walk consumed");
        need("U5".equals(s.result.legs.get(0).board.line),"real U5 line");
        need(s.result.legs.get(0).arrival.equals(s.result.legs.get(1).walk.routedAt),"final walk uses scheduled alighting instant");
        need(s.result.legs.get(1).walk.directionsAvailable&&!s.result.legs.get(1).walk.directions().isEmpty(),"real final walking instructions retained");
        need(!s.info.authenticated,"unsigned remains untrusted");result[0]=s.result;
      });long completed=System.nanoTime();
      need(snapshots.isDirectory()&&!empty(snapshots),"provider retained while visible");main(r,vm::checkVisible);drain(r,worker);main(r,()->need(vm.getState().getValue().result==result[0],"current source polling retains result"));
      phase(r,"suspend-retires-provider-resume-retains-generation");main(r,vm::suspend);drain(r,worker);main(r,()->need(vm.getState().getValue().result==null&&vm.getState().getValue().info==null,"pause drops presentation"));need(empty(snapshots),"pause closes native snapshot");
      main(r,vm::refresh);await(r,()->!vm.getState().getValue().busy&&vm.getState().getValue().info!=null);main(r,()->need(vm.canPlanWithWalking(vm.getState().getValue()),"same-generation selections reconciled on resume"));
      phase(r,"cancel-before-native-worker-start");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);Future<?> held=worker.submit(()->{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("held worker deadline");}catch(InterruptedException e){Thread.currentThread().interrupt();}});need(entered.await(2,TimeUnit.SECONDS),"owner barrier entered");
      try{main(r,vm::planWithWalking);main(r,()->vm.invalidate(true));}finally{release.countDown();}held.get(12,TimeUnit.SECONDS);drain(r,worker);main(r,()->{var s=vm.getState().getValue();need(!s.busy&&s.from==null&&s.to!=null&&s.result==null,"cancelled generation never publishes");});need(empty(snapshots),"cancelled preparation leaves no native copy");
      phase(r,"reselect-recreate-and-synchronous-expiry");choose(r,vm,true,"Alexanderplatz","de:11000:900100003::3");main(r,vm::planWithWalking);await(r,()->!vm.getState().getValue().busy);main(r,()->need(vm.getState().getValue().result!=null&&vm.getState().getValue().result.found(),"native session recreated"));
      now.set(Instant.parse("2027-01-03T00:00:00Z"));main(r,()->{vm.checkVisible();need(vm.getState().getValue().result==null&&vm.getState().getValue().info==null,"expiry removes display before worker I/O");});drain(r,worker);need(empty(snapshots),"expired session closed");
      JSONObject metrics=new JSONObject();metrics.put("scope","REAL_BERLIN_ANDROID_PRODUCTION_VIEWMODEL_NO_ACTIVITY");metrics.put("first_plan_ms",(completed-started)/1e6);metrics.put("map_snapshot_id",result[0].mapSnapshotId);metrics.put("pack_envelope_sha256",result[0].packEnvelopeSha256);metrics.put("actual_registry_supplier",true);metrics.put("actual_repository_and_native_provider",true);metrics.put("search_selection",true);metrics.put("visible_provider_retained",true);metrics.put("pause_close_resume_reconcile",true);metrics.put("cancelled_generation_refused",true);metrics.put("recreated_native_session",true);metrics.put("synchronous_expiry",true);var ride=result[0].legs.get(0);var last=result[0].legs.get(1);metrics.put("vehicle_line",ride.board.line);metrics.put("from_source_id",ride.board.stopSourceId);metrics.put("alight_source_id",ride.alight.stopSourceId);metrics.put("scheduled_alight_utc",ride.arrival.toString());metrics.put("walk_routed_at_utc",last.walk.routedAt.toString());metrics.put("arrival_utc",result[0].arrival.toString());metrics.put("walk_seconds",last.walk.seconds);metrics.put("walk_directions_available",last.walk.directionsAvailable);metrics.put("walk_direction_count",last.walk.directions().size());metrics.put("walk_meters",last.walk.distanceMeters);metrics.put("walk_unverified_endpoint_access",last.walk.unverifiedEndpointAccess);Bundle b=new Bundle();b.putString("dmaps_berlin_model_metrics",metrics.toString());r.sendStatus(0,b);
    }finally{
      if(attached){Method clear=JourneyViewModel.class.getDeclaredMethod("onCleared");clear.setAccessible(true);main(r,()->clear.invoke(model[0]));}else{repository.close();worker.shutdown();}
      boolean stopped=worker.awaitTermination(20,TimeUnit.SECONDS);retirementSafe.set(stopped);need(stopped,"worker not retired; retain working files until process exit");need(empty(snapshots),"cleared model leaves no native snapshots");phase(r,"worker-and-repository-retired");
    }
  }
}
