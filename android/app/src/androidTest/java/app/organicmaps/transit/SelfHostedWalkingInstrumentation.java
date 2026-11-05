package app.organicmaps.transit;

import android.app.*;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.*;
import dalvik.system.PathClassLoader;
import java.lang.reflect.InvocationTargetException;

/** Test APK only; target itself, not the installed rider application's UID/Application/providers. */
public final class SelfHostedWalkingInstrumentation extends Instrumentation {
  public static final String OWNER="app.comaps.fdroid.debug", SELF=OWNER+".test";
  private String scope,token;
  @Override public Application newApplication(ClassLoader loader,String name,Context context)
      throws InstantiationException,IllegalAccessException,ClassNotFoundException {
    if(!Application.class.getName().equals(name)||!SELF.equals(context.getPackageName()))
      throw new InstantiationException("Native fixture requires its own plain Application");
    return super.newApplication(loader,name,context);
  }
  @Override public void onCreate(Bundle args) {
    super.onCreate(args);
    scope=args==null?null:args.getString("scope");token=args==null?null:args.getString("berlin_inputs");
    if(!"mapped-walking".equals(scope)&&!"berlin-walking".equals(scope))
      throw new IllegalArgumentException("Native fixture scope required");
    if("berlin-walking".equals(scope)?token==null||!token.matches("dmaps-berlin-input-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"):token!=null)
      throw new IllegalArgumentException("Invalid native fixture cohort");
    start();
  }
  @Override public void onStart() {
    try {
      Context target=getTargetContext();ApplicationInfo owner=target.getPackageManager().getApplicationInfo(OWNER,0);
      if(!SELF.equals(target.getPackageName())||android.os.Process.myUid()!=target.getApplicationInfo().uid
          ||android.os.Process.myUid()==owner.uid||target.getApplicationContext().getClass()!=Application.class)
        throw new IllegalStateException("Native fixture UID/Application isolation refused");
      // Only public installed code/resources/native libraries. Never construct owner Application.
      Context publicCode=target.createPackageContext(OWNER,Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
      ClassLoader delegate=new PathClassLoader(getContext().getApplicationInfo().sourceDir,publicCode.getClassLoader());
      Bundle identity=new Bundle();identity.putString("dmaps_native_isolation","separate_test_uid_plain_application_public_code_only");sendStatus(0,identity);
      Class.forName("app.organicmaps.sdk.transit.NativeWalkingEntry",true,delegate)
          .getMethod("run",Instrumentation.class,String.class,String.class,String.class)
          .invoke(null,this,scope,token,owner.sourceDir);
    } catch(Throwable failure) {
      if(failure instanceof InvocationTargetException&&failure.getCause()!=null)failure=failure.getCause();
      Bundle b=new Bundle();
      if(failure instanceof UnsatisfiedLinkError){
        String text=String.valueOf(failure.getMessage());
        StringBuilder kind=new StringBuilder();
        for(String word:new String[]{"hwasan","HWAddressSanitizer","libclang_rt","libc++_shared","not found","namespace","already loaded","cannot locate symbol","JNI_OnLoad","Permission denied"})
          if(text.contains(word))kind.append(word).append(';');
        b.putString("dmaps_fixture_linkage_categories",kind.length()==0?"unclassified":kind.toString());
      }
      StringBuilder report=new StringBuilder("FAIL self-targeted native fixture bootstrap (").append(failure.getClass().getSimpleName()).append(")\n");
      for(StackTraceElement f:failure.getStackTrace())if(f.getClassName().startsWith("app.organicmaps.transit.")||f.getClassName().startsWith("app.organicmaps.sdk.transit."))report.append("  AT ").append(f.getFileName()).append(":").append(f.getLineNumber()).append("\n");
      b.putString("stream",report+"Tests: 1, failures: 1\n");finish(Activity.RESULT_CANCELED,b);
    }
  }
}
