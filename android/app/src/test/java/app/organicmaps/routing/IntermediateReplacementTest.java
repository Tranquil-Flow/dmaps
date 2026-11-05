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

/** Real controller bodies; JNI calls recorded using a Robolectric native shadow, not Mockito native mocking.
 * No native ordering/atomicity/ART claim. Reflection keeps tests-only baseline compilable. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, manifest = Config.NONE,
        shadows = IntermediateReplacementTest.NativeRoute.class)
public class IntermediateReplacementTest
{
  @Implements(value = Framework.class, isInAndroidSdk = false)
  public static class NativeRoute
  {
    static Supplier<RouteMarkData[]> points;
    static final List<String> calls = new ArrayList<>();
    static RouteMarkData added;
    static boolean reorder;
    static boolean failAdd;
    @Implementation protected static RouteMarkData[] nativeGetRoutePoints() { return points.get(); }
    @Implementation protected static void nativeRemoveRoutePoint(RouteMarkType type, int index)
    { calls.add("remove:" + type + ":" + index); }
    @Implementation protected static void nativeAddRoutePoint(String title, String subtitle, RouteMarkType type,
        int index, boolean mine, double lat, double lon, boolean optimize)
    {
      calls.add("add:" + type + ":" + index);
      if (failAdd) throw new IllegalStateException("injected add failure");
      added = new RouteMarkData(title, subtitle, type, index, true, mine, false, lat, lon);
      reorder = optimize;
    }
    @Implementation protected static int nativeOpenRoutePointsTransaction() { calls.add("open:17"); return 17; }
    @Implementation protected static void nativeApplyRoutePointsTransaction(int id) { calls.add("apply:" + id); }
    @Implementation protected static void nativeCancelRoutePointsTransaction(int id) { calls.add("cancel:" + id); }
    @Implementation protected static void nativeRemoveRoute() { calls.add("remove-route"); }
    @Implementation protected static void nativeBuildRoute() { calls.add("build"); }
    @Implementation protected static void nativeRemoveRoutePoints() { throw new AssertionError("Whole-route deletion forbidden"); }
    @Implementation protected static void nativeLoadRoutePoints() { calls.add("load"); }
    @Implementation protected static boolean nativeCouldAddIntermediatePoint() { return false; }
  }

  private RoutingController controller;
  private RouteMarkData[] points;
  private static RouteMarkData mark(String name, RouteMarkType type, int index, boolean mine)
  { return new RouteMarkData(name, "", type, index, true, mine, false, 50 + index, 13 + index); }
  private void field(String name, Object value) throws Exception
  {
    Field f = RoutingController.class.getDeclaredField(name); f.setAccessible(true); f.set(controller, value);
  }
  @SuppressWarnings({"rawtypes", "unchecked"})
  private void state(String name) throws Exception
  {
    Field f = RoutingController.class.getDeclaredField("mState"); f.setAccessible(true);
    f.set(controller, Enum.valueOf((Class) f.getType(), name));
  }
  private boolean begin(int index) throws Exception
  {
    Method method;
    try { method = RoutingController.class.getMethod("waitForIntermediatePointReplacement", RouteMarkData[].class, int.class); }
    catch (NoSuchMethodException missing)
    { throw new AssertionError("Missing explicit exact-index intermediate replacement picker", missing); }
    return (boolean) method.invoke(controller, points, index);
  }
  private MapObject pick(boolean mine)
  { return MapObject.createMapObject(FeatureId.EMPTY, mine ? MapObject.MY_POSITION : MapObject.SEARCH,
                                     "replacement", "place", 52.5, 13.4); }
  @Before public void setup() throws Exception
  {
    controller = new RoutingController();
    points = new RouteMarkData[] {mark("start", RouteMarkType.Start, 0, false),
        mark("first", RouteMarkType.Intermediate, 0, false), mark("middle", RouteMarkType.Intermediate, 1, false),
        mark("last", RouteMarkType.Intermediate, 2, false), mark("finish", RouteMarkType.Finish, 0, false)};
    NativeRoute.points = () -> points;
    NativeRoute.calls.clear(); NativeRoute.added = null; NativeRoute.reorder = false; NativeRoute.failAdd = false;
    state("PREPARE"); field("mLastRouterType", Router.Pedestrian);
  }
  private void noCommit()
  {
    assertNull(NativeRoute.added);
    for (String call : NativeRoute.calls)
      assertFalse("Unexpected mutation: " + call, call.startsWith("remove:") || call.startsWith("add:")
          || call.equals("build") || call.startsWith("open:"));
  }
  private void committed(int index)
  {
    assertEquals(Arrays.asList("open:17", "remove:Intermediate:" + index, "add:Intermediate:" + index,
                              "apply:17", "remove-route", "build"), NativeRoute.calls);
    assertEquals(index, NativeRoute.added.mIntermediateIndex);
    assertFalse(NativeRoute.reorder);
  }
  @Test public void middleReplacementUsesExactIndexWithoutOptimizationAndBuildsOnce() throws Exception
  {
    assertTrue(begin(1)); controller.onPoiSelected(pick(false)); committed(1);
    assertEquals("replacement", NativeRoute.added.mTitle); assertEquals("place", NativeRoute.added.mSubtitle);
    assertEquals(52.5, NativeRoute.added.mLat, 0); assertEquals(13.4, NativeRoute.added.mLon, 0);
    assertFalse(controller.isWaitingPoiPick()); controller.onPoiSelected(pick(false)); committed(1);
  }
  @Test public void cancelAndLateResultDoNotMutate() throws Exception
  {
    assertTrue(begin(1)); controller.onPoiSelected(null); controller.onPoiSelected(pick(false));
    assertFalse(controller.isWaitingPoiPick()); noCommit();
  }
  @Test public void staleCoordinateWithIdenticalLabelsIsRejected() throws Exception
  {
    assertTrue(begin(1));
    points[2] = new RouteMarkData("middle", "", RouteMarkType.Intermediate, 1, true, false, false, 1, 2);
    controller.onPoiSelected(pick(false)); noCommit();
  }
  @Test public void snapshotIsNotAliasedToMutableMarkIndices() throws Exception
  {
    assertTrue(begin(1)); points[2].mIntermediateIndex = 0; controller.onPoiSelected(pick(false)); noCommit();
  }
  @Test public void newlyPassedStopRejectsPick() throws Exception
  {
    assertTrue(begin(1)); RouteMarkData old = points[1];
    points[1] = new RouteMarkData(old.mTitle, old.mSubtitle, old.mPointType, old.mIntermediateIndex,
                                 old.mIsVisible, old.mIsMyPosition, true, old.mLat, old.mLon);
    controller.onPoiSelected(pick(false)); noCommit();
  }
  @Test public void duplicateMyPositionCannotRemoveAnUnrelatedEndpoint() throws Exception
  {
    points[0] = mark("start", RouteMarkType.Start, 0, true);
    assertTrue(begin(1)); controller.onPoiSelected(pick(true)); noCommit();
  }
  @Test public void replacementAllowedAtTheExistingHundredIntermediateLimit() throws Exception
  {
    points = new RouteMarkData[102]; points[0] = mark("start", RouteMarkType.Start, 0, false);
    for (int i = 0; i < 100; ++i) points[i + 1] = mark("stop" + i, RouteMarkType.Intermediate, i, false);
    points[101] = mark("finish", RouteMarkType.Finish, 0, false);
    assertTrue(begin(98)); controller.onPoiSelected(pick(false)); committed(98);
  }
  @Test public void configurationSaveDetachAttachRestoreRetainsExactPendingIndex() throws Exception
  {
    assertTrue(begin(2)); controller.onSaveState(); controller.detach();
    controller.attach(new RoutingController.Container() {}); controller.restore();
    controller.onPoiSelected(pick(false)); committed(2);
  }
  @Test public void diskRouteRestoreDoesNotResurrectPendingPick() throws Exception
  {
    assertTrue(begin(1)); controller.restoreRoute(); controller.onPoiSelected(pick(false)); noCommit();
    assertEquals(Arrays.asList("load"), NativeRoute.calls);
  }
  @Test public void newProcessControllerHasNoPendingAuthority() throws Exception
  {
    assertTrue(begin(1)); controller = new RoutingController(); controller.onPoiSelected(pick(false)); noCommit();
  }
  @Test public void navigationOrRouterChangeWhilePickingRejectsResult() throws Exception
  {
    assertTrue(begin(1)); state("NAVIGATION"); controller.onPoiSelected(pick(false)); noCommit();
    state("PREPARE"); assertTrue(begin(1)); field("mLastRouterType", Router.Transit);
    controller.onPoiSelected(pick(false)); noCommit();
  }
  @Test public void invalidIndexPartialEndpointsAndTransitAreRefused() throws Exception
  {
    assertFalse(begin(-1)); assertFalse(begin(3)); field("mLastRouterType", Router.Transit);
    assertFalse(begin(1)); field("mLastRouterType", Router.Ruler); assertFalse(begin(1));
    field("mLastRouterType", Router.Vehicle); points = new RouteMarkData[] {points[1], points[2], points[3], points[4]};
    assertFalse(begin(1)); noCommit();
  }
  @Test public void unsavedSheetOrderCannotSilentlyReplaceAnotherNativeStop() throws Exception
  {
    RouteMarkData[] nativePoints = points.clone(); NativeRoute.points = () -> nativePoints;
    points = new RouteMarkData[] {points[0], points[3], points[2], points[1], points[4]};
    assertFalse(begin(1)); noCommit();
  }
  @Test public void rebuildInvalidatesEvenByteIdenticalRouteSnapshot() throws Exception
  {
    assertTrue(begin(1)); controller.checkAndBuildRoute(); NativeRoute.calls.clear();
    controller.onPoiSelected(pick(false)); noCommit();
  }
  @Test public void failedNativeAddRollsBackAndNeverBuilds() throws Exception
  {
    assertTrue(begin(1)); NativeRoute.failAdd = true;
    try { controller.onPoiSelected(pick(false)); fail("Injected native failure must propagate"); }
    catch (IllegalStateException expected) { assertEquals("injected add failure", expected.getMessage()); }
    assertEquals(Arrays.asList("open:17", "remove:Intermediate:1", "add:Intermediate:1", "cancel:17"), NativeRoute.calls);
    assertFalse(controller.isWaitingPoiPick());
  }
  @Test public void unchangedSelectionDoesNotRebuild() throws Exception
  {
    assertTrue(begin(1)); RouteMarkData p = points[2];
    controller.onPoiSelected(MapObject.createMapObject(FeatureId.EMPTY, MapObject.SEARCH,
                                                      p.mTitle, p.mSubtitle, p.mLat, p.mLon)); noCommit();
  }
  @Test public void anotherPendingPickerCannotBeOverwritten() throws Exception
  {
    controller.waitForPoiPick(RouteMarkType.Start); assertFalse(begin(1)); noCommit();
  }
}
