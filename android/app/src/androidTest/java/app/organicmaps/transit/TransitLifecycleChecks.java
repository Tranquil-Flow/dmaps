package app.organicmaps.transit;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.Departure;
import app.organicmaps.sdk.transit.Stop;
import app.organicmaps.sdk.transit.rt.RealtimeMerger;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Profile-bound synthetic checks, NOT observations of an operator feed. Offline preview exercises
 * no-socket denial and scheduled presentation; original live HTTP oracles remain in a separate
 * capability-selected branch and are explicitly NOT_RUN in preview. Injects an in-memory
 * scheduled board into the real ViewModel, then uses the real Activity, HTTP client, parser,
 * projection, adapter, localized row descriptions and lifecycle. Never changes the installed pack.
 * Reflection stays in the test APK; no production intent, bypass or mutable endpoint is introduced.
 */
final class TransitLifecycleChecks
{
  private final TransitPilotInstrumentation runner;
  private TimetableActivity activity;
  private Relay relay;
  private TimetableViewModel.ScreenState screen;
  private final long now = Instant.now().getEpochSecond();
  private final String date = LocalDate.now(ZoneId.of("Europe/Berlin")).toString();
  private final List<Departure> board = new ArrayList<>();
  TransitLifecycleChecks(TransitPilotInstrumentation runner) { this.runner = runner; }

  private interface Probe { boolean ready() throws Exception; }
  private static void require(boolean ok) { if (!ok) throw new AssertionError(); }
  private static Object field(Object object, String name) throws Exception
  {
    Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
  }
  private static Object call(Object object, String name, Class<?>[] types, Object... values) throws Exception
  {
    Method m = object.getClass().getDeclaredMethod(name, types); m.setAccessible(true);
    try { return m.invoke(object, values); }
    catch (InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
  }
  private void main(TransitPilotInstrumentation.Check check) throws Exception
  {
    AtomicReference<Throwable> error = new AtomicReference<>();
    runner.runOnMainSync(() -> { try { check.run(); } catch (Throwable e) { error.set(e); } });
    if (error.get() != null) throw new Exception("main-thread test failure", error.get());
  }
  private void await(Probe probe) throws Exception { await(probe, 8000); }
  private void await(Probe probe, long timeoutMs) throws Exception
  {
    long end = android.os.SystemClock.uptimeMillis() + timeoutMs;
    do
    {
      runner.waitForIdleSync();
      AtomicBoolean ready = new AtomicBoolean();
      main(() -> ready.set(probe.ready()));
      if (ready.get()) return;
      android.os.SystemClock.sleep(40);
    } while (android.os.SystemClock.uptimeMillis() < end);
    main(() -> {
      TimetableViewModel model = (TimetableViewModel) field(activity, "mModel");
      TimetableViewModel.ScreenState state = model.getState().getValue();
      runner.note("Transit readiness: resumed=" + activity.hasWindowFocus()
          + " state=" + (state == null ? "null" : state.status.name())
          + " packInfoPresent=" + (state != null && state.packInfo != null));
    });
    throw new AssertionError("composition readiness deadline");
  }
  private Departure departure(String trip, String stop, int sequence)
  {
    return new Departure(trip, "Synthetic " + trip, "Test destination", "Test stop", stop,
        400, 0, 1, Instant.ofEpochSecond(now + 300), "Europe/Berlin", sequence, stop, date, sequence);
  }
  @SuppressWarnings("unchecked") private void publish(List<Departure> rows, long stopId) throws Exception
  {
    main(() -> {
      TimetableViewModel model = (TimetableViewModel) field(activity, "mModel");
      MutableLiveData<TimetableViewModel.ScreenState> live =
          (MutableLiveData<TimetableViewModel.ScreenState>) model.getState();
      TimetableViewModel.ScreenState previous = live.getValue();
      screen = new TimetableViewModel.ScreenState();
      screen.status = TimetableViewModel.Status.READY;
      screen.packPresent = true;
      screen.packInfo = previous == null ? null : previous.packInfo;
      screen.selectedStop = new Stop(stopId, "TEST", "Synthetic test station", "", null,
          null, null, 1, Double.NaN);
      screen.board = rows;
      screen.revision = previous == null ? 1 : previous.revision + 1;
      live.setValue(screen);
    });
    runner.waitForIdleSync();
  }
  private void toggle() throws Exception { main(() -> ((Button) field(activity, "mLiveToggle")).performClick()); }
  private void fetch() throws Exception
  { main(() -> call(activity, "fetchLive", new Class<?>[]{List.class}, screen.board)); }
  private void settled() throws Exception { await(() -> !(Boolean) field(activity, "mLiveFetching")); }
  @SuppressWarnings("unchecked") private List<Object> items() throws Exception
  { return (List<Object>) field(field(activity, "mAdapter"), "mItems"); }
  @SuppressWarnings("unchecked") private RealtimeMerger.DepartureRow row(Departure d) throws Exception
  { return ((Map<Departure, RealtimeMerger.DepartureRow>) field(activity, "mLiveRows")).get(d); }
  @SuppressWarnings({"rawtypes", "unchecked"}) private TextView rendered(int position) throws Exception
  {
    RecyclerView.Adapter adapter = (RecyclerView.Adapter) field(activity, "mAdapter");
    RecyclerView.ViewHolder holder = adapter.createViewHolder((ViewGroup) field(activity, "mList"),
        adapter.getItemViewType(position));
    adapter.bindViewHolder(holder, position); // Production binding + accessibility description.
    return (TextView) holder.itemView;
  }
  private void reset() throws Exception
  {
    main(() -> {
      if ((Boolean) field(activity, "mLiveOn")) ((Button) field(activity, "mLiveToggle")).performClick();
    });
    publish(new ArrayList<>(board), 700);
  }
  private void expectText(int position, int visible, int spoken) throws Exception
  {
    main(() -> {
      TextView view = rendered(position);
      require(view.getText().toString().contains(activity.getString(visible)));
      require(view.getContentDescription().toString().contains(activity.getString(spoken)));
    });
  }

  void run() throws Exception
  {
    if (RealtimeClient.liveUpdatesAvailable()) runLivePrototype();
    else runOfflinePreview();
  }

  private void runOfflinePreview() throws Exception
  {
    runner.note("NOT_RUN live HTTP lifecycle controls: unavailable in offline-preview build; retained for separately qualified live release");
    android.content.Intent intent = new android.content.Intent(runner.getTargetContext(), TimetableActivity.class);
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
    activity = (TimetableActivity) runner.startActivitySync(intent);
    try
    {
      await(() -> {
        TimetableViewModel.ScreenState s = ((TimetableViewModel)field(activity,"mModel")).getState().getValue();
        return s != null && s.packInfo != null && s.status != TimetableViewModel.Status.WORKING;
      }, 45000);
      board.add(departure("A", "S", 1)); board.add(departure("B", "S", 1));
      board.add(departure("C", "S", 1)); board.add(departure("D", "S", 1));
      board.add(departure("E", "S", 1));
      relay = new Relay(0); // Owned ephemeral endpoint; never touches the prototype's shared port.
      reset();
      runner.check("offline preview actual Activity discloses disabled live capability", () -> {
        main(() -> {
          require(!RealtimeClient.liveUpdatesAvailable());
          require(((Button)field(activity,"mLiveToggle")).getVisibility() == android.view.View.GONE);
          require(!((Button)field(activity,"mLiveToggle")).isEnabled());
          require(((TextView)field(activity,"mLiveStatusLine")).getText().toString()
              .equals(activity.getString(R.string.transit_offline_preview)));
        });
        expectText(0, R.string.transit_scheduled_only, R.string.transit_live_spoken_scheduled);
      });
      runner.check("offline preview forced toggle and old callback cannot allocate transport", () -> {
        main(() -> {
          Field on = TimetableActivity.class.getDeclaredField("mLiveOn"); on.setAccessible(true);
          on.set(activity,true);call(activity,"onLiveToggle",new Class<?>[]{});
          require(!(Boolean)field(activity,"mLiveOn"));
          on.set(activity,true);call(activity,"fetchLive",new Class<?>[]{List.class},screen.board);
          require(field(activity,"mLiveExecutor") == null);
        });
        require(relay.connections.get()==0 && relay.requests.get()==0);
      });
      runner.check("offline preview cached live evidence cannot hide or relabel scheduled rows", () -> {
        app.organicmaps.sdk.transit.rt.GtfsRealtime.FeedMessage feed =
            app.organicmaps.sdk.transit.rt.GtfsRealtime.parse(payload(now,false));
        require(app.organicmaps.sdk.transit.rt.RealtimeBoard.project(board,feed,now).rows.get(board.get(0)).hasLiveData());
        main(() -> {
          Field on=TimetableActivity.class.getDeclaredField("mLiveOn");on.setAccessible(true);on.set(activity,true);
          Field cache=TimetableActivity.class.getDeclaredField("mLiveFeed");cache.setAccessible(true);cache.set(activity,feed);
          call(activity,"renderBoard",new Class<?>[]{TimetableViewModel.ScreenState.class},screen);
          require(items().size()==5 && items().contains(board.get(3)));
          for(Departure d:board)require(!row(d).hasLiveData());
          require((Integer)field(activity,"mLiveSkipped")==0);
        });
        expectText(2,R.string.transit_scheduled_only,R.string.transit_live_spoken_scheduled);
      });
      runner.check("offline preview public realtime overloads refuse before any socket", () -> {
        String endpoint="http://127.0.0.1:"+relay.listener.getLocalPort();
        List<RealtimeClient.Result> results=Arrays.asList(
            RealtimeClient.fetchAndJoin(endpoint,"all",10800,Collections.singleton("A")),
            RealtimeClient.fetchAndJoin(endpoint,"all",10800,Collections.singleton("A"),"old-validator"),
            RealtimeClient.fetchAndJoin(endpoint,"all",10800,Collections.singleton("A"),"old-validator",new app.organicmaps.sdk.transit.rt.RealtimeRequest()));
        for(RealtimeClient.Result result:results)require(!result.ok()&&result.errorRes==R.string.transit_offline_preview&&result.feed==null&&result.joined.isEmpty());
        require(relay.connections.get()==0&&relay.requests.get()==0);
      });
      runner.check("offline preview background resume cannot restore old live consent", () -> {
        main(() -> runner.callActivityOnPause(activity));main(() -> runner.callActivityOnStop(activity));
        main(() -> runner.callActivityOnRestart(activity));main(() -> runner.callActivityOnStart(activity));
        main(() -> runner.callActivityOnResume(activity));
        main(() -> require(!(Boolean)field(activity,"mLiveOn")
            && !(Boolean)field(activity,"mNeedLiveFetch")
            && field(activity,"mLiveFeed")==null && field(activity,"mLiveExecutor")==null));
        require(relay.connections.get()==0&&relay.requests.get()==0);
      });
    }
    finally
    {
      if(relay!=null)relay.close();
      if(activity!=null&&!activity.isDestroyed())main(activity::finish);
    }
  }

  private void runLivePrototype() throws Exception
  {
    android.content.Intent intent = new android.content.Intent(runner.getTargetContext(), TimetableActivity.class);
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
    activity = (TimetableActivity) runner.startActivitySync(intent);
    try
    {
      await(() -> {
        TimetableViewModel model = (TimetableViewModel) field(activity, "mModel");
        TimetableViewModel.ScreenState s = model.getState().getValue();
        return s != null && s.packInfo != null && s.status != TimetableViewModel.Status.WORKING;
      }, 45000); // Measured real repository opens now exceed 14-16s; HTTP assertions retain 8s.

      board.add(departure("A", "S", 1)); board.add(departure("B", "S", 1));
      board.add(departure("C", "S", 1)); board.add(departure("D", "S", 1));
      board.add(departure("E", "S", 1));
      relay = new Relay();
      runner.check("synthetic Activity starts live OFF and initiates no HTTP", () -> {
        reset(); android.os.SystemClock.sleep(150);
        require(relay.requests.get() == 0);
        main(() -> require(!(Boolean) field(activity, "mLiveOn")));
        expectText(0, R.string.transit_scheduled_only, R.string.transit_live_spoken_scheduled);
      });
      runner.check("synthetic HTTP response drives actual skipped membership and all row labels", () -> {
        reset(); relay.offer(new Response(200, payload(now, false), false)); toggle(); settled();
        main(() -> {
          require(items().size() == 4 && !items().contains(board.get(3)));
          require((Integer) field(activity, "mLiveSkipped") == 1);
          require(row(board.get(0)).state == RealtimeMerger.LiveState.LIVE);
          require(row(board.get(1)).state == RealtimeMerger.LiveState.DELAYED);
          require(row(board.get(2)).state == RealtimeMerger.LiveState.CANCELED);
          require(row(board.get(4)).state == RealtimeMerger.LiveState.SCHEDULED_ONLY);
          TextView delayed = rendered(1);
          require(delayed.getContentDescription().toString().contains(activity.getString(
              R.string.transit_live_spoken_delayed, 180)));
        });
        expectText(0, R.string.transit_live_on_time, R.string.transit_live_spoken_live);
        expectText(2, R.string.transit_live_canceled, R.string.transit_live_spoken_canceled);
        expectText(3, R.string.transit_scheduled_only, R.string.transit_live_spoken_scheduled);
        require("/v1/slice?region=all&horizon=10800".equals(relay.uri.get()));
      });
      runner.check("synthetic live OFF restores skipped scheduled row without another request", () -> {
        int before = relay.requests.get(); toggle();
        main(() -> require(items().size() == 5 && items().contains(board.get(3))));
        expectText(0, R.string.transit_scheduled_only, R.string.transit_live_spoken_scheduled);
        require(before == relay.requests.get());
      });
      runner.check("synthetic 304 retains raw snapshot and reprojects rather than clearing", () -> {
        reset(); relay.offer(new Response(200, payload(now, false), false)); toggle(); settled();
        relay.offer(new Response(304, new byte[0], false)); fetch(); settled();
        main(() -> require(items().size() == 4 && row(board.get(0)).hasLiveData()));
        require("\"test-snapshot\"".equals(relay.validator.get()));
      });
      runner.check("synthetic failed refresh restores scheduled membership and localized error", () -> {
        relay.offer(new Response(503, new byte[0], false)); fetch(); settled();
        main(() -> {
          require(items().size() == 5);
          require((Integer) field(activity, "mLiveErrorRes") == R.string.transit_live_reason_refused);
        });
        expectText(0, R.string.transit_scheduled_only, R.string.transit_live_spoken_scheduled);
      });
      runner.check("synthetic local expiry ages labels restores skipped row and never refetches", () -> {
        reset(); relay.offer(new Response(200, payload(Instant.now().getEpochSecond() - 599, false), false));
        toggle(); settled(); int before = relay.requests.get();
        await(() -> items().size() == 5 && row(board.get(0)).stale);
        expectText(0, R.string.transit_live_stale, R.string.transit_live_spoken_stale);
        require(relay.requests.get() == before);
      });
      runner.check("synthetic board switch cancels held fetch and fences old publication", () -> {
        reset(); Response held = new Response(200, payload(now, false), true); relay.offer(held);
        toggle(); require(held.arrived.await(3, TimeUnit.SECONDS));
        List<Departure> next = Collections.singletonList(departure("A", "OTHER", 8));
        publish(next, 701); held.release.countDown(); settled();
        main(() -> {
          require(items().size() == 1 && items().get(0) == next.get(0));
          require(!row(next.get(0)).hasLiveData());
        });
      });
      runner.check("synthetic OFF revokes held and queued refresh work before network admission", () -> {
        reset(); Response held = new Response(200, payload(now, false), true); relay.offer(held);
        toggle(); require(held.arrived.await(3, TimeUnit.SECONDS));
        int before = relay.requests.get();
        main(() -> {
          call(activity, "fetchLive", new Class<?>[]{List.class}, screen.board);
          call(activity, "fetchLive", new Class<?>[]{List.class}, screen.board);
          ((Button) field(activity, "mLiveToggle")).performClick();
        });
        held.release.countDown();
        java.util.concurrent.ThreadPoolExecutor executor = (java.util.concurrent.ThreadPoolExecutor) field(activity, "mLiveExecutor");
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS); runner.waitForIdleSync();
        require(relay.requests.get() == before);
        main(() -> require(items().size() == 5 && !(Boolean) field(activity, "mLiveOn")));
      });
      runner.check("synthetic background resume ages cached snapshot without automatic HTTP", () -> {
        reset(); relay.offer(new Response(200, payload(now, false), false)); toggle(); settled();
        int before = relay.requests.get();
        main(() -> runner.callActivityOnPause(activity));
        main(() -> runner.callActivityOnStop(activity));
        main(() -> runner.callActivityOnRestart(activity));
        main(() -> runner.callActivityOnStart(activity));
        main(() -> runner.callActivityOnResume(activity));
        require(relay.requests.get() == before);
        main(() -> require(row(board.get(0)).hasLiveData()));
      });
      runner.check("synthetic expired pack metadata is visibly and accessibly disclosed", () -> {
        reset();
        main(() -> {
          app.organicmaps.sdk.transit.PackInfo original = screen.packInfo;
          screen.packInfo = new app.organicmaps.sdk.transit.PackInfo(original.publisher,
              original.attribution, original.license, original.sourceUrl, original.sourceSha256,
              original.databaseSha256, original.createdUtc, "2000-01-01", "2000-01-02",
              original.timezone, original.databaseBytes, original.maxDepartureSeconds);
          screen.revision++;
          call(activity, "bind", new Class<?>[]{TimetableViewModel.ScreenState.class}, screen);
          TextView label = (TextView) field(activity, "mPackLine");
          require(label.getText().toString().contains(activity.getString(R.string.transit_pack_expired)));
          require(label.getContentDescription() == null);
          screen.packInfo = original;
        });
      });
      runner.check("synthetic absent pack disables search and discloses unavailable state", () -> {
        reset();
        main(() -> {
          TimetableViewModel.ScreenState absent = new TimetableViewModel.ScreenState();
          absent.status = TimetableViewModel.Status.NO_PACK; absent.revision = screen.revision + 1;
          call(activity, "bind", new Class<?>[]{TimetableViewModel.ScreenState.class}, absent);
          require(!((Button)field(activity,"mSearchBtn")).isEnabled());
          require(((TextView)field(activity,"mStatusLine")).getText().toString()
              .contains(activity.getString(R.string.transit_no_pack)));
          screen.revision = absent.revision + 1;
          call(activity, "bind", new Class<?>[]{TimetableViewModel.ScreenState.class}, screen);
        });
      });
      runner.check("synthetic destruction revokes pending connection and shuts down executor", () -> {
        reset(); Response held = new Response(200, payload(now, false), true); relay.offer(held);
        toggle(); require(held.arrived.await(3, TimeUnit.SECONDS));
        ExecutorService executor = (ExecutorService) field(activity, "mLiveExecutor");
        main(activity::finish); runner.waitForIdleSync(); held.release.countDown();
        require(executor.awaitTermination(5, TimeUnit.SECONDS));
        require(activity.isDestroyed());
      });
      runner.check("real Activity recreation returns with live consent OFF and no cached feed", () -> {
        activity = (TimetableActivity) runner.startActivitySync(intent);
        await(() -> {
          TimetableViewModel model = (TimetableViewModel)field(activity,"mModel");
          return model.getState().getValue().packInfo != null;
        }, 45000); // This second Activity also opens the real installed pack.

        reset(); relay.offer(new Response(200, payload(now, false), false)); toggle(); settled();
        main(() -> require((Boolean)field(activity,"mLiveOn") && row(board.get(0)).hasLiveData()));
        int requests = relay.requests.get();
        android.app.Instrumentation.ActivityMonitor monitor = runner.addMonitor(
            TimetableActivity.class.getName(), null, false);
        TimetableActivity old = activity;
        try
        {
          main(activity::recreate);
          Activity recreated = runner.waitForMonitorWithTimeout(monitor, 10000);
          require(recreated instanceof TimetableActivity && recreated != old);
          activity = (TimetableActivity)recreated;
          runner.waitForIdleSync();
          main(() -> {
            require(!(Boolean)field(activity,"mLiveOn"));
            require(field(activity,"mLiveFeed") == null);
          });
          require(relay.requests.get() == requests);
        }
        finally { runner.removeMonitor(monitor); }
      });
    }
    finally
    {
      if (relay != null) relay.close();
      if (activity != null && !activity.isDestroyed()) main(activity::finish);
    }
  }

  // Canonical protobuf field numbers pinned by protoc goldens in the JVM suite.
  private static byte[] v(long n)
  {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    do { int b = (int)(n & 127); n >>>= 7; o.write(n == 0 ? b : b | 128); } while (n != 0);
    return o.toByteArray();
  }
  private static byte[] cat(byte[]... arrays) throws IOException
  { ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] a : arrays) out.write(a); return out.toByteArray(); }
  private static byte[] number(int f, long n) throws IOException { return cat(v(f << 3), v(n)); }
  private static byte[] message(int f, byte[] bytes) throws IOException { return cat(v((f << 3) | 2), v(bytes.length), bytes); }
  private static byte[] text(int f, String s) throws IOException { return message(f, s.getBytes(StandardCharsets.UTF_8)); }
  private byte[] entity(String trip, int relationship, int stopRelationship, int delay) throws Exception
  {
    byte[] descriptor = cat(text(1, trip), text(3, date.replace("-", "")), number(4, relationship));
    byte[] stop = cat(number(1, 1), text(4, "S"), number(5, stopRelationship), message(3, number(1, delay)));
    return message(2, cat(text(1, trip), message(3, cat(message(1, descriptor), message(2, stop)))));
  }
  private byte[] payload(long timestamp, boolean unused) throws Exception
  {
    return cat(message(1, cat(text(1, "2.0"), number(3, timestamp))),
        entity("A", 0, 0, 0), entity("B", 0, 0, 180), entity("C", 3, 0, 0), entity("D", 0, 1, 0));
  }

  private static final class Response
  {
    final int status; final byte[] body; final boolean held;
    final CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
    Response(int status, byte[] body, boolean held) { this.status = status; this.body = body; this.held = held; }
  }
  private static final class Relay implements AutoCloseable
  {
    final ServerSocket listener = new ServerSocket();
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final BlockingQueue<Response> responses = new LinkedBlockingQueue<>(8);
    final AtomicInteger requests = new AtomicInteger(), connections = new AtomicInteger();
    final AtomicReference<String> uri = new AtomicReference<>(), validator = new AtomicReference<>();
    volatile boolean closed; volatile Socket active; volatile Response current;
    Relay() throws IOException
    { this(8099); }
    Relay(int port) throws IOException
    {
      listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
      worker.submit(() -> {
        while (!closed)
        {
          try (Socket socket = listener.accept())
          {
            connections.incrementAndGet();
            active = socket; socket.setSoTimeout(3000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String line = in.readLine(); if (line == null) continue;
            requests.incrementAndGet(); uri.set(line.split(" ")[1]); validator.set(null);
            for (int i = 0; i < 32; i++)
            {
              line = in.readLine(); if (line == null || line.isEmpty()) break;
              if (line.toLowerCase(Locale.ROOT).startsWith("if-none-match:")) validator.set(line.substring(line.indexOf(':') + 1).trim());
            }
            Response response = responses.poll(2, TimeUnit.SECONDS);
            if (response == null) response = new Response(503, new byte[0], false);
            current = response;
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 " + response.status + " Test\r\nConnection: close\r\nETag: \"test-snapshot\"\r\nContent-Length: "
                + response.body.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush(); response.arrived.countDown();
            if (response.held) response.release.await(6, TimeUnit.SECONDS);
            out.write(response.body); out.flush();
          }
          catch (IOException disconnected) { /* Owned request cancellation/cleanup. */ }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
          finally { active = null; current = null; }
        }
      });
    }
    void offer(Response r) { if (!responses.offer(r)) throw new AssertionError("fixture response queue full"); }
    @Override public void close() throws Exception
    {
      closed = true; if (current != null) current.release.countDown(); listener.close();
      if (active != null) active.close(); worker.shutdownNow(); require(worker.awaitTermination(5, TimeUnit.SECONDS));
    }
  }
}
