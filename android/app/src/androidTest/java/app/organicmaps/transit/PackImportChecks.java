package app.organicmaps.transit;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Debug;
import app.organicmaps.sdk.transit.*;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.json.*;

/** Real Android SQLite/importer over isolated synthetic fixtures + frozen real VBB cases. */
final class PackImportChecks
{
  private final TransitPilotInstrumentation runner;
  PackImportChecks(TransitPilotInstrumentation runner) { this.runner = runner; }
  private static void require(boolean ok) { if (!ok) throw new AssertionError(); }
  private InputStream asset(String name) throws IOException
  { return runner.getContext().getAssets().open("transit/" + name); }
  private static String digest(File file) throws Exception
  { MessageDigest digest = MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)) { byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n); } return Arrays.toString(digest.digest()); }

  void run() throws Exception
  {
    File root=new File(runner.getTargetContext().getCacheDir(),"dmaps-import-"+UUID.randomUUID());
    require(root.mkdir());
    Context isolated=new ContextWrapper(runner.getTargetContext()) {
      @Override public Context getApplicationContext() { return this; }
      @Override public File getFilesDir() { return root; }
    };
    File unrelated=new File(root,"maps-favorites-tracks-sentinel");Files.write(unrelated.toPath(),new byte[]{1,2,3});
    try
    {
      runner.check("isolated real importer accepts canonical synthetic pack and reopens query", () -> {
        try(TimetableRepository repo=new TimetableRepository(isolated);InputStream in=asset("synthetic.ctpack"))
        {
          repo.importPack(in);require(repo.hasPack());require(repo.info().publisher.contains("TEST FIXTURE"));
        }
        try(TimetableRepository repo=new TimetableRepository(isolated))
        {
          List<Stop> found=repo.searchStops("Hauptbahnhof",20);require(!found.isEmpty());
          Stop parent=null;for(Stop stop:found)if(stop.locationType==1)parent=stop;
          require(parent!=null);
          List<Departure> board=repo.departures(parent.id,Instant.parse("2026-10-14T08:00:00Z"),20,180);
          require(board.size()==1 && "H1a".equals(board.get(0).stopSourceId));
        }
      });
      for(String name:new String[]{"bad-digest.ctpack","bad-version.ctpack","oversize-declaration.ctpack","traversal.ctpack","corrupt-sqlite.ctpack","large-manifest.ctpack"})
      {
        runner.check("isolated rejected "+name+" preserves previous pack through restart", () -> {
          File db=new File(root,"transit_pack/timetable.sqlite"),manifest=new File(root,"transit_pack/manifest.json");
          String beforeDb=digest(db),beforeManifest=digest(manifest);
          try(TimetableRepository repo=new TimetableRepository(isolated);InputStream in=asset(name))
          { try {repo.importPack(in);throw new AssertionError("invalid import accepted");}catch(IOException expected){} }
          require(beforeDb.equals(digest(db)) && beforeManifest.equals(digest(manifest)));
          try(TimetableRepository restarted=new TimetableRepository(isolated))
          {require(restarted.hasPack() && !restarted.searchStops("Hauptbahnhof",20).isEmpty());}
          require(unrelated.isFile());
        });
      }
      runner.check("isolated stream interruption preserves usable previous pack and cleans staging", () -> {
        File db=new File(root,"transit_pack/timetable.sqlite");String before=digest(db);
        try(TimetableRepository repo=new TimetableRepository(isolated);InputStream real=asset("synthetic.ctpack"))
        {
          InputStream broken=new FilterInputStream(real) {
            int read;
            @Override public int read(byte[] b,int offset,int length)throws IOException
            {if(read>=64)throw new IOException("synthetic interruption");int n=super.read(b,offset,Math.min(64-read,length));if(n>0)read+=n;return n;}
          };
          try{repo.importPack(broken);throw new AssertionError("interrupted stream accepted");}catch(IOException expected){}
          require(repo.hasPack() && repo.info()!=null);
        }
        require(before.equals(digest(db)));
        for(File file:Objects.requireNonNull(new File(root,"transit_pack").listFiles()))
          require(!file.getName().startsWith("staging.") && !file.getName().endsWith(".zip"));
      });
      runner.check("isolated Android calendar exception overnight reservation and headsign semantics", () -> {
        try(TimetableRepository repo=new TimetableRepository(isolated);InputStream in=asset("calendar-overnight.ctpack"))
        {
          repo.importPack(in);
          Stop platform=null;for(Stop s:repo.searchStops("Alexanderplatz",20))if("P1a".equals(s.sourceId))platform=s;
          require(platform!=null);
          List<Departure> rows=repo.departures(platform.id,Instant.parse("2026-10-13T23:00:00Z"),20,60);
          require(rows.size()==2);
          require("T_OV".equals(rows.get(0).tripSourceId) && "2026-10-13".equals(rows.get(0).serviceDate));
          require(rows.get(0).when.equals(Instant.parse("2026-10-13T23:30:00Z")));
          require("Platform override".equals(rows.get(0).destination));
          require(rows.get(1).requiresReservation() && rows.get(1).timepoint==0);
          require(repo.departures(platform.id,Instant.parse("2026-10-14T23:00:00Z"),20,60).isEmpty());
        }
      });
      runner.check("isolated native repository fences repeat visits outside board and old trip timestamps", () -> {
        try(TimetableRepository repo=new TimetableRepository(isolated);InputStream in=asset("repeated-stop.ctpack"))
        {
          repo.importPack(in);
          Stop platform=null;for(Stop s:repo.searchStops("Alexanderplatz",20))if("P1a".equals(s.sourceId))platform=s;
          require(platform!=null);
          List<Departure> rows=repo.departures(platform.id,Instant.parse("2026-10-13T23:00:00Z"),1,60);
          require(rows.size()==1 && rows.get(0).stopVisitCount==2);
          try(InputStream idOnly=asset("repeated-id-only.pb"))
          {
            app.organicmaps.sdk.transit.rt.RealtimeBoard.Projection p=app.organicmaps.sdk.transit.rt.RealtimeBoard.project(rows,
                app.organicmaps.sdk.transit.rt.GtfsRealtime.parse(idOnly.readAllBytes()),1000);
            require(p.coverage==0 && p.departures.size()==1);
          }
          try(InputStream old=asset("old-trip-new-header.pb"))
          {
            app.organicmaps.sdk.transit.rt.RealtimeBoard.Projection p=app.organicmaps.sdk.transit.rt.RealtimeBoard.project(rows,
                app.organicmaps.sdk.transit.rt.GtfsRealtime.parse(old.readAllBytes()),1601);
            require(p.skipped==0 && p.departures.size()==1 && p.rows.get(rows.get(0)).stale);
          }
        }
      });
      runner.check("isolated replacement and removal do not affect unrelated maps favorites tracks", () -> {
        try(TimetableRepository repo=new TimetableRepository(isolated);InputStream in=asset("synthetic.ctpack"))
        {repo.importPack(in);require(repo.info()!=null);repo.removePack();require(!repo.hasPack());}
        require(Arrays.equals(new byte[]{1,2,3},Files.readAllBytes(unrelated.toPath())));
      });
    }
    finally
    {
      File pack=new File(root,"transit_pack");File[] children=pack.listFiles();
      if(children!=null)for(File file:children)require(file.delete());
      if(pack.exists())require(pack.delete());require(unrelated.delete());require(root.delete());
    }

    JSONObject corpus;
    try(InputStream in=asset("pilot-host-query-additive.json"))
    {corpus=new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
    JSONObject frozen;
    try(InputStream in=asset("pilot-sample-v1.json"))
    {frozen=new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
    JSONObject extra;
    try(InputStream in=asset("pilot-sample-v1-addendum.json"))
    {extra=new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
    Map<String,JSONObject> cases=new HashMap<>();
    for(JSONObject source:new JSONObject[]{frozen,extra})
    {JSONArray a=source.getJSONArray("cases");for(int i=0;i<a.length();i++){JSONObject c=a.getJSONObject(i);require(cases.put(c.getString("case_id"),c)==null);}}
    JSONArray expected=corpus.getJSONArray("cases");require(expected.length()==cases.size());
    try(TimetableRepository repo=new TimetableRepository(runner.getTargetContext()))
    {
      require(repo.info().databaseSha256.equals(frozen.getString("pack_database_sha256")));
      for(int i=0;i<expected.length();i++)
      {
        JSONObject measured=expected.getJSONObject(i);String id=measured.getString("case_id");JSONObject input=cases.get(id);
        runner.check("frozen real VBB Android repository oracle equality "+id, () -> {
          List<Departure> board=repo.departures(input.getJSONObject("stop").getLong("id"),Instant.parse(input.getString("now")),300,180);
          JSONArray wanted=measured.getJSONArray("actual_departures");require(board.size()==wanted.length());
          List<String> actualKeys=new ArrayList<>(),expectedKeys=new ArrayList<>();
          for(Departure d:board)actualKeys.add(d.tripSourceId+"|"+d.when+"|"+d.stopSourceId+"|"+d.stopSequence+"|"+d.line+"|"+d.destination+"|"+d.serviceDate);
          for(int j=0;j<wanted.length();j++){JSONObject d=wanted.getJSONObject(j);expectedKeys.add(d.getString("trip_id")+"|"+Instant.parse(d.getString("when"))+"|"+d.getString("stop_source_id")+"|"+d.getInt("stop_sequence")+"|"+d.getString("line")+"|"+d.getString("headsign")+"|"+d.getString("service_date"));}
          Collections.sort(actualKeys);Collections.sort(expectedKeys);require(actualKeys.equals(expectedKeys));
        });
      }
    }
    runner.check("real VBB phone repository latency and process memory measured", () -> {
      long open=android.os.SystemClock.elapsedRealtimeNanos();
      try(TimetableRepository repo=new TimetableRepository(runner.getTargetContext()))
      {
        repo.info();double openMs=(android.os.SystemClock.elapsedRealtimeNanos()-open)/1e6;
        List<Stop> choices=repo.searchStops("Alexanderplatz",20);Stop parent=null;for(Stop s:choices)if(s.locationType==1){parent=s;break;}
        require(parent!=null);List<Double> samples=new ArrayList<>();int rows=0;
        for(int i=0;i<9;i++){long start=android.os.SystemClock.elapsedRealtimeNanos();rows=repo.departures(parent.id,Instant.now(),300,180).size();samples.add((android.os.SystemClock.elapsedRealtimeNanos()-start)/1e6);}
        require(rows>0);Collections.sort(samples);
        Debug.MemoryInfo memory=new Debug.MemoryInfo();Debug.getMemoryInfo(memory);
        JSONObject metric=new JSONObject();metric.put("repository_first_open_ms",openMs);metric.put("query_n",samples.size());metric.put("query_p50_ms",samples.get(4));metric.put("query_max_ms",samples.get(8));metric.put("rows",rows);metric.put("process_total_pss_kib",memory.getTotalPss());
        metric.put("scope","in-process Pixel measurement; OS cache not cleared; not UI paint time or cold boot");runner.note("PHONE_METRICS "+metric);
      }
    });
  }
}
