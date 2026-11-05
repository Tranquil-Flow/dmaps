package app.organicmaps.routing;

import android.app.Application;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.routing.RouteAltitudeData;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.util.Distance;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.highlight.Highlight;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual phone routing_plan + menu includes, separate ownership and measured menu hierarchy.
 * MapView/place-page shell deliberately excluded: not an MwmActivity/device/recreation test.
 * Native producers/route state controlled exactly as in RouteAltitudePanelTest.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, manifest = Config.NONE,
        qualifiers = "w400dp-h800dp-port",
        shadows = {RouteAltitudePanelTest.NativeChart.class, RouteAltitudePanelTest.NativeDistance.class})
public class RouteAltitudePhoneTest
{
  private AppCompatActivity activity;
  private RelativeLayout root;
  private View top, bottom;
  private RoutingBottomMenuController menu;
  private RoutingController routing;
  private MockedStatic<RoutingController> routes;
  private LineChart chart;

  @Before public void setup()
  {
    RouteAltitudePanelTest.NativeChart.profile = profile(-12, 37);
    RouteAltitudePanelTest.NativeChart.bitmap = true;
    RouteAltitudePanelTest.NativeChart.metric = true;
    RouteAltitudePanelTest.NativeChart.calls = RouteAltitudePanelTest.NativeChart.saves = 0;
    var owner = Robolectric.buildActivity(AppCompatActivity.class);
    activity = owner.get();
    activity.setTheme(R.style.MwmTheme);
    owner.setup();
    routing = mock(RoutingController.class);
    when(routing.isBuilt()).thenReturn(true);
    routes = mockStatic(RoutingController.class);
    routes.when(RoutingController::get).thenReturn(routing);
    // Same separate includes and bottom alignment as activity_map, without the native map shell.
    root = new RelativeLayout(activity);
    top = LayoutInflater.from(activity).inflate(R.layout.routing_plan, root, false);
    bottom = LayoutInflater.from(activity).inflate(R.layout.menu, root, false);
    ((RelativeLayout.LayoutParams) bottom.getLayoutParams()).addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
    root.addView(top);
    root.addView(bottom);
    bottom.setVisibility(View.VISIBLE);
    activity.setContentView(root);
    assertNull(top.findViewById(R.id.altitude_chart_panel));
    assertNotNull(bottom.findViewById(R.id.altitude_chart_panel));
    menu = RoutingBottomMenuController.newInstance(activity, top, mock(RoutingBottomMenuListener.class));
    chart = bottom.findViewById(R.id.route_altitude_line_chart);
  }

  @After public void teardown()
  {
    if (activity != null) activity.finish();
    if (routes != null) routes.close();
  }

  private static RouteAltitudeData profile(double min, double max)
  { return new RouteAltitudeData(new double[] {0, 100}, new double[] {min, max}); }

  private void measurePhone()
  {
    boolean land = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    float density = activity.getResources().getDisplayMetrics().density;
    int width = Math.round((land ? 800 : 400) * density);
    int height = Math.round((land ? 400 : 800) * density);
    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                 View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    root.layout(0, 0, width, height);
    assertTrue(bottom.getMeasuredWidth() > 0);
    assertTrue(bottom.getMeasuredHeight() > 0);
  }

  private View slot() { return bottom.findViewById(R.id.route_altitude_slot); }

  // Resolve new resource IDs at runtime so this test compiles against the OLD production proposal.
  private TextView label(String name)
  {
    int id = activity.getResources().getIdentifier(name, "id",
        activity.getResources().getResourcePackageName(R.id.route_altitude_chart));
    assertTrue("Missing actual extrema label: " + name, id != 0);
    TextView view = bottom.findViewById(id);
    assertNotNull(view);
    return view;
  }

  private void assertReset()
  {
    assertEquals(View.GONE, slot().getVisibility());
    assertNull(chart.getData());
    assertFalse(chart.valuesToHighlight());
    assertEquals(1f, chart.getViewPortHandler().getScaleX(), 0.001f);
    assertEquals("", ((TextView) bottom.findViewById(R.id.route_altitude_selection)).getText().toString());
  }

  @Test public void separatePhoneOwnerPublishesOnlySuccessfulChartOrBitmap()
  {
    assertEquals(View.GONE, slot().getVisibility());
    menu.showAltitudeChartAndRoutingDetails();
    measurePhone();
    assertEquals(View.VISIBLE, slot().getVisibility());
    assertTrue(slot().getMeasuredWidth() > 0);
    assertTrue(chart.getMeasuredHeight() > 0);
    assertEquals(View.VISIBLE, bottom.findViewById(R.id.route_altitude_chart).getVisibility());
    assertEquals(View.GONE, bottom.findViewById(R.id.altitude_chart).getVisibility());
    RouteAltitudePanelTest.NativeChart.profile = null;
    menu.showAltitudeChartAndRoutingDetails();
    measurePhone();
    assertEquals(View.VISIBLE, slot().getVisibility());
    assertEquals(View.GONE, bottom.findViewById(R.id.route_altitude_chart).getVisibility());
    assertEquals(View.VISIBLE, bottom.findViewById(R.id.altitude_chart).getVisibility());
    RouteAltitudePanelTest.NativeChart.bitmap = false;
    menu.showAltitudeChartAndRoutingDetails();
    measurePhone();
    assertReset();
  }

  @Test public void vehicleGetsSameMeasuredWidthAsPhysicallyAbsentChartSlot()
  {
    menu.showAltitudeChartAndRoutingDetails(); // Transition from populated chart, not just initial state.
    RoutingInfo info = mock(RoutingInfo.class);
    ReflectionHelpers.setField(info, "distToTarget", new Distance(1555000, "1555", (byte) 0));
    when(routing.getCachedRoutingInfo()).thenReturn(info);
    when(routing.isVehicleRouterType()).thenReturn(true);
    int calls = RouteAltitudePanelTest.NativeChart.calls;
    menu.showAltitudeChartAndRoutingDetails();
    menu.setStartButton(true);
    measurePhone();
    TextView vehicle = bottom.findViewById(R.id.time_vehicle);
    assertEquals(View.VISIBLE, vehicle.getVisibility());
    assertFalse(vehicle.getText().toString().isEmpty());
    int actualWidth = vehicle.getMeasuredWidth();
    assertTrue(actualWidth > 0);
    View slot = slot();
    ViewGroup parent = (ViewGroup) slot.getParent();
    int index = parent.indexOfChild(slot);
    ViewGroup.LayoutParams params = slot.getLayoutParams();
    parent.removeView(slot);
    measurePhone();
    int withoutSlotWidth = vehicle.getMeasuredWidth();
    parent.addView(slot, index, params);
    // Landscape old proposal loses width to an empty weighted sibling.
    assertEquals("Empty chart slot must not steal vehicle width", withoutSlotWidth, actualWidth);
    assertEquals(View.GONE, slot.getVisibility());
    assertEquals(calls, RouteAltitudePanelTest.NativeChart.calls);
  }

  @Test public void failedBitmapReleasesMeasuredPhoneSlotAfterAnInteractiveProfile()
  {
    menu.showAltitudeChartAndRoutingDetails();
    measurePhone();
    RouteAltitudePanelTest.NativeChart.bitmap = false;
    menu.showAltitudeChartAndRoutingDetails();
    measurePhone();
    View button = bottom.findViewById(R.id.btn__manage_route);
    int actualLeft = button.getLeft();
    View slot = slot();
    ViewGroup parent = (ViewGroup) slot.getParent();
    int index = parent.indexOfChild(slot);
    ViewGroup.LayoutParams params = slot.getLayoutParams();
    parent.removeView(slot);
    measurePhone();
    int withoutSlotLeft = button.getLeft();
    parent.addView(slot, index, params);
    assertEquals("No-data slot must not reserve a landscape column", withoutSlotLeft, actualLeft);
    assertReset();
  }

  @Test public void exactExtremaUseNativeFormatterAndClearOnFallbackAndHide()
  {
    for (boolean metric : new boolean[] {true, false})
    {
      RouteAltitudePanelTest.NativeChart.metric = metric;
      RouteAltitudePanelTest.NativeChart.profile = new RouteAltitudeData(
          new double[] {0, 50, 100}, new double[] {37.75, -12.25, 20});
      menu.showAltitudeChartAndRoutingDetails();
      TextView min = label("route_altitude_min"), max = label("route_altitude_max");
      assertEquals(activity.getString(R.string.elevation_profile_min_elevation) + ": height:-12.25", min.getText().toString());
      assertEquals(activity.getString(R.string.elevation_profile_max_elevation) + ": height:37.75", max.getText().toString());
      assertEquals(View.VISIBLE, min.getVisibility());
      assertEquals(View.VISIBLE, max.getVisibility());
      RouteAltitudePanelTest.NativeChart.profile = null;
      menu.showAltitudeChartAndRoutingDetails();
      assertEquals("", min.getText().toString());
      assertEquals("", max.getText().toString());
      assertEquals(View.GONE, min.getVisibility());
      RouteAltitudePanelTest.NativeChart.profile = profile(5, 5);
      menu.showAltitudeChartAndRoutingDetails();
      assertTrue(min.getText().toString().endsWith("height:5.0"));
      assertTrue(max.getText().toString().endsWith("height:5.0"));
      menu.hideAltitudeChartAndRoutingDetails();
      assertEquals("", min.getText().toString());
      assertEquals("", max.getText().toString());
    }
  }

  @Test public void measuredChartZoomSelectionReplacementAndMenuHideResetViewport()
  {
    menu.showAltitudeChartAndRoutingDetails();
    measurePhone();
    chart.zoom(2f, 1f, 0f, 0f);
    chart.highlightValue(new Highlight(100f, 37f, 0), true);
    assertTrue(chart.getViewPortHandler().getScaleX() > 1f);
    assertTrue(chart.valuesToHighlight());
    RouteAltitudePanelTest.NativeChart.profile = profile(200, 300);
    menu.showAltitudeChartAndRoutingDetails();
    measurePhone();
    assertEquals(1f, chart.getViewPortHandler().getScaleX(), 0.001f);
    assertFalse(chart.valuesToHighlight());
    assertEquals(200f, chart.getData().getYMin(), 0f);
    chart.zoom(2f, 1f, 0f, 0f);
    chart.highlightValue(new Highlight(100f, 300f, 0), true);
    menu.hideAltitudeChartAndRoutingDetails();
    assertReset();
  }

  @Test public void actualPlanProgressOwnerUsesActivityFallbackAndClearsPhoneMenu()
  {
    MwmApplication application = mock(MwmApplication.class);
    when(application.getResources()).thenReturn(activity.getResources());
    try (MockedStatic<MwmApplication> applications = mockStatic(MwmApplication.class))
    {
      applications.when(() -> MwmApplication.from(any(android.content.Context.class))).thenReturn(application);
      RoutingPlanController plan = new RoutingPlanController(top, activity,
          mock(ActivityResultLauncher.class), show -> {}, mock(RoutingBottomMenuListener.class));
      when(routing.getBuildState()).thenReturn(RoutingController.BuildState.BUILT);
      plan.updateBuildProgress(100, Router.Pedestrian);
      measurePhone();
      assertNotNull(chart.getData());
      chart.highlightValue(new Highlight(100f, 37f, 0), true);
      when(routing.getBuildState()).thenReturn(RoutingController.BuildState.BUILDING);
      when(routing.isBuilt()).thenReturn(false);
      when(routing.isBuilding()).thenReturn(true);
      plan.updateBuildProgress(0, Router.Pedestrian);
      assertReset();
    }
  }

  @Test public void phoneBottomMenuDetachClearsSelectedProfileAndExtrema()
  {
    menu.showAltitudeChartAndRoutingDetails();
    measurePhone();
    chart.highlightValue(new Highlight(100f, 37f, 0), true);
    root.removeView(bottom);
    assertReset();
    assertEquals("", label("route_altitude_min").getText().toString());
    assertEquals("", label("route_altitude_max").getText().toString());
  }
}
