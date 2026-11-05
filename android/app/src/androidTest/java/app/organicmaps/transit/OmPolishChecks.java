package app.organicmaps.transit;

import android.app.Activity;
import android.app.Instrumentation.ActivityMonitor;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.R;
import app.organicmaps.MwmActivity;
import app.organicmaps.search.SearchActivity;
import app.organicmaps.search.SearchFragment;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.bookmarks.data.Metadata;
import app.organicmaps.widget.SearchToolbarController;
import app.organicmaps.widget.placepage.PlacePageViewModel;
import app.organicmaps.widget.placepage.sections.PlaceOpeningHoursAdapter;
import app.organicmaps.widget.placepage.sections.PlacePageOpeningHoursFragment;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Calendar;
import java.util.List;

/** Real Android/native composition. Opening-hours metadata is explicitly synthetic and never saved. */
final class OmPolishChecks
{
  private final TransitPilotInstrumentation runner;
  private final String editedQuery;
  private String stage;
  private long completedSearchTimestamp = Long.MIN_VALUE;
  private long updatedSearchTimestamp = Long.MIN_VALUE;
  OmPolishChecks(TransitPilotInstrumentation runner) { this(runner, "Berlin"); }
  OmPolishChecks(TransitPilotInstrumentation runner, String editedQuery)
  { this.runner = runner; this.editedQuery = editedQuery; }
  private static void require(boolean value) { if (!value) throw new AssertionError(); }

  private void onMain(TransitPilotInstrumentation.Check action) throws Exception
  {
    Throwable[] error = {null};
    runner.runOnMainSync(() -> {
      try { action.run(); }
      catch (Exception | AssertionError e) { error[0] = e; }
    });
    if (error[0] instanceof AssertionError) throw (AssertionError) error[0];
    if (error[0] != null) throw (Exception) error[0];
  }

  private static Object field(Object object, Class<?> owner, String name) throws Exception
  {
    Field f = owner.getDeclaredField(name); f.setAccessible(true); return f.get(object);
  }

  @SuppressWarnings("unchecked")
  private static List<PlaceOpeningHoursAdapter.WeekScheduleData> rows(PlaceOpeningHoursAdapter adapter)
      throws Exception
  {
    return (List<PlaceOpeningHoursAdapter.WeekScheduleData>)
        field(adapter, PlaceOpeningHoursAdapter.class, "mWeekSchedule");
  }

  private void assertStyle(RecyclerView.ViewHolder holder, boolean bold, int minimumShifts)
  {
    require(holder != null && holder.itemView.isShown());
    TextView days = holder.itemView.findViewById(R.id.tv__opening_hours_weekdays);
    require(days.getTypeface().isBold() == bold);
    if (bold)
    {
      require(days.getContentDescription() != null);
      String description = days.getContentDescription().toString();
      require(description.contains(days.getText().toString()));
      require(description.contains(runner.getTargetContext().getString(app.organicmaps.sdk.R.string.today)));
    }
    else require(days.getContentDescription() == null);
    LinearLayout shifts = holder.itemView.findViewById(R.id.ll__opening_hours_shifts);
    require(shifts.getChildCount() >= minimumShifts);
    for (int i = 0; i < shifts.getChildCount(); i++)
      require(((TextView) shifts.getChildAt(i)).getTypeface().isBold() == bold);
  }

  private void hours(boolean working) throws Exception
  {
    stage = "launch isolated hours host";
    FragmentActivity activity = (FragmentActivity) runner.startActivitySync(
        new Intent(runner.getTargetContext(), TimetableActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try
    {
      PlacePageOpeningHoursFragment fragment = new PlacePageOpeningHoursFragment();
      onMain(() -> {
        stage = "mount real opening-hours Fragment and native parser";
        int today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        int next = today == Calendar.SATURDAY ? Calendar.SUNDAY : today + 1;
        String[] dayNames = {"", "Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"};
        String hours = dayNames[working ? today : next] + " 09:00-12:00,13:00-18:00";
        MapObject fixture = MapObject.createMapObject(FeatureId.EMPTY, MapObject.SEARCH,
            "Controlled opening-hours fixture", "Synthetic metadata, not an OSM place", 0, 0);
        fixture.addMetadata(Metadata.MetadataType.FMD_OPEN_HOURS.toInt(), hours);
        new ViewModelProvider(activity).get(PlacePageViewModel.class).setMapObject(fixture);
        activity.getSupportFragmentManager().beginTransaction()
            .add(android.R.id.content, fragment, "om-polish-synthetic-hours").commitNow();
        stage = "expand through actual Fragment click handler";
        require(fragment.requireView().performClick());
      });
      long layoutDeadline = SystemClock.uptimeMillis() + 8000;
      boolean[] laidOut = {false};
      do
      {
        runner.waitForIdleSync();
        onMain(() -> {
          RecyclerView list = fragment.requireView().findViewById(R.id.rw__full_opening_hours);
          PlaceOpeningHoursAdapter adapter = (PlaceOpeningHoursAdapter) list.getAdapter();
          List<PlaceOpeningHoursAdapter.WeekScheduleData> schedule = rows(adapter);
          for (int i = 0; i < schedule.size(); i++)
            if (schedule.get(i).isToday)
            {
              RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(i);
              laidOut[0] = holder != null && holder.itemView.isShown()
                  && holder.itemView.getHeight() > 0 && !list.isComputingLayout();
            }
        });
        if (!laidOut[0]) SystemClock.sleep(50);
      } while (!laidOut[0] && SystemClock.uptimeMillis() < layoutDeadline);
      require(laidOut[0]);
      onMain(() -> {
        stage = "inspect actual Android row fonts and accessibility";
        RecyclerView list = fragment.requireView().findViewById(R.id.rw__full_opening_hours);
        PlaceOpeningHoursAdapter adapter = (PlaceOpeningHoursAdapter) list.getAdapter();
        require(adapter != null && adapter.getItemCount() >= 2);
        List<PlaceOpeningHoursAdapter.WeekScheduleData> schedule = rows(adapter);
        int current = -1, other = -1, count = 0;
        for (int i = 0; i < schedule.size(); i++)
          if (schedule.get(i).isToday) { current = i; count++; } else other = i;
        require(count == 1 && current >= 0 && other >= 0);
        require(schedule.get(current).isClosed != working);
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(current);
        assertStyle(holder, true, working ? 2 : 1);
        View root = fragment.requireView();
        require(root.getWidth() > 0 && root.getHeight() > 0);
        // Capture only this authored Fragment, never the status bar, other apps, a map or picker.
        stage = "capture isolated native View drawing";
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(new File(runner.getTargetContext().getCacheDir(),
            "om-polish-20261018-" + (working ? "working" : "closed") + ".png")))
        {
          root.draw(new Canvas(bitmap)); require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        finally { bitmap.recycle(); }
        stage = "verify actual Android recycled-holder reset";
        adapter.onBindViewHolder((PlaceOpeningHoursAdapter.ViewHolder) holder, other);
        assertStyle(holder, false, schedule.get(other).isClosed ? 1 : 2);
        adapter.onBindViewHolder((PlaceOpeningHoursAdapter.ViewHolder) holder, current);
        assertStyle(holder, true, working ? 2 : 1);
      });
    }
    catch (Exception | AssertionError e) { runner.note("OM hours failed at fixed stage: " + stage); throw e; }
    finally { onMain(activity::finish); }
  }

  private SearchFragment fragment(SearchActivity activity)
  {
    SearchFragment result = (SearchFragment) activity.getSupportFragmentManager()
        .findFragmentByTag(SearchFragment.class.getName());
    require(result != null); return result;
  }

  private SearchToolbarController toolbar(SearchFragment fragment) throws Exception
  { return (SearchToolbarController) field(fragment, SearchFragment.class, "mToolbarController"); }

  private void waitForResults(SearchActivity activity) throws Exception
  {
    long searchStart = SystemClock.uptimeMillis();
    long deadline = searchStart + 120000;
    boolean[] ready = {false};
    do
    {
      runner.waitForIdleSync();
      onMain(() -> {
        RecyclerView.Adapter<?> adapter = (RecyclerView.Adapter<?>)
            field(fragment(activity), SearchFragment.class, "mSearchAdapter");
        long requested = (Long) field(fragment(activity), SearchFragment.class, "mLastQueryTimestamp");
        ready[0] = adapter.getItemCount() > 0 && completedSearchTimestamp == requested
            && updatedSearchTimestamp == requested;
      });
      if (!ready[0]) SystemClock.sleep(100);
    }
    while (!ready[0] && SystemClock.uptimeMillis() < deadline);
    runner.note("OM native results ready=" + ready[0] + " elapsedMs="
        + (SystemClock.uptimeMillis() - searchStart));
    if (!ready[0])
      onMain(() -> {
        SearchFragment f = fragment(activity);
        runner.note("OM search readiness: resumed=" + f.isResumed()
            + " focused=" + activity.hasWindowFocus()
            + " fixtureQueryMatches=" + ("Alexanderplatz".equals(toolbar(f).getQuery()) || editedQuery.equals(toolbar(f).getQuery()))
            + " initialConsumed=" + (field(f, SearchFragment.class, "mInitialQuery") == null)
            + " running=" + field(f, SearchFragment.class, "mSearchRunning"));
      });
    require(ready[0]);
  }

  private void search() throws Exception
  {
    stage = "launch real SearchActivity with initial locale";
    SearchActivity activity = (SearchActivity) runner.startActivitySync(
        new Intent(runner.getTargetContext(), SearchActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(SearchActivity.EXTRA_QUERY, "Alexanderplatz")
            .putExtra(SearchActivity.EXTRA_LOCALE, "de_AT"));
    ActivityMonitor monitor = null;
    SearchActivity recreated = null;
    int[] nativeCounts = {0, 0};
    long[] nativeTimestamp = {0};
    app.organicmaps.sdk.search.SearchListener observer = new app.organicmaps.sdk.search.SearchListener() {
      @Override public void onResultsUpdate(app.organicmaps.sdk.search.SearchResult[] results, long timestamp)
      { nativeCounts[0]++; nativeTimestamp[0] = timestamp; updatedSearchTimestamp = timestamp; }
      @Override public void onResultsEnd(long timestamp)
      { nativeCounts[1]++; nativeTimestamp[0] = timestamp; completedSearchTimestamp = timestamp; }
    };
    onMain(() -> app.organicmaps.sdk.search.SearchEngine.INSTANCE.addListener(observer));
    try
    {
      waitForResults(activity);
      onMain(() -> {
        stage = "assert applied initial query and retained one-shot locale";
        SearchFragment f = fragment(activity);
        require("Alexanderplatz".equals(toolbar(f).getQuery()));
        require("de_AT".equals(field(f, SearchFragment.class, "mInitialLocale")));
        require(field(f, SearchFragment.class, "mInitialQuery") == null);
        stage = "edit through actual EditText watcher";
        toolbar(f).setQuery(editedQuery, false);
        require(field(f, SearchFragment.class, "mInitialLocale") == null);
      });
      stage = "wait fresh edited-query completion";
      waitForResults(activity);
      stage = "recreate actual Android SearchActivity";
      onMain(() -> { nativeCounts[0] = 0; nativeCounts[1] = 0; nativeTimestamp[0] = 0; });
      monitor = runner.addMonitor(SearchActivity.class.getName(), null, false);
      onMain(activity::recreate);
      Activity fresh = monitor.waitForActivityWithTimeout(10000);
      require(fresh instanceof SearchActivity && fresh != activity);
      recreated = (SearchActivity) fresh;
      final SearchActivity current = recreated;
      long resumeDeadline = SystemClock.uptimeMillis() + 10000;
      boolean[] resumed = {false};
      do
      {
        runner.waitForIdleSync();
        onMain(() -> {
          SearchFragment f = fragment(current);
          resumed[0] = f.isResumed() && current.hasWindowFocus()
              && field(f, SearchFragment.class, "mInitialQuery") == null;
        });
        if (!resumed[0]) SystemClock.sleep(50);
      } while (!resumed[0] && SystemClock.uptimeMillis() < resumeDeadline);
      require(resumed[0]);
      onMain(() -> {
        stage = "assert recreated query and revoked locale";
        SearchFragment f = fragment(current);
        runner.note("OM recreated text: matchesEdited=" + editedQuery.equals(toolbar(f).getQuery())
            + " matchesOriginal=" + "Alexanderplatz".equals(toolbar(f).getQuery())
            + " empty=" + toolbar(f).getQuery().isEmpty());
        require(editedQuery.equals(toolbar(f).getQuery()));
        require(field(f, SearchFragment.class, "mInitialLocale") == null);
      });
      try { waitForResults(current); }
      catch (AssertionError original)
      {
        onMain(() -> runner.note("OM recreate native callbacks: updates=" + nativeCounts[0]
            + " ends=" + nativeCounts[1] + " timestampMatchesCurrent="
            + (nativeTimestamp[0] == (Long) field(fragment(current), SearchFragment.class, "mLastQueryTimestamp"))));
        // Diagnostic recovery only; never turn an initial failed recreation into a pass.
        onMain(() -> toolbar(fragment(current)).setQuery(editedQuery, false));
        long retryEnd = SystemClock.uptimeMillis() + 8000;
        boolean[] recovered = {false};
        do
        {
          runner.waitForIdleSync();
          onMain(() -> recovered[0] = ((RecyclerView.Adapter<?>) field(fragment(current),
              SearchFragment.class, "mSearchAdapter")).getItemCount() > 0);
          if (!recovered[0]) SystemClock.sleep(50);
        } while (!recovered[0] && SystemClock.uptimeMillis() < retryEnd);
        runner.note("OM diagnostic same-query manual retry recovered=" + recovered[0]);
        throw original;
      }
    }
    catch (Exception | AssertionError e) { runner.note("OM search failed at fixed stage: " + stage); throw e; }
    finally
    {
      onMain(() -> app.organicmaps.sdk.search.SearchEngine.INSTANCE.removeListener(observer));
      if (monitor != null) runner.removeMonitor(monitor);
      SearchActivity current = recreated;
      if (current != null) onMain(current::finish);
      onMain(activity::finish);
    }
  }

  void run() throws Exception
  {
    // TimetableActivity can run without the map core; SearchActivity uses the normal core-readiness gate.
    // Initialize through the real launcher rather than bypassing that production prerequisite.
    ActivityMonitor ready = runner.addMonitor(MwmActivity.class.getName(), null, false);
    try
    {
      Intent launch = runner.getTargetContext().getPackageManager()
          .getLaunchIntentForPackage(runner.getTargetContext().getPackageName());
      require(launch != null);
      runner.startActivitySync(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      Activity map = ready.waitForActivityWithTimeout(15000);
      require(map instanceof MwmActivity);
      // SearchAPI delays every request until a real viewport exists (search_api.cpp).
      // An Activity monitor only observes onResume, before Surface/renderer readiness.
      long renderStart = SystemClock.uptimeMillis();
      long renderDeadline = renderStart + 30000;
      boolean[] rendering = {false};
      boolean[] readiness = new boolean[4];
      do
      {
        runner.waitForIdleSync();
        onMain(() -> {
          app.organicmaps.sdk.MapController controller = (app.organicmaps.sdk.MapController)
              field(map, MwmActivity.class, "mMapController");
          readiness[0] = map.hasWindowFocus();
          readiness[1] = controller.isRenderingActive();
          readiness[2] = controller.getView().isShown();
          readiness[3] = controller.getView().getWidth() > 0;
          rendering[0] = readiness[0] && readiness[1] && readiness[2] && readiness[3];
        });
        if (!rendering[0]) SystemClock.sleep(50);
      }
      while (!rendering[0] && SystemClock.uptimeMillis() < renderDeadline);
      runner.note("OM normal map renderer ready=" + rendering[0]
          + " elapsedMs=" + (SystemClock.uptimeMillis() - renderStart));
      if (!rendering[0])
        runner.note("OM renderer readiness: focused=" + readiness[0] + " active=" + readiness[1]
            + " shown=" + readiness[2] + " widthPositive=" + readiness[3]);
      require(rendering[0]);
      runner.waitForIdleSync();
    }
    finally { runner.removeMonitor(ready); }
    runner.note("OM hours: authored ephemeral metadata, real Fragment/native parser/Android fonts; no OSM data edits.");
    runner.note("OM captures: isolated native View.draw images, not whole-device screenshots.");
    runner.check("OM native working-day emphasis, two shifts and recycled-holder reset", () -> hours(true));
    runner.check("OM native closed-day emphasis and recycled-holder reset", () -> hours(false));
    runner.check("OM real SearchActivity initial locale, manual edit and configuration recreation", this::search);
  }
}
