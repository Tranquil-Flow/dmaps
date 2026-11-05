package app.organicmaps.sdk.transit;

import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;

/** Test-only actual producer-derived SQLite fixture, isolated directory and public untrusted import.
 * No production roots, JNI fake success, user pack mutation or fabricated service responses. */
public final class JourneyUiFixture {
  public final TimetableRepository repository;
  private byte[] manifest,data; private final File root;
  public JourneyUiFixture(Context app,File root,Clock clock)throws Exception {
    this.root=root;
    Context context=new ContextWrapper(app){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return root;}};
    repository=new TimetableRepository(context,PackTrustPolicy.production(),clock,p->{});
    try(InputStream resource=getClass().getResourceAsStream("/transit/journey-v2.ctpack");ZipInputStream in=new ZipInputStream(resource)){
      ZipEntry e;while((e=in.getNextEntry())!=null){if(e.getName().equals("manifest.json"))manifest=in.readAllBytes();else data=in.readAllBytes();}
    }
    File f=new File(root,"fixture-input.sqlite");Files.write(f.toPath(),data);
    SQLiteDatabase db=SQLiteDatabase.openDatabase(f.getPath(),null,SQLiteDatabase.OPEN_READWRITE|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
    try{db.execSQL("UPDATE stop_times SET pickup_type=0,drop_off_type=0");db.execSQL("UPDATE stops SET location_type=1 WHERE source_id='P1'");}finally{db.close();}
    data=Files.readAllBytes(f.toPath());Files.delete(f.toPath());
    JSONObject meta=new JSONObject(new String(manifest,StandardCharsets.UTF_8));
    StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))sha.append(String.format(Locale.ROOT,"%02x",b&255));
    meta.put("database_sha256",sha.toString());meta.put("database_bytes",data.length);manifest=meta.toString().getBytes(StandardCharsets.UTF_8);
    restore();
  }
  /** Real valid A→B replacement retaining numeric IDs for deterministic publication tests. */
  public void replaceLabels()throws Exception {changeRows("UPDATE stops SET name=name||' B',search_name=search_name||' b'");}
  /** Authored German names (synthetic, not VBB rows), stored the way the compiler stores them: casefolded with diacritics kept. */
  public void useGermanStationNames()throws Exception {
    changeRows("UPDATE stops SET name='S Südkreuz Bhf (Berlin)',search_name='s südkreuz bhf (berlin)' WHERE source_id='S1'",
        "UPDATE stops SET name='U Görlitzer Bahnhof (Berlin)',search_name='u görlitzer bahnhof (berlin)' WHERE source_id IN ('H1','H1a')",
        "UPDATE stops SET name='Bärenplatz Straße',search_name='bärenplatz strasse' WHERE source_id IN ('P1','P1a','P1b')");
  }
  /** Authored multi-word names (synthetic, not VBB rows): station + platform share one phrase; S1 holds the same words reordered. */
  public void useMultiWordStationNames()throws Exception {
    changeRows("UPDATE stops SET name='S+U Berlin Hauptbahnhof',search_name='s+u berlin hauptbahnhof' WHERE source_id IN ('H1','H1a')",
        "UPDATE stops SET name='Hauptbahnhof Berlin Ost',search_name='hauptbahnhof berlin ost' WHERE source_id='S1'");
  }
  /** Synthetic coordinates matching the actual authored MWM; never regional/real-world evidence. */
  public void useAuthoredWalkingCoordinates()throws Exception {changeRows("UPDATE stops SET lat=0,lon=CASE WHEN source_id='P1b' THEN 0.002 WHEN source_id='S1' THEN 0.004 ELSE 0 END","UPDATE stop_times SET pickup_type=1 WHERE stop_sequence=1");}
  /** Synthetic ride to P1b, then real authored-map egress back to S1; vehicle alighting at S1 prohibited. */
  public void useAuthoredEgressCoordinates()throws Exception {changeRows("UPDATE stops SET lat=0,lon=CASE WHEN source_id='P1b' THEN 0.002 ELSE 0 END","UPDATE stop_times SET pickup_type=0,drop_off_type=CASE WHEN stop_id=(SELECT id FROM stops WHERE source_id='S1') THEN 1 ELSE 0 END");}
  /** Initial street path to boarding sibling, then vehicle back to a distinct destination at
   * the origin coordinates. Coincidence does not invent a direct zero-length platform link. */
  public void useAuthoredAccessCoordinates()throws Exception {changeRows("UPDATE stops SET lat=0,lon=CASE WHEN source_id='P1b' THEN 0.002 ELSE 0 END","UPDATE stop_times SET pickup_type=CASE WHEN stop_id=(SELECT id FROM stops WHERE source_id='P1a') THEN 1 ELSE 0 END,drop_off_type=0");}
  public void useAuthoredJustMissedBoarding()throws Exception {
    useAuthoredEgressQueryCases(1,1,false);
    changeRows("UPDATE stop_times SET arrival_seconds=36000,departure_seconds=36030 WHERE stop_sequence=1",
        "UPDATE stop_times SET arrival_seconds=36900,departure_seconds=36930,pickup_type=0 WHERE stop_sequence=2",
        "INSERT INTO stop_times SELECT 1,3,id,37800,37830,1,'',1,0 FROM stops WHERE source_id='S1'");
  }
  /** First sibling misses its departures or lies outside the real map; a later one can board.
   * Mode3 has four missed nominations before a fifth catchable sibling, testing the unchanged cap. */
  public void useAuthoredInitialAlternatives(int mode)throws Exception {
    if(mode<0||mode>3)throw new IllegalArgumentException();useAuthoredEgressQueryCases(1,1,false);
    List<String> sql=new ArrayList<>();sql.add("DELETE FROM stop_times");
    sql.add("UPDATE stops SET parent_id=(SELECT id FROM stops WHERE source_id='P1'),lat=0,lon="+(mode==1?"0.006":"0.002")+" WHERE source_id='H1a'");
    sql.add("INSERT INTO trips SELECT 2,'A2',route_id,service_id,headsign,direction_id FROM trips WHERE id=1");
    sql.add("INSERT INTO stop_times SELECT 1,1,id,36000,36030,0,'',1,1 FROM stops WHERE source_id='H1a'");
    sql.add("INSERT INTO stop_times SELECT 1,2,id,36600,36630,1,'',1,0 FROM stops WHERE source_id='S1'");
    int good=(mode==2||mode==3)?36000:36900;
    sql.add("INSERT INTO stop_times SELECT 2,1,id,"+good+","+(good+30)+",0,'',1,1 FROM stops WHERE source_id='P1b'");
    sql.add("INSERT INTO stop_times SELECT 2,2,id,37800,37830,1,'',1,0 FROM stops WHERE source_id='S1'");
    if(mode==3)for(int id=7;id<=9;id++){
      sql.add("INSERT INTO stops SELECT "+id+",'A"+id+"',name,search_name,lat,lon,parent_id,location_type,platform FROM stops WHERE source_id='P1b'");
      int trip=id-4,at=id==9?36900:36000;
      sql.add("INSERT INTO trips SELECT "+trip+",'A"+trip+"',route_id,service_id,headsign,direction_id FROM trips WHERE id=1");
      sql.add("INSERT INTO stop_times VALUES("+trip+",1,"+id+","+at+","+(at+30)+",0,'',1,1)");
      sql.add("INSERT INTO stop_times SELECT "+trip+",2,id,37800,37830,1,'',1,0 FROM stops WHERE source_id='S1'");
    }
    changeRows(sql.toArray(new String[0]));
  }
  public void useAuthoredBusyOrigin()throws Exception {
    useAuthoredEgressQueryCases(1,1,false);List<String> sql=new ArrayList<>();
    for(int id=2;id<=TimetableRepository.MAX_BOARD_LIMIT;id++){
      sql.add("INSERT INTO trips SELECT "+id+",'B"+id+"',route_id,service_id,headsign,direction_id FROM trips WHERE id=1");
      sql.add("INSERT INTO stop_times SELECT "+id+",stop_sequence,stop_id,arrival_seconds,departure_seconds,pickup_type,stop_headsign,timepoint,drop_off_type FROM stop_times WHERE trip_id=1");
    }changeRows(sql.toArray(new String[0]));
  }
  /** Four reached transit arrivals after a required initial hop; the fourth egress must share
   * the existing four-query cap rather than receive a second independent native budget. */
  public void useAuthoredSharedAccessEgressQuota()throws Exception {
    useAuthoredEgressQueryCases(4,1,false);
    changeRows("UPDATE stops SET lat=0,lon=CASE WHEN source_id IN ('P1b','H1a') THEN 0.002 ELSE 0 END",
        "UPDATE stop_times SET stop_id=(SELECT id FROM stops WHERE source_id=CASE WHEN stop_sequence=1 THEN 'P1b' ELSE 'H1a' END),arrival_seconds=arrival_seconds+600,departure_seconds=departure_seconds+600");
  }
  /** Synthetic alternatives for real-native quota/cache checks. Duplicate trips preserve distinct
   * incoming identities while sharing an exact (alighting platform, arrival instant) cache key. */
  public void useAuthoredEgressQueryCases(int instants,int duplicates,boolean outside)throws Exception {
    if(instants<1||instants>8||duplicates<1||duplicates>8)throw new IllegalArgumentException();
    List<String> sql=new ArrayList<>();
    sql.add("DELETE FROM transfers");sql.add("DELETE FROM stop_times");sql.add("DELETE FROM trips WHERE id<>1");
    sql.add("UPDATE stops SET lat=CASE WHEN source_id='S1' THEN "+(outside?"0.006":"0")+" ELSE 0 END,lon=CASE WHEN source_id='P1b' THEN 0.002 ELSE 0 END");
    int id=0;for(int at=0;at<instants;at++)for(int copy=0;copy<duplicates;copy++){
      id++;if(id>1)sql.add("INSERT INTO trips SELECT "+id+",'Q"+id+"',route_id,service_id,headsign,direction_id FROM trips WHERE id=1");
      sql.add("INSERT INTO stop_times SELECT "+id+",1,id,"+(36000+at*60)+","+(36000+at*60)+",0,'',1,1 FROM stops WHERE source_id='P1a'");
      sql.add("INSERT INTO stop_times SELECT "+id+",2,id,"+(36600+at*60)+","+(36600+at*60)+",1,'',1,0 FROM stops WHERE source_id='P1b'");
    }
    changeRows(sql.toArray(new String[0]));
  }
  private void changeRows(String...sql)throws Exception {
    File file=new File(root,"replacement-input.sqlite");Files.write(file.toPath(),data);
    SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READWRITE|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
    JSONObject meta=new JSONObject(new String(manifest,StandardCharsets.UTF_8));
    try{
      for(String statement:sql)db.execSQL(statement);
      JSONObject counts=meta.getJSONObject("counts");
      for(String table:new String[]{"agencies","routes","service_days","services","stop_times","stops","transfers","trips"})
        try(android.database.Cursor c=db.rawQuery("SELECT count(*) FROM "+table,null)){c.moveToFirst();counts.put(table,c.getLong(0));}
      try(android.database.Cursor c=db.rawQuery("SELECT max(arrival_seconds),max(departure_seconds) FROM stop_times",null)){c.moveToFirst();meta.put("max_arrival_seconds",c.getLong(0));meta.put("max_departure_seconds",c.getLong(1));}
    }finally{db.close();}
    data=Files.readAllBytes(file.toPath());Files.delete(file.toPath());
    StringBuilder sha=new StringBuilder();
    for(byte b:MessageDigest.getInstance("SHA-256").digest(data))sha.append(String.format(Locale.ROOT,"%02x",b&255));
    meta.put("database_sha256",sha.toString());meta.put("database_bytes",data.length);manifest=meta.toString().getBytes(StandardCharsets.UTF_8);restore();
  }
  public void restore()throws Exception {
    ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(out)){
      z.putNextEntry(new ZipEntry("manifest.json"));z.write(manifest);z.closeEntry();z.putNextEntry(new ZipEntry("timetable.sqlite"));z.write(data);z.closeEntry();
    }repository.importPack(new ByteArrayInputStream(out.toByteArray()),ImportMode.USER_UNTRUSTED);
  }
}
