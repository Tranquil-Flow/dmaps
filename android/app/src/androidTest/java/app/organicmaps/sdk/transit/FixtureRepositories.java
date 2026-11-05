package app.organicmaps.sdk.transit;

import android.content.Context;
import java.time.Clock;
import java.lang.reflect.*;

/** Test-only historical-clock composition across ART class loaders; no production API changes. */
public final class FixtureRepositories {
  private FixtureRepositories() {}
  public static TimetableRepository open(Context context,Clock clock)throws Exception {
    ClassLoader production=TimetableRepository.class.getClassLoader();
    Class<?> policy=Class.forName("app.organicmaps.sdk.transit.PackTrustPolicy",true,production);
    Class<?> faults=Class.forName("app.organicmaps.sdk.transit.PackActivation$FaultInjector",true,production);
    Method compiledPolicy=policy.getDeclaredMethod("production");compiledPolicy.setAccessible(true);
    Object noFaults=Proxy.newProxyInstance(production,new Class<?>[]{faults},(proxy,method,args)->{
      switch(method.getName()) {
        case "at": if(method.getReturnType()==void.class&&method.getParameterCount()==1)return null;break;
        case "hashCode": return System.identityHashCode(proxy);
        case "equals": return proxy==args[0];
        case "toString": return "DMaps test-only no-fault composition";
      }
      throw new UnsupportedOperationException("Unexpected fixture seam");
    });
    Constructor<TimetableRepository> ctor=TimetableRepository.class.getDeclaredConstructor(Context.class,policy,Clock.class,faults);
    ctor.setAccessible(true);
    return ctor.newInstance(context,compiledPolicy.invoke(null),clock,noFaults);
  }
}
