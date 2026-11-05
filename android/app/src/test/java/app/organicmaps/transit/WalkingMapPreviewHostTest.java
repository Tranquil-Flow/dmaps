package app.organicmaps.transit;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.view.View;
import android.os.Looper;
import app.organicmaps.MwmActivity;
import app.organicmaps.R;
import app.organicmaps.sdk.Map;
import app.organicmaps.sdk.MapController;
import app.organicmaps.sdk.ChoosePositionMode;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.transit.WalkingMapOverlay;
import app.organicmaps.search.FloatingSearchToolbarController;
import com.google.android.material.appbar.MaterialToolbar;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual MwmActivity Intent/panel/close bodies on an ATTACHED BUT UNSTARTED Activity.
 * Renderer, routing-state and SDK presentation are controlled; NOT normal startup/JNI/GPU. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,manifest=Config.NONE)
public class WalkingMapPreviewHostTest {
  private static void field(Object owner,String name,Object value)throws Exception{Field f=MwmActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
  private static void call(Object owner,String name)throws Exception{Method m=MwmActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(owner);}
  @Test public void refusedPreviewKeepsASeparateReturnPathWithoutCancellingExistingNavigation()throws Exception {
    MwmActivity map=Robolectric.buildActivity(MwmActivity.class).get();map.setTheme(R.style.MwmTheme);
    MapController renderer=mock(MapController.class);when(renderer.isRenderingActive()).thenReturn(true);
    FloatingSearchToolbarController search=mock(FloatingSearchToolbarController.class);MaterialToolbar toolbar=new MaterialToolbar(map);toolbar.setVisibility(View.GONE);when(search.getToolbar()).thenReturn(toolbar);
    field(map,"mMapController",renderer);field(map,"mSearchController",search);field(map,"mWalkingMapResumed",true);
    AtomicInteger released=new AtomicInteger();WalkingMapPreviewSession session=new WalkingMapPreviewSession(new double[]{52.5,13.4,52.501,13.401},answer->answer.accept(true),released::incrementAndGet);
    map.setIntent(new Intent().putExtra(WalkingMapPreviewSession.EXTRA_TOKEN,session.token()));
    RoutingController route=mock(RoutingController.class);when(route.isNavigating()).thenReturn(true);
    try(var nativeMap=mockStatic(Map.class);var routes=mockStatic(RoutingController.class);var choose=mockStatic(ChoosePositionMode.class);var overlay=mockStatic(WalkingMapOverlay.class)){
      nativeMap.when(Map::isEngineCreated).thenReturn(true);routes.when(RoutingController::get).thenReturn(route);choose.when(ChoosePositionMode::get).thenReturn(ChoosePositionMode.None);
      try{
        call(map,"processIntent");assertFalse(session.isOpen());assertEquals(1,released.get());
        assertNotNull("refusal must retain its own Back path instead of navigation cancellation",map.getWindow().getDecorView().findViewWithTag("dmaps-walking-map-panel"));
        map.onBackPressed();Intent back=Shadows.shadowOf(map).getNextStartedActivity();assertNotNull(back);assertEquals(JourneyActivity.class.getName(),back.getComponent().getClassName());
        assertTrue("refused preview must explicitly require a new plan",back.hasExtra("app.organicmaps.transit.WALK_RETURN_TOKEN"));
        assertEquals("",back.getStringExtra("app.organicmaps.transit.WALK_RETURN_TOKEN"));
        call(map,"returnFromWalkingPreview");assertNull("no duplicate explicit return after panel removal",Shadows.shadowOf(map).getNextStartedActivity());
        verify(route,never()).cancel();verify(route,never()).resetToPlanningStateIfNavigating();overlay.verifyNoInteractions();
        assertNull(map.getWindow().getDecorView().findViewWithTag("dmaps-walking-map-panel"));
      }finally{call(map,"closeWalkingPreview");session.close();}
    }
  }
  @Test public void oneShotIntentIsClaimedBeforePendingCleanupAndPhonePanelDoesNotRequireTabletAnimator()throws Exception {
    MwmActivity map=Robolectric.buildActivity(MwmActivity.class).get();map.setTheme(R.style.MwmTheme);
    MapController renderer=mock(MapController.class);when(renderer.isRenderingActive()).thenReturn(true);
    FloatingSearchToolbarController search=mock(FloatingSearchToolbarController.class);MaterialToolbar toolbar=new MaterialToolbar(map);toolbar.setVisibility(View.GONE);when(search.getToolbar()).thenReturn(toolbar);
    field(map,"mMapController",renderer);field(map,"mSearchController",search);field(map,"mWalkingMapResumed",true);
    AtomicInteger released=new AtomicInteger();WalkingMapPreviewSession session=new WalkingMapPreviewSession(new double[]{52.5,13.4,52.501,13.401},answer->answer.accept(true),released::incrementAndGet);
    map.setIntent(new Intent().putExtra(WalkingMapPreviewSession.EXTRA_TOKEN,session.token()));
    WalkingMapOverlay line=mock(WalkingMapOverlay.class);when(line.isCurrent()).thenReturn(true);
    try(var nativeMap=mockStatic(Map.class);var routes=mockStatic(RoutingController.class);var choose=mockStatic(ChoosePositionMode.class);var overlay=mockStatic(WalkingMapOverlay.class)){
      nativeMap.when(Map::isEngineCreated).thenReturn(true);routes.when(RoutingController::get).thenReturn(mock(RoutingController.class));choose.when(ChoosePositionMode::get).thenReturn(ChoosePositionMode.None);
      overlay.when(()->WalkingMapOverlay.show(any(double[].class))).thenReturn(line);
      try{
        call(map,"processIntent");Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull("phone map needs its actual panel and must not cancel the incoming nonce",map.getWindow().getDecorView().findViewWithTag("dmaps-walking-map-panel"));
        assertTrue(session.isOpen());assertNull(WalkingMapPreviewSession.claim(session.token()));
        overlay.verify(()->WalkingMapOverlay.show(any(double[].class)),times(1));
        call(map,"processIntent");overlay.verify(()->WalkingMapOverlay.show(any(double[].class)),times(1));
        call(map,"closeWalkingPreview");assertFalse(session.isOpen());assertEquals(1,released.get());verify(line,times(1)).close();
        assertNull(map.getWindow().getDecorView().findViewWithTag("dmaps-walking-map-panel"));
      }finally{call(map,"closeWalkingPreview");session.close();}
    }
  }
}
