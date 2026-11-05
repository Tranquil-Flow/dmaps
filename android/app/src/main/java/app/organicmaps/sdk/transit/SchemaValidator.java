package app.organicmaps.sdk.transit;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.IOException;
import java.util.*;

/** Validate supported versioned column/schema contracts before activation or querying. */
final class SchemaValidator {
  private static final String[][] TABLES = {
    {"meta", "key:TEXT", "value:TEXT"},
    {"agencies", "id:INTEGER", "source_id:TEXT", "name:TEXT", "timezone:TEXT"},
    {"routes", "id:INTEGER", "source_id:TEXT", "agency_id:INTEGER", "short_name:TEXT", "long_name:TEXT", "route_type:INTEGER", "color:TEXT"},
    {"stops", "id:INTEGER", "source_id:TEXT", "name:TEXT", "search_name:TEXT", "lat:REAL", "lon:REAL", "parent_id:INTEGER", "location_type:INTEGER", "platform:TEXT"},
    {"services", "id:INTEGER", "source_id:TEXT"},
    {"service_days", "service_date:INTEGER", "service_id:INTEGER"},
    {"trips", "id:INTEGER", "source_id:TEXT", "route_id:INTEGER", "service_id:INTEGER", "headsign:TEXT", "direction_id:INTEGER"},
    {"stop_times", "trip_id:INTEGER", "stop_sequence:INTEGER", "stop_id:INTEGER", "arrival_seconds:INTEGER", "departure_seconds:INTEGER", "pickup_type:INTEGER", "stop_headsign:TEXT", "timepoint:INTEGER"}
  };
  // Exact non-internal sqlite_master contract emitted by SCHEMA_V2_SQL (16 objects).
  // Framing: sorted type/name; UTF-8 fields type,name,tbl_name,sql, each prefixed by uint32-BE length.
  private static final String V2_SCHEMA_SHA256 = "1accf247bf186384aa5a18e3719fb62252cd7317f29147e5a34f0f580cdba38c";

  static void validate(SQLiteDatabase db) throws IOException { validate(db, 1); }

  static void validate(SQLiteDatabase db, int version) throws IOException {
    if(version!=1 && version!=2) throw new IOException("Unsupported timetable schema version");
    List<String[]> tables=new ArrayList<>(Arrays.asList(TABLES));
    if(version==2) {
      validateJourneyDefinitions(db);
      String[] times=Arrays.copyOf(TABLES[TABLES.length-1],TABLES[TABLES.length-1].length+1);
      times[times.length-1]="drop_off_type:INTEGER";tables.set(tables.size()-1,times);
      tables.add(new String[]{"transfers","id:INTEGER","from_stop_id:INTEGER","to_stop_id:INTEGER",
          "from_route_id:INTEGER","to_route_id:INTEGER","from_trip_id:INTEGER","to_trip_id:INTEGER",
          "transfer_type:INTEGER","min_transfer_time:INTEGER"});
    }
    Set<String> remaining = new HashSet<>();
    for(String[] table:tables) remaining.add(table[0]);
    try(Cursor c=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",null)) {
      while(c.moveToNext()) if(!remaining.remove(c.getString(0))) throw new IOException("Unsupported timetable schema table");
    }
    if(!remaining.isEmpty()) throw new IOException("Missing timetable schema table");
    for(String[] table:tables) {
      List<String> columns=new ArrayList<>();
      try(Cursor c=db.rawQuery("PRAGMA table_info('"+table[0]+"')",null)) {
        while(c.moveToNext()) columns.add(c.getString(1)+":"+c.getString(2).toUpperCase(Locale.ROOT));
      }
      if(!columns.equals(Arrays.asList(table).subList(1,table.length))) throw new IOException("Unsupported timetable schema columns");
    }
    try(Cursor c=db.rawQuery("PRAGMA foreign_key_check",null)) {
      if(c.moveToFirst()) throw new IOException("Invalid timetable references");
    }
  }

  private static void validateJourneyDefinitions(SQLiteDatabase db)throws IOException {
    final java.security.MessageDigest digest;
    try { digest=java.security.MessageDigest.getInstance("SHA-256"); }
    catch(java.security.NoSuchAlgorithmException impossible){throw new IOException("Schema digest unavailable");}
    int objects=0;
    try(Cursor c=db.rawQuery("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE substr(name,1,7)<>'sqlite_' ORDER BY type,name",null)) {
      while(c.moveToNext()) {
        if(++objects>64)throw new IOException("Unsupported journey schema");
        for(int i=0;i<4;i++) {
          if(c.isNull(i))throw new IOException("Unsupported journey schema");
          byte[] value=c.getString(i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
          int n=value.length;if(n>32768)throw new IOException("Unsupported journey schema");
          digest.update((byte)(n>>>24));digest.update((byte)(n>>>16));digest.update((byte)(n>>>8));digest.update((byte)n);digest.update(value);
        }
      }
    }
    StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
    if(!V2_SCHEMA_SHA256.equals(hex.toString()))throw new IOException("Unsupported journey schema definitions");
  }

  static void validateJourneyRows(SQLiteDatabase db,int maxDeparture,int maxArrival)throws IOException {
    // SQLite INTEGER affinity permits TEXT/REAL cells; getInt would silently coerce them.
    try(Cursor c=db.rawQuery("SELECT 1 FROM stops WHERE typeof(location_type)<>'integer' OR location_type NOT BETWEEN 0 AND 4 LIMIT 1",null)) {
      if(c.moveToFirst())throw new IOException("Invalid journey stop role");
    }
    // One aggregate pass, no window functions or per-record Java/JNI traversal at open.
    String invalid="typeof(trip_id)<>'integer' OR trip_id<1 OR typeof(stop_id)<>'integer' OR stop_id<1"
        +" OR typeof(stop_sequence)<>'integer' OR stop_sequence NOT BETWEEN 0 AND 100000"
        +" OR typeof(pickup_type)<>'integer' OR pickup_type NOT BETWEEN 0 AND 3"
        +" OR typeof(drop_off_type)<>'integer' OR drop_off_type NOT BETWEEN 0 AND 3"
        +" OR (timepoint IS NOT NULL AND (typeof(timepoint)<>'integer' OR timepoint NOT BETWEEN 0 AND 1))"
        +" OR (arrival_seconds IS NOT NULL AND (typeof(arrival_seconds)<>'integer' OR arrival_seconds NOT BETWEEN 0 AND 604800))"
        +" OR (departure_seconds IS NOT NULL AND (typeof(departure_seconds)<>'integer' OR departure_seconds NOT BETWEEN 0 AND 604800))"
        +" OR (arrival_seconds IS NOT NULL AND departure_seconds IS NOT NULL AND arrival_seconds>departure_seconds)";
    try(Cursor c=db.rawQuery("SELECT count(*),COALESCE(MAX(arrival_seconds),0),COALESCE(MAX(departure_seconds),0),"
        +"COALESCE(MAX(CASE WHEN "+invalid+" THEN 1 ELSE 0 END),0) FROM stop_times",null)) {
      if(!c.moveToFirst()||c.getLong(0)==0||c.getInt(3)!=0||c.getLong(1)!=maxArrival||c.getLong(2)!=maxDeparture)
        throw new IOException("Invalid journey stop-time data or temporal bounds");
    }
    try(Cursor c=db.rawQuery("SELECT count(*) FROM transfers",null)) {
      if(!c.moveToFirst()||c.getLong(0)>TimetableRepository.MAX_TRANSFER_RULES)throw new IOException("Journey transfer bound exceeded");
    }
    String bad="typeof(x.transfer_type)<>'integer' OR x.transfer_type NOT BETWEEN 0 AND 5"
        +" OR (x.min_transfer_time IS NOT NULL AND (typeof(x.min_transfer_time)<>'integer' OR x.min_transfer_time NOT BETWEEN 0 AND 2147483647))"
        +" OR (x.transfer_type=2 AND x.min_transfer_time IS NULL)"
        +" OR (x.transfer_type<4 AND (x.from_stop_id IS NULL OR x.to_stop_id IS NULL))"
        +" OR (x.transfer_type>=4 AND (x.from_trip_id IS NULL OR x.to_trip_id IS NULL))"
        +" OR (x.from_stop_id IS NOT NULL AND fs.location_type NOT IN (0,1))"
        +" OR (x.to_stop_id IS NOT NULL AND ts.location_type NOT IN (0,1))"
        +" OR (x.transfer_type>=4 AND ((x.from_stop_id IS NOT NULL AND fs.location_type<>0) OR (x.to_stop_id IS NOT NULL AND ts.location_type<>0)))"
        +" OR (x.from_trip_id IS NOT NULL AND x.from_route_id IS NOT NULL AND ft.route_id<>x.from_route_id)"
        +" OR (x.to_trip_id IS NOT NULL AND x.to_route_id IS NOT NULL AND tt.route_id<>x.to_route_id)";
    for(String selector:new String[]{"from_stop_id","to_stop_id","from_route_id","to_route_id","from_trip_id","to_trip_id"})
      bad+=" OR (x."+selector+" IS NOT NULL AND (typeof(x."+selector+")<>'integer' OR x."+selector+"<1))";
    try(Cursor c=db.rawQuery("SELECT 1 FROM transfers x LEFT JOIN stops fs ON fs.id=x.from_stop_id"
        +" LEFT JOIN stops ts ON ts.id=x.to_stop_id LEFT JOIN trips ft ON ft.id=x.from_trip_id"
        +" LEFT JOIN trips tt ON tt.id=x.to_trip_id WHERE "+bad+" LIMIT 1",null)) {
      if(c.moveToFirst())throw new IOException("Invalid published transfer rule");
    }
  }
}
