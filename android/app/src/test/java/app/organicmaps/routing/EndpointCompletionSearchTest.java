package app.organicmaps.routing;

import android.app.Application;
import app.organicmaps.search.SearchActivity;
import app.organicmaps.search.SearchFragment;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.search.SearchEngine;
import app.organicmaps.sdk.search.SearchResult;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.util.Utils;
import app.organicmaps.widget.SearchToolbarController;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual SearchFragment -> controller -> recorded JNI; no Activity startup/GPU/native atomicity claim. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(application=Application.class,sdk=35,manifest=org.robolectric.annotation.Config.NONE,
    shadows={EndpointCompletionTest.NativeRoute.class,EndpointCompletionTest.NativeRouter.class,EndpointCompletionSearchTest.NativeSearch.class})
public class EndpointCompletionSearchTest {
  @Implements(value=SearchEngine.class,callNativeMethodsByDefault=false)
  public static class NativeSearch {
    static int shown=-1;
    @Implementation protected static void nativeShowResult(int index){shown=index;}
    @Implementation protected static void nativeCancelAllSearches(){}
    @Implementation protected static void nativeUpdateViewportWithLastResults(){}
    @Implementation protected static void nativeRunInteractiveSearch(byte[] q,boolean category,String locale,
        long stamp,boolean mapAndTable,boolean position,double lat,double lon){}
  }
  private RoutingController route;
  private SearchFragment fragment;
  private SearchToolbarController toolbar;
  private RouteMarkData[] points;
  private MockedStatic<RoutingController> routes;
  private MockedStatic<Config> config;
  private MockedStatic<Utils> utils;
  private static void set(Object object,Class<?> owner,String field,Object value)throws Exception{
    Field f=owner.getDeclaredField(field);f.setAccessible(true);f.set(object,value);
  }
  @SuppressWarnings({"rawtypes","unchecked"})
  @Before public void setup()throws Exception{
    route=new RoutingController();
    EndpointCompletionTest.NativeRoute.points=new RouteMarkData[0];
    EndpointCompletionTest.NativeRoute.owner=route;
    EndpointCompletionTest.NativeRoute.undo=null;
    EndpointCompletionTest.NativeRoute.failAdd=false;EndpointCompletionTest.NativeRoute.failApply=false;
    route.prepare(EndpointCompletionTest.pick(false),null,Router.Pedestrian);
    points=EndpointCompletionTest.NativeRoute.points;
    EndpointCompletionTest.NativeRoute.calls.clear();NativeSearch.shown=-1;
    routes=mockStatic(RoutingController.class);routes.when(RoutingController::get).thenReturn(route);
    config=mockStatic(Config.class);utils=mockStatic(Utils.class);
    fragment=spy(new SearchFragment());
    doReturn(RuntimeEnvironment.getApplication()).when(fragment).requireContext();
    doReturn(mock(SearchActivity.class)).when(fragment).requireActivity();
    Class<?> type=Class.forName(SearchFragment.class.getName()+"$ToolbarController");
    toolbar=(SearchToolbarController)mock(type,CALLS_REAL_METHODS);
    set(toolbar,type,"this$0",fragment);
    doReturn("owned query").when(toolbar).getQuery();doReturn(false).when(toolbar).isCategory();
    doReturn(false).when(toolbar).hasQuery();doNothing().when(toolbar).deactivate();
    set(fragment,SearchFragment.class,"mToolbarController",toolbar);
  }
  @After public void cleanup(){if(utils!=null)utils.close();if(config!=null)config.close();if(routes!=null)routes.close();}
  private static RouteMarkData mark(String name,RouteMarkType type,int index){return new RouteMarkData(name,"",type,index,true,false,false,52.5,13.4);}
  private void begin()throws Exception{
    Method m=RoutingController.class.getMethod("waitForMissingEndpoint",RouteMarkData[].class);
    assertTrue((boolean)m.invoke(route,(Object)points));
  }
  private void showAll()throws Exception{Method m=SearchFragment.class.getDeclaredMethod("showAllResultsOnMap");m.setAccessible(true);m.invoke(fragment);}
  private void single()throws Exception{
    Method m=SearchFragment.class.getDeclaredMethod("showSingleResultOnMap",SearchResult.class,int.class);m.setAccessible(true);
    m.invoke(fragment,new SearchResult("different","",53.0,14.0,new int[0],new int[0]),7);
  }
  @Test public void leavingEndpointThroughShowOnMapCannotHijackLaterOrdinaryResult()throws Exception{
    begin();showAll();
    assertFalse("Show on map leaves this search-only endpoint: retire its pending authority",route.isWaitingPoiPick());
    single();assertEquals(7,NativeSearch.shown);assertEquals(1,EndpointCompletionTest.NativeRoute.points.length);
    assertEquals(java.util.Collections.emptyList(),EndpointCompletionTest.NativeRoute.calls);
  }
  @Test public void ordinaryEndpointPickerStillContinuesThroughShowOnMap()throws Exception{
    route.waitForPoiPick(RouteMarkType.Start);showAll();assertTrue(route.isWaitingPoiPick());
    assertEquals(1,EndpointCompletionTest.NativeRoute.points.length);
  }
  @Test public void directEndpointResultStillCommitsOnceThroughTheRealFragment()throws Exception{
    begin();single();assertFalse(route.isWaitingPoiPick());assertEquals(-1,NativeSearch.shown);
    assertEquals(Arrays.asList("open","add:Finish:0:false","apply","remove-route","build"),EndpointCompletionTest.NativeRoute.calls);
  }
  @Test public void backCancelsEndpointAndLateOrdinaryResultCannotCommit()throws Exception{
    begin();fragment.onBackPressed();assertFalse(route.isWaitingPoiPick());single();
    assertEquals(7,NativeSearch.shown);assertEquals(1,EndpointCompletionTest.NativeRoute.points.length);
    assertEquals(java.util.Collections.emptyList(),EndpointCompletionTest.NativeRoute.calls);
  }
}
