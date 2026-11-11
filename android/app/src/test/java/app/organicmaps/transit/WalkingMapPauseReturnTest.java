package app.organicmaps.transit;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import app.organicmaps.MwmActivity;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.sdk.Map;
import app.organicmaps.sdk.MapController;
import app.organicmaps.sdk.ChoosePositionMode;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.transit.JourneyPlanner;
import app.organicmaps.sdk.transit.JourneyUiFixture;
import app.organicmaps.sdk.transit.TripStop;
import app.organicmaps.sdk.transit.WalkingMapOverlay;
import app.organicmaps.search.FloatingSearchToolbarController;
import com.google.android.material.appbar.MaterialToolbar;
import java.lang.reflect.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual MwmActivity onPause and Back on an attached/unstarted activity; controlled renderer,
 * routing and sensors. Real fixture-imported platform identities and native host SQLite, not ART/GPU. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE,shadows=WalkingMapPauseReturnTest.NativeContextShadow.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class WalkingMapPauseReturnTest {
  @org.robolectric.annotation.Implements(value=app.organicmaps.sdk.OrganicMaps.class,isInAndroidSdk=false)
  public static class NativeContextShadow {
    static int contextCalls;
    @org.robolectric.annotation.Implementation protected static void __staticInitializer__() {}
    @org.robolectric.annotation.Implementation protected static void nativeSetContext(android.content.Context context) { contextCalls++; }
  }
  @Rule public TemporaryFolder temp=new TemporaryFolder();
  private static void field(Object o,String n,Object v)throws Exception{Field f=MwmActivity.class.getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
  private static void call(Object o,String n)throws Exception{Method m=MwmActivity.class.getDeclaredMethod(n);m.setAccessible(true);m.invoke(o);}
  private static JourneyViewModel.Endpoint endpoint(JourneyUiFixture fixture,long id)throws Exception{
    JourneyPlanner.Selection selection=fixture.repository.journeySelection(id);
    for(app.organicmaps.sdk.transit.Stop stop:selection.platforms)if(stop.id==id)return new JourneyViewModel.Endpoint(selection,stop);
    throw new AssertionError("fixture platform missing");
  }
  @Test public void pauseRetiresLineButRetainsBackAndBothPlatforms()throws Exception{
    Clock clock=Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"),ZoneOffset.UTC);
    JourneyUiFixture fixture=new JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),clock);
    List<TripStop> rows=fixture.repository.tripStops("T1",LocalDate.parse("2026-10-21"));
    JourneyViewModel.Endpoint from=endpoint(fixture,rows.get(0).stopId),to=endpoint(fixture,rows.get(rows.size()-1).stopId);
    Constructor<JourneyPlanner.Walk> ctor=JourneyPlanner.Walk.class.getDeclaredConstructor(long.class,long.class,int.class,String.class,String.class,String.class,Instant.class,double.class,double[].class,boolean.class);ctor.setAccessible(true);
    JourneyPlanner.Walk walk=ctor.newInstance(from.stop.id,to.stop.id,120,"path",from.selection.packEnvelopeSha256,"map",Instant.parse("2026-10-21T08:05:00Z"),74.0,new double[]{52.525,13.369,52.526,13.370},true);
    MwmActivity map=Robolectric.buildActivity(MwmActivity.class).get();map.setTheme(R.style.MwmTheme);
    // Restore ComponentActivity's real empty saved-state registry, then attach its fragment host,
    // without invoking MwmActivity.onCreate/native startup.
    boolean restored=false;
    for(Field f:androidx.activity.ComponentActivity.class.getDeclaredFields())if(f.getType()==androidx.savedstate.SavedStateRegistryController.class){f.setAccessible(true);((androidx.savedstate.SavedStateRegistryController)f.get(map)).performRestore(null);restored=true;break;}
    assertTrue("fixture needs the actual ComponentActivity saved-state controller",restored);
    Field fragments=androidx.fragment.app.FragmentActivity.class.getDeclaredField("mFragments");fragments.setAccessible(true);((androidx.fragment.app.FragmentController)fragments.get(map)).attachHost(null);
    MapController renderer=mock(MapController.class);when(renderer.isRenderingActive()).thenReturn(true);
    FloatingSearchToolbarController search=mock(FloatingSearchToolbarController.class);MaterialToolbar toolbar=new MaterialToolbar(map);toolbar.setVisibility(View.GONE);when(search.getToolbar()).thenReturn(toolbar);
    field(map,"mMapController",renderer);field(map,"mSearchController",search);field(map,"mWalkingMapResumed",true);
    AtomicInteger released=new AtomicInteger();WalkingMapPreviewSession session=new WalkingMapPreviewSession(walk,null,from,to,answer->answer.accept(true),released::incrementAndGet);
    map.setIntent(new Intent().putExtra(WalkingMapPreviewSession.EXTRA_TOKEN,session.token()));
    RoutingController route=mock(RoutingController.class);WalkingMapOverlay line=mock(WalkingMapOverlay.class);when(line.isCurrent()).thenReturn(true);
    MwmApplication application=mock(MwmApplication.class,RETURNS_DEEP_STUBS);
    try(var nativeMap=mockStatic(Map.class);var routes=mockStatic(RoutingController.class);var choose=mockStatic(ChoosePositionMode.class);var overlay=mockStatic(WalkingMapOverlay.class);var apps=mockStatic(MwmApplication.class)){
      nativeMap.when(Map::isEngineCreated).thenReturn(true);routes.when(RoutingController::get).thenReturn(route);choose.when(ChoosePositionMode::get).thenReturn(ChoosePositionMode.None);
      overlay.when(()->WalkingMapOverlay.show(any(double[].class))).thenReturn(line);apps.when(()->MwmApplication.from(map)).thenReturn(application);
      try{
        call(map,"processIntent");Shadows.shadowOf(Looper.getMainLooper()).idle();overlay.verify(()->WalkingMapOverlay.show(any(double[].class)),times(1));
        NativeContextShadow.contextCalls=0;call(map,"onPause");assertEquals("real superclass pause reaches the controlled JNI context handoff",1,NativeContextShadow.contextCalls);assertFalse("pause still retires the native owner",session.isOpen());assertEquals(1,released.get());verify(line,times(1)).close();
        assertNotNull("pause must retain the separate Back path",map.getWindow().getDecorView().findViewWithTag("dmaps-walking-map-panel"));
        field(map,"mWalkingMapResumed",true);Field controller=MwmActivity.class.getDeclaredField("mWalkingPreview");controller.setAccessible(true);((WalkingMapPreviewController)controller.get(map)).resume();Shadows.shadowOf(Looper.getMainLooper()).idle();
        overlay.verify(()->WalkingMapOverlay.show(any(double[].class)),times(1));when(route.isNavigating()).thenReturn(true);
        map.onBackPressed();Intent back=Shadows.shadowOf(map).getNextStartedActivity();assertNotNull(back);assertEquals(JourneyActivity.class.getName(),back.getComponent().getClassName());
        WalkingMapPreviewSession.ReturnSelection kept=WalkingMapPreviewSession.takeReturn(back.getStringExtra(WalkingMapPreviewSession.EXTRA_RETURN_TOKEN));assertNotNull("pause must keep both platform identities",kept);assertEquals(from.stop.id,kept.fromId);assertEquals(to.stop.id,kept.toId);
        verify(route,never()).cancel();verify(route,never()).resetToPlanningStateIfNavigating();assertEquals(1,released.get());verify(line,times(1)).close();
      }finally{call(map,"closeWalkingPreview");session.close();}
    }
  }
}
