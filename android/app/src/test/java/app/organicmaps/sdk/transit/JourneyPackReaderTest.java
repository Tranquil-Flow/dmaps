package app.organicmaps.sdk.transit;

import android.app.Application;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

/** Real production import/history/reopen and native-host SQLite; JDK fixture authority, not JNI/device. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class JourneyPackReaderTest {
  @Rule public TemporaryFolder temporary=new TemporaryFolder();
  private Context context; private File root; private byte[] v1,manifest1,data1,manifest2,data2;
  private PackEnvelopeTest.Fixture signer;
  private final Clock clock=Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"),ZoneOffset.UTC);
  @Before public void setup()throws Exception {
    root=temporary.newFolder();context=new ContextWrapper(RuntimeEnvironment.getApplication()){
      @Override public Context getApplicationContext(){return this;}
      @Override public File getFilesDir(){return root;}
    };signer=new PackEnvelopeTest.Fixture();
    v1=Files.readAllBytes(new File("src/androidTest/assets/transit/synthetic.ctpack").toPath());
    try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(v1))){ZipEntry e;while((e=in.getNextEntry())!=null){if(e.getName().equals("manifest.json"))manifest1=in.readAllBytes();else data1=in.readAllBytes();}}
    try(InputStream resource=getClass().getResourceAsStream("/transit/journey-v2.ctpack")){
      assertNotNull(resource);try(ZipInputStream in=new ZipInputStream(resource)){ZipEntry e;while((e=in.getNextEntry())!=null){if(e.getName().equals("manifest.json"))manifest2=in.readAllBytes();else data2=in.readAllBytes();}}
    }
  }
  private TimetableRepository repo(){return new TimetableRepository(context,signer.policy,clock,p->{});}
  private interface Action{void run()throws Exception;}
  private static IOException refused(Action action)throws Exception{try{action.run();fail("unexpected admission");return null;}catch(IOException expected){return expected;}}
  private static Object field(Object value,String name)throws Exception{return value.getClass().getField(name).get(value);}
  @SuppressWarnings("unchecked") private static List<?> trip(TimetableRepository r,String id,LocalDate day)throws Exception {
    return (List<?>)invoke(r,"tripStops",new Class<?>[]{String.class,LocalDate.class},id,day);
  }
  private static Object invoke(Object target,String method,Class<?>[] types,Object...args)throws Exception {
    try{return target.getClass().getMethod(method,types).invoke(target,args);}
    catch(InvocationTargetException ex){if(ex.getCause() instanceof Exception)throw (Exception)ex.getCause();throw ex;}
  }
  private static List<?> rules(TimetableRepository r)throws Exception{return (List<?>)invoke(r,"transferRules",new Class<?>[]{});}
  private static byte[] bytes(JSONObject o){return o.toString().getBytes(StandardCharsets.UTF_8);}
  private static String hash(byte[] b)throws Exception {StringBuilder s=new StringBuilder();for(byte x:MessageDigest.getInstance("SHA-256").digest(b))s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
  private byte[] signed(byte[] pack,long sequence)throws Exception {
    String body=PackEnvelopeTest.metadata();body="{\"pack\":"+new String(pack,StandardCharsets.UTF_8)+body.substring(body.indexOf(",\"namespace\":"));
    return signer.envelope(body.replace("\"sequence\":1,","\"sequence\":"+sequence+","),false);
  }
  private static InputStream archive(byte[] manifest,byte[] data)throws Exception {
    ByteArrayOutputStream b=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(b)){
      z.putNextEntry(new ZipEntry("manifest.json"));z.write(manifest);z.closeEntry();z.putNextEntry(new ZipEntry("timetable.sqlite"));z.write(data);z.closeEntry();
    }return new ByteArrayInputStream(b.toByteArray());
  }
  private byte[] mutate(String...sql)throws Exception {
    File f=temporary.newFile();Files.write(f.toPath(),data2);
    // A normal framework RW open adds android_metadata; data-only fixtures must not change DDL.
    SQLiteDatabase d=SQLiteDatabase.openDatabase(f.getPath(),null,SQLiteDatabase.OPEN_READWRITE|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
    try{for(String s:sql)d.execSQL(s);}finally{d.close();}return Files.readAllBytes(f.toPath());
  }
  private byte[] metadata(byte[] data)throws Exception {JSONObject m=new JSONObject(new String(manifest2,StandardCharsets.UTF_8));m.put("database_sha256",hash(data));m.put("database_bytes",data.length);return bytes(m);}
  private String generation()throws Exception {
    StringBuilder out=new StringBuilder();for(String name:new String[]{"timetable.sqlite","manifest.json","trust-state.json"})out.append(hash(Files.readAllBytes(new File(root,"transit_pack/"+name).toPath())));return out.toString();
  }
  @Test public void unsignedV2ImportBoardsAndTypedDataSurviveReopen()throws Exception {
    try(TimetableRepository r=repo()){
      r.importPack(archive(manifest2,data2),ImportMode.USER_UNTRUSTED);
      assertEquals(2,field(r.info(),"formatVersion"));assertFalse(r.info().authenticated);
      assertEquals(37200,field(r.info(),"maxArrivalSeconds"));assertFalse(r.searchStops("Alexanderplatz",20).isEmpty());
      List<?> stops=trip(r,"T1",LocalDate.parse("2026-10-21"));assertEquals(4,stops.size());
      for(int i=0;i<4;i++){assertEquals(i,field(stops.get(i),"dropOffType"));assertEquals(i,field(stops.get(i),"pickupType"));}
      assertEquals("P1a",field(stops.get(0),"stopSourceId"));assertEquals("1",field(stops.get(0),"platform"));
      assertEquals(Instant.parse("2026-10-21T08:00:00Z"),field(stops.get(0),"arrival"));
      List<?> transfers=rules(r);assertEquals(3,transfers.size());assertEquals(2,field(transfers.get(0),"transferType"));assertEquals(180,field(transfers.get(0),"minTransferSeconds"));assertNull(field(transfers.get(1),"minTransferSeconds"));assertEquals(3,field(transfers.get(1),"transferType"));
      assertNotNull(field(transfers.get(1),"fromRouteId"));assertNotNull(field(transfers.get(1),"toTripId"));
      long platform=0;for(Stop s:r.searchStops("Alexanderplatz",20))if(s.sourceId.equals("P1a"))platform=s.id;
      assertTrue(platform>0);assertFalse(r.departures(platform,Instant.parse("2026-10-21T07:00:00Z"),20,180).isEmpty());
      try{((List)stops).clear();fail("mutable result");}catch(UnsupportedOperationException expected){}
    }
    try(TimetableRepository r=repo()){assertEquals(2,field(r.info(),"formatVersion"));assertEquals(4,trip(r,"T1",LocalDate.parse("2026-10-21")).size());}
  }
  @Test public void officialV1V2CacheRollbackAndExplicitUntrustedReplacementRetainFloor()throws Exception {
    try(TimetableRepository r=repo()){
      r.importPack(archive(signed(manifest1,7),data1),ImportMode.OFFICIAL_REQUIRED);assertEquals(1,field(r.info(),"formatVersion"));assertNull(field(r.info(),"maxArrivalSeconds"));
      r.importPack(archive(signed(manifest2,8),data2),ImportMode.OFFICIAL_REQUIRED);assertTrue(r.info().authenticated);assertEquals(8,r.info().officialSequence);assertEquals(3,rules(r).size());
      String before=generation();refused(()->r.importPack(archive(signed(manifest1,7),data1),ImportMode.OFFICIAL_REQUIRED));assertEquals(before,generation());
      r.importPack(archive(manifest1,data1),ImportMode.USER_UNTRUSTED);assertEquals(1,field(r.info(),"formatVersion"));refused(()->trip(r,"T1",LocalDate.parse("2026-10-21")));refused(()->rules(r));assertFalse(r.searchStops("Hauptbahnhof",20).isEmpty());
      refused(()->r.importPack(archive(signed(manifest1,7),data1),ImportMode.OFFICIAL_REQUIRED));r.importPack(archive(signed(manifest2,8),data2),ImportMode.OFFICIAL_REQUIRED);assertEquals(8,r.info().officialSequence);
    }
  }
  @Test public void badV2MetadataNeverReplacesTheHealthyGeneration()throws Exception {
    try(TimetableRepository r=repo()){
      r.importPack(archive(manifest2,data2));String before=generation();
      for(Object bad:new Object[]{-1,604801,true,"0",JSONObject.NULL}){
        JSONObject m=new JSONObject(new String(manifest2,StandardCharsets.UTF_8));m.put("max_arrival_seconds",bad);
        refused(()->r.importPack(archive(bytes(m),data2)));assertEquals(before,generation());assertEquals(3,rules(r).size());
      }
    }
  }
  @Test public void schemaVersionColumnAndDataViolationsRejectBeforeCommit()throws Exception {
    try(TimetableRepository r=repo()){
      r.importPack(archive(manifest2,data2));String before=generation();
      JSONObject mismatch=new JSONObject(new String(manifest2,StandardCharsets.UTF_8));mismatch.put("format_version",1);
      refused(()->r.importPack(archive(bytes(mismatch),data2)));assertEquals(before,generation());
      for(String sql:new String[]{"ALTER TABLE stop_times RENAME COLUMN drop_off_type TO lost_rule","DROP TABLE transfers","CREATE VIEW injected AS SELECT 1"}){
        byte[] data=mutate(sql);refused(()->r.importPack(archive(metadata(data),data)));assertEquals(before,generation());
      }
      byte[] data=mutate("PRAGMA ignore_check_constraints=ON","UPDATE stop_times SET drop_off_type=9 WHERE stop_sequence=1");
      IOException invalid=refused(()->r.importPack(archive(metadata(data),data)));
      assertTrue("must reach row validation, not a fixture schema mismatch",invalid.getMessage().contains("stop-time data"));assertEquals(before,generation());
    }
  }
  @Test public void noFakeTimesAndAfterMidnightArrivalExtendsValidity()throws Exception {
    byte[] data=mutate("UPDATE stop_times SET arrival_seconds=NULL,departure_seconds=NULL WHERE stop_sequence=2","UPDATE stop_times SET arrival_seconds=91800,departure_seconds=NULL WHERE stop_sequence=4");
    JSONObject m=new JSONObject(new String(metadata(data),StandardCharsets.UTF_8));m.put("max_arrival_seconds",91800);m.put("max_departure_seconds",36630);m.put("valid_until","2026-10-21");
    try(TimetableRepository r=repo()){
      r.importPack(archive(bytes(m),data));List<?> stops=trip(r,"T1",LocalDate.parse("2026-10-21"));
      assertNull(field(stops.get(1),"arrival"));assertNull(field(stops.get(1),"departure"));assertNull(field(stops.get(3),"departure"));assertEquals(Instant.parse("2026-10-21T23:30:00Z"),field(stops.get(3),"arrival"));
      Instant end=GtfsTime.serviceOrigin(LocalDate.parse("2026-10-21"),ZoneId.of("Europe/Berlin")).plusSeconds(91800);
      assertFalse(r.info().isExpired(end));assertTrue(r.info().isExpired(end.plusSeconds(1)));
      assertTrue(trip(r,"T1",LocalDate.parse("2026-10-22")).isEmpty());
    }
  }
  @Test public void stopRoleRuntimeTypeAndRangeAreCheckedBeforeReplacingSignedGeneration()throws Exception {
    try(TimetableRepository r=repo()){
      // Same canonical schema and consistent counts: only the published cell value varies.
      for(int role:new int[]{0,4}){
        byte[] data=mutate("DELETE FROM transfers","UPDATE stops SET location_type="+role+" WHERE source_id='P1a'");
        JSONObject m=new JSONObject(new String(metadata(data),StandardCharsets.UTF_8));m.getJSONObject("counts").put("transfers",0);
        r.importPack(archive(bytes(m),data),ImportMode.USER_UNTRUSTED);
        assertEquals(role,r.tripStops("T1",LocalDate.parse("2026-10-21")).get(0).stopLocationType);
      }
      r.importPack(archive(signed(manifest2,8),data2),ImportMode.OFFICIAL_REQUIRED);
      String before=generation();
      for(String value:new String[]{"'entrance'","-1","5","4294967296","0.5"}){
        byte[] data=mutate("DELETE FROM transfers","UPDATE stops SET location_type="+value+" WHERE source_id='P1a'");
        JSONObject m=new JSONObject(new String(metadata(data),StandardCharsets.UTF_8));m.getJSONObject("counts").put("transfers",0);
        IOException invalid=refused(()->r.importPack(archive(bytes(m),data),ImportMode.USER_UNTRUSTED));
        assertTrue("must reach role validation",invalid.getMessage().contains("stop role"));
        assertEquals(before,generation());assertTrue(r.info().authenticated);assertEquals(8,r.info().officialSequence);
        assertEquals(3,r.transferRules().size());
      }
    }
  }
  @Test public void inactiveServiceUnknownTripAndBackwardsTripCannotProduceData()throws Exception {
    try(TimetableRepository r=repo()){
      r.importPack(archive(manifest2,data2));assertTrue(trip(r,"T1",LocalDate.parse("2026-10-24")).isEmpty());assertTrue(trip(r,"missing",LocalDate.parse("2026-10-21")).isEmpty());
      // Keep final-row maxima unchanged and insert an untimed row before the backwards record.
      byte[] data=mutate("UPDATE stop_times SET arrival_seconds=NULL,departure_seconds=NULL WHERE stop_sequence=2",
          "UPDATE stop_times SET arrival_seconds=1,departure_seconds=2 WHERE stop_sequence=3");
      // Cross-record chronology must be rejected either before activation or at query.
      try{r.importPack(archive(metadata(data),data));}catch(IOException rejected){assertTrue(rejected.getMessage().contains("chronology"));return;}
      IOException rejected=refused(()->trip(r,"T1",LocalDate.parse("2026-10-21")));
      assertTrue(rejected.getMessage().contains("chronology"));
    }
  }
  @Test public void plannerUsesActualRepositoryAndEffectiveStopHeadsign()throws Exception {
    byte[] data=mutate("UPDATE stop_times SET pickup_type=0,drop_off_type=0",
        "UPDATE stops SET location_type=1 WHERE source_id='P1'",
        "UPDATE stop_times SET stop_headsign='Effective platform destination' WHERE stop_sequence=1");
    try(TimetableRepository r=repo()){
      r.importPack(archive(metadata(data),data));
      List<TripStop> rows=r.tripStops("T1",LocalDate.parse("2026-10-21"));
      JourneyPlanner.Request request=new JourneyPlanner.Request(r.journeySelection(rows.get(0).stopId),
          r.journeySelection(rows.get(rows.size()-1).stopId),Instant.parse("2026-10-21T08:00:00Z"),60,3,Collections.emptyList());
      JourneyPlanner.Result result=(JourneyPlanner.Result)invoke(r,"journeys",new Class<?>[]{JourneyPlanner.Request.class},request);
      assertTrue(result.found());assertEquals(1,result.legs.size());
      assertEquals(rows.get(0).departure,result.legs.get(0).board.departure);
      assertEquals("Effective platform destination",result.legs.get(0).board.destination);
      assertNotNull(field(result,"packInfo"));assertEquals(64,((String)field(result,"packEnvelopeSha256")).length());
      List<?> platforms=(List<?>)invoke(r,"boardingPlatforms",new Class<?>[]{long.class},rows.get(0).stopId);
      assertEquals(1,platforms.size());assertEquals(rows.get(0).stopId,((Stop)platforms.get(0)).id);
    }
  }
  @Test public void staleSelectionsAndMappedWalksCannotBeReinterpretedByReplacementPack()throws Exception {
    byte[] a=mutate("UPDATE stop_times SET pickup_type=0,drop_off_type=0","UPDATE stops SET location_type=1 WHERE source_id='P1'");
    try(TimetableRepository r=repo()){
      r.importPack(archive(metadata(a),a));List<TripStop> rows=r.tripStops("T1",LocalDate.parse("2026-10-21"));
      long origin=rows.get(0).stopId,destination=rows.get(rows.size()-1).stopId;
      JourneyPlanner.Selection fromA=r.journeySelection(origin),toA=r.journeySelection(destination);
      JourneyPlanner.Request stale=new JourneyPlanner.Request(fromA,toA,Instant.parse("2026-10-21T08:00:00Z"),60,3,Collections.emptyList());
      byte[] b=mutate("UPDATE stop_times SET pickup_type=0,drop_off_type=0","UPDATE stops SET location_type=1 WHERE source_id='P1'","UPDATE stops SET name='Replacement platform',search_name='replacement platform' WHERE source_id='P1a'");
      r.importPack(archive(metadata(b),b));JourneyPlanner.Selection fromB=r.journeySelection(origin),toB=r.journeySelection(destination);
      assertNotEquals(fromA.packEnvelopeSha256,fromB.packEnvelopeSha256);
      assertTrue(refused(()->r.journeys(stale)).getMessage().contains("generation"));
      JourneyPlanner.Request fresh=new JourneyPlanner.Request(fromB,toB,stale.earliest,60,3,Collections.emptyList());assertTrue(r.journeys(fresh).found());
      try{new JourneyPlanner.Request(fromA,toB,stale.earliest,60,3,Collections.emptyList());fail("mixed selection generations");}catch(IllegalArgumentException expected){}
      JourneyPlanner.Walk staleWalk=new JourneyPlanner.Walk(origin,destination,60,"synthetic-mapped-path",fromA.packEnvelopeSha256);
      JourneyPlanner.Request mismatchedWalk=new JourneyPlanner.Request(fromB,toB,stale.earliest,60,3,Collections.singletonList(staleWalk));
      assertTrue(refused(()->r.journeys(mismatchedWalk)).getMessage().contains("generation"));
      JourneyPlanner.Request unbound=new JourneyPlanner.Request(Collections.singletonList(origin),Collections.singletonList(destination),stale.earliest,60,3,Collections.emptyList());
      assertTrue(refused(()->r.journeys(unbound)).getMessage().contains("generation"));
    }
  }
  @Test public void plannerDoesNotReinterpretLegacyDataOrInheritRemovedAuthority()throws Exception {
    JourneyPlanner.Request request=new JourneyPlanner.Request(Collections.singletonList(1L),Collections.singletonList(2L),clock.instant(),60,2,Collections.emptyList());
    try(TimetableRepository r=repo()){
      r.importPack(archive(manifest1,data1));
      refused(()->invoke(r,"journeys",new Class<?>[]{JourneyPlanner.Request.class},request));
      r.removePack();refused(()->invoke(r,"journeys",new Class<?>[]{JourneyPlanner.Request.class},request));
    }
  }
  @Test public void riderSearchAndPublishedResultsAreFencedAgainstReusedIds()throws Exception {
    byte[] a=mutate("UPDATE stop_times SET pickup_type=0,drop_off_type=0","UPDATE stops SET location_type=1 WHERE source_id='P1'");
    try(TimetableRepository r=repo()){
      r.importPack(archive(metadata(a),a));
      Object candidates=invoke(r,"searchJourneyStops",new Class<?>[]{String.class,int.class},"Alexanderplatz",20);
      String envelope=(String)field(candidates,"packEnvelopeSha256");
      @SuppressWarnings("unchecked") List<Stop> stops=(List<Stop>)field(candidates,"stops");assertFalse(stops.isEmpty());
      Stop selected=stops.get(0);
      JourneyPlanner.Selection from=(JourneyPlanner.Selection)invoke(r,"journeySelection",new Class<?>[]{long.class,String.class},selected.id,envelope);
      assertFalse(from.platforms.isEmpty());
      assertEquals(true,invoke(r,"isJourneyCurrent",new Class<?>[]{String.class},envelope));
      byte[] b=mutate("UPDATE stop_times SET pickup_type=0,drop_off_type=0","UPDATE stops SET location_type=1 WHERE source_id='P1'","UPDATE stops SET name='Different station',search_name='different station' WHERE id="+selected.id);
      r.importPack(archive(metadata(b),b));
      assertEquals(false,invoke(r,"isJourneyCurrent",new Class<?>[]{String.class},envelope));
      assertTrue(refused(()->invoke(r,"journeySelection",new Class<?>[]{long.class,String.class},selected.id,envelope)).getMessage().contains("generation"));
      r.removePack();refused(()->invoke(r,"isJourneyCurrent",new Class<?>[]{String.class},envelope));
    }
  }
  @Test public void newFormatDoesNotInstallAProductionRoot()throws Exception {
    try(TimetableRepository r=repo()){r.importPack(archive(signed(manifest2,8),data2),ImportMode.OFFICIAL_REQUIRED);}
    try(TimetableRepository r=new TimetableRepository(context)){assertTrue(r.hasPackChecked());refused(r::info);refused(()->rules(r));}
  }
}
