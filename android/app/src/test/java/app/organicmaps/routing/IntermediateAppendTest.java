package app.organicmaps.routing;

import android.app.Application;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import app.organicmaps.sdk.routing.RoutingController;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import static org.junit.Assert.*;

/** Real controller with recorded JNI. Not native ordering, observer/crash atomicity or device proof. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, manifest = Config.NONE,
        shadows = IntermediateAppendTest.NativeRoute.class)
public class IntermediateAppendTest
{
  @Implements(value = Framework.class, isInAndroidSdk = false)
  public static class NativeRoute
  {
    static Supplier<RouteMarkData[]> points;
    static final List<String> calls = new ArrayList<>();
    static RouteMarkData added;
    static boolean capacity, reorder, failAdd, failApply;
    @Implementation protected static RouteMarkData[] nativeGetRoutePoints() { return points.get(); }
    @Implementation protected static boolean nativeCouldAddIntermediatePoint()
    { calls.add("capacity"); return capacity; }
    @Implementation protected static void nativeRemoveRoutePoint(RouteMarkType type, int index)
    { throw new AssertionError("Append must not remove any mark"); }
    @Implementation protected static void nativeAddRoutePoint(String title, String subtitle, RouteMarkType type,
        int index, boolean mine, double lat, double lon, boolean optimize)
    {
      calls.add("add:" + type + ":" + index);
      if (failAdd) throw new IllegalStateException("injected add failure");
      added = new RouteMarkData(title, subtitle, type, index, !mine, mine, false, lat, lon);
      reorder = optimize;
    }
    @Implementation protected static int nativeOpenRoutePointsTransaction() { calls.add("open:17"); return 17; }
    @Implementation protected static void nativeApplyRoutePointsTransaction(int id)
    { calls.add("apply:" + id); if (failApply) throw new IllegalStateException("injected apply failure"); }
    @Implementation protected static void nativeCancelRoutePointsTransaction(int id) { calls.add("cancel:" + id); }
    @Implementation protected static void nativeRemoveRoute() { calls.add("remove-route"); }
    @Implementation protected static void nativeBuildRoute() { calls.add("build"); }
    @Implementation protected static void nativeRemoveRoutePoints() { throw new AssertionError("Whole-route deletion forbidden"); }
    @Implementation protected static void nativeLoadRoutePoints() { calls.add("load"); }
  }

  private RoutingController controller;
  private RouteMarkData[] points;
  private static RouteMarkData mark(String name, RouteMarkType type, int index, boolean mine)
  { return new RouteMarkData(name, "", type, index, true, mine, false, 50 + index, 13 + index); }
  private void field(String name, Object value) throws Exception
  { Field f = RoutingController.class.getDeclaredField(name); f.setAccessible(true); f.set(controller, value); }
  @SuppressWarnings({"rawtypes", "unchecked"})
  private void state(String name) throws Exception
  {
    Field f = RoutingController.class.getDeclaredField("mState"); f.setAccessible(true);
    f.set(controller, Enum.valueOf((Class) f.getType(), name));
  }
  private boolean begin() throws Exception
  {
    Method method;
    try { method = RoutingController.class.getMethod("waitForIntermediatePointAppend", RouteMarkData[].class); }
    catch (NoSuchMethodException missing) { throw new AssertionError("Missing explicit append picker", missing); }
    return (boolean) method.invoke(controller, (Object) points);
  }
  private MapObject pick(boolean mine)
  { return MapObject.createMapObject(FeatureId.EMPTY, mine ? MapObject.MY_POSITION : MapObject.SEARCH,
                                     "appended", "place", 52.5, 13.4); }
  @Before public void setup() throws Exception
  {
    controller = new RoutingController();
    points = new RouteMarkData[] {mark("start", RouteMarkType.Start, 0, false),
        mark("first", RouteMarkType.Intermediate, 0, false), mark("middle", RouteMarkType.Intermediate, 1, false),
        mark("last", RouteMarkType.Intermediate, 2, false), mark("finish", RouteMarkType.Finish, 0, false)};
    NativeRoute.points = () -> points;
    NativeRoute.calls.clear(); NativeRoute.added = null; NativeRoute.capacity = true;
    NativeRoute.reorder = false; NativeRoute.failAdd = false; NativeRoute.failApply = false;
    state("PREPARE"); field("mLastRouterType", Router.Pedestrian);
  }
  private void noCommit()
  {
    assertNull(NativeRoute.added);
    for (String call : NativeRoute.calls)
      assertFalse("Unexpected mutation: " + call, call.startsWith("add:") || call.equals("build")
          || call.startsWith("apply:") || call.equals("remove-route"));
  }
  private void committed(int index)
  {
    assertEquals(Arrays.asList("capacity", "open:17", "capacity", "add:Intermediate:" + index,
                              "apply:17", "remove-route", "build"), NativeRoute.calls);
    assertEquals(index, NativeRoute.added.mIntermediateIndex); assertFalse(NativeRoute.reorder);
  }
  @Test public void appendUsesCountAsIndexNeverRemovesOrOptimizesAndBuildsOnce() throws Exception
  {
    RouteMarkData[] original = points.clone();
    assertTrue(begin()); controller.onPoiSelected(pick(false)); committed(3);
    for (int i = 0; i < points.length; ++i) assertSame(original[i], points[i]);
    assertEquals("appended", NativeRoute.added.mTitle); assertEquals("place", NativeRoute.added.mSubtitle);
    assertEquals(52.5, NativeRoute.added.mLat, 0); assertEquals(13.4, NativeRoute.added.mLon, 0);
    assertFalse(controller.isWaitingPoiPick()); controller.onPoiSelected(pick(false)); committed(3);
  }
  @Test public void completeRouteWithNoIntermediatesAppendsAtZero() throws Exception
  {
    points = new RouteMarkData[] {points[0], points[4]};
    assertTrue(begin()); controller.onPoiSelected(pick(false)); committed(0);
  }
  @Test public void nativeCapacityRefusalAtStartDoesNotArmAuthority() throws Exception
  {
    NativeRoute.capacity = false; assertFalse(begin()); assertFalse(controller.isWaitingPoiPick());
    controller.onPoiSelected(pick(false)); noCommit(); assertEquals(Arrays.asList("capacity"), NativeRoute.calls);
  }
  @Test public void inactiveNativeRoutePolicyRefusesEvenCompleteZeroIntermediateRoute() throws Exception
  {
    // The real predicate combines IsRoutingActive with available native mark capacity.
    // The JNI shadow models its false return; it does not establish native routing activity.
    points = new RouteMarkData[] {points[0], points[4]}; NativeRoute.capacity = false;
    assertFalse(begin()); assertFalse(controller.isWaitingPoiPick());
    controller.onPoiSelected(pick(false)); noCommit();
    assertEquals(Arrays.asList("capacity"), NativeRoute.calls);
  }
  @Test public void capacityLostAtCommitCancelsSnapshotTransactionWithoutBuilding() throws Exception
  {
    assertTrue(begin()); NativeRoute.capacity = false; controller.onPoiSelected(pick(false)); noCommit();
    assertEquals(Arrays.asList("capacity", "open:17", "capacity", "cancel:17"), NativeRoute.calls);
    NativeRoute.capacity = true; controller.onPoiSelected(pick(false)); noCommit();
    assertFalse(controller.isWaitingPoiPick());
  }
  @Test public void cancelAndLateResultAreNoOps() throws Exception
  { assertTrue(begin()); controller.onPoiSelected(null); controller.onPoiSelected(pick(false)); noCommit(); }
  @Test public void explicitIntermediateAbandonmentRetiresAppend() throws Exception
  {
    assertTrue(begin()); controller.cancelIntermediatePointReplacement();
    controller.onPoiSelected(pick(false)); assertFalse(controller.isWaitingPoiPick()); noCommit();
  }
  @Test public void staleCoordinatesWithIdenticalLabelsAreRejected() throws Exception
  {
    assertTrue(begin()); RouteMarkData p = points[2];
    points[2] = new RouteMarkData(p.mTitle, p.mSubtitle, p.mPointType, p.mIntermediateIndex,
                                 p.mIsVisible, p.mIsMyPosition, p.mIsPassed, 1, 2);
    controller.onPoiSelected(pick(false)); noCommit();
  }
  @Test public void fullFieldEqualityRejectsEachChangedField() throws Exception
  {
    for (int change = 0; change < 9; ++change)
    {
      setup(); assertTrue(begin()); RouteMarkData p = points[2];
      points[2] = new RouteMarkData(change == 0 ? "changed" : p.mTitle, change == 1 ? "changed" : p.mSubtitle,
          change == 2 ? RouteMarkType.Start : p.mPointType, change == 3 ? 9 : p.mIntermediateIndex,
          change == 4 ? !p.mIsVisible : p.mIsVisible, change == 5 ? !p.mIsMyPosition : p.mIsMyPosition,
          change == 6 ? !p.mIsPassed : p.mIsPassed, change == 7 ? 1 : p.mLat, change == 8 ? 2 : p.mLon);
      controller.onPoiSelected(pick(false)); noCommit(); assertFalse(controller.isWaitingPoiPick());
    }
  }
  @Test public void snapshotDoesNotAliasMutableIndices() throws Exception
  { assertTrue(begin()); points[2].mIntermediateIndex = 0; controller.onPoiSelected(pick(false)); noCommit(); }
  @Test public void unsavedSheetOrderIsRefusedBeforeCapacityOrPicker() throws Exception
  {
    RouteMarkData[] nativePoints = points.clone(); NativeRoute.points = () -> nativePoints;
    points = new RouteMarkData[] {points[0], points[3], points[2], points[1], points[4]};
    assertFalse(begin()); noCommit(); assertTrue(NativeRoute.calls.isEmpty());
  }
  @Test public void staleSheetFieldIsRefusedEvenWithIdenticalLabels() throws Exception
  {
    RouteMarkData[] nativePoints = points.clone(); NativeRoute.points = () -> nativePoints;
    points[0] = new RouteMarkData("start", "", RouteMarkType.Start, 0, false, false, false, 50, 13);
    assertFalse(begin()); noCommit();
  }
  @Test public void duplicateMyPositionAtAnyExistingSlotCannotRemoveIt() throws Exception
  {
    for (int slot = 0; slot < 5; ++slot)
    {
      setup(); RouteMarkData p = points[slot];
      points[slot] = new RouteMarkData(p.mTitle, p.mSubtitle, p.mPointType, p.mIntermediateIndex,
                                     p.mIsVisible, true, p.mIsPassed, p.mLat, p.mLon);
      assertTrue(begin()); controller.onPoiSelected(pick(true)); noCommit();
    }
  }
  @Test public void uniqueMyPositionCanBeAppended() throws Exception
  { assertTrue(begin()); controller.onPoiSelected(pick(true)); committed(3); assertTrue(NativeRoute.added.mIsMyPosition); }
  @Test public void invalidCoordinatesConsumeAuthorityWithoutMutation() throws Exception
  {
    for (double[] coords : new double[][] {{Double.NaN, 0}, {0, Double.POSITIVE_INFINITY}, {91, 0}, {0, -181}})
    {
      setup(); assertTrue(begin());
      controller.onPoiSelected(MapObject.createMapObject(FeatureId.EMPTY, MapObject.SEARCH, "bad", "", coords[0], coords[1]));
      controller.onPoiSelected(pick(false)); noCommit();
    }
  }
  @Test public void configurationRecreationKeepsPendingAppend() throws Exception
  {
    assertTrue(begin()); controller.onSaveState(); controller.detach();
    controller.attach(new RoutingController.Container() {}); controller.restore();
    controller.onPoiSelected(pick(false)); committed(3);
  }
  @Test public void diskRestoreDoesNotResurrectAuthority() throws Exception
  { assertTrue(begin()); controller.restoreRoute(); controller.onPoiSelected(pick(false)); noCommit(); }
  @Test public void freshProcessHasNoAuthority() throws Exception
  { assertTrue(begin()); controller = new RoutingController(); controller.onPoiSelected(pick(false)); noCommit(); }
  @Test public void navigationAndRouterChangesRejectLateResult() throws Exception
  {
    assertTrue(begin()); state("NAVIGATION"); controller.onPoiSelected(pick(false)); noCommit();
    state("PREPARE"); assertTrue(begin()); field("mLastRouterType", Router.Vehicle);
    controller.onPoiSelected(pick(false)); noCommit();
  }
  @Test public void rebuildInvalidatesByteIdenticalSnapshot() throws Exception
  {
    assertTrue(begin()); controller.checkAndBuildRoute(); NativeRoute.calls.clear();
    controller.onPoiSelected(pick(false)); noCommit();
  }
  @Test public void partialEndpointsTransitRulerAndMalformedIndicesAreRefused() throws Exception
  {
    field("mLastRouterType", Router.Transit); assertFalse(begin());
    field("mLastRouterType", Router.Ruler); assertFalse(begin()); field("mLastRouterType", Router.Vehicle);
    points[2].mIntermediateIndex = 7; assertFalse(begin()); points[2].mIntermediateIndex = 1;
    points = new RouteMarkData[] {points[1], points[2], points[3], points[4]}; assertFalse(begin()); noCommit();
  }
  @Test public void existingPickerCannotBeOverwritten() throws Exception
  {
    controller.waitForPoiPick(RouteMarkType.Start); assertFalse(begin());
    controller.cancelIntermediatePointReplacement(); assertTrue(controller.isWaitingPoiPick()); noCommit();
  }
  @Test public void repeatedBeginDoesNotChangePendingAppend() throws Exception
  { assertTrue(begin()); assertFalse(begin()); controller.onPoiSelected(pick(false)); committed(3); }
  @Test public void failedAddRollsBackAndNeverBuilds() throws Exception
  {
    assertTrue(begin()); NativeRoute.failAdd = true;
    try { controller.onPoiSelected(pick(false)); fail("Expected injected failure"); }
    catch (IllegalStateException expected) { assertEquals("injected add failure", expected.getMessage()); }
    assertEquals(Arrays.asList("capacity", "open:17", "capacity", "add:Intermediate:3", "cancel:17"), NativeRoute.calls);
    assertFalse(controller.isWaitingPoiPick());
  }
  @Test public void failedApplyCancelsAndNeverBuildsOrReplays() throws Exception
  {
    assertTrue(begin()); NativeRoute.failApply = true;
    try { controller.onPoiSelected(pick(false)); fail("Expected injected failure"); }
    catch (IllegalStateException expected) { assertEquals("injected apply failure", expected.getMessage()); }
    assertEquals(Arrays.asList("capacity", "open:17", "capacity", "add:Intermediate:3", "apply:17", "cancel:17"), NativeRoute.calls);
    int count = NativeRoute.calls.size(); controller.onPoiSelected(pick(false)); assertEquals(count, NativeRoute.calls.size());
  }
}
