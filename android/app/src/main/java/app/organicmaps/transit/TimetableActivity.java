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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import app.organicmaps.sdk.transit.Departure;
import app.organicmaps.sdk.transit.Stop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline timetable pilot activity: pack import/remove (SAF), manual station search,
 * optional nearby candidates from intent extras, explicit platform disambiguation,
 * and a scheduled departure board. All data work happens in {@link TimetableViewModel}'s
 * single background executor; this class only binds state on the main thread.
 */
public class TimetableActivity extends AppCompatActivity
{
  private static final int REQ_IMPORT_PACK = 47001;

  private static final String EXTRA_LAT = "transit.extra.lat";
  private static final String EXTRA_LON = "transit.extra.lon";
  private static final String EXTRA_NAME = "transit.extra.name";

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
  private TextView mStatusLine;
  private TextView mPackLine;
  private TextView mAttributionLine;
  private RecyclerView mList;
  private BoardAdapter mAdapter;
  private int mLastBoundRevision = -1;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    mModel = new ViewModelProvider(this).get(TimetableViewModel.class);

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
    mQuery.setText(mModel.getQueryText());
    mModel.refreshPackState(restored);

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
    mPackLine = new TextView(c);
    mPackLine.setId(View.generateViewId());
    mPackLine.setContentDescription(getString(R.string.transit_pack_info));
    root.addView(mPackLine);

    mAttributionLine = new TextView(c);
    mAttributionLine.setId(View.generateViewId());
    mAttributionLine.setContentDescription(getString(R.string.transit_attribution));
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

    // -- Search ---------------------------------------------------------------
    LinearLayout searchRow = vrow(c);
    mQuery = new EditText(c);
    mQuery.setId(View.generateViewId());
    // Disable personalized keyboard learning for the search field.
    mQuery.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    mQuery.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
    mQuery.setContentDescription(getString(R.string.transit_search_hint));
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
    mStatusLine.setContentDescription(getString(R.string.transit_status));
    mStatusLine.setPadding(0, dp(8), 0, dp(8));
    root.addView(mStatusLine);

    mRefreshBtn = new Button(c);
    mRefreshBtn.setId(View.generateViewId());
    mRefreshBtn.setText(R.string.transit_refresh);
    mRefreshBtn.setContentDescription(getString(R.string.transit_refresh));
    mRefreshBtn.setOnClickListener(v -> mModel.refreshBoard());
    root.addView(mRefreshBtn, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

  private void openPackPicker()
  {
    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    pick.addCategory(Intent.CATEGORY_OPENABLE);
    pick.setType("*/*");
    // .ctpack is a ZIP; no reliable MIME type, so accept any openable document.
    try
    {
      startActivityForResult(pick, REQ_IMPORT_PACK);
    }
    catch (android.content.ActivityNotFoundException e)
    {
      mStatusLine.setText(R.string.transit_error_no_document_picker);
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data)
  {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != REQ_IMPORT_PACK)
      return;
    if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null)
      return; // cancelled: previous pack untouched, no error surface
    Uri uri = data.getData();
    try
    {
      grantUriPermission(getPackageName(), uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    catch (Exception ignored) {}
    mModel.importPack(uri);
  }

  private void bind(TimetableViewModel.ScreenState s)
  {
    if (s == null || s.revision == mLastBoundRevision)
      return;
    mLastBoundRevision = s.revision;

    // Buttons
    boolean ready = s.packPresent;
    mRemoveBtn.setEnabled(ready);
    mSearchBtn.setEnabled(ready);
    mQuery.setEnabled(ready);
    mNearbyBtn.setEnabled(ready && mModel.hasNearbyHint());

    // Pack line: publisher, validity window in agency timezone, disclosure.
    if (s.packInfo != null)
    {
      StringBuilder sb = new StringBuilder();
      sb.append(getString(R.string.transit_pack_line,
          s.packInfo.publisher, s.packInfo.validFrom, s.packInfo.validUntil));
      java.time.Instant now = java.time.Instant.now();
      if (s.packInfo.isExpired(now))
        sb.append("\n").append(getString(R.string.transit_pack_expired));
      else if (s.packInfo.isNotYetValid(now))
        sb.append("\n").append(getString(R.string.transit_pack_not_yet_valid));
      sb.append("\n").append(getString(R.string.transit_unsigned_pack_disclosure));
      mPackLine.setText(sb.toString());
      // Attribution + license + source + service window.
      mAttributionLine.setText(getString(R.string.transit_attribution_line,
          s.packInfo.attribution, s.packInfo.license, s.packInfo.sourceUrl,
          s.packInfo.maxDepartureSeconds / 3600));
    }
    else
    {
      mPackLine.setText(ready ? null : getString(R.string.transit_no_pack));
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
        msg = getString(R.string.transit_error_generic);
        break;
      default: break;
    }
    if (s.importFailedFlag())
      msg = getString(R.string.transit_import_failed);
    mStatusLine.setText(msg);

    // Board / candidates list
    mRefreshBtn.setVisibility(s.selectedStop != null ? View.VISIBLE : View.GONE);
    mAdapter.setShowBoard(s.selectedStop != null);
    List<Object> rows = new ArrayList<>();
    if (s.selectedStop != null)
      rows.addAll(s.board);
    else
      rows.addAll(filterBoardingCandidates(s.candidates));
    mAdapter.submit(rows);
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
      notifyDataSetChanged(); // bounded lists (<= 50), fine for pilot
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
        tv.setContentDescription(getString(R.string.transit_departure_row, d.line, d.destination,
            TimetableViewModel.formatAgencyTime(d)));
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
      sb.append("  · ").append(getString(R.string.transit_scheduled_only));
      return sb.toString();
    }

    private String stopLine(Stop st)
    {
      StringBuilder sb = new StringBuilder(st.name);
      if (st.platform != null && !st.platform.isEmpty())
        sb.append(" — ").append(getString(R.string.transit_platform, st.platform));
      if (st.locationType == 1)
        sb.append("  (").append(getString(R.string.transit_station)).append(')');
      if (!Double.isNaN(st.distanceMetres))
        sb.append("  ·  ").append(String.format(Locale.getDefault(), "%.0f m", st.distanceMetres));
      return sb.toString();
    }
  }
}
