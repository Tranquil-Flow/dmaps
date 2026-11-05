package app.organicmaps.transit;

import android.content.Context;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Debug-only on-device timing ring (SYSTEM.md I6). The logcat main buffer on the test phone holds about one
 * minute, so measurements were lost; this keeps the newest journey timing events in app-private
 * {@code files/dmaps-diag/timing.jsonl}, read with {@code run-as}. Only the fixed event vocabulary, ok/failed,
 * elapsed milliseconds and device uptime are written: never names, IDs, coordinates, queries or wall-clock time.
 * Called only under {@code BuildConfig.DEBUG}; measurement failures never alter app behaviour. */
final class TimingRing {
  static final int MAX_LINES=400,KEEP_LINES=200;
  static final long MAX_MS=3_600_000L;
  private static final Pattern EVENT=Pattern.compile("(journey_[a-z_]+) (ok|failed)");
  private static volatile File dir;
  private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"dmaps-timing-ring");t.setDaemon(true);return t;});
  private TimingRing(){}

  static void init(Context context){
    // Always bind the current application: a once-only latch would keep a stale directory across Robolectric apps.
    if(app.organicmaps.BuildConfig.DEBUG&&context!=null)dir=new File(context.getApplicationContext().getFilesDir(),"dmaps-diag");
  }
  static void record(String event,long ms){
    final File d=dir;if(d==null)return;final long uptime=SystemClock.uptimeMillis();
    try{IO.execute(()->write(d,event,ms,uptime));}catch(RuntimeException ignored){/* never alters behaviour */}
  }
  static synchronized void write(File d,String event,long ms,long uptimeMs){
    if(d==null||event==null)return;
    Matcher m=EVENT.matcher(event);
    if(!m.matches()||ms<0||ms>MAX_MS||uptimeMs<0)return;
    try{
      if(!d.isDirectory()&&!d.mkdirs())return;
      File f=new File(d,"timing.jsonl");
      String line="{\"event\":\""+m.group(1)+"\",\"outcome\":\""+m.group(2)+"\",\"ms\":"+ms+",\"uptimeMs\":"+uptimeMs+"}\n";
      try(FileOutputStream out=new FileOutputStream(f,true)){out.write(line.getBytes(StandardCharsets.UTF_8));}
      List<String> lines=readLines(f);
      if(lines.size()>MAX_LINES){
        File tmp=new File(d,"timing.jsonl.tmp");
        StringBuilder kept=new StringBuilder();
        for(String s:lines.subList(lines.size()-KEEP_LINES,lines.size()))kept.append(s).append('\n');
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(kept.toString().getBytes(StandardCharsets.UTF_8));}
        // java.nio.file.Files/StandardCopyOption need API 26 (minSdk 21). File.renameTo is rename(2) on Android and the JVM: atomic, and it
        // replaces an existing target, as Files.move(REPLACE_EXISTING, ATOMIC_MOVE) did. A failure leaves the old ring whole, as before.
        if(!tmp.renameTo(f))throw new IOException("timing ring rotation failed");
      }
    }catch(IOException|RuntimeException ignored){/* never alters behaviour */}
  }
  /** Same line splitting as Files.readAllLines (\n, \r\n, \r; no trailing empty line) without java.nio.file (API 26). */
  private static List<String> readLines(File f)throws IOException {
    List<String> lines=new ArrayList<>();
    try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8))){
      for(String s=in.readLine();s!=null;s=in.readLine())lines.add(s);
    }
    return lines;
  }
}
