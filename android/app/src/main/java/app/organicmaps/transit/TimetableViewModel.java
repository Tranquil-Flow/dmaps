package app.organicmaps.transit;
import app.organicmaps.R;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import app.organicmaps.sdk.transit.Departure;
import app.organicmaps.sdk.transit.PackInfo;
import app.organicmaps.sdk.transit.Stop;
import app.organicmaps.sdk.transit.TimetableRepository;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lifecycle-safe state holder for the offline timetable pilot UI.
 *
 * Rules honoured here (PACK_CONTRACT.md / task brief):
 * - One single-worker background executor; every repository call is off main thread.
 * - Generation tokens: every async result is discarded unless its token still matches,
 *   so rotation or a new operation can never surface stale results.
 * - No usage history, no analytics, no persisted search/selection. Only a minimal
 *   Parcelable snapshot is kept for process-death recovery, and it is rebuilt from the
 *   repository (never trusted as cached data), so process recreation cannot corrupt
 *   or desynchronise state.
 * - No network, GPS, or remote fallback anywhere.
 */
public class TimetableViewModel extends AndroidViewModel
{
  /** Bounded result sizes. */
  public static final int SEARCH_LIMIT = 20;
  public static final int NEARBY_LIMIT = 20;
  public static final int NEARBY_RADIUS_METRES = 1000;
  /**
   * Depth of the departure board. Measured 2026-10-14 against the live VBB
   * feed: at 50 rows the board spans only ~27 minutes and carries no realtime
   * coverage at all (0 of 50 at Alexanderplatz), because the feed covers a
   * sparse subset of trips that begins further out. At 300 the window reaches
   * ~160 minutes, close to the horizon below, and 18-24% of rows resolve to
   * live data. Deeper is not better: the rate falls again past this, since the
   * board then outruns what the feed describes.
   */
  public static final int BOARD_LIMIT = 300;
  public static final int BOARD_HORIZON_MINUTES = 180;

  /** Immutable snapshot for onSaveInstanceState; holds only IDs/text, no live data. */
  public static final class SavedState implements Parcelable
  {
    public final String queryText;
    public final long selectedStopId;
    public final String selectedStopLabel;

    public SavedState(String queryText, long selectedStopId, String selectedStopLabel)
    {
      this.queryText = queryText == null ? "" : queryText;
      this.selectedStopId = selectedStopId;
      this.selectedStopLabel = selectedStopLabel == null ? "" : selectedStopLabel;
    }

    protected SavedState(Parcel in)
    {
      queryText = in.readString();
      selectedStopId = in.readLong();
      selectedStopLabel = in.readString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags)
    {
      dest.writeString(queryText);
      dest.writeLong(selectedStopId);
      dest.writeString(selectedStopLabel);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<SavedState> CREATOR = new Creator<>()
    {
      @Override public SavedState createFromParcel(Parcel in) { return new SavedState(in); }
      @Override public SavedState[] newArray(int size) { return new SavedState[size]; }
    };
  }

  /** What the screen should show as its primary status. */
  public enum Status
  {
    IDLE, WORKING, NO_PACK, NO_RESULTS, ERROR, READY
  }

  /** Everything the activity renders; only ever mutated on main thread, published via LiveData. */
  public static final class ScreenState
  {
    public Status status = Status.IDLE;
    public boolean packPresent = false;
    @Nullable public PackInfo packInfo;
    /** Bounded candidate list; only boarding-relevant points are surfaced by the activity. */
    @NonNull public List<Stop> candidates = new ArrayList<>();
    @Nullable public Stop selectedStop;
    @NonNull public List<Departure> board = new ArrayList<>();
    /** Already-localized, bounded error/status line (from string resources, never raw exception text). */
    @Nullable public CharSequence message;
    /** True when the last import attempt failed (previous pack preserved). */
    public boolean importFailed;
    /** Monotonic id so the activity can skip no-op rebinds. */
    public int revision;

    public boolean importFailedFlag() { return importFailed; }
  }

  private final MutableLiveData<ScreenState> mState = new MutableLiveData<>(new ScreenState());
  private final ExecutorService mExecutor = Executors.newSingleThreadExecutor(r ->
  {
    Thread t = new Thread(r, "timetable-ui-worker");
    t.setDaemon(true);
    return t;
  });
  private final AtomicInteger mGeneration = new AtomicInteger();
  private final Object mRepoLock = new Object();
  @Nullable private TimetableRepository mRepository;

  @Nullable private Uri mPendingImport;   // SAF uri captured on main thread, consumed on worker
  @Nullable private String mQueryText = "";
  @Nullable private Stop mSelected;       // main-thread only
  private double mNearbyLat = Double.NaN, mNearbyLon = Double.NaN;
  @Nullable private String mNearbyName;

  public TimetableViewModel(@NonNull Application application)
  {
    super(application);
    refreshPackState(null);
  }

  public LiveData<ScreenState> getState() { return mState; }

  @MainThread
  private TimetableRepository repo()
  {
    synchronized (mRepoLock)
    {
      if (mRepository == null)
        mRepository = new TimetableRepository(getApplication());
      return mRepository;
    }
  }

  @MainThread
  private ScreenState mutate()
  {
    ScreenState s = mState.getValue();
    if (s == null) s = new ScreenState();
    s.revision++;
    return s;
  }

  /** Generic single background op with generation guard. */
  @MainThread
  private void runOp(@NonNull final String opName, @NonNull final Op op)
  {
    final ScreenState s = mutate();
    s.status = Status.WORKING;
    s.message = null;
    mState.setValue(s);
    final int gen = mGeneration.incrementAndGet();
    final Context ctx = getApplication();
    mExecutor.execute(() -> {
      Object result = null;
      Throwable error = null;
      try
      {
        result = op.run(ctx);
      }
      catch (IOException | IllegalArgumentException | IllegalStateException e)
      {
        // Bounded, repository-sanitized errors; never raw SQLException reach here.
        error = e;
      }
      catch (Exception e)
      {
        error = e;
      }
      final Object r = result;
      final Throwable err = error;
      // Post back to main thread; LiveData setValue must run on main.
      final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
      h.post(() -> {
        if (gen != mGeneration.get())
          return; // stale: a newer operation or clear() superseded this one
        ScreenState st = mutate();
        op.apply(st, r, err);
        mState.setValue(st);
      });
    });
  }

  private interface Op
  {
    Object run(Context ctx) throws Exception;
    void apply(ScreenState st, Object result, Throwable error);
  }

  private static boolean isPackGone(Throwable e)
  {
    return e instanceof IOException; // repository signals invalid/unavailable pack as IOException
  }

  /** Load pack presence/info; optionally restore a previously selected stop. */
  @MainThread
  public void refreshPackState(@Nullable SavedState restored)
  {
    final long restoreStopId = restored != null ? restored.selectedStopId : 0L;
    if (restored != null)
    {
      mQueryText = restored.queryText;
      mNearbyLat = Double.NaN;
      mNearbyLon = Double.NaN;
      mNearbyName = null;
    }
    runOp("pack-state", new Op()
    {
      @Override public Object run(Context ctx) throws IOException
      {
        if (!repo().hasPack())
          return Boolean.FALSE;
        return repo().info();
      }

      @Override public void apply(ScreenState st, Object result, Throwable error)
      {
        if (error != null)
        {
          st.packPresent = false;
          st.packInfo = null;
          st.status = Status.ERROR;
          st.candidates = new ArrayList<>();
          st.board = new ArrayList<>();
          mSelected = null;
          st.selectedStop = null;
          return;
        }
        if (Boolean.FALSE.equals(result))
        {
          st.packPresent = false;
          st.packInfo = null;
          st.status = Status.NO_PACK;
          st.candidates = new ArrayList<>();
          st.board = new ArrayList<>();
          mSelected = null;
          st.selectedStop = null;
          return;
        }
        st.packPresent = true;
        st.packInfo = (PackInfo) result;
        st.status = Status.READY;
        if (restoreStopId != 0L)
          reloadBoardForRestore(restoreStopId);
      }
    });
  }

  /** After process death, re-validate the persisted selection against live data. */
  @MainThread
  private void reloadBoardForRestore(long stopId)
  {
    final long id = stopId;
    runOp("restore-board", new Op()
    {
      @Override public Object run(Context ctx) throws IOException
      {
        // The board call itself validates the stop against live data; a stale
        // selection resolves to IOException -> clean drop in apply().
        return repo().departures(id, Instant.now(), BOARD_LIMIT, BOARD_HORIZON_MINUTES);
      }

      @Override public void apply(ScreenState st, Object result, Throwable error)
      {
        if (error != null)
        {
          // Selection no longer valid / pack changed: drop it cleanly, no stale board.
          mSelected = null;
          st.selectedStop = null;
          st.board = new ArrayList<>();
          st.status = st.packPresent ? Status.READY : Status.NO_PACK;
          return;
        }
        st.board = safeList(result);
        st.status = st.board.isEmpty() ? Status.NO_RESULTS : Status.READY;
        st.selectedStop = mSelected = restorePlaceholderStop(id);
      }
    });
  }

  @MainThread
  @SuppressWarnings("unchecked")
  private static List<Departure> safeList(Object o)
  {
    return o instanceof List ? (List<Departure>) o : new ArrayList<>();
  }

  /** Minimal Stop shell used only to label a restored board; id is authoritative. */
  @MainThread
  private Stop restorePlaceholderStop(long id)
  {
    return new Stop(id, "", "", "", null, null, null, 0, Double.NaN);
  }

  /** Import a pack chosen via SAF. Reads on the worker; previous pack preserved on failure. */
  @MainThread
  public void importPack(@NonNull Uri packUri)
  {
    mPendingImport = packUri;
    runOp("import", new Op()
    {
      @Override public Object run(Context ctx) throws IOException
      {
        Uri uri = mPendingImport;
        mPendingImport = null;
        if (uri == null)
          throw new IOException("no uri");
        try (InputStream in = ctx.getContentResolver().openInputStream(uri))
        {
          if (in == null)
            throw new IOException("unreadable");
          return repo().importPack(in);
        }
      }

      @Override public void apply(ScreenState st, Object result, Throwable error)
      {
        if (error != null)
        {
          // Import failed: previous active pack (if any) is untouched.
          st.status = st.packPresent ? Status.READY : Status.NO_PACK;
          st.importFailed = true;
          return;
        }
        st.packPresent = true;
        st.packInfo = (PackInfo) result;
        st.importFailed = false;
        st.candidates = new ArrayList<>();
        st.board = new ArrayList<>();
        mSelected = null;
        st.selectedStop = null;
        st.status = Status.READY;
      }
    });
  }

  /** Remove the active pack. Never touches maps/bookmarks/tracks. */
  @MainThread
  public void removePack()
  {
    runOp("remove", new Op()
    {
      @Override public Object run(Context ctx) throws IOException
      {
        repo().removePack();
        return Boolean.TRUE;
      }

      @Override public void apply(ScreenState st, Object result, Throwable error)
      {
        st.candidates = new ArrayList<>();
        st.board = new ArrayList<>();
        mSelected = null;
        st.selectedStop = null;
        if (error != null)
        {
          st.status = Status.ERROR;
          return;
        }
        st.packPresent = false;
        st.packInfo = null;
        st.status = Status.NO_PACK;
      }
    });
  }

  /** Manual text search for stations/platforms. No automatic unique-stop guess. */
  @MainThread
  public void searchStops(@NonNull String text)
  {
    mQueryText = text;
    mSelected = null;
    final String q = text.trim();
    runOp("search", new Op()
    {
      @Override public Object run(Context ctx) throws IOException
      {
        return repo().searchStops(q, SEARCH_LIMIT);
      }

      @Override public void apply(ScreenState st, Object result, Throwable error)
      {
        st.selectedStop = null;
        st.board = new ArrayList<>();
        st.candidates = error != null ? new ArrayList<>() : safeStops(result);
        if (error != null)
          st.status = isPackGone(error) ? Status.NO_PACK : Status.ERROR;
        else
          st.status = st.candidates.isEmpty() ? Status.NO_RESULTS : Status.READY;
      }
    });
  }

  /** Nearby candidates from optional intent extras (lat/lon[/name]). No GPS dependency. */
  @MainThread
  public void setNearbyHint(double lat, double lon, @Nullable String name)
  {
    mNearbyLat = lat;
    mNearbyLon = lon;
    mNearbyName = name;
  }

  public boolean hasNearbyHint()
  {
    return !Double.isNaN(mNearbyLat) && !Double.isNaN(mNearbyLon);
  }

  @MainThread
  public void nearbyStops()
  {
    if (!hasNearbyHint())
      return;
    final double lat = mNearbyLat, lon = mNearbyLon;
    runOp("nearby", new Op()
    {
      @Override public Object run(Context ctx) throws IOException
      {
        return repo().nearbyStops(lat, lon, NEARBY_RADIUS_METRES, NEARBY_LIMIT);
      }

      @Override public void apply(ScreenState st, Object result, Throwable error)
      {
        st.selectedStop = null;
        st.board = new ArrayList<>();
        st.candidates = error != null ? new ArrayList<>() : safeStops(result);
        if (error != null)
          st.status = isPackGone(error) ? Status.NO_PACK : Status.ERROR;
        else
          st.status = st.candidates.isEmpty() ? Status.NO_RESULTS : Status.READY;
      }
    });
  }

  /** Explicit user selection; never called implicitly from a single search hit. */
  @MainThread
  public void selectStop(@NonNull Stop stop)
  {
    mSelected = stop;
    final long id = stop.id;
    runOp("board", new Op()
    {
      @Override public Object run(Context ctx) throws IOException
      {
        // Manual refresh semantics: computed locally from the pack at Instant.now();
        // scheduled data only, never live/on-time labels.
        return repo().departures(id, Instant.now(), BOARD_LIMIT, BOARD_HORIZON_MINUTES);
      }

      @Override public void apply(ScreenState st, Object result, Throwable error)
      {
        st.selectedStop = mSelected;
        if (error != null)
        {
          st.board = new ArrayList<>();
          st.status = isPackGone(error) ? Status.NO_PACK : Status.ERROR;
          return;
        }
        st.board = safeList(result);
        st.status = st.board.isEmpty() ? Status.NO_RESULTS : Status.READY;
      }
    });
  }

  /** Manual refresh of the currently selected stop's board. */
  @MainThread
  public void refreshBoard()
  {
    if (mSelected != null)
      selectStop(mSelected);
  }

  @MainThread
  public void cancelPendingResults()
  {
    // Bump generation so any in-flight result is discarded; the worker itself is
    // never interrupted mid-import (contract: interrupted import must preserve old pack).
    mGeneration.incrementAndGet();
    ScreenState s = mutate();
    if (s.status == Status.WORKING)
      s.status = Status.READY;
    mState.setValue(s);
  }

  public String getQueryText() { return mQueryText == null ? "" : mQueryText; }

  @MainThread
  public SavedState snapshot()
  {
    Stop sel = mSelected;
    return new SavedState(getQueryText(),
        sel != null ? sel.id : 0L,
        sel != null ? sel.name + (sel.platform == null || sel.platform.isEmpty() ? "" : " " + sel.platform) : "");
  }

  @SuppressWarnings("unchecked")
  private static List<Stop> safeStops(Object o)
  {
    return o instanceof List ? (List<Stop>) o : new ArrayList<>();
  }

  @Override
  protected void onCleared()
  {
    mGeneration.incrementAndGet();
    mExecutor.shutdown();
    synchronized (mRepoLock)
    {
      if (mRepository != null)
        mRepository.close();  // idempotent per contract
      mRepository = null;
    }
    super.onCleared();
  }

  // Small static formatting helpers shared with the activity (agency-zone display).
  public static String formatAgencyTime(@NonNull Departure d)
  {
    DateTimeFormatter f = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault());
    ZoneId zone;
    try
    {
      zone = ZoneId.of(d.timezone);
    }
    catch (Exception e)
    {
      zone = ZoneId.systemDefault(); // unknown-time fallback; labelled by caller
    }
    return f.format(java.time.ZonedDateTime.ofInstant(d.when, zone)) + " (" + zone.getId() + ")";
  }

  public static long minutesUntil(@NonNull Departure d)
  {
    long secs = java.time.Duration.between(Instant.now(), d.when).getSeconds();
    return Math.round(secs / 60.0);
  }
}
