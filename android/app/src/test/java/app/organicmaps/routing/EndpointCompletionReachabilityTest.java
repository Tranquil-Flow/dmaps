package app.organicmaps.routing;

import android.app.Activity;
import android.app.Application;
import android.view.LayoutInflater;
import android.view.View;
import app.organicmaps.R;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.routing.*;
import java.lang.reflect.Method;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real partial prepare -> actual routing action panel -> existing manage listener. Not Activity startup. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,manifest=Config.NONE,
    shadows={EndpointCompletionTest.NativeRoute.class,EndpointCompletionTest.NativeRouter.class})
public class EndpointCompletionReachabilityTest {
  @Test public void startOnlyPrepareExposesManageWithoutBuiltAltitudePanel() throws Exception {
    Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
    activity.setTheme(R.style.MwmTheme);
    RoutingController route=new RoutingController();
    EndpointCompletionTest.NativeRoute.points=new RouteMarkData[0];
    EndpointCompletionTest.NativeRoute.owner=route;
    EndpointCompletionTest.NativeRoute.undo=null;
    EndpointCompletionTest.NativeRoute.failAdd=false;
    EndpointCompletionTest.NativeRoute.failApply=false;
    route.prepare(EndpointCompletionTest.pick(false),null,Router.Pedestrian);
    try(MockedStatic<RoutingController> routes=mockStatic(RoutingController.class)) {
      routes.when(RoutingController::get).thenReturn(route);
      View root=LayoutInflater.from(activity).inflate(R.layout.fragment_routing,null,false);
      RoutingBottomMenuListener listener=mock(RoutingBottomMenuListener.class);
      RoutingBottomMenuController menu=RoutingBottomMenuController.newInstance(activity,root,listener);
      menu.hideAltitudeChartAndRoutingDetails();
      menu.showAddFinishFrame();
      assertEquals(View.GONE,root.findViewById(R.id.altitude_chart_panel).getVisibility());
      int id=activity.getResources().getIdentifier("btn__manage_partial_route","id",
          activity.getResources().getResourcePackageName(R.id.btn__manage_route));
      assertTrue("Missing manage entry in the visible partial-route action panel",id!=0);
      View manage=root.findViewById(id);assertNotNull(manage);
      assertEquals(View.VISIBLE,manage.getVisibility());assertTrue(manage.performClick());
      verify(listener).onManageRouteOpen();
      // Existing search continues to dispatch the ordinary Finish picker, not managed authority.
      root.findViewById(R.id.btn__search_point).performClick();
      verify(listener).onSearchRoutePoint(RouteMarkType.Finish);
      assertFalse(route.isWaitingPoiPick());
    } finally { activity.finish(); }
  }
}
