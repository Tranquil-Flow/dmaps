package app.organicmaps.routing;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import app.organicmaps.ChartController;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.Router;
import androidx.activity.result.ActivityResultLauncher;
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.routing.RouteAltitudeData;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.util.Distance;
import app.organicmaps.sdk.util.StringUtils;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.highlight.Highlight;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual resource/menu/chart consumers; only native producers and route state are controlled.
 * NOT JNI/native-stat arithmetic, ART, GPU, physical touch or full Activity acceptance.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, manifest = Config.NONE, qualifiers = "port",
        shadows = {RouteAltitudePanelTest.NativeChart.class, RouteAltitudePanelTest.NativeDistance.class})
public class RouteAltitudePanelTest
{
  @Implements(value = Framework.class, isInAndroidSdk = false)
  public static class NativeChart
  {
    static RouteAltitudeData profile;
    static boolean bitmap, metric;
    static int calls, saves;
    @Implementation protected static int[] nativeGenerateRouteAltitudeChartBits(
        int width, int height, Framework.RouteAltitudeLimits limits)
    {
      calls++;
      limits.profile = profile;
      limits.isMetricUnits = metric;
      // Deliberately noncanonical strings catch Java stat recalculation/reformatting.
      limits.totalAscent = 999;
      limits.totalDescent = 888;
      limits.totalAscentString = metric ? "007" : "023";
      limits.totalDescentString = metric ? "019" : "062";
      return bitmap ? new int[width * height] : null;
    }
    @Implementation protected static String nativeFormatAltitude(double metres)
    { return "height:" + metres; }
    @Implementation protected static void nativeSaveRoute() { saves++; }
  }

  @Implements(value = StringUtils.class, isInAndroidSdk = false)
  public static class NativeDistance
  {
    @Implementation protected static Distance nativeFormatDistance(double metres)
    { return new Distance(metres, "distance:" + metres, (byte) 0); }
  }

  private Activity activity;
  private View root;
  private RoutingBottomMenuController menu;
  private RoutingController routing;
  private MockedStatic<RoutingController> routes;
  private LineChart chart;
  private static RouteAltitudeData profile(double a, double b)
  { return new RouteAltitudeData(new double[] {0, 100}, new double[] {a, b}); }

  @Before public void setup()
  {
    NativeChart.profile = profile(-12, -12);
    NativeChart.bitmap = true;
    NativeChart.metric = true;
    NativeChart.calls = NativeChart.saves = 0;
    org.robolectric.android.controller.ActivityController<androidx.appcompat.app.AppCompatActivity> owner =
        Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity.class);
    activity = owner.get();
    activity.setTheme(R.style.MwmTheme);
    owner.setup();
    routing = mock(RoutingController.class);
    when(routing.isBuilt()).thenReturn(true);
    routes = mockStatic(RoutingController.class);
    routes.when(RoutingController::get).thenReturn(routing);
    root = LayoutInflater.from(activity).inflate(R.layout.fragment_routing, null, false);
    menu = RoutingBottomMenuController.newInstance(activity, root, mock(RoutingBottomMenuListener.class));
    chart = root.findViewById(R.id.route_altitude_line_chart);
  }

  @After public void teardown()
  {
    if (routes != null) routes.close();
    if (activity != null) activity.finish();
  }

  private TextView stats() { return root.findViewById(R.id.altitude_difference); }
  private void assertCleared()
  {
    assertNull(chart.getData());
    assertFalse(chart.valuesToHighlight());
    assertEquals(View.GONE, root.findViewById(R.id.route_altitude_chart).getVisibility());
    assertEquals("", ((TextView) root.findViewById(R.id.route_altitude_selection)).getText().toString());
    assertEquals(View.GONE, stats().getVisibility());
    assertNull(((ImageView) root.findViewById(R.id.altitude_chart)).getDrawable());
  }

  @Test public void actualPanelUsesDefaultRendererAndRawMetresForFlatNegativeProfile()
  {
    menu.showAltitudeChartAndRoutingDetails();
    assertEquals(1, NativeChart.calls);
    assertEquals(View.VISIBLE, root.findViewById(R.id.route_altitude_chart).getVisibility());
    assertEquals(View.GONE, root.findViewById(R.id.altitude_chart).getVisibility());
    assertEquals("com.github.mikephil.charting.renderer.LineChartRenderer", chart.getRenderer().getClass().getName());
    assertEquals(2, chart.getData().getEntryCount());
    assertEquals(-12, chart.getData().getDataSetByIndex(0).getEntryForIndex(0).getY(), 0);
    assertTrue(chart.getAxisLeft().getAxisMinimum() < -12);
    assertTrue(chart.getAxisLeft().getAxisMaximum() > -12);
    // Exercise real retained renderer on a host Canvas, not a mock chart destination.
    chart.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY));
    chart.layout(0, 0, 600, 320);
    chart.draw(new Canvas(Bitmap.createBitmap(600, 320, Bitmap.Config.ARGB_8888)));
    chart.highlightValue(new Highlight(100f, -12f, 0), true);
    assertTrue(chart.valuesToHighlight());
    assertTrue(((TextView) root.findViewById(R.id.route_altitude_selection)).getText().toString().contains("height:-12"));
    menu.hideAltitudeChartAndRoutingDetails();
    assertCleared();
  }

  @Test public void bitmapFallbackAndInteractiveChartDisplayIdenticalNativeStringsInBothUnits()
  {
    for (boolean metric : new boolean[] {true, false})
    {
      NativeChart.metric = metric;
      NativeChart.profile = profile(10, 25);
      menu.showAltitudeChartAndRoutingDetails();
      String interactive = stats().getText().toString();
      String unit = activity.getString(metric ? app.organicmaps.sdk.R.string.m : app.organicmaps.sdk.R.string.ft);
      assertEquals(metric ? "↗ 007 " + unit + " ↘ 019 " + unit : "↗ 023 " + unit + " ↘ 062 " + unit, interactive);
      NativeChart.profile = null;
      menu.showAltitudeChartAndRoutingDetails();
      assertNull(chart.getData());
      assertEquals(View.VISIBLE, root.findViewById(R.id.altitude_chart).getVisibility());
      assertNotNull(((ImageView) root.findViewById(R.id.altitude_chart)).getDrawable());
      assertEquals(interactive, stats().getText().toString());
    }
  }

  @Test public void unsupportedFloatOrDuplicateProfileFallsBackWithoutStaleHighlight()
  {
    menu.showAltitudeChartAndRoutingDetails();
    chart.highlightValue(new Highlight(100f, -12f, 0), true);
    for (RouteAltitudeData invalid : new RouteAltitudeData[] {
        new RouteAltitudeData(new double[] {0, 1, 1}, new double[] {1, 2, 3}),
        new RouteAltitudeData(new double[] {0, Double.MAX_VALUE}, new double[] {1, 2}),
        profile(Double.MAX_VALUE, Double.MAX_VALUE)})
    {
      NativeChart.profile = invalid;
      menu.showAltitudeChartAndRoutingDetails();
      assertNull(chart.getData());
      assertFalse(chart.valuesToHighlight());
      assertEquals(View.VISIBLE, root.findViewById(R.id.altitude_chart).getVisibility());
    }
  }

  @Test public void noBitmapDoesNotPublishEvenAnAvailableProfileOrOldStatistics()
  {
    menu.showAltitudeChartAndRoutingDetails();
    NativeChart.bitmap = false;
    menu.showAltitudeChartAndRoutingDetails();
    assertCleared();
  }

  @Test public void replacementRebuildRestoreSuppressionAndSaveClearOldSelection()
  {
    menu.showAltitudeChartAndRoutingDetails();
    chart.highlightValue(new Highlight(100f, -12f, 0), true);
    NativeChart.profile = profile(200, 300);
    menu.showAltitudeChartAndRoutingDetails();
    assertFalse(chart.valuesToHighlight());
    assertEquals(200, chart.getData().getYMin(), 0);
    when(routing.isBuilt()).thenReturn(false);
    menu.showAltitudeChartAndRoutingDetails();
    assertCleared();
    Bundle state = new Bundle();
    state.putBoolean("altitude_chart_shown", true);
    menu.restoreRoutingPanelState(state);
    assertCleared();
    when(routing.isBuilt()).thenReturn(true);
    for (int mode = 0; mode < 3; ++mode)
    {
      when(routing.isVehicleRouterType()).thenReturn(mode == 0);
      when(routing.isRulerRouterType()).thenReturn(mode == 1);
      when(routing.isTransitType()).thenReturn(mode == 2);
      int before = NativeChart.calls;
      menu.showAltitudeChartAndRoutingDetails();
      assertEquals(before, NativeChart.calls);
      assertCleared();
    }
    when(routing.isTransitType()).thenReturn(false);
    menu.showAltitudeChartAndRoutingDetails();
    root.findViewById(R.id.btn__save).performClick();
    assertEquals(1, NativeChart.saves);
    assertCleared();
  }

  @Test public void realPlanProgressOwnerClearsOnRebuildAndHiddenPlanHook()
  {
    // The real plan constructs/owns its real bottom menu against the same production resources.
    root.setId(R.id.menu_frame);
    activity.setContentView(root);
    MwmApplication application = mock(MwmApplication.class);
    when(application.getResources()).thenReturn(activity.getResources());
    when(routing.getBuildState()).thenReturn(RoutingController.BuildState.BUILT);
    try (MockedStatic<MwmApplication> applications = mockStatic(MwmApplication.class))
    {
      applications.when(() -> MwmApplication.from(any(android.content.Context.class))).thenReturn(application);
      RoutingPlanController plan = new RoutingPlanController(root, activity,
          mock(ActivityResultLauncher.class), show -> {}, mock(RoutingBottomMenuListener.class));
      plan.updateBuildProgress(100, Router.Pedestrian);
      assertNotNull(chart.getData());
      chart.highlightValue(new Highlight(100f, -12f, 0), true);
      when(routing.getBuildState()).thenReturn(RoutingController.BuildState.BUILDING);
      when(routing.isBuilt()).thenReturn(false);
      when(routing.isBuilding()).thenReturn(true);
      plan.updateBuildProgress(0, Router.Pedestrian);
      assertCleared();
      when(routing.getBuildState()).thenReturn(RoutingController.BuildState.BUILT);
      when(routing.isBuilt()).thenReturn(true);
      when(routing.isBuilding()).thenReturn(false);
      plan.updateBuildProgress(100, Router.Bicycle);
      assertNotNull(chart.getData());
      plan.clearRouteAltitudeChart(); // Actual hook called before InplaceController hide animation.
      assertCleared();
    }
  }

  @Test public void detachingRealPanelDropsProfileAndSelection()
  {
    activity.setContentView(root);
    menu.showAltitudeChartAndRoutingDetails();
    chart.highlightValue(new Highlight(100f, -12f, 0), true);
    activity.setContentView(new android.widget.FrameLayout(activity));
    assertCleared();
  }

  @Test public void savedTrackViewAndControllerRemainSeparateConsumers()
  {
    android.widget.FrameLayout track = new android.widget.FrameLayout(activity);
    LayoutInflater.from(activity).inflate(R.layout.elevation_profile_internal, track, true);
    new ChartController(activity).initialize(track);
    LineChart trackChart = track.findViewById(R.id.elevation_profile_chart);
    assertNotSame(chart, trackChart);
    assertEquals("app.organicmaps.widget.placepage.ElevationProfileChart", trackChart.getClass().getName());
    menu.showAltitudeChartAndRoutingDetails();
    assertNull(trackChart.getData());
    menu.hideAltitudeChartAndRoutingDetails();
    assertNull(trackChart.getData());
  }
}
