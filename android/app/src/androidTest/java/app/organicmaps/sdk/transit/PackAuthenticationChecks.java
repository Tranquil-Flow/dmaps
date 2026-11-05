package app.organicmaps.sdk.transit;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;
import app.organicmaps.sdk.util.Ed25519;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.zip.*;

/** Test-APK-only composition: synthetic public roots, real production JNI and isolated files.
 * Never selected by an app intent; never reads/replaces the owner's installed pack or maps. */
public final class PackAuthenticationChecks {
  public interface Case { void run() throws Exception; }
  public interface Reporter { void check(String name, Case body); }
  private static final String PREFIX="transit/pack-auth-fixture/";
  private static final String SCOPE="test.dmaps/fixture/fixture-vbb";
  private static void require(boolean value){if(!value)throw new AssertionError();}
  private static IOException refused(Case body)throws Exception {try{body.run();throw new AssertionError("unexpected admission");}catch(IOException expected){return expected;}}
  private static String hash(File file)throws Exception {
    MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
  }
  public static void run(Context target,Context test,Reporter reporter)throws Exception {
    JSONObject key;
    try(InputStream in=test.getAssets().open(PREFIX+"root.json")){key=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
    require(key.getBoolean("fixture_only"));
    PackTrustPolicy policy=new PackTrustPolicy(Collections.singletonList(new PackTrustPolicy.Root(
        key.getString("key_id"),key.getString("namespace"),key.getString("channel"),key.getString("pack_id"),
        Base64.decode(key.getString("public_key_base64"),Base64.NO_WRAP),false)),Ed25519::verify);
    File root=new File(target.getCacheDir(),"dmaps-pack-auth-"+UUID.randomUUID());require(root.mkdir());
    Context isolated=new ContextWrapper(target){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return root;}};
    Clock clock=Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"),ZoneOffset.UTC);
    File directory=new File(root,"transit_pack"),database=new File(directory,"timetable.sqlite"),manifest=new File(directory,"manifest.json"),state=new File(directory,"trust-state.json");
    File sentinel=new File(root,"maps-favorites-tracks-sentinel");Files.write(sentinel.toPath(),new byte[]{7,4,9});
    class Fixture {
      TimetableRepository repo(){return new TimetableRepository(isolated,policy,clock,p->{});}
      void install(TimetableRepository repo,String name,ImportMode mode)throws Exception {try(InputStream in=test.getAssets().open(PREFIX+name)){repo.importPack(in,mode);}}
      long floor()throws Exception{return PackTrustState.parse(Files.readAllBytes(state.toPath())).history.get(SCOPE).sequence;}
      void query(TimetableRepository repo)throws Exception {require(!repo.searchStops("bahnhof",20).isEmpty());}
      String generation()throws Exception{return hash(database)+hash(manifest)+hash(state);}
    }
    Fixture f=new Fixture();
    try {
      reporter.check("isolated native authenticated archive installs and queries",()->{try(TimetableRepository repo=f.repo()){f.install(repo,"official-v7.ctpack",ImportMode.OFFICIAL_REQUIRED);f.query(repo);require(repo.info().authenticated&&repo.info().officialSequence==7&&f.floor()==7);}});
      reporter.check("isolated native rollback and equal-sequence conflict retain generation",()->{String before=f.generation();try(TimetableRepository repo=f.repo()){refused(()->f.install(repo,"rollback-v6.ctpack",ImportMode.OFFICIAL_REQUIRED));refused(()->f.install(repo,"conflict-v7.ctpack",ImportMode.OFFICIAL_REQUIRED));f.query(repo);}require(before.equals(f.generation())&&f.floor()==7);});
      reporter.check("isolated native identical metadata reinstall remains admissible",()->{try(TimetableRepository repo=f.repo()){f.install(repo,"official-v7.ctpack",ImportMode.OFFICIAL_REQUIRED);require(repo.info().authenticated&&f.floor()==7);}});
      reporter.check("isolated native changed payload replaces cached SQLite and survives reopen",()->{try(TimetableRepository repo=f.repo()){require(!repo.searchStops("Hauptbahnhof",20).isEmpty());f.install(repo,"official-v8.ctpack",ImportMode.OFFICIAL_REQUIRED);require(repo.searchStops("Hauptbahnhof",20).isEmpty());require(!repo.searchStops("Xauptbahnhof",20).isEmpty());}try(TimetableRepository repo=f.repo()){require(!repo.searchStops("Xauptbahnhof",20).isEmpty());require(repo.info().officialSequence==8&&f.floor()==8);}});
      reporter.check("isolated native wrong signature and scope never demote",()->{String before=f.generation();try(TimetableRepository repo=f.repo()){refused(()->f.install(repo,"bad-signature-v9.ctpack",ImportMode.OFFICIAL_REQUIRED));refused(()->f.install(repo,"wrong-scope-v9.ctpack",ImportMode.OFFICIAL_REQUIRED));require(repo.info().authenticated);}require(before.equals(f.generation())&&f.floor()==8);});
      reporter.check("isolated native explicitly untrusted replacement retains authenticated floor",()->{try(TimetableRepository repo=f.repo()){f.install(repo,"bad-signature-v9.ctpack",ImportMode.USER_UNTRUSTED);f.query(repo);require(!repo.info().authenticated&&repo.info().importMode==ImportMode.USER_UNTRUSTED&&f.floor()==8);f.install(repo,"official-v8.ctpack",ImportMode.OFFICIAL_REQUIRED);}});
      reporter.check("isolated native production roots cannot inherit fixture authority",()->{try(TimetableRepository repo=new TimetableRepository(isolated)){require(repo.hasPackChecked());refused(()->repo.info());}require(PackTrustState.parse(Files.readAllBytes(state.toPath())).active.mode==ImportMode.OFFICIAL_REQUIRED&&f.floor()==8);});
      reporter.check("isolated native valid SQLite substitution is refused on reopen",()->{
        byte[] original=Files.readAllBytes(database.toPath());
        try {SQLiteDatabase db=SQLiteDatabase.openDatabase(database.getPath(),null,SQLiteDatabase.OPEN_READWRITE);try{db.execSQL("UPDATE stops SET name='Yauptbahnhof' WHERE source_id='H1'");try(Cursor c=db.rawQuery("PRAGMA integrity_check",null)){require(c.moveToFirst()&&"ok".equals(c.getString(0)));}}finally{db.close();}try(TimetableRepository repo=f.repo()){refused(()->f.query(repo));}}
        finally {Files.write(database.toPath(),original);}require(f.floor()==8);
      });
      reporter.check("isolated native missing initialized history refuses read and remove",()->{byte[] original=Files.readAllBytes(state.toPath());require(state.delete());try{try(TimetableRepository repo=f.repo()){refused(()->repo.hasPackChecked());refused(()->repo.removePack());require(database.isFile()&&manifest.isFile());}}finally{Files.write(state.toPath(),original);}require(f.floor()==8);});
      reporter.check("isolated native removal retains history and unrelated sentinel",()->{try(TimetableRepository repo=f.repo()){repo.removePack();require(!repo.hasPackChecked());refused(()->f.install(repo,"rollback-v6.ctpack",ImportMode.OFFICIAL_REQUIRED));}require(f.floor()==8&&Arrays.equals(new byte[]{7,4,9},Files.readAllBytes(sentinel.toPath())));});
      reporter.check("isolated Android v2 trip and transfer data preserve restrictions and history",()->{
        try(TimetableRepository repo=f.repo()){
          try(InputStream in=test.getAssets().open("transit/journey-v2.ctpack")){repo.importPack(in,ImportMode.USER_UNTRUSTED);}
          require(repo.info().formatVersion==2&&!repo.info().authenticated&&repo.info().maxArrivalSeconds==37200&&f.floor()==8);
          List<TripStop> stops=repo.tripStops("T1",LocalDate.parse("2026-10-21"));require(stops.size()==4);
          for(int i=0;i<4;i++)require(stops.get(i).dropOffType==i&&stops.get(i).pickupType==i);
          List<TransferRule> rules=repo.transferRules();require(rules.size()==3&&rules.get(0).transferType==2&&rules.get(0).minTransferSeconds==180);
          require(rules.get(1).transferType==3&&rules.get(1).minTransferSeconds==null&&rules.get(1).toTripId!=null);
        }
      });
      reporter.check("isolated Android v2 malformed stop role refuses before replacing generation",()->{
        String before=f.generation();File clone=new File(root,"bad-role.sqlite");
        try {
          Files.copy(database.toPath(),clone.toPath());
          SQLiteDatabase db=SQLiteDatabase.openDatabase(clone.getPath(),null,SQLiteDatabase.OPEN_READWRITE|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
          try{db.execSQL("DELETE FROM transfers");db.execSQL("UPDATE stops SET location_type='entrance' WHERE source_id='P1a'");
            try(Cursor c=db.rawQuery("PRAGMA integrity_check",null)){require(c.moveToFirst()&&"ok".equals(c.getString(0)));}
          }finally{db.close();}
          byte[] data=Files.readAllBytes(clone.toPath());
          JSONObject metadata=new JSONObject(new String(Files.readAllBytes(manifest.toPath()),StandardCharsets.UTF_8));
          metadata.put("database_bytes",data.length);metadata.put("database_sha256",hash(clone));metadata.getJSONObject("counts").put("transfers",0);
          ByteArrayOutputStream buffer=new ByteArrayOutputStream();
          try(ZipOutputStream zip=new ZipOutputStream(buffer)){
            zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(metadata.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("timetable.sqlite"));zip.write(data);zip.closeEntry();
          }
          try(TimetableRepository repo=f.repo()){
            IOException rejection=refused(()->repo.importPack(new ByteArrayInputStream(buffer.toByteArray()),ImportMode.USER_UNTRUSTED));
            require(rejection.getMessage().contains("stop role"));require(repo.info().formatVersion==2&&repo.transferRules().size()==3);
          }
          require(before.equals(f.generation())&&f.floor()==8);
        }finally{Files.deleteIfExists(clone.toPath());}
      });
      reporter.check("isolated Android scheduled journey uses guarded v2 data and effective headsign",()->{
        String before=f.generation();File clone=new File(root,"journey.sqlite");
        JourneyPlanner.Selection priorFrom,priorTo;
        try(TimetableRepository repo=f.repo()){
          List<TripStop> rows=repo.tripStops("T1",LocalDate.parse("2026-10-21"));
          priorFrom=repo.journeySelection(rows.get(0).stopId);priorTo=repo.journeySelection(rows.get(rows.size()-1).stopId);
        }
        try {
          Files.copy(database.toPath(),clone.toPath());
          SQLiteDatabase db=SQLiteDatabase.openDatabase(clone.getPath(),null,SQLiteDatabase.OPEN_READWRITE|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
          try{db.execSQL("UPDATE stop_times SET pickup_type=0,drop_off_type=0");
            db.execSQL("UPDATE stops SET location_type=1 WHERE source_id='P1'");
            db.execSQL("UPDATE stop_times SET stop_headsign='Native platform destination' WHERE stop_sequence=1");
          }finally{db.close();}
          byte[] data=Files.readAllBytes(clone.toPath());JSONObject metadata=new JSONObject(new String(Files.readAllBytes(manifest.toPath()),StandardCharsets.UTF_8));
          metadata.put("database_bytes",data.length);metadata.put("database_sha256",hash(clone));
          ByteArrayOutputStream buffer=new ByteArrayOutputStream();
          try(ZipOutputStream zip=new ZipOutputStream(buffer)){
            zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(metadata.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("timetable.sqlite"));zip.write(data);zip.closeEntry();
          }
          try(TimetableRepository repo=f.repo()){
            repo.importPack(new ByteArrayInputStream(buffer.toByteArray()),ImportMode.USER_UNTRUSTED);
            List<TripStop> rows=repo.tripStops("T1",LocalDate.parse("2026-10-21"));
            JourneyPlanner.Request request=new JourneyPlanner.Request(repo.journeySelection(rows.get(0).stopId),repo.journeySelection(rows.get(rows.size()-1).stopId),Instant.parse("2026-10-21T08:00:00Z"),60,3,Collections.emptyList());
            JourneyPlanner.Result result=repo.journeys(request);
            require(result.found()&&result.legs.size()==1&&result.packInfo!=null&&!result.packInfo.authenticated);
            require("Native platform destination".equals(result.legs.get(0).board.destination));
            require(result.legs.get(0).board.departure.equals(rows.get(0).departure)&&result.packEnvelopeSha256.length()==64&&f.floor()==8);
            JourneyPlanner.Request stale=new JourneyPlanner.Request(priorFrom,priorTo,request.earliest,60,3,Collections.emptyList());
            require(refused(()->repo.journeys(stale)).getMessage().contains("generation"));
            JourneyPlanner.Walk staleWalk=new JourneyPlanner.Walk(rows.get(0).stopId,rows.get(rows.size()-1).stopId,60,"synthetic-mapped-path",priorFrom.packEnvelopeSha256);
            JourneyPlanner.Request staleMapping=new JourneyPlanner.Request(repo.journeySelection(rows.get(0).stopId),repo.journeySelection(rows.get(rows.size()-1).stopId),request.earliest,60,3,Collections.singletonList(staleWalk));
            require(refused(()->repo.journeys(staleMapping)).getMessage().contains("generation"));
          }
        }finally{
          Files.deleteIfExists(clone.toPath());
          try(TimetableRepository repo=f.repo();InputStream in=test.getAssets().open("transit/journey-v2.ctpack")){repo.importPack(in,ImportMode.USER_UNTRUSTED);}
        }
        require(before.equals(f.generation())&&f.floor()==8);
      });
      reporter.check("isolated Android exact candidate boundary cannot falsely claim complete",()->{
        List<Long> origins=new ArrayList<>();for(long id=1;id<=14;id++)origins.add(id);
        Instant at=Instant.parse("2026-10-21T08:00:00Z");int[] examined={0},walkBoards={0};
        JourneyPlanner.Network network=new JourneyPlanner.Network(){
          public Stop stop(long id){return new Stop(id,"S"+id,"Synthetic stop","",null,null,null,0,Double.NaN);}
          public List<TransferRule> rules(){return Collections.emptyList();}
          public List<TripStop> trip(String id,LocalDate day){return Arrays.asList(
              new TripStop(1,1,"reachable","T","Destination",1,15,"S15","Synthetic stop","",at.plusSeconds(300),at.plusSeconds(300),0,0,1,0,day,"Europe/Berlin"),
              new TripStop(1,1,"reachable","T","Destination",2,999,"S999","Synthetic stop","",at.plusSeconds(600),at.plusSeconds(600),0,0,1,0,day,"Europe/Berlin"));}
          public List<Departure> departures(long id,Instant now,int limit,int minutes){
            if(id==15){walkBoards[0]++;return Collections.singletonList(new Departure("reachable","T","Destination","Synthetic stop","",3,0,1,at.plusSeconds(300),"Europe/Berlin",id,"S15","2026-10-21",1));}
            int count=id<=13?299:JourneyPlanner.CANDIDATE_LIMIT-13*299;examined[0]+=count;
            List<Departure> rows=new ArrayList<>();for(int i=0;i<count;i++)rows.add(new Departure("reserved","T","Destination","Synthetic stop","",3,2,1,at,"Europe/Berlin",id,"S"+id,"2026-10-21",1));return rows;
          }
        };
        JourneyPlanner.Result result=JourneyPlanner.solve(network,new JourneyPlanner.Request(origins,Collections.singletonList(999L),at,60,2,Collections.singletonList(new JourneyPlanner.Walk(14,15,120,"synthetic-mapped-path"))));
        require(examined[0]==JourneyPlanner.CANDIDATE_LIMIT&&walkBoards[0]==0&&!result.found());
        require(result.issues.contains(JourneyPlanner.Issue.WORK_LIMIT)&&!result.completeWithinSuppliedConnections());
      });
      reporter.check("isolated Android v2 reopen and v1 restoration do not invent journey data",()->{
        try(TimetableRepository repo=f.repo()){
          require(repo.info().formatVersion==2&&repo.transferRules().size()==3);
          f.install(repo,"official-v8.ctpack",ImportMode.OFFICIAL_REQUIRED);f.query(repo);
          require(repo.info().formatVersion==1&&repo.info().maxArrivalSeconds==null&&repo.info().authenticated&&f.floor()==8);
          refused(()->repo.transferRules());refused(()->repo.tripStops("T1",LocalDate.parse("2026-10-21")));
          repo.removePack();require(!repo.hasPackChecked());
        }
        require(f.floor()==8&&Arrays.equals(new byte[]{7,4,9},Files.readAllBytes(sentinel.toPath())));
      });
    } finally {
      File[] files=directory.listFiles();if(files!=null)for(File file:files)require(file.isFile()&&file.delete());
      if(directory.exists())require(directory.delete());require(sentinel.delete());require(root.delete());
    }
  }
}
