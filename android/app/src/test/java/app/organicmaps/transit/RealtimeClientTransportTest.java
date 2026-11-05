package app.organicmaps.transit;

import static org.junit.Assert.*;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.rt.RealtimeRequest;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;

/** Real loopback HTTP + production client, canonical synthetic payloads; no public feed. */
public class RealtimeClientTransportTest
{
  static final class Relay implements AutoCloseable
  {
    final java.net.ServerSocket server;
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final AtomicInteger requests = new AtomicInteger();
    final AtomicReference<String> uri = new AtomicReference<>(), validator = new AtomicReference<>();
    final CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
    volatile boolean closing;
    volatile java.net.Socket current;
    Relay(int code, byte[] body, boolean hold) throws Exception
    {
      server = new java.net.ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1"));
      executor.submit(() -> {
        while (!closing)
        {
          try (java.net.Socket socket = server.accept())
          {
            current = socket;
            socket.setSoTimeout(3000);
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                socket.getInputStream(), java.nio.charset.StandardCharsets.ISO_8859_1));
            String request = reader.readLine();
            if (request == null) continue;
            requests.incrementAndGet();
            uri.set(request.split(" ")[1]);
            for (int i = 0; i < 32; i++)
            {
              String line = reader.readLine();
              if (line == null || line.isEmpty()) break;
              if (line.toLowerCase(java.util.Locale.ROOT).startsWith("if-none-match:"))
                validator.set(line.substring(line.indexOf(':') + 1).trim());
            }
            java.io.OutputStream out = socket.getOutputStream();
            String headers = "HTTP/1.1 " + code + (code == 304 ? " Not Modified" : " OK")
                + "\r\nConnection: close\r\nETag: \"opaque-validator\"\r\n"
                + "Content-Encoding: gzip\r\nContent-Length: " + (code == 304 ? 0 : body.length)
                + "\r\n\r\n";
            out.write(headers.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            if (code != 304)
            {
              if (hold)
              {
                out.write(body, 0, 1); out.flush(); arrived.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                out.write(body, 1, body.length - 1);
              }
              else { arrived.countDown(); out.write(body); }
            }
            else arrived.countDown();
            out.flush();
          }
          catch (java.io.IOException disconnect)
          {
            // Client cancellation and closing the listening socket are test-owned cleanup.
            if (!closing && !hold) throw new RuntimeException(disconnect);
          }
          finally { current = null; }
        }
      });
    }
    String base() { return "http://127.0.0.1:" + server.getLocalPort(); }
    @Override public void close() throws Exception
    {
      closing = true; release.countDown(); server.close();
      java.net.Socket socket = current;
      if (socket != null) socket.close();
      executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
    }
  }

  byte[] payload() throws Exception
  {
    try (java.io.InputStream in = getClass().getResourceAsStream("/transit/canonical-feed.pb"))
    { assertNotNull(in); return in.readAllBytes(); }
  }
  byte[] gzip(byte[] raw) throws Exception
  {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream stream = new GZIPOutputStream(out)) { stream.write(raw); }
    return out.toByteArray();
  }
  /** These six original oracles qualify the retained private transport, not preview permission.
   * Reflection is test-only; production has no preference/intent/debug bypass entrypoint. */
  private RealtimeClient.Result fetch(Relay relay, String etag, RealtimeRequest ticket) throws Exception
  {
    java.lang.reflect.Method transport = RealtimeClient.class.getDeclaredMethod("fetchLivePrototype",
        String.class, String.class, int.class, java.util.Set.class, String.class, RealtimeRequest.class);
    transport.setAccessible(true);
    return (RealtimeClient.Result) transport.invoke(null, relay.base(), "all", 10800,
        Collections.singleton("T100"), etag, ticket);
  }

  @Test(timeout = 10000) public void compressedCanonicalFeedAndPrivateRequestShape() throws Exception
  {
    try (Relay relay = new Relay(200, gzip(payload()), false))
    {
      RealtimeClient.Result result = fetch(relay, null, new RealtimeRequest());
      assertTrue(result.ok()); assertNotNull(result.feed);
      assertEquals(Long.valueOf(-60), result.feed.tripUpdates.get(0).delay);
      assertEquals("/v1/slice?region=all&horizon=10800", relay.uri.get());
      assertFalse(relay.uri.get().contains("T100"));
      assertEquals("\"opaque-validator\"", result.etag);
    }
  }
  @Test(timeout = 10000) public void unchangedResponseKeepsOpaqueValidator() throws Exception
  {
    try (Relay relay = new Relay(304, new byte[0], false))
    {
      RealtimeClient.Result result = fetch(relay, "\"opaque-validator\"", new RealtimeRequest());
      assertTrue(result.ok()); assertTrue(result.notModified);
      assertNull(result.feed); // caller retains raw prior snapshot, not a fake empty feed
      assertEquals("\"opaque-validator\"", relay.validator.get());
    }
  }
  @Test(timeout = 10000) public void unexpected304IsNotAnEmptySuccess() throws Exception
  {
    try (Relay relay = new Relay(304, new byte[0], false))
    {
      RealtimeClient.Result result = fetch(relay, null, new RealtimeRequest());
      assertFalse(result.ok()); assertEquals(R.string.transit_live_reason_bad_feed, result.errorRes);
    }
  }
  @Test(timeout = 10000) public void emptyBodyIsNotAnEmptyFeed() throws Exception
  {
    try (Relay relay = new Relay(200, gzip(new byte[0]), false))
    {
      RealtimeClient.Result result = fetch(relay, null, new RealtimeRequest());
      assertFalse(result.ok()); assertEquals(R.string.transit_live_reason_bad_feed, result.errorRes);
    }
  }
  @Test(timeout = 10000) public void revokedTicketInitiatesZeroHttpRequests() throws Exception
  {
    try (Relay relay = new Relay(200, gzip(payload()), false))
    {
      RealtimeRequest ticket = new RealtimeRequest(); ticket.cancel();
      assertFalse(fetch(relay, null, ticket).ok());
      assertEquals(0, relay.requests.get());
    }
  }
  @Test(timeout = 10000) public void admittedFetchCanBeDisconnectedWhileBodyIsHeld() throws Exception
  {
    ExecutorService client = Executors.newSingleThreadExecutor();
    try (Relay relay = new Relay(200, gzip(payload()), true))
    {
      RealtimeRequest ticket = new RealtimeRequest();
      Future<RealtimeClient.Result> result = client.submit(() -> fetch(relay, null, ticket));
      assertTrue(relay.arrived.await(3, TimeUnit.SECONDS));
      ticket.cancel(); relay.release.countDown();
      assertFalse(result.get(3, TimeUnit.SECONDS).ok());
      assertEquals(1, relay.requests.get()); // already admitted traffic cannot be unsent
    }
    finally { client.shutdownNow(); assertTrue(client.awaitTermination(3, TimeUnit.SECONDS)); }
  }
}
