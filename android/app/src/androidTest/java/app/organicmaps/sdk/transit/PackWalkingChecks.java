package app.organicmaps.sdk.transit;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import app.organicmaps.sdk.OrganicMaps;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import org.json.JSONObject;

/** Test APK only. Real production Android JNI/router/SQLite, wholly synthetic owned data.
 * Native platform + Framework phases use isolated cache paths. No Activity, managed TTS,
 * location/navigation controller, owner map registration, network request or production root.
 * Does NOT qualify normal app startup, registered real maps, Berlin or rider UI. */
public final class PackWalkingChecks {
  private static final long BUDGET=1024*1024;
  private static final String COUNTRY="DMapsAuthoredWalking";
  private static void need(boolean b){if(!b)throw new AssertionError();}
  private static File dir(File parent,String name){File f=new File(parent,name);need(f.mkdir());return f;}
  private static Method method(Class<?> c,String name,Class<?>...types)throws Exception{Method m=c.getDeclaredMethod(name,types);m.setAccessible(true);return m;}
  private static MappedWalking open(File maps,File cache,long budget){return MappedWalking.open(maps,COUNTRY,261001,cache,MappedWalking.SnapshotMode.PRIVATE_COPY,budget);}
  private static MappedWalking provider(File maps,File cache){MappedWalking p=open(maps,cache,BUDGET);need(p!=null);return p;}
  private static MappedWalking.Result route(MappedWalking p){return p.query(0,0,0,0.002,1792540800L,p.mapSnapshotId);}
  private static void empty(MappedWalking.Result r,MappedWalking.Status s){need(r.status==s&&r.mapSnapshotId.isEmpty()&&r.pathToken.isEmpty()&&r.latLonGeometry().length==0&&r.distanceMeters==0&&r.estimatedSeconds==0);}
  private static void copy(Context test,String asset,File dest)throws Exception{
    JSONObject metadata;try(InputStream in=test.getAssets().open("transit/walking-fixture/fixture.json")){metadata=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}need(metadata.getBoolean("fixture_only"));
    try(InputStream in=test.getAssets().open("transit/walking-fixture/"+asset)){Files.copy(in,dest.toPath());}
    JSONObject expected=metadata.getJSONObject("files").getJSONObject(asset);need(dest.length()==expected.getLong("bytes")&&sha(Files.readAllBytes(dest.toPath())).equals(expected.getString("sha256")));
  }
  private static String sha(byte[] bytes)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
  private static void remove(File f)throws Exception{File[] children=f.listFiles();if(children!=null)for(File child:children)remove(child);need(f.delete());}
  private static void alternatives(SQLiteDatabase sql,int mode){
    need(mode>=0&&mode<=3);sql.execSQL("DELETE FROM transfers");sql.execSQL("DELETE FROM stop_times");sql.execSQL("DELETE FROM trips WHERE id<>1");
    sql.execSQL("UPDATE stops SET lat=0,lon=CASE WHEN source_id='P1b' THEN 0.002 ELSE 0 END");
    sql.execSQL("UPDATE stops SET parent_id=(SELECT id FROM stops WHERE source_id='P1'),lon="+(mode==1?"0.006":"0.002")+" WHERE source_id='H1a'");
    sql.execSQL("INSERT INTO trips SELECT 2,'A2',route_id,service_id,headsign,direction_id FROM trips WHERE id=1");
    sql.execSQL("INSERT INTO stop_times SELECT 1,1,id,36000,36030,0,'',1,1 FROM stops WHERE source_id='H1a'");
    sql.execSQL("INSERT INTO stop_times SELECT 1,2,id,36600,36630,1,'',1,0 FROM stops WHERE source_id='S1'");
    int good=(mode==2||mode==3)?36000:36900;
    sql.execSQL("INSERT INTO stop_times SELECT 2,1,id,"+good+","+(good+30)+",0,'',1,1 FROM stops WHERE source_id='P1b'");
    sql.execSQL("INSERT INTO stop_times SELECT 2,2,id,37800,37830,1,'',1,0 FROM stops WHERE source_id='S1'");
    if(mode==3)for(int id=7;id<=9;id++){
      sql.execSQL("INSERT INTO stops SELECT "+id+",'A"+id+"',name,search_name,lat,lon,parent_id,location_type,platform FROM stops WHERE source_id='P1b'");
      int trip=id-4,at=id==9?36900:36000;
      sql.execSQL("INSERT INTO trips SELECT "+trip+",'A"+trip+"',route_id,service_id,headsign,direction_id FROM trips WHERE id=1");
      sql.execSQL("INSERT INTO stop_times VALUES("+trip+",1,"+id+","+at+","+(at+30)+",0,'',1,1)");
      sql.execSQL("INSERT INTO stop_times SELECT "+trip+",2,id,37800,37830,1,'',1,0 FROM stops WHERE source_id='S1'");
    }
  }
  private static byte[] mappedPack(Context test,File working)throws Exception{return mappedPack(test,working,-1);}
  private static byte[] mappedPack(Context test,File working,int mode)throws Exception{
    byte[] db=null,manifest=null;
    try(ZipInputStream z=new ZipInputStream(test.getAssets().open("transit/journey-v2.ctpack"))){ZipEntry e;while((e=z.getNextEntry())!=null){if(e.getName().equals("timetable.sqlite"))db=z.readAllBytes();else if(e.getName().equals("manifest.json"))manifest=z.readAllBytes();}}
    need(db!=null&&manifest!=null);File input=new File(working,"authored.sqlite");Files.write(input.toPath(),db);
    JSONObject meta=new JSONObject(new String(manifest,StandardCharsets.UTF_8));
    SQLiteDatabase sql=SQLiteDatabase.openDatabase(input.getPath(),null,SQLiteDatabase.OPEN_READWRITE|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
    try{
      sql.execSQL("UPDATE stop_times SET pickup_type=0,drop_off_type=0");sql.execSQL("UPDATE stops SET location_type=1 WHERE source_id='P1'");sql.execSQL("UPDATE stops SET lat=0,lon=CASE WHEN source_id='P1b' THEN 0.002 WHEN source_id='S1' THEN 0.004 ELSE 0 END");sql.execSQL("UPDATE stop_times SET pickup_type=1 WHERE stop_sequence=1");
      if(mode>=0)alternatives(sql,mode);
      JSONObject counts=meta.getJSONObject("counts");for(String table:new String[]{"agencies","routes","service_days","services","stop_times","stops","transfers","trips"})try(android.database.Cursor c=sql.rawQuery("SELECT count(*) FROM "+table,null)){need(c.moveToFirst());counts.put(table,c.getLong(0));}
      try(android.database.Cursor c=sql.rawQuery("SELECT max(arrival_seconds),max(departure_seconds) FROM stop_times",null)){need(c.moveToFirst());meta.put("max_arrival_seconds",c.getLong(0));meta.put("max_departure_seconds",c.getLong(1));}
    }finally{sql.close();}
    db=Files.readAllBytes(input.toPath());need(input.delete());meta.put("database_bytes",db.length);meta.put("database_sha256",sha(db));
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(meta.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();zip.putNextEntry(new ZipEntry("timetable.sqlite"));zip.write(db);zip.closeEntry();}return bytes.toByteArray();
  }
  public static void run(Instrumentation runner,PackAuthenticationChecks.Reporter report)throws Exception{
    Context target=runner.getTargetContext(),test=runner.getContext();
    File root=dir(target.getCacheDir(),"dmaps-walking-android-"+UUID.randomUUID());
    // Exact owned-root token only; host removes it only after this process naturally exits.
    runner.sendStatus(0,bundle("dmaps_walking_owned_root",root.getName()));
    File working=dir(root,"working"),maps=dir(working,"authored"),cache=dir(working,"copy-cache");
    File source=new File(maps,COUNTRY+".mwm");copy(test,"foot.mwm",source);
    try{
      report.check("isolated Android uninitialized walking refuses without cache output",()->{need(open(maps,cache,BUDGET)==null);need(cache.list().length==0);});
      // Invoke ONLY the existing production native initialization phases, on main. Skip the
      // managed init path which would initialize TTS, location/routing and owner bookmarks.
      File settings=dir(root,"settings"),writable=dir(root,"writable"),priv=dir(root,"private"),temp=dir(root,"tmp");
      CountDownLatch loaded=new CountDownLatch(1);Throwable[] setup={null};
      runner.runOnMainSync(()->{try{
        NativeWalkingEntry.initialize(target,settings,writable,priv,temp,loaded::countDown);
      }catch(Throwable e){setup[0]=e;}});
      need(setup[0]==null&&loaded.await(30,TimeUnit.SECONDS));
      report.check("isolated Android real Framework registry is complete empty without owner maps",()->{RegisteredWalkingMaps.Inventory[] inventory={null};runner.runOnMainSync(()->inventory[0]=RegisteredWalkingMaps.capture());need(inventory[0]!=null&&inventory[0].complete&&inventory[0].maps.isEmpty());});
      report.check("isolated Android actual copied pedestrian detour geometry and cleanup",()->{
        try(MappedWalking p=provider(maps,cache)){MappedWalking.Result r=route(p);need(r.status==MappedWalking.Status.OK&&p.isCurrent()&&r.directionsAvailable&&!r.directions().isEmpty()&&r.directions().get(r.directions().size()-1).maneuver==MappedWalking.Maneuver.FINISH&&r.unverifiedEndpointAccess&&r.distanceMeters>600&&r.mapSnapshotId.equals(p.mapSnapshotId));double[] points=r.latLonGeometry();boolean north=false;for(int i=0;i<points.length;i+=2)north|=points[i]>0.001;need(north&&points.length>=8);double first=points[0];points[0]=999;need(r.latLonGeometry()[0]==first);try(var paths=Files.walk(cache.toPath())){Path copied=paths.filter(f->f.toString().endsWith(".mwm")).findFirst().orElseThrow();need(!Files.isSameFile(source.toPath(),copied));}}need(cache.list().length==0);
      });
      report.check("isolated Android raw JNI nonfinite coordinates refuse and preserve valid routing",()->{
        try(MappedWalking p=provider(maps,cache)){var field=MappedWalking.class.getDeclaredField("handle");field.setAccessible(true);long handle=field.getLong(p);Method query=method(MappedWalking.class,"nativeQuery",long.class,double.class,double.class,double.class,double.class,long.class,String.class);for(double lat:new double[]{Double.NaN,Double.POSITIVE_INFINITY,91,89})empty((MappedWalking.Result)query.invoke(null,handle,lat,0d,0d,0.002d,1792540800L,p.mapSnapshotId),MappedWalking.Status.INVALID_REQUEST);need(route(p).status==MappedWalking.Status.OK);}need(cache.list().length==0);
      });
      report.check("isolated Android byte budget refusal releases slot and cleans cache",()->{need(open(maps,cache,1)==null&&cache.list().length==0);try(MappedWalking p=provider(maps,cache)){need(route(p).status==MappedWalking.Status.OK);}need(cache.list().length==0);});
      report.check("isolated Android motorway only refuses pedestrian route without partial result",()->{
        File road=dir(working,"motorway");copy(test,"road.mwm",new File(road,COUNTRY+".mwm"));try(MappedWalking p=provider(road,cache)){empty(route(p),MappedWalking.Status.NO_PATH);}need(cache.list().length==0);
      });
      report.check("isolated Android actual mapped walk and SQLite schedule bind and retire source",()->{
        File files=dir(working,"transit");Context isolated=new ContextWrapper(target){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};
        Clock clock=Clock.fixed(Instant.parse("2026-10-21T07:56:00Z"),ZoneOffset.UTC);
        try(TimetableRepository repo=FixtureRepositories.open(isolated,clock)){
          repo.importPack(new ByteArrayInputStream(mappedPack(test,working)),ImportMode.USER_UNTRUSTED);
          Map<String,Stop> stops=new HashMap<>();for(String q:Arrays.asList("Alexanderplatz","Suedkreuz"))for(Stop s:repo.searchStops(q,20))stops.put(s.sourceId,s);
          Stop a=stops.get("P1a"),b=stops.get("P1b"),c=stops.get("S1");need(a!=null&&b!=null&&c!=null);
          var from=repo.journeySelection(a.id);var transfer=repo.journeySelection(b.id);var to=repo.journeySelection(c.id);
          try(MappedWalking p=provider(maps,cache)){
            var walk=JourneyPlanner.Walk.route(p,from,a.id,transfer,b.id,clock.instant());
            var request=new JourneyPlanner.Request(from,to,clock.instant(),60,1,Collections.singletonList(walk));
            need(!repo.journeys(new JourneyPlanner.Request(from,to,clock.instant(),60,1,Collections.emptyList())).found());
            var result=repo.journeys(request,p);need(result.found()&&result.legs.size()==2&&result.legs.get(0).isWalking()&&!result.legs.get(1).isWalking()&&result.mapSnapshotId.equals(p.mapSnapshotId)&&result.arrival.equals(Instant.parse("2026-10-21T08:20:00Z")));
            var automatic=repo.journeysWithMappedAccessAndEgress(new JourneyPlanner.Request(from,to,clock.instant(),60,1,Collections.emptyList()),p);
            need(automatic.found()&&automatic.legs.size()==2&&automatic.legs.get(0).isWalking()&&!automatic.legs.get(1).isWalking());
            var initial=automatic.legs.get(0);var ride=automatic.legs.get(1);
            need(initial.walk.fromStopId==a.id&&initial.walk.toStopId==b.id&&initial.walk.routedAt.equals(clock.instant())&&!ride.departure.isBefore(initial.arrival));
            need(automatic.mapSnapshotId.equals(p.mapSnapshotId)&&automatic.packEnvelopeSha256.equals(from.packEnvelopeSha256)&&initial.walk.unverifiedEndpointAccess&&initial.walk.latLonGeometry().length>=8);
            JSONObject metric=new JSONObject();metric.put("scope","ANDROID_AUTHORED_INITIAL_NATIVE_WALK_AND_SQLITE_RIDE_NO_ACTIVITY");metric.put("initial_from_source_id",a.sourceId);metric.put("boarding_source_id",b.sourceId);metric.put("walking_seconds",initial.walk.seconds);metric.put("directions_available",initial.walk.directionsAvailable);metric.put("direction_count",initial.walk.directions().size());metric.put("walking_arrival_utc",initial.arrival.toString());metric.put("vehicle_departure_utc",ride.departure.toString());metric.put("final_arrival_utc",automatic.arrival.toString());metric.put("issues",automatic.issues.toString());metric.put("unverified_platform_access",true);runner.sendStatus(0,bundle("dmaps_initial_walk_metrics",metric.toString()));
            try{repo.journeys(request);throw new AssertionError();}catch(IOException expected){}
            Path replacement=maps.toPath().resolve("replacement.mwm");Files.copy(source.toPath(),replacement);Files.move(replacement,source.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            need(!p.isCurrent());empty(route(p),MappedWalking.Status.MAP_CHANGED);try{repo.journeys(request,p);throw new AssertionError();}catch(IOException expected){}
          }need(cache.list().length==0);
          for(int mode=0;mode<4;mode++){
            repo.importPack(new ByteArrayInputStream(mappedPack(test,working,mode)),ImportMode.USER_UNTRUSTED);
            Map<String,Stop> options=new HashMap<>();for(String q:Arrays.asList("Alexanderplatz","Suedkreuz","Hauptbahnhof"))for(Stop s:repo.searchStops(q,20))options.put(s.sourceId,s);
            Stop origin=options.get("P1a"),later=options.get("P1b"),destination=options.get("S1"),first=options.get("H1a");need(origin!=null&&later!=null&&destination!=null&&first!=null&&first.id<later.id&&origin.parentId.equals(first.parentId));
            need(repo.departures(origin.id,clock.instant(),1,60).isEmpty());var firstBoard=repo.departures(first.id,clock.instant(),1,60);need(firstBoard.size()==1);
            try(MappedWalking p=provider(maps,cache)){
              MappedWalking.Result witness=p.query(origin.lat,origin.lon,first.lat,first.lon,clock.instant().getEpochSecond(),p.mapSnapshotId);
              need(witness.status==(mode==1?MappedWalking.Status.OUTSIDE_MAP:MappedWalking.Status.OK));
              if(mode!=1)need(clock.instant().plusSeconds(witness.estimatedSeconds).isAfter(firstBoard.get(0).when));
              var start=repo.journeySelection(origin.id);var end=repo.journeySelection(destination.id);
              var result=repo.journeysWithMappedAccessAndEgress(new JourneyPlanner.Request(start,end,clock.instant(),60,1,Collections.emptyList()),p);
              JSONObject metric=new JSONObject();metric.put("scope","ANDROID_AUTHORED_INITIAL_ALTERNATIVE_NO_ACTIVITY");metric.put("mode",mode);metric.put("found",result.found());metric.put("first_native_status",witness.status.name());metric.put("first_native_seconds",witness.estimatedSeconds);metric.put("issues",result.issues.toString());metric.put("walking_limit_reported",result.issues.contains(JourneyPlanner.Issue.WALK_QUERY_LIMIT));metric.put("exact_plan_query_count",JSONObject.NULL);metric.put("query_count_scope","Exact call counts independently observed on host, not instrumented in this Android APK");
              metric.put("legs",result.legs.size());if(result.found()&&result.legs.size()==2&&result.legs.get(1).board!=null){metric.put("boarding_source_id",result.legs.get(1).board.stopSourceId);metric.put("walk_arrival_utc",result.legs.get(0).arrival.toString());metric.put("boarding_departure_utc",result.legs.get(1).departure.toString());}
              runner.sendStatus(0,bundle("dmaps_initial_alternative_metrics",metric.toString()));
              need(result.packEnvelopeSha256.equals(start.packEnvelopeSha256)&&result.mapSnapshotId.equals(p.mapSnapshotId)&&p.isCurrent());
              if(mode<2){need(result.found()&&result.legs.size()==2&&result.legs.get(0).isWalking()&&!result.legs.get(1).isWalking());need(result.legs.get(0).walk.toStopId==later.id&&result.legs.get(1).board.stopId==later.id&&!result.legs.get(1).departure.isBefore(result.legs.get(0).arrival));need(result.arrival.equals(Instant.parse("2026-10-21T08:30:00Z")));}
              else need(!result.found()&&result.issues.contains(JourneyPlanner.Issue.WALK_QUERY_LIMIT)==(mode==3));
            }need(cache.list().length==0);
          }
        }
      });
    }finally{remove(working);}
    // The live native Framework owns platform paths until instrumentation process teardown.
    // Do not delete them from beneath asynchronous Framework tasks.
  }
  private static android.os.Bundle bundle(String key,String value){android.os.Bundle b=new android.os.Bundle();b.putString(key,value);return b;}
}
