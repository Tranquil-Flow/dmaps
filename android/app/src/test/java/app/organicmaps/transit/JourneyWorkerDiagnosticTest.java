package app.organicmaps.transit;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

/** In-memory diagnostic is code/line only: never exception messages, paths or pack text. */
public class JourneyWorkerDiagnosticTest {
  private static String location(Exception error)throws Exception {
    Method method=JourneyViewModel.class.getDeclaredMethod("failureLocation",Exception.class);
    method.setAccessible(true);return (String)method.invoke(null,error);
  }
  @Test public void rejectsPayloadAndForeignFramesButRetainsKnownBoundary()throws Exception {
    IOException error=new IOException("PRIVATE_PACK_TEXT /private/owner/map 52.12345,13.12345");
    error.setStackTrace(new StackTraceElement[]{
      new StackTraceElement("private.owner.Identity","PRIVATE_QUERY","PRIVATE_FILE",42),
      new StackTraceElement("app.organicmaps.sdk.transit.MappedWalking$Result","PRIVATE_METHOD","PRIVATE_FILE",58),
      new StackTraceElement("app.organicmaps.transit.JourneyViewModel","PRIVATE_METHOD","PRIVATE_FILE",180)});
    assertEquals("IO|WALK_RESULT:58|VIEWMODEL:180",location(error));
  }
  @Test public void nativeStatusRequiresExactAllowlistedMessage()throws Exception {
    IOException error=new IOException("mapped walking unavailable: LIMIT_REACHED");error.setStackTrace(new StackTraceElement[0]);assertEquals("WALK_LIMIT_REACHED",location(error));
    IOException injected=new IOException("mapped walking unavailable: LIMIT_REACHED PRIVATE_CONTENT");injected.setStackTrace(new StackTraceElement[0]);assertEquals("IO",location(injected));
  }
  @Test public void boundsFramesAndClassifiesWithoutRetainingExceptionIdentity()throws Exception {
    Exception error=new InterruptedIOException("PRIVATE_CONTENT");
    StackTraceElement[] frames=new StackTraceElement[40];
    for(int i=0;i<frames.length;i++)frames[i]=new StackTraceElement("app.organicmaps.sdk.transit.TimetableRepository$1","PRIVATE_METHOD","PRIVATE_FILE",700+i);
    error.setStackTrace(frames);String value=location(error);
    assertTrue(value.startsWith("INTERRUPTED_IO|REPOSITORY:700"));
    assertEquals(9,value.split("\\|",-1).length);assertTrue(value.length()<256);
    Exception other=new Exception("PRIVATE_TEXT");other.setStackTrace(new StackTraceElement[0]);assertEquals("OTHER",location(other));
  }
}
