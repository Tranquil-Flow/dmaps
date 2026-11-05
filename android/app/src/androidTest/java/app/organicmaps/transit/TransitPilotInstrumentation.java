package app.organicmaps.transit;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import app.organicmaps.sdk.transit.Departure;
import app.organicmaps.sdk.transit.Stop;
import app.organicmaps.sdk.transit.TimetableRepository;

import java.time.Instant;
import java.util.List;

/** Read-only pilot checks against the actual pack installed in the target app. */
public final class TransitPilotInstrumentation extends Instrumentation
{
  // Owner authorized temporary test-window wakefulness; no device setting or lock change.
  private final java.util.Set<Activity> mKeptAwake = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap<Activity, Boolean>());
  private String mScope = "all";
  private String mBerlinInputs;
  private int mTests;
  private int mFailures;
  private final StringBuilder mReport = new StringBuilder();

  @Override public void onCreate(Bundle arguments)
  {
    super.onCreate(arguments);
    if (arguments != null) mScope = arguments.getString("scope", "all");
    if (arguments != null) mBerlinInputs = arguments.getString("berlin_inputs");
    if ("mapped-walking".equals(mScope) || "berlin-walking".equals(mScope))
      throw new IllegalArgumentException("Native fixtures require self-targeted instrumentation");
    start();
  }

  @Override public void callActivityOnResume(Activity activity)
  {
    super.callActivityOnResume(activity);
    int flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
    if ("app.comaps.fdroid.debug".equals(activity.getPackageName())
        && (activity.getWindow().getAttributes().flags & flag) == 0)
    {
      mKeptAwake.add(activity);
      activity.getWindow().addFlags(flag);
    }
  }

  @Override public void callActivityOnPause(Activity activity)
  {
    if (mKeptAwake.remove(activity))
      activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    super.callActivityOnPause(activity);
  }

  interface Check { void run() throws Exception; }

  void check(String name, Check check)
  {
    mTests++;
    try
    {
      check.run();
      mReport.append("PASS ").append(name).append('\n');
    }
    catch (Exception | AssertionError e)
    {
      mFailures++;
      // No arbitrary pack payload, station history or exception text in output.
      mReport.append("FAIL ").append(name).append(" (")
          .append(e.getClass().getSimpleName()).append(")\n");
      // Source frames only: no exception message, payload, query history or location.
      for (StackTraceElement frame : e.getStackTrace())
        if (frame.getClassName().startsWith("app.organicmaps.transit.")
            || frame.getClassName().startsWith("app.organicmaps.sdk.transit."))
          mReport.append("  AT ").append(frame.getFileName()).append(":")
              .append(frame.getLineNumber()).append("\n");
    }
  }

  void note(String text) { mReport.append(text).append('\n'); }

  private static void require(boolean condition)
  {
    if (!condition) throw new AssertionError();
  }

  private static boolean keepsText(TextView view)
  {
    CharSequence description = view.getContentDescription();
    return description == null || description.toString().contains(view.getText().toString());
  }

  private static TextView findText(View view, String fragment)
  {
    if (view instanceof TextView && ((TextView) view).getText().toString().contains(fragment))
      return (TextView) view;
    if (view instanceof ViewGroup)
    {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++)
      {
        TextView found = findText(group.getChildAt(i), fragment);
        if (found != null) return found;
      }
    }
    return null;
  }

  @Override public void onStart()
  {
    if ("berlin-walking".equals(mScope))
    {
      check("real Berlin Android isolated registered map and regional repository composition", () ->
          app.organicmaps.sdk.transit.BerlinWalkingChecks.run(this, mBerlinInputs));
      reportResult(); return;
    }
    if ("mapped-walking".equals(mScope))
    {
      check("isolated Android native mapped walking composition harness", () ->
          app.organicmaps.sdk.transit.PackWalkingChecks.run(this,
              (name, body) -> check(name, body::run)));
      reportResult(); return;
    }
    if ("pack-auth".equals(mScope))
    {
      check("isolated authenticated pack JNI SQLite lifecycle harness", () ->
          app.organicmaps.sdk.transit.PackAuthenticationChecks.run(getTargetContext(), getContext(),
              (name, body) -> check(name, body::run)));
      reportResult(); return;
    }
    if ("pack-crypto".equals(mScope) || "all".equals(mScope))
    {
      check("synthetic public Ed25519 native primitive harness", () -> new PackCryptoChecks(this).run());
      if ("pack-crypto".equals(mScope)) { reportResult(); return; }
    }
    if ("om-prefix".equals(mScope))
    {
      check("OM bounded-prefix diagnostic composition", () -> new OmPolishChecks(this, "Alexanderplat").run());
      reportResult(); return;
    }
    if ("transit-lifecycle".equals(mScope))
    {
      check("synthetic Activity profile lifecycle composition harness", () -> new TransitLifecycleChecks(this).run());
      reportResult(); return;
    }
    check("OM polish real Android composition harness", () -> new OmPolishChecks(this).run());
    if ("om".equals(mScope)) { reportResult(); return; }
    try (TimetableRepository repo = new TimetableRepository(getTargetContext()))
    {
      check("installed pack is readable", () -> require(repo.hasPack() && repo.info() != null));
      check("UI board depth is accepted by repository", () -> {
        List<Stop> candidates = repo.searchStops("Alexanderplatz", 20);
        Stop station = null;
        for (Stop stop : candidates)
          if (stop.locationType == 1) { station = stop; break; }
        require(station != null);
        List<Departure> rows = repo.departures(station.id, Instant.now(),
            TimetableViewModel.BOARD_LIMIT, TimetableViewModel.BOARD_HORIZON_MINUTES);
        require(!rows.isEmpty() && rows.size() <= TimetableViewModel.BOARD_LIMIT);
        for (Departure row : rows) require(row.when != null && row.stopSourceId != null);
      });
      check("over-budget board is refused", () -> {
        try
        {
          repo.departures(1, Instant.now(), TimetableViewModel.BOARD_LIMIT + 1,
              TimetableViewModel.BOARD_HORIZON_MINUTES);
          throw new AssertionError();
        }
        catch (IllegalArgumentException expected) { }
      });
    }
    check("dynamic metadata remains in the accessibility name", () -> {
      Intent intent = new Intent(getTargetContext(), TimetableActivity.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      Activity activity = startActivitySync(intent);
      try
      {
        // Wait for the real asynchronous pack read, not merely activity creation.
        long deadline = android.os.SystemClock.uptimeMillis() + 15000;
        boolean[] ready = {false};
        do
        {
          waitForIdleSync();
          runOnMainSync(() -> ready[0] = findText(activity.getWindow().getDecorView(),
              getTargetContext().getString(app.organicmaps.R.string.transit_unsigned_pack_disclosure)) != null);
          if (!ready[0]) android.os.SystemClock.sleep(100);
        }
        while (!ready[0] && android.os.SystemClock.uptimeMillis() < deadline);
        require(ready[0]);
        boolean[] accessible = {false};
        runOnMainSync(() -> {
          TextView pack = findText(activity.getWindow().getDecorView(),
              getTargetContext().getString(app.organicmaps.R.string.transit_unsigned_pack_disclosure));
          TextView attribution = findText(activity.getWindow().getDecorView(), "CC BY 4.0");
          if (pack != null && attribution != null)
            accessible[0] = keepsText(pack) && keepsText(attribution);
        });
        require(accessible[0]);
      }
      finally { runOnMainSync(activity::finish); }
    });
    check("synthetic Activity profile lifecycle composition harness", () -> new TransitLifecycleChecks(this).run());
    check("isolated pack activation crash-layout harness", () -> new PackRecoveryChecks(this).run());
    check("isolated import real-corpus and phone-metrics harness", () -> new PackImportChecks(this).run());
    reportResult();
  }

  private void reportResult()
  {
    Bundle result = new Bundle();
    result.putString("stream", "\n" + mReport + "Tests: " + mTests + ", failures: " + mFailures + "\n");
    result.putInt("tests", mTests);
    result.putInt("failures", mFailures);
    finish(mFailures == 0 ? -1 : 0, result);
  }
}
