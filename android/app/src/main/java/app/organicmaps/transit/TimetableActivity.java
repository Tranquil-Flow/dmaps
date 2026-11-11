package app.organicmaps.transit;
import app.organicmaps.R;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import app.organicmaps.sdk.transit.Departure;
import app.organicmaps.sdk.transit.ImportMode;
import app.organicmaps.sdk.transit.PackInfo;
import app.organicmaps.sdk.transit.Stop;
import app.organicmaps.sdk.transit.rt.RealtimeMerger;
import app.organicmaps.sdk.transit.rt.RealtimeBoard;
import app.organicmaps.sdk.transit.rt.RealtimeRequest;
import app.organicmaps.sdk.transit.rt.GtfsRealtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * Offline timetable pilot activity: pack import/remove (SAF), manual station search,
 * optional nearby candidates from intent extras, explicit platform disambiguation,
 * and a scheduled departure board. All data work happens in {@link TimetableViewModel}'s
 * single background executor; this class only binds state on the main thread.
 */
public class TimetableActivity extends AppCompatActivity
{
  private static final int REQ_IMPORT_PACK = 47001;
  private int mNextPickerCode = REQ_IMPORT_PACK;
  private int mPendingPickerCode;
  @Nullable private ImportMode mPendingPickerMode;
  @Nullable private androidx.appcompat.app.AlertDialog mImportChoice;

  /** Local-only pilot relay, reached via adb reverse on emulator or USB device. */
  private static final String LIVE_RELAY_BASE = "http://127.0.0.1:8099";
  private static final String LIVE_REGION = "all";
  /** Matches the board horizon (BOARD_HORIZON_MINUTES = 180). */
  private static final int LIVE_HORIZON_SEC = 3 * 3600;

  private static final String EXTRA_LAT = "transit.extra.lat";
  private static final String EXTRA_LON = "transit.extra.lon";
  private static final String EXTRA_NAME = "transit.extra.name";

  /**
   * Launches the timetable for a place.
   *
   * Only coordinates and a label are passed. The stop itself is resolved
   * locally against the pack on arrival, so a caller never needs to know
   * whether a pack exists or which stop id it uses.
   */
  @androidx.annotation.NonNull
  public static android.content.Intent createIntent(@NonNull Context context,
                                                    double lat, double lon,
                                                    @Nullable String name)
  {
    android.content.Intent i = new android.content.Intent(context, TimetableActivity.class);
    i.putExtra(EXTRA_LAT, lat);
    i.putExtra(EXTRA_LON, lon);
    if (name != null)
      i.putExtra(EXTRA_NAME, name);
    return i;
  }

  /** Minimal linear-layout DSL so the UI stays programmatic but readable. */
  private static LinearLayout vlinear(Context c)
  {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private static LinearLayout vrow(Context c)
  {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.HORIZONTAL);
    return l;
  }

  private TimetableViewModel mModel;
  private EditText mQuery;
  private Button mSearchBtn;
  private Button mNearbyBtn;
  private Button mImportBtn;
  private Button mRemoveBtn;
  private Button mRefreshBtn;
  private Button mLiveToggle;
  private TextView mLiveStatusLine;
  private TextView mStatusLine;
  private TextView mPackLine;
  private TextView mDatasetLine; // S1 DatasetStatus header, additive to the pack/trust/attribution lines
  /** Facts of the one registry capture taken at the last resume (presence plus the registered map versions). */
  private app.organicmaps.sdk.transit.DatasetStatus.MapFacts mMapFacts =
      app.organicmaps.sdk.transit.DatasetStatus.MapFacts.UNKNOWN;
  /** Written only by the production datasetMapPresence() inside the current datasetMapFacts() call. */
  private app.organicmaps.sdk.transit.DatasetStatus.MapFacts mCapturedMapFacts;
  private TextView mSetupGuide; // UX06 manual setup guide, shown only while S1 has an action to offer
  private TextView mTrustLine;
  private TextView mAttributionLine;
  private RecyclerView mList;
  private BoardAdapter mAdapter;
  private int mLastBoundRevision = -1;
  private boolean mWatchingPack;
  private boolean mHasResumed;
  @Nullable private PackInfo mExpiryRefreshRequested;
  private final android.os.Handler mPackHandler = new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable mCheckPackAuthorization = () -> {
    if (mWatchingPack && mModel != null) {
      mModel.checkVisible();
      updatePackTrust(mModel.getState().getValue());
      renderBoard(mModel.getState().getValue());
    }
  };

  // -- Opt-in realtime overlay state (main thread only) ----------------------
  private boolean mLiveOn = false;             // default OFF: live = network
  private boolean mNeedLiveFetch = false;      // board reloaded while live on
  private int mLiveGen;                        // discards out-of-order fetches
  private java.util.concurrent.ThreadPoolExecutor mLiveExecutor;
  private final Map<Departure, RealtimeMerger.DepartureRow> mLiveRows = new HashMap<>();
  private GtfsRealtime.FeedMessage mLiveFeed;
  private List<Departure> mLiveBoardReference;
  private long mLiveStopId;
  private String mLivePackIdentity;
  private RealtimeRequest mLiveTicket;
  private Future<?> mLiveFuture;
  private boolean mLiveFetching;
  private int mLiveSkipped;
  private final android.os.Handler mLiveHandler = new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable mExpireOverlay = () -> {
    if (mLiveOn && mModel != null)
    {
      renderBoard(mModel.getState().getValue());
      updateLiveStatusLine();
    }
  };
  private int mLiveCoverage;                   // rows with resolvable live data
  private int mLiveBoardCount;                 // board rows at fetch time
  /** Bounded localized failure reason; 0 = none. Never raw endpoint text. */
  private int mLiveErrorRes;
  /** Relay ETag echoed as If-None-Match so an unchanged slice costs a 304. */
  private String mLiveEtag;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    if (savedInstanceState != null)
    {
      int next = savedInstanceState.getInt("transit.picker_next", REQ_IMPORT_PACK);
      mNextPickerCode = next >= REQ_IMPORT_PACK && next <= 65535 ? next : 65535;
      int pending = savedInstanceState.getInt("transit.picker_code", 0);
      String mode = savedInstanceState.getString("transit.picker_mode");
      if (pending >= REQ_IMPORT_PACK && pending < mNextPickerCode && mode != null)
      {
        try { mPendingPickerMode = ImportMode.valueOf(mode); mPendingPickerCode = pending; }
        catch (IllegalArgumentException malformed) { /* No fallback mode for an unknown request. */ }
      }
    }
    mModel = new ViewModelProvider(this).get(TimetableViewModel.class);
    if(savedInstanceState!=null)mModel.restoreImportFailure(savedInstanceState.getBoolean("transit.import_failed",false),
        savedInstanceState.getString("transit.import_failed_database"));

    buildContentView();

    Intent intent = getIntent();
    if (intent != null && intent.hasExtra(EXTRA_LAT) && intent.hasExtra(EXTRA_LON))
    {
      double lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN);
      double lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN);
      String name = intent.getStringExtra(EXTRA_NAME);
      if (isFinite(lat) && isFinite(lon))
        mModel.setNearbyHint(lat, lon, name);
    }

    TimetableViewModel.SavedState restored = null;
    if (savedInstanceState != null)
      restored = savedInstanceState.getParcelable("transit.saved_state");
    // Restore query text before observing so the field survives rotation/process death.
    mQuery.setText(restored != null ? restored.queryText : mModel.getQueryText());
    if (restored == null) mModel.refreshPackState(null);
    else mModel.refreshPackState(restored, savedInstanceState.getString("transit.saved_database"));

    mModel.getState().observe(this, this::bind);
    mNearbyBtn.setEnabled(mModel.hasNearbyHint());
    mNearbyBtn.setVisibility(mModel.hasNearbyHint() ? View.VISIBLE : View.GONE);
  }

  private static boolean isFinite(double d)
  {
    return !Double.isNaN(d) && !Double.isInfinite(d);
  }

  private void buildContentView()
  {
    Context c = this;
    ScrollView scroll = new ScrollView(c);
    scroll.setFillViewport(true);
    LinearLayout root = vlinear(c);
    int pad = dp(12);
    root.setPadding(pad, pad, pad, dp(88));
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
      androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
      view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, dp(24) + bars.bottom);
      return insets;
    });
    scroll.addView(root, new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    // -- Pack header ----------------------------------------------------------
    // Dynamic text is its accessibility name; a generic description would
    // replace validity, attribution and failure details for screen readers.
    mDatasetLine = new TextView(c);
    androidx.core.view.ViewCompat.setAccessibilityLiveRegion(mDatasetLine, androidx.core.view.ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);
    mDatasetLine.setId(View.generateViewId());
    mDatasetLine.setTag("dmaps-dataset-status");
    mDatasetLine.setVisibility(View.GONE); // shown only once the first pack read has answered
    root.addView(mDatasetLine); // Text is the accessible name; no colour-only status.

    mPackLine = new TextView(c);
    mPackLine.setId(View.generateViewId());
    root.addView(mPackLine);

    mTrustLine = new TextView(c);
    mTrustLine.setId(View.generateViewId());
    root.addView(mTrustLine); // Dynamic text is also the accessibility name.

    mAttributionLine = new TextView(c);
    mAttributionLine.setId(View.generateViewId());
    mAttributionLine.setTextSize(11f);
    root.addView(mAttributionLine);

    LinearLayout packRow = vrow(c);
    mImportBtn = new Button(c);
    mImportBtn.setId(View.generateViewId());
    mImportBtn.setText(R.string.transit_import_pack);
    mImportBtn.setContentDescription(getString(R.string.transit_import_pack));
    mImportBtn.setOnClickListener(v -> openPackPicker());
    packRow.addView(mImportBtn, rowWeight());

    mRemoveBtn = new Button(c);
    mRemoveBtn.setId(View.generateViewId());
    mRemoveBtn.setText(R.string.transit_remove_pack);
    mRemoveBtn.setContentDescription(getString(R.string.transit_remove_pack));
    mRemoveBtn.setOnClickListener(v -> mModel.removePack());
    packRow.addView(mRemoveBtn, rowWeight());
    root.addView(packRow, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    // UX06: a compact manual guide to the existing Download Maps and import chooser. Text only: no control, no intent.
    mSetupGuide = new TextView(c);
    mSetupGuide.setId(View.generateViewId());
    mSetupGuide.setTag("dmaps-setup-guide");
    mSetupGuide.setTextSize(12f);
    mSetupGuide.setVisibility(View.GONE);
    root.addView(mSetupGuide);

    // A separate local screen leaves the board/live-consent and global map router untouched.
    Button journeys = new Button(c);
    journeys.setText(R.string.journey_entry);
    journeys.setOnClickListener(v -> startActivity(new Intent(this, JourneyActivity.class)));
    root.addView(journeys);

    // -- Search ---------------------------------------------------------------
    LinearLayout searchRow = vrow(c);
    mQuery = new EditText(c);
    mQuery.setId(View.generateViewId());
    // Disable personalized keyboard learning for the search field.
    mQuery.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    mQuery.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
    mQuery.setHint(R.string.transit_search_hint);
    mQuery.setOnEditorActionListener((v, actionId, event) -> {
      if (actionId == EditorInfo.IME_ACTION_SEARCH)
      {
        doSearch();
        return true;
      }
      return false;
    });
    searchRow.addView(mQuery, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

    mSearchBtn = new Button(c);
    mSearchBtn.setId(View.generateViewId());
    mSearchBtn.setText(R.string.transit_search);
    mSearchBtn.setContentDescription(getString(R.string.transit_search));
    mSearchBtn.setOnClickListener(v -> doSearch());
    searchRow.addView(mSearchBtn, rowWrap());
    root.addView(searchRow, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    mNearbyBtn = new Button(c);
    mNearbyBtn.setId(View.generateViewId());
    mNearbyBtn.setText(R.string.transit_nearby_stops);
    mNearbyBtn.setContentDescription(getString(R.string.transit_nearby_stops));
    mNearbyBtn.setOnClickListener(v -> mModel.nearbyStops());
    root.addView(mNearbyBtn, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    // -- Status / board -------------------------------------------------------
    mStatusLine = new TextView(c);
    mStatusLine.setId(View.generateViewId());
    // Q10: import, search and error outcomes replace this text in place; announce them politely.
    androidx.core.view.ViewCompat.setAccessibilityLiveRegion(mStatusLine, androidx.core.view.ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);
    mStatusLine.setPadding(0, dp(8), 0, dp(8));
    root.addView(mStatusLine);

    mRefreshBtn = new Button(c);
    mRefreshBtn.setId(View.generateViewId());
    mRefreshBtn.setText(R.string.transit_refresh);
    mRefreshBtn.setContentDescription(getString(R.string.transit_refresh));
    mRefreshBtn.setOnClickListener(v -> {
      mNeedLiveFetch = mLiveOn;
      mModel.refreshBoard(); // only the READY result may trigger the explicit live refresh
    });
    root.addView(mRefreshBtn, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    // Opt-in live overlay: default OFF because live data makes network calls.
    mLiveToggle = new Button(c);
    mLiveToggle.setId(View.generateViewId());
    mLiveToggle.setText(R.string.transit_live_toggle);
    mLiveToggle.setContentDescription(getString(R.string.transit_live_toggle));
    mLiveToggle.setOnClickListener(v -> onLiveToggle());
    root.addView(mLiveToggle, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    mLiveStatusLine = new TextView(c);
    mLiveStatusLine.setId(View.generateViewId());
    mLiveStatusLine.setTextSize(12f);
    mLiveStatusLine.setText(RealtimeClient.liveUpdatesAvailable()
        ? R.string.transit_live_off : R.string.transit_offline_preview);
    root.addView(mLiveStatusLine);

    mList = new RecyclerView(c);
    mList.setId(View.generateViewId());
    mList.setLayoutManager(new LinearLayoutManager(this));
    mAdapter = new BoardAdapter();
    mList.setAdapter(mAdapter);
    root.addView(mList, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    setContentView(scroll);
  }

  private static LinearLayout.LayoutParams rowWeight()
  {
    return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
  }

  private static LinearLayout.LayoutParams rowWrap()
  {
    return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
  }

  private int dp(int v)
  {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  private void doSearch()
  {
    String q = mQuery.getText().toString();
    mModel.searchStops(q);
  }

  /** Trusted local wall-clock seam for deterministic host UI tests, not an intent/pack option. */
  java.time.Instant presentationNow() { return java.time.Instant.now(); }

  /** S1 registry seam, read once per resume on main. Host tests override it; production never reaches native
   * code unless the real application's core is initialized (see DatasetMapPresence). */
  app.organicmaps.sdk.transit.DatasetStatus.MapPresence datasetMapPresence()
  {
    mCapturedMapFacts = DatasetMapPresence.captureFacts(this); // the only registry read of this resume
    return mCapturedMapFacts.presence;
  }

  /** One immutable capture per resume. Reads the presence seam exactly once, so an overriding host test still works. */
  app.organicmaps.sdk.transit.DatasetStatus.MapFacts datasetMapFacts()
  {
    mCapturedMapFacts = null;
    app.organicmaps.sdk.transit.DatasetStatus.MapPresence presence = datasetMapPresence();
    app.organicmaps.sdk.transit.DatasetStatus.MapFacts captured = mCapturedMapFacts;
    mCapturedMapFacts = null;
    return captured != null && captured.presence == presence
        ? captured : app.organicmaps.sdk.transit.DatasetStatus.MapFacts.ofPresence(presence);
  }

  private void openPackPicker()
  {
    TimetableViewModel.ScreenState state = mModel.getState().getValue();
    if (isFinishing() || isDestroyed() || mPendingPickerMode != null
        || (state != null && state.status == TimetableViewModel.Status.WORKING)
        || (mImportChoice != null && mImportChoice.isShowing())) return;
    if (mNextPickerCode >= 65535)
    {
      mStatusLine.setText(R.string.transit_picker_exhausted);
      return;
    }
    mImportChoice = new androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle(R.string.transit_import_pack)
        .setItems(new CharSequence[]{getString(R.string.transit_import_official),
            getString(R.string.transit_import_untrusted)}, (dialog, which) -> {
          if (which == 0) launchPackPicker(ImportMode.OFFICIAL_REQUIRED);
          else if (which == 1) launchPackPicker(ImportMode.USER_UNTRUSTED);
        }).create();
    mImportChoice.setOnDismissListener(dialog -> mImportChoice = null);
    mImportChoice.show();
  }

  private void launchPackPicker(ImportMode mode)
  {
    TimetableViewModel.ScreenState state = mModel.getState().getValue();
    if (isFinishing() || isDestroyed() || mPendingPickerMode != null
        || (state != null && state.status == TimetableViewModel.Status.WORKING)
        || mNextPickerCode >= 65535) return;
    mPendingPickerMode = mode;
    mPendingPickerCode = mNextPickerCode++;
    mImportBtn.setEnabled(false);
    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    pick.addCategory(Intent.CATEGORY_OPENABLE);
    pick.setType("*/*");
    // .ctpack is a ZIP; no reliable MIME type, so accept any openable document.
    try
    {
      startActivityForResult(pick, mPendingPickerCode);
    }
    catch (android.content.ActivityNotFoundException | SecurityException e)
    {
      mPendingPickerMode = null; mPendingPickerCode = 0;
      updatePackTrust(mModel.getState().getValue());
      mStatusLine.setText(R.string.transit_error_no_document_picker);
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data)
  {
    super.onActivityResult(requestCode, resultCode, data);
    if (mPendingPickerMode == null || requestCode != mPendingPickerCode)
      return;
    final ImportMode mode = mPendingPickerMode;
    mPendingPickerMode = null; mPendingPickerCode = 0;
    updatePackTrust(mModel.getState().getValue());
    if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null)
      return; // cancelled: previous pack untouched, no error surface
    Uri uri = data.getData();
    try
    {
      grantUriPermission(getPackageName(), uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    catch (Exception ignored) {}
    mModel.importPack(uri, mode);
  }

  @Override protected void onSaveInstanceState(@NonNull Bundle state)
  {
    state.putInt("transit.picker_next", mNextPickerCode);
    state.putInt("transit.picker_code", mPendingPickerCode);
    if (mPendingPickerMode != null) state.putString("transit.picker_mode", mPendingPickerMode.name());
    if (mModel != null)
    {
      state.putParcelable("transit.saved_state", mModel.snapshot());
      TimetableViewModel.ScreenState current = mModel.getState().getValue();
      if(current!=null&&current.importFailedFlag()) {
        state.putBoolean("transit.import_failed",true);
        if(current.packInfo!=null)state.putString("transit.import_failed_database",current.packInfo.databaseSha256);
      }
      if (current != null && current.selectedStop != null && current.packInfo != null)
        state.putString("transit.saved_database", current.packInfo.databaseSha256);
    }
    super.onSaveInstanceState(state);
  }

  private boolean isQueryable(@Nullable TimetableViewModel.ScreenState state)
  {
    if (state == null || !state.packPresent || state.packInfo == null
        || state.status == TimetableViewModel.Status.ERROR
        || state.status == TimetableViewModel.Status.WORKING) return false;
    PackInfo info = state.packInfo;
    java.time.Instant now=presentationNow();
    return !info.isExpired(now)&&!info.isNotYetValid(now)&&(!info.authenticated || (info.authorizationExpires != null
        && now.isBefore(info.authorizationExpires)));
  }

  private void updatePackTrust(@Nullable TimetableViewModel.ScreenState state)
  {
    mPackHandler.removeCallbacks(mCheckPackAuthorization);
    if (state == null) return;
    app.organicmaps.sdk.transit.DatasetStatus dataset =
        DatasetStatusText.forTimetableWithMap(state, presentationNow(), mMapFacts);
    DatasetStatusText.render(mDatasetLine, dataset);
    DatasetStatusText.renderGuide(mSetupGuide, dataset);
    boolean busy = state.status == TimetableViewModel.Status.WORKING;
    boolean queryable = isQueryable(state);
    mImportBtn.setEnabled(!busy && mPendingPickerMode == null);
    mRemoveBtn.setEnabled(state.packPresent && !busy);
    mSearchBtn.setEnabled(queryable); mQuery.setEnabled(queryable);
    mNearbyBtn.setEnabled(queryable && mModel.hasNearbyHint());
    mRefreshBtn.setEnabled(queryable);
    mLiveToggle.setEnabled(queryable && RealtimeClient.liveUpdatesAvailable());
    PackInfo info = state.packInfo;
    if (info == null)
    {
      mTrustLine.setText(state.status == TimetableViewModel.Status.ERROR
          ? getString(R.string.transit_pack_unavailable) : null);
      mExpiryRefreshRequested = null;
      return;
    }
    if(mWatchingPack)mPackHandler.postDelayed(mCheckPackAuthorization,1000L);
    if (!info.authenticated)
    {
      mTrustLine.setText(R.string.transit_unsigned_pack_disclosure);
      mExpiryRefreshRequested = null;
      return;
    }
    java.time.Instant now = presentationNow();
    if (info.authorizationExpires == null || !now.isBefore(info.authorizationExpires))
    {
      mTrustLine.setText(R.string.transit_authorization_expired);
      invalidateLiveContext();
      mAdapter.submit(new ArrayList<>());
      if (mWatchingPack && !busy && mExpiryRefreshRequested != info)
      {
        mExpiryRefreshRequested = info;
        mModel.refreshPackState(null); // Reverify below UI; never switch to USER_UNTRUSTED.
      }
      return;
    }
    mExpiryRefreshRequested = null;
    mTrustLine.setText(getString(R.string.transit_authenticated_pack, info.signingKeyId,
        info.officialSequence, info.authorizationExpires.toString()));

  }

  private void bind(TimetableViewModel.ScreenState s)
  {
    if (s == null || s.revision == mLastBoundRevision)
      return;
    mLastBoundRevision = s.revision;
    if (!RealtimeClient.liveUpdatesAvailable())
    {
      mLiveOn = false; mNeedLiveFetch = false;
      invalidateLiveContext();
    }
    long selectedId = s.selectedStop != null ? s.selectedStop.id : 0L;
    String pack = s.packInfo != null ? s.packInfo.databaseSha256 : null;
    if (s.status == TimetableViewModel.Status.WORKING || s.board != mLiveBoardReference
        || selectedId != mLiveStopId || !java.util.Objects.equals(pack, mLivePackIdentity))
      invalidateLiveContext();
    mLiveBoardReference = s.board;
    mLiveStopId = selectedId;
    mLivePackIdentity = pack;

    // Buttons
    boolean ready = s.packPresent;
    updatePackTrust(s);

    // Pack line: publisher, validity window in agency timezone, disclosure.
    if (s.packInfo != null)
    {
      StringBuilder sb = new StringBuilder();
      sb.append(getString(R.string.transit_pack_line,
          s.packInfo.publisher, s.packInfo.validFrom, s.packInfo.validUntil));
      java.time.Instant now = presentationNow();
      if (s.packInfo.isExpired(now))
        sb.append("\n").append(getString(R.string.transit_pack_expired));
      else if (s.packInfo.isNotYetValid(now))
        sb.append("\n").append(getString(R.string.transit_pack_not_yet_valid));

      mPackLine.setText(sb.toString());
      // Attribution + license + source + service window.
      mAttributionLine.setText(getString(R.string.transit_attribution_line,
          s.packInfo.attribution, s.packInfo.license, s.packInfo.sourceUrl,
          s.packInfo.maxDepartureSeconds / 3600));
    }
    else
    {
      mPackLine.setText(s.status == TimetableViewModel.Status.ERROR
          ? getString(R.string.transit_error_generic)
          : ready ? null : getString(R.string.transit_no_pack));
      mAttributionLine.setText(null);
    }

    // Status line
    CharSequence msg = null;
    switch (s.status)
    {
      case WORKING: msg = getString(R.string.transit_working); break;
      case NO_PACK: msg = getString(R.string.transit_no_pack); break;
      case NO_RESULTS:
        msg = (s.selectedStop != null)
            ? getString(R.string.transit_no_departures)
            : getString(R.string.transit_no_results);
        break;
      case ERROR:
        msg = s.message != null ? s.message : getString(R.string.transit_error_generic);
        break;
      default: break;
    }
    if (s.importFailedFlag() && s.status != TimetableViewModel.Status.ERROR)
      msg = getString(R.string.transit_import_failed);
    mStatusLine.setText(msg);

    // Board / candidates list
    mRefreshBtn.setVisibility(s.selectedStop != null ? View.VISIBLE : View.GONE);
    mLiveToggle.setVisibility(RealtimeClient.liveUpdatesAvailable() && s.selectedStop != null
        ? View.VISIBLE : View.GONE);
    mLiveStatusLine.setVisibility(s.selectedStop != null ? View.VISIBLE : View.GONE);
    renderBoard(s);
    updateLiveStatusLine();

    // A board arrived while live was on (toggle or explicit refresh): join it once.
    if (isQueryable(s) && mLiveOn && mNeedLiveFetch && s.status == TimetableViewModel.Status.READY
        && s.selectedStop != null && !s.board.isEmpty())
    {
      mNeedLiveFetch = false;
      fetchLive(s.board);
    }
  }

  // ---------------------------------------------------------------------
  // Opt-in realtime overlay: one fetch per toggle/refresh, no polling loop.
  // ---------------------------------------------------------------------
  @MainThread
  private void cancelLiveWork()
  {
    if (mLiveTicket != null) mLiveTicket.cancel();
    if (mLiveFuture != null) mLiveFuture.cancel(true);
    if (mLiveExecutor != null) mLiveExecutor.purge();
    mLiveTicket = null;
    mLiveFuture = null;
    mLiveFetching = false;
    mLiveGen++;
  }

  @MainThread
  private void invalidateLiveContext()
  {
    cancelLiveWork();
    mLiveFeed = null;
    mLiveEtag = null;
    mLiveErrorRes = 0;
    mLiveRows.clear();
    mLiveCoverage = 0;
    mLiveSkipped = 0;
    mLiveHandler.removeCallbacks(mExpireOverlay);
  }

  /** A pure projection controls BOTH adapter membership and each occurrence's live label. */
  @MainThread
  private void renderBoard(@Nullable TimetableViewModel.ScreenState s)
  {
    if (s == null) return;
    if (!isQueryable(s))
    {
      invalidateLiveContext();
      mAdapter.submit(new ArrayList<>());
      return;
    }
    mLiveHandler.removeCallbacks(mExpireOverlay);
    mAdapter.setShowBoard(s.selectedStop != null);
    mLiveRows.clear();
    List<Object> items = new ArrayList<>();
    if (s.selectedStop != null)
    {
      long now = System.currentTimeMillis() / 1000L;
      RealtimeBoard.Projection projection = RealtimeBoard.project(s.board,
          RealtimeClient.liveUpdatesAvailable() && mLiveOn ? mLiveFeed : null, now);
      items.addAll(projection.departures);
      mLiveRows.putAll(projection.rows);
      mLiveCoverage = projection.coverage;
      mLiveSkipped = projection.skipped;
      mLiveBoardCount = projection.departures.size();
      if (mLiveOn && projection.nextExpiryEpochSecond != null)
      {
        // One local expiry invalidation; never a fetch or a network polling loop.
        long delay = Math.max(1L, projection.nextExpiryEpochSecond * 1000L - System.currentTimeMillis());
        mLiveHandler.postDelayed(mExpireOverlay, delay);
      }
    }
    else
    {
      items.addAll(filterBoardingCandidates(s.candidates));
      mLiveCoverage = 0;
      mLiveBoardCount = 0;
      mLiveSkipped = 0;
    }
    mAdapter.submit(items);
  }

  @MainThread
  private void onLiveToggle()
  {
    TimetableViewModel.ScreenState s = mModel.getState().getValue();
    if (!RealtimeClient.liveUpdatesAvailable() || !isQueryable(s))
    {
      mLiveOn = false; mNeedLiveFetch = false;
      invalidateLiveContext(); renderBoard(s); updateLiveStatusLine();
      return;
    }
    mLiveOn = !mLiveOn;
    if (!mLiveOn)
    {
      mNeedLiveFetch = false;
      invalidateLiveContext();
      renderBoard(s); // restores skipped scheduled occurrences as well as labels
      updateLiveStatusLine();
      return;
    }
    renderBoard(s);
    if (s != null && s.status == TimetableViewModel.Status.READY
        && s.selectedStop != null && !s.board.isEmpty())
      fetchLive(s.board);
    else
    {
      mNeedLiveFetch = true; // explicit opt-in may wait for the local board, not use an old one
      updateLiveStatusLine();
    }
  }

  @MainThread
  private void fetchLive(@NonNull List<Departure> board)
  {
    if (!RealtimeClient.liveUpdatesAvailable() || !mLiveOn || board.isEmpty()
        || !isQueryable(mModel.getState().getValue())) return;
    cancelLiveWork(); // coalesce rapid refreshes, revoke active/pending transport tickets
    if (mLiveExecutor == null)
      mLiveExecutor = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
          java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1), r -> {
        Thread thread = new Thread(r, "timetable-live-fetch");
        thread.setDaemon(true);
        return thread;
      });
    final int generation = mLiveGen;
    final RealtimeRequest ticket = new RealtimeRequest();
    mLiveTicket = ticket;
    final java.util.Set<String> tripIds = RealtimeClient.tripIdsOf(board);
    final String etag = mLiveFeed != null ? mLiveEtag : null;
    mLiveErrorRes = 0;
    mLiveFetching = true;
    updateLiveStatusLine();
    mLiveFuture = mLiveExecutor.submit(() -> {
      if (!RealtimeClient.liveUpdatesAvailable() || ticket.isCanceled()) return;
      RealtimeClient.Result result = RealtimeClient.fetchAndJoin(
          LIVE_RELAY_BASE, LIVE_REGION, LIVE_HORIZON_SEC, tripIds, etag, ticket);
      mLiveHandler.post(() -> {
        TimetableViewModel.ScreenState current = mModel.getState().getValue();
        if (!RealtimeClient.liveUpdatesAvailable() || ticket.isCanceled() || !mLiveOn
            || generation != mLiveGen || isDestroyed()
            || current == null || current.status != TimetableViewModel.Status.READY
            || current.board != board)
          return; // old board/pack/consent cannot publish into the current screen
        mLiveFetching = false;
        if (!result.ok())
        {
          mLiveFeed = null;
          mLiveEtag = null;
          mLiveErrorRes = result.errorRes;
        }
        else if (result.notModified)
        {
          if (mLiveFeed == null) mLiveErrorRes = R.string.transit_live_reason_bad_feed;
          // Retain the RAW snapshot, then reproject age/occurrences with the current clock.
        }
        else
        {
          mLiveFeed = result.feed;
          mLiveEtag = result.etag;
          mLiveErrorRes = 0;
        }
        renderBoard(current);
        updateLiveStatusLine();
      });
    });
  }

  @MainThread
  private void updateLiveStatusLine()
  {
    if (!RealtimeClient.liveUpdatesAvailable())
      mLiveStatusLine.setText(R.string.transit_offline_preview);
    else if (!mLiveOn)
      mLiveStatusLine.setText(R.string.transit_live_off);
    else if (mLiveFetching)
      mLiveStatusLine.setText(R.string.transit_working);
    else if (mLiveErrorRes != 0)
      mLiveStatusLine.setText(getString(R.string.transit_live_error, getString(mLiveErrorRes)));
    else
    {
      String status = getString(R.string.transit_live_coverage, mLiveCoverage, mLiveBoardCount)
          + "  ·  " + getString(R.string.transit_live_region_all);
      if (mLiveSkipped > 0)
        status += "  ·  " + getString(R.string.transit_live_skipped_count, mLiveSkipped);
      mLiveStatusLine.setText(status);
    }
  }

  @Override protected void onResume()
  {
    super.onResume();
    // Do not supersede onCreate's identity-bound restore or an in-flight picker/import. Later
    // resumes revalidate the local pack before old candidate/board controls become usable.
    if(mHasResumed&&mModel!=null&&mPendingPickerMode==null) {
      TimetableViewModel.ScreenState current=mModel.getState().getValue();
      if(current!=null&&current.status!=TimetableViewModel.Status.WORKING)mModel.refreshPackState(null);
    }
    mHasResumed=true;
    // A resumed LiveData observer may see the same revision, so bind() need not run.
    // Preview must revoke stale consent/cache independently of new board publication.
    if (!RealtimeClient.liveUpdatesAvailable())
    {
      mLiveOn = false; mNeedLiveFetch = false;
      invalidateLiveContext();
    }
    mWatchingPack = true;
    mMapFacts = datasetMapFacts();
    if (mModel != null)
    {
      updatePackTrust(mModel.getState().getValue());
      renderBoard(mModel.getState().getValue());
      updateLiveStatusLine(); // ages cached evidence; does not fetch on resume
    }
  }

  @Override protected void onStop()
  {
    mWatchingPack = false;
    mPackHandler.removeCallbacks(mCheckPackAuthorization);
    mNeedLiveFetch = false;
    cancelLiveWork();
    mLiveHandler.removeCallbacks(mExpireOverlay);
    super.onStop();
  }

  @Override protected void onDestroy()
  {
    mWatchingPack = false;
    mPackHandler.removeCallbacks(mCheckPackAuthorization);
    if (mImportChoice != null) mImportChoice.dismiss();
    mLiveOn = false;
    mNeedLiveFetch = false;
    invalidateLiveContext();
    if (mLiveExecutor != null) mLiveExecutor.shutdownNow();
    super.onDestroy();
  }

  /**
   * Candidate display filter: only boarding platforms/stations (GTFS location_type 0
   * and 1) are surfaced; any other source point roles are made explicit by label
   * rather than silently shown as boardable stops.
   */
  private static List<Stop> filterBoardingCandidates(List<Stop> candidates)
  {
    List<Stop> out = new ArrayList<>();
    for (Stop st : candidates)
    {
      if (st.locationType == 0 || st.locationType == 1)
        out.add(st);
    }
    return out;
  }

  // ---------------------------------------------------------------------
  // RecyclerView adapter for both candidate stops and departure rows.
  // ---------------------------------------------------------------------
  private class BoardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>
  {
    private static final int TYPE_STOP = 1;
    private static final int TYPE_DEPARTURE = 2;

    private final List<Object> mItems = new ArrayList<>();
    private boolean mShowBoard = false;

    void setShowBoard(boolean showBoard) { mShowBoard = showBoard; }

    void submit(List<Object> items)
    {
      mItems.clear();
      mItems.addAll(items);
      notifyDataSetChanged(); // bounded lists (<= BOARD_LIMIT), fine for pilot
    }

    @Override public int getItemCount() { return mItems.size(); }

    @Override public int getItemViewType(int position)
    {
      return mShowBoard ? TYPE_DEPARTURE : TYPE_STOP;
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
    {
      TextView tv = new TextView(parent.getContext());
      tv.setPadding(dp(8), dp(10), dp(8), dp(10));
      tv.setTextSize(15f);
      tv.setLayoutParams(new RecyclerView.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      return new RecyclerView.ViewHolder(tv) {};
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position)
    {
      TextView tv = (TextView) h.itemView;
      if (mShowBoard)
      {
        Departure d = (Departure) mItems.get(position);
        tv.setText(departureLine(d));
        // A contentDescription REPLACES the visible text for a screen reader,
        // so it has to carry the live state too. Omitting it here meant a
        // TalkBack user never heard whether a departure was live, delayed or
        // cancelled — the live layer was silent to them.
        tv.setContentDescription(departureDescription(d));
      }
      else
      {
        Stop st = (Stop) mItems.get(position);
        tv.setText(stopLine(st));
        tv.setContentDescription(getString(R.string.transit_stop_row, stopLine(st)));
        tv.setClickable(true);
        tv.setOnClickListener(v -> {
          // Explicit user selection — disambiguation is always the user's action.
          mModel.selectStop(st);
        });
      }
    }

    /**
     * Spoken form of a departure row.
     *
     * Mirrors {@link #departureLine} but in words a screen reader can read
     * naturally: the visible line separates fields with middle dots, which a
     * screen reader announces literally as punctuation.
     */
    private String departureDescription(Departure d)
    {
      StringBuilder sb = new StringBuilder()
          .append(getString(R.string.transit_departure_row, d.line, d.destination,
                            TimetableViewModel.formatAgencyTime(d)));
      if (d.platform != null && !d.platform.isEmpty())
        sb.append(", ").append(getString(R.string.transit_platform, d.platform));
      if (d.requiresReservation())
        sb.append(", ").append(getString(R.string.transit_reservation_required));
      if (d.timepoint != null && d.timepoint == 0)
        sb.append(", ").append(getString(R.string.transit_approximate_time));
      sb.append(", ").append(liveDescription(d));
      return sb.toString();
    }

    /** Spoken realtime state; always says something, including when absent. */
    private String liveDescription(Departure d)
    {
      RealtimeMerger.DepartureRow live =
          mLiveRows.get(d);
      if (live == null || !live.hasLiveData())
        return getString(R.string.transit_live_spoken_scheduled);
      final String base;
      switch (live.state)
      {
        case CANCELED:
          base = getString(R.string.transit_live_spoken_canceled);
          break;
        case DELAYED:
        {
          long delaySec = live.delaySeconds != null ? live.delaySeconds
              : (live.liveDeparture != null
                  ? live.liveDeparture - live.scheduledDeparture : 0L);
          base = getString(R.string.transit_live_spoken_delayed, (int) delaySec);
          break;
        }
        default:
          base = getString(R.string.transit_live_spoken_live);
          break;
      }
      return live.stale ? base + ", " + getString(R.string.transit_live_spoken_stale) : base;
    }

    private String departureLine(Departure d)
    {
      String time = TimetableViewModel.formatAgencyTime(d);
      StringBuilder sb = new StringBuilder(time).append("  ")
          .append(d.line).append("  →  ").append(d.destination);
      if (d.platform != null && !d.platform.isEmpty())
        sb.append("  [").append(d.platform).append(']');
      if (d.requiresReservation())
        sb.append("  · ").append(getString(R.string.transit_reservation_required));
      if (d.timepoint != null && d.timepoint == 0)
        sb.append("  · ").append(getString(R.string.transit_approximate_time));
      // Key invariant: absent live data is NEVER rendered as live or cancelled.
      RealtimeMerger.DepartureRow live =
          mLiveRows.get(d);
      if (live != null && live.hasLiveData())
      {
        switch (live.state)
        {
          case CANCELED:
            sb.append("  · ").append(getString(R.string.transit_live_canceled));
            break;
          case DELAYED:
          {
            long delaySec = live.delaySeconds != null ? live.delaySeconds
                : (live.liveDeparture != null
                    ? live.liveDeparture - live.scheduledDeparture : 0L);
            sb.append("  · ").append(getString(R.string.transit_live_delayed, (int) delaySec));
            break;
          }
          case LIVE:
          default:
            sb.append("  · ").append(getString(R.string.transit_live_on_time));
            break;
        }
        if (live.stale)
          sb.append(" · ").append(getString(R.string.transit_live_stale));
      }
      else
      {
        sb.append("  · ").append(getString(R.string.transit_scheduled_only));
      }
      return sb.toString();
    }

    private String stopLine(Stop st)
    {
      StringBuilder sb = new StringBuilder(st.name);
      if (st.platform != null && !st.platform.isEmpty())
        sb.append(" — ").append(getString(R.string.transit_platform, st.platform));
      else if (st.locationType == 0)
        sb.append(" — ").append(getString(R.string.journey_platform_unknown));
      if (st.locationType == 1)
        sb.append("  (").append(getString(R.string.transit_station)).append(')');
      if (!Double.isNaN(st.distanceMetres))
        sb.append("  ·  ").append(String.format(Locale.getDefault(), "%.0f m", st.distanceMetres));
      // Names and even supplied platform labels can collide; retain the exact
      // source identity, just as the journey platform chooser does. Never infer
      // a platform number or direction from an opaque source-ID suffix.
      return getString(R.string.journey_platform_choice, sb.toString(), st.sourceId);
    }
  }
}
