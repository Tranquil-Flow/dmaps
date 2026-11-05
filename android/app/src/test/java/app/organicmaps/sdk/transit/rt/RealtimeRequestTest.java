package app.organicmaps.sdk.transit.rt;

import static org.junit.Assert.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Deterministic admission/cancellation tests; no public network or device setting is used. */
public class RealtimeRequestTest
{
  private static class Connection extends HttpURLConnection
  {
    int disconnected;
    Connection() throws Exception { super(new URL("http://127.0.0.1/")); }
    @Override public void disconnect() { disconnected++; }
    @Override public boolean usingProxy() { return false; }
    @Override public void connect() { }
  }

  @Test public void canceledTicketCannotAdmitAConnection() throws Exception
  {
    RealtimeRequest request = new RealtimeRequest();
    request.cancel();
    Connection conn = new Connection();
    assertFalse(request.attach(conn));
    assertEquals(1, conn.disconnected);
  }
  @Test public void cancelDisconnectsOwnedConnectionAndIsIdempotent() throws Exception
  {
    RealtimeRequest request = new RealtimeRequest();
    Connection conn = new Connection();
    assertTrue(request.attach(conn));
    request.cancel(); request.cancel();
    assertTrue(request.isCanceled());
    assertEquals(1, conn.disconnected);
  }
  @Test public void canceledQueuedWorkNeverAdmitsTransport() throws Exception
  {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicInteger admitted = new AtomicInteger();
    RealtimeRequest active = new RealtimeRequest(), queued = new RealtimeRequest();
    try
    {
      Future<?> first = executor.submit(() -> {
        try
        {
          if (active.attach(new Connection())) admitted.incrementAndGet();
          entered.countDown();
          if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError();
        }
        catch (Exception e) { throw new RuntimeException(e); }
      });
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      Future<?> second = executor.submit(() -> {
        try { if (queued.attach(new Connection())) admitted.incrementAndGet(); }
        catch (Exception e) { throw new RuntimeException(e); }
      });
      active.cancel(); queued.cancel(); release.countDown();
      first.get(3, TimeUnit.SECONDS); second.get(3, TimeUnit.SECONDS);
      assertEquals(1, admitted.get());
    }
    finally { release.countDown(); executor.shutdownNow(); }
  }
}
