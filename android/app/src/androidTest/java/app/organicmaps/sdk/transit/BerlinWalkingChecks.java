package app.organicmaps.sdk.transit;

import android.app.Instrumentation;
import android.content.*;
import android.os.Bundle;
import app.organicmaps.sdk.OrganicMaps;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Test APK only. Real pinned Berlin map/regional pack in owned cache roots, historical clock.
 * No Activity, managed TTS/location/navigation, owner map/pack replacement or live service. */
public final class BerlinWalkingChecks {
  private static final long COPY_CAP=128L*1024*1024;
  private static void need(boolean b){if(!b)throw new AssertionError();}
  private static File dir(File parent,String name){File f=new File(parent,name);need(f.mkdir());return f;}
  private static Method method(String name,Class<?>...types)throws Exception{Method m=OrganicMaps.class.getDeclaredMethod(name,types);m.setAccessible(true);return m;}
  private static void remove(File f)throws Exception{File[] children=f.listFiles();if(children!=null)for(File child:children)remove(child);need(f.delete());}
  private static void verify(File file,long bytes,String expected)throws Exception{
    need(Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)&&file.length()==bytes);
    MessageDigest h=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] block=new byte[65536];int n;while((n=in.read(block))!=-1)h.update(block,0,n);}
    StringBuilder hex=new StringBuilder();for(byte b:h.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));need(expected.equals(hex.toString()));
  }
  private static void status(Instrumentation runner,String key,String value){Bundle b=new Bundle();b.putString(key,value);runner.sendStatus(0,b);}
  public static void run(Instrumentation runner,String inputToken)throws Exception{
    need(inputToken!=null&&inputToken.matches("dmaps-berlin-input-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
    Context target=runner.getTargetContext();File base=target.getCacheDir().getCanonicalFile(),input=new File(base,inputToken);
    need(input.getCanonicalFile().equals(input)&&Files.isDirectory(input.toPath(),LinkOption.NOFOLLOW_LINKS));
    File original=new File(input,"Germany_Berlin.mwm"),pack=new File(input,"VBB-JOURNEY-v2.ctpack");
    verify(original,111442791L,"c437fed083f7a97668743856d2c4f4c0275f308457df8e8e96cdc81ff9250d8e");
    verify(pack,58050491L,"f895497127987e70bf48eb5b4e5ae4e4e5031dbe5b015e708a680787b5b0afbc");
    need(base.getUsableSpace()>=1024L*1024*1024);
    File root=dir(base,"dmaps-berlin-android-"+UUID.randomUUID());status(runner,"dmaps_berlin_owned_root",root.getName());
    File working=dir(root,"working"),cache=dir(working,"copy-cache"),files=dir(working,"transit"),settings=dir(root,"settings"),writable=dir(root,"writable"),priv=dir(root,"private"),temp=dir(root,"tmp");
    File version=dir(writable,"261004"),source=new File(version,"Germany_Berlin.mwm");
    // Android's app sandbox can deny hard links even between same-UID cache files.
    // Copy only the pinned public test input; never use an owner-installed map.
    Files.copy(original.toPath(),source.toPath());
    verify(source,111442791L,"c437fed083f7a97668743856d2c4f4c0275f308457df8e8e96cdc81ff9250d8e");
    CountDownLatch loaded=new CountDownLatch(1);Throwable[] setup={null};
    runner.runOnMainSync(()->{try{
      NativeWalkingEntry.initialize(target,settings,writable,priv,temp,loaded::countDown);
    }catch(Throwable failure){setup[0]=failure;}});
    need(setup[0]==null&&loaded.await(40,TimeUnit.SECONDS));
    java.util.concurrent.atomic.AtomicBoolean modelRetired=new java.util.concurrent.atomic.AtomicBoolean(true);
    try{
      RegisteredWalkingMaps.Inventory[] inventory={null};runner.runOnMainSync(()->inventory[0]=RegisteredWalkingMaps.capture());
      need(inventory[0]!=null&&inventory[0].complete&&inventory[0].maps.size()==1);
      var candidates=inventory[0].candidates(52.521754,13.41425,52.525261,13.369313,COPY_CAP);need(candidates.size()==1);
      var candidate=candidates.get(0);need(candidate.countryName.equals("Germany_Berlin")&&candidate.mapVersion==261004&&candidate.cachedBytes==111442791L);
      Context isolated=new ContextWrapper(target){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};
      Clock clock=Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"),ZoneOffset.UTC);
      try(TimetableRepository repo=FixtureRepositories.open(isolated,clock)){
        long importStart=System.nanoTime();try(InputStream in=new FileInputStream(pack)){repo.importPack(in,ImportMode.USER_UNTRUSTED);}long imported=System.nanoTime();
        Stop stationA=null,stationB=null;for(Stop s:repo.searchStops("Alexanderplatz",20))if("de:11000:900100003".equals(s.sourceId))stationA=s;
        for(Stop s:repo.searchStops("Berlin Hauptbahnhof",20))if("de:11000:900003201".equals(s.sourceId))stationB=s;need(stationA!=null&&stationB!=null);
        var groupA=repo.journeySelection(stationA.id);var groupB=repo.journeySelection(stationB.id);Stop a=null,b=null;
        for(Stop s:groupA.platforms)if("de:11000:900100003::2".equals(s.sourceId))a=s;for(Stop s:groupB.platforms)if("de:11000:900003201:3:55".equals(s.sourceId))b=s;need(a!=null&&b!=null);
        need(a.lat==52.521754&&a.lon==13.41425&&b.lat==52.525261&&b.lon==13.369313);
        var from=repo.journeySelection(a.id,groupA.packEnvelopeSha256);var to=repo.journeySelection(b.id,groupB.packEnvelopeSha256);
        need(!repo.journeys(new JourneyPlanner.Request(from,to,clock.instant(),60,1,Collections.emptyList())).found());
        // Exercise the rider worker before the independent long direct-route probe. The latter
        // can honestly exhaust its5s native budget; it must not hide diagnostics from the model.
        BerlinJourneyModelChecks.run(runner,isolated,working,clock,modelRetired);
        long openStart=System.nanoTime();
        try(MappedWalking provider=candidate.open(cache,MappedWalking.SnapshotMode.PRIVATE_COPY,COPY_CAP)){
          need(provider!=null&&provider.mapSnapshotId.equals("66700c9cde60fc3aaf48a5c98e2cd538a8b08c62f772c16e77219d9f452b7c89"));long opened=System.nanoTime();
          var walk=JourneyPlanner.Walk.route(provider,from,a.id,to,b.id,clock.instant());long routed=System.nanoTime();
          var request=new JourneyPlanner.Request(from,to,clock.instant(),60,1,Collections.singletonList(walk));var result=repo.journeys(request,provider);
          need(result.found()&&result.legs.size()==1&&result.legs.get(0).isWalking()&&result.arrival.equals(clock.instant().plusSeconds(walk.seconds)));
          need(result.mapSnapshotId.equals(provider.mapSnapshotId)&&result.packEnvelopeSha256.equals(from.packEnvelopeSha256)&&!result.packInfo.authenticated);
          need(walk.unverifiedEndpointAccess&&walk.distanceMeters>1000&&walk.latLonGeometry().length>4&&walk.directionsAvailable);
          int realTurns=0;for(var direction:walk.directions())if(direction.maneuver==MappedWalking.Maneuver.LEFT||direction.maneuver==MappedWalking.Maneuver.RIGHT)realTurns++;
          need(realTurns>0&&walk.directions().get(walk.directions().size()-1).maneuver==MappedWalking.Maneuver.FINISH);
          try{repo.journeys(request);throw new AssertionError();}catch(IOException expected){}
          RegisteredWalkingMaps.Inventory[] during={null};runner.runOnMainSync(()->during[0]=RegisteredWalkingMaps.capture());need(during[0].complete&&during[0].maps.size()==1&&during[0].maps.get(0).countryName.equals(candidate.countryName));
          need(provider.query(0,0,b.lat,b.lon,clock.instant().getEpochSecond(),provider.mapSnapshotId).status==MappedWalking.Status.OUTSIDE_MAP);
          Files.move(source.toPath(),version.toPath().resolve("retired-owned-alias.mwm"));need(!provider.isCurrent());try{repo.journeys(request,provider);throw new AssertionError();}catch(IOException expected){}
          JSONObject metrics=new JSONObject();metrics.put("scope","REAL_BERLIN_ANDROID_ISOLATED_FRAMEWORK_REPOSITORY");metrics.put("from_source_id",a.sourceId);metrics.put("to_source_id",b.sourceId);metrics.put("map_snapshot_id",result.mapSnapshotId);metrics.put("pack_envelope_sha256",result.packEnvelopeSha256);metrics.put("seconds",walk.seconds);metrics.put("distance_meters",walk.distanceMeters);metrics.put("geometry_points",walk.latLonGeometry().length/2);metrics.put("directions_available",walk.directionsAvailable);metrics.put("direction_count",walk.directions().size());metrics.put("left_or_right_count",realTurns);metrics.put("unverified_endpoint_access",walk.unverifiedEndpointAccess);metrics.put("issues",result.issues.toString());metrics.put("import_ms",(imported-importStart)/1e6);metrics.put("open_ms",(opened-openStart)/1e6);metrics.put("route_ms",(routed-opened)/1e6);metrics.put("retired_source_refused",true);status(runner,"dmaps_berlin_metrics",metrics.toString());
        }
        need(cache.list().length==0);need(candidate.open(cache,MappedWalking.SnapshotMode.PRIVATE_COPY,COPY_CAP)==null&&cache.list().length==0);
        // Both owners retired before restoring only this exact public source alias.
        Files.move(version.toPath().resolve("retired-owned-alias.mwm"),source.toPath());
      }
    }finally{if(modelRetired.get())remove(working);}
    // Platform/Framework paths and input cohort are retired by the host only after natural process exit.
  }
}
