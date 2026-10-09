package app.organicmaps.sdk.transit;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.IOException;
import java.util.*;

/** Validate the frozen v1 column contract before activation or querying. */
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
  static void validate(SQLiteDatabase db) throws IOException {
    Set<String> remaining = new HashSet<>();
    for(String[] table:TABLES) remaining.add(table[0]);
    try(Cursor c=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",null)) {
      while(c.moveToNext()) if(!remaining.remove(c.getString(0))) throw new IOException("Unsupported timetable schema table");
    }
    if(!remaining.isEmpty()) throw new IOException("Missing timetable schema table");
    for(String[] table:TABLES) {
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
}
