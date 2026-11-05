package app.organicmaps.sdk.transit;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import app.organicmaps.sdk.OrganicMaps;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.sdk.util.ConnectionState;
import java.io.File;
import java.lang.reflect.Method;

/** Child-loaded test-only entry; parent supplies exact installed production SDK/JNI classes. */
public final class NativeWalkingEntry {
  private static String publicApk;
  static void initialize(Context target,File settings,File writable,File priv,File temp,Runnable loaded)throws Exception {
    if(!"app.comaps.fdroid.debug.test".equals(target.getPackageName())||publicApk==null)throw new IllegalStateException();
    Context offline=new ContextWrapper(target){
      @Override public Context getApplicationContext(){return this;}
      // Report no network to this fixture only; no device/network state or permission changes.
      @Override public Object getSystemService(String name){return CONNECTIVITY_SERVICE.equals(name)?null:super.getSystemService(name);}
    };
    call("nativeSetSettingsDir",new Class[]{String.class},settings.getAbsolutePath());
    // First native settings access occurs only after the owned path, with memory-only Java prefs.
    Config.init(offline,new FixtureMemoryPreferences(),"fdroid","app.comaps.fdroid.debug.test",1,"native-fixture","app.comaps.fdroid.debug.test.fixture");
    ConnectionState.INSTANCE.initialize(offline);
    call("nativeInitPlatform",new Class[]{Context.class,String.class,String.class,String.class,String.class,String.class,String.class,boolean.class},offline,publicApk,writable.getAbsolutePath(),priv.getAbsolutePath(),temp.getAbsolutePath(),"fdroid","debug",false);
    // Match the production platform prerequisite: UsageStats requires a materialized settings file.
    // This native write is to the already-selected, test-UID-owned settings directory only.
    Config.setStoragePath(writable.getAbsolutePath());
    if(!new File(settings,"settings.ini").isFile()||!writable.getAbsolutePath().equals(Config.getStoragePath()))throw new IllegalStateException("Owned native settings not materialized");
    call("nativeInitFramework",new Class[]{Runnable.class},loaded);
  }
  private static void call(String name,Class<?>[] types,Object...args)throws Exception{Method m=OrganicMaps.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(null,args);}
  private static int tests,failures;private static final StringBuilder report=new StringBuilder();
  private static void check(String name,PackAuthenticationChecks.Case body){
    tests++;try{body.run();report.append("PASS ").append(name).append('\n');}
    catch(Exception|AssertionError e){if(e instanceof java.io.IOException)for(MappedWalking.Status status:MappedWalking.Status.values())if(("mapped walking unavailable: "+status.name()).equals(e.getMessage()))report.append("NATIVE_WALK_STATUS ").append(status.name()).append("\n");failures++;report.append("FAIL ").append(name).append(" (").append(e.getClass().getSimpleName()).append(")\n");for(StackTraceElement f:e.getStackTrace())if(f.getClassName().startsWith("app.organicmaps.sdk.transit."))report.append("  AT ").append(f.getFileName()).append(":").append(f.getLineNumber()).append('\n');}
  }
  public static void run(Instrumentation runner,String scope,String token,String apk){
    publicApk=apk;tests=failures=0;report.setLength(0);
    if("berlin-walking".equals(scope))check("real Berlin Android self-targeted registered map and regional repository composition",()->BerlinWalkingChecks.run(runner,token));
    else if("mapped-walking".equals(scope))check("self-targeted Android native mapped walking composition harness",()->PackWalkingChecks.run(runner,NativeWalkingEntry::check));
    else throw new IllegalArgumentException();
    Bundle b=new Bundle();b.putString("stream",report+"Tests: "+tests+", failures: "+failures+"\n");runner.finish(failures==0?Activity.RESULT_OK:Activity.RESULT_CANCELED,b);
  }
}
