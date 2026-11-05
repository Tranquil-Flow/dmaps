package app.organicmaps.routing;

import android.app.Application;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.bookmarks.data.FeatureId;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Real prepare and controller; JNI recording model, not native/device proof. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,manifest=Config.NONE,
    shadows={EndpointCompletionTest.NativeRoute.class,EndpointCompletionTest.NativeRouter.class})
public class EndpointCompletionTest {
  @Implements(value=Router.class,isInAndroidSdk=false)
  public static class NativeRouter {
    @Implementation protected static void nativeSet(int type) {}
  }
  @Implements(value=Framework.class,isInAndroidSdk=false)
  public static class NativeRoute {
    static RouteMarkData[] points;
    static RouteMarkData[] undo;
    static final List<String> calls=new ArrayList<>();
    static boolean failAdd, failApply;
    static RoutingController owner;
    @Implementation protected static RouteMarkData[] nativeGetRoutePoints(){return points;}
    @Implementation protected static boolean nativeCouldAddIntermediatePoint(){throw new AssertionError("Partial route is inactive: must not use intermediate admission");}
    @Implementation protected static void nativeAddRoutePoint(String title,String subtitle,RouteMarkType type,int index,
        boolean mine,double lat,double lon,boolean reorder){
      calls.add("add:"+type+":"+index+":"+reorder);
      if (undo!=null) assertFalse("consume before native effect",owner.isWaitingPoiPick());
      if(failAdd) throw new IllegalStateException("add");
      List<RouteMarkData> next=new ArrayList<>(Arrays.asList(points));
      next.removeIf(p->p.mPointType==type);
      RouteMarkData added=new RouteMarkData(title,subtitle,type,index,!mine,mine,false,lat,lon);
      if(type==RouteMarkType.Start)next.add(0,added);else next.add(added);
      points=next.toArray(new RouteMarkData[0]);
    }
    @Implementation protected static int nativeOpenRoutePointsTransaction(){calls.add("open");undo=points.clone();return 17;}
    @Implementation protected static void nativeApplyRoutePointsTransaction(int id){calls.add("apply");if(failApply)throw new IllegalStateException("apply");undo=null;}
    @Implementation protected static void nativeCancelRoutePointsTransaction(int id){calls.add("rollback");points=undo;undo=null;}
    @Implementation protected static void nativeRemoveRoute(){calls.add("remove-route");}
    @Implementation protected static void nativeBuildRoute(){calls.add("build");}
    @Implementation protected static void nativeLoadRoutePoints(){calls.add("load");}
    @Implementation protected static void nativeDeleteSavedRoutePoints(){}
    @Implementation protected static void nativeCloseRouting(){points=new RouteMarkData[0];}
    @Implementation protected static void nativeRemoveRoutePoints(){throw new AssertionError("No wholesale route rewrite");}
    @Implementation protected static void nativeRemoveRoutePoint(RouteMarkType type,int index){throw new AssertionError("No explicit endpoint removal");}
  }
  RoutingController route;
  static MapObject pick(boolean mine){return MapObject.createMapObject(FeatureId.EMPTY,mine?MapObject.MY_POSITION:MapObject.SEARCH,"chosen","place",52.5,13.4);}
  static void field(RoutingController r,String name,Object value)throws Exception{Field f=RoutingController.class.getDeclaredField(name);f.setAccessible(true);f.set(r,value);}
  @Before public void setup(){
    route=new RoutingController();NativeRoute.owner=route;NativeRoute.points=new RouteMarkData[0];
    NativeRoute.undo=null;NativeRoute.failAdd=false;NativeRoute.failApply=false;NativeRoute.calls.clear();
    route.attach(new RoutingController.Container(){
      @Override public void showRoutePlan(boolean show,Runnable completion){if(completion!=null)completion.run();}
    });
  }
  void prepare(RouteMarkType existing){
    route.prepare(existing==RouteMarkType.Start?pick(false):null,existing==RouteMarkType.Finish?pick(false):null,Router.Pedestrian);
    assertTrue(route.isPlanning());assertEquals(1,NativeRoute.points.length);
    assertFalse(NativeRoute.calls.contains("build"));NativeRoute.calls.clear();
  }
  boolean begin(RouteMarkData[] expected)throws Exception{
    Method method;
    try{method=RoutingController.class.getMethod("waitForMissingEndpoint",RouteMarkData[].class);}
    catch(NoSuchMethodException e){throw new AssertionError("Missing explicit managed endpoint completion API",e);}
    return (boolean)method.invoke(route,(Object)expected);
  }
  boolean begin()throws Exception{return begin(NativeRoute.points);}
  void abandon()throws Exception{RoutingController.class.getMethod("cancelManagedRoutePointEdit").invoke(route);}
  void noEffects(){assertTrue(NativeRoute.calls.toString(),NativeRoute.calls.isEmpty());}
  void commit(RouteMarkType missing){
    RouteMarkData preserved=NativeRoute.points[0];
    route.onPoiSelected(MapObject.createMapObject(FeatureId.EMPTY,MapObject.SEARCH,"new","",48,11));
    assertEquals(Arrays.asList("open","add:"+missing+":0:false","apply","remove-route","build"),NativeRoute.calls);
    assertSame(preserved,NativeRoute.points[missing==RouteMarkType.Start?1:0]);
    assertEquals(2,NativeRoute.points.length);assertFalse(route.isWaitingPoiPick());
    route.onPoiSelected(pick(false));assertEquals(5,NativeRoute.calls.size());
  }
  @Test public void realPrepareStartOnlyCompletesFinishOnce()throws Exception{prepare(RouteMarkType.Start);assertTrue(begin());commit(RouteMarkType.Finish);}
  @Test public void realPrepareFinishOnlyCompletesStartOnce()throws Exception{prepare(RouteMarkType.Finish);assertTrue(begin());commit(RouteMarkType.Start);}
  @Test public void missingEndpointsAreNotSynthesizedByPrepare()throws Exception{prepare(RouteMarkType.Start);assertNull(route.getEndPoint());assertNotNull(route.getStartPoint());noEffects();}
  @Test public void emptyCompleteAndIntermediateShapesRefused()throws Exception{
    route.prepare(null,null,Router.Pedestrian);assertFalse(begin());
    prepare(RouteMarkType.Start);NativeRoute.points=new RouteMarkData[]{NativeRoute.points[0],new RouteMarkData("i","",RouteMarkType.Intermediate,0,true,false,false,1,2)};assertFalse(begin());
    NativeRoute.points[1].mPointType=RouteMarkType.Finish;assertFalse(begin());
  }
  @Test public void ordinaryPickerIsNotReclassifiedOrCleared()throws Exception{
    prepare(RouteMarkType.Start);route.waitForPoiPick(RouteMarkType.Finish);assertFalse(begin());abandon();assertTrue(route.isWaitingPoiPick());noEffects();
  }
  @Test public void cancelAndDuplicateCallbacksAreInert()throws Exception{prepare(RouteMarkType.Start);assertTrue(begin());route.onPoiSelected(null);route.onPoiSelected(pick(false));noEffects();}
  @Test public void showMapAbandonmentIsScoped()throws Exception{prepare(RouteMarkType.Finish);assertTrue(begin());abandon();route.onPoiSelected(pick(false));noEffects();}
  @Test public void admissionRejectsStaleDisplayedMark()throws Exception{
    prepare(RouteMarkType.Start);RouteMarkData p=NativeRoute.points[0];
    assertFalse(begin(new RouteMarkData[]{new RouteMarkData(p.mTitle,p.mSubtitle,p.mPointType,8,p.mIsVisible,p.mIsMyPosition,p.mIsPassed,p.mLat,p.mLon)}));noEffects();
  }
  @Test public void everyCurrencyFieldIsCheckedAtCommit()throws Exception{
    for(int change=0;change<9;change++){
      setup();prepare(RouteMarkType.Start);assertTrue(begin());RouteMarkData p=NativeRoute.points[0];
      NativeRoute.points[0]=new RouteMarkData(change==0?"changed":p.mTitle,change==1?"changed":p.mSubtitle,
          change==2?RouteMarkType.Finish:p.mPointType,change==3?8:p.mIntermediateIndex,
          change==4?!p.mIsVisible:p.mIsVisible,change==5?!p.mIsMyPosition:p.mIsMyPosition,
          change==6?!p.mIsPassed:p.mIsPassed,change==7?1:p.mLat,change==8?2:p.mLon);
      route.onPoiSelected(pick(false));assertFalse(route.isWaitingPoiPick());noEffects();
    }
  }
  @Test public void snapshotDoesNotAliasMutableType()throws Exception{prepare(RouteMarkType.Start);assertTrue(begin());NativeRoute.points[0].mPointType=RouteMarkType.Finish;route.onPoiSelected(pick(false));noEffects();}
  @Test public void revisionAndRouterRoundTripInvalidateAuthority()throws Exception{
    prepare(RouteMarkType.Start);assertTrue(begin());route.setRouterType(Router.Vehicle);route.setRouterType(Router.Pedestrian);NativeRoute.calls.clear();route.onPoiSelected(pick(false));noEffects();
  }
  @Test public void navigationStateRefusesLateSelection()throws Exception{
    prepare(RouteMarkType.Start);assertTrue(begin());Field f=RoutingController.class.getDeclaredField("mState");f.setAccessible(true);
    for(Object value:f.getType().getEnumConstants())if(value.toString().equals("NAVIGATION"))f.set(route,value);
    route.onPoiSelected(pick(false));noEffects();
  }
  @Test public void diskAndFreshControllerCannotRecoverAuthority()throws Exception{
    prepare(RouteMarkType.Start);assertTrue(begin());route.restoreRoute();NativeRoute.calls.clear();route.onPoiSelected(pick(false));noEffects();
    route=new RoutingController();route.onPoiSelected(pick(false));noEffects();
  }
  @Test public void configurationDetachKeepsProcessLocalAuthority()throws Exception{
    prepare(RouteMarkType.Finish);assertTrue(begin());route.onSaveState();route.detach();route.attach(new RoutingController.Container(){});route.restore();commit(RouteMarkType.Start);
  }
  @Test public void invalidSelectionConsumesWithoutMutation()throws Exception{
    for(double[] c:new double[][]{{Double.NaN,0},{0,Double.POSITIVE_INFINITY},{91,0},{0,-181}}){
      setup();prepare(RouteMarkType.Start);assertTrue(begin());route.onPoiSelected(MapObject.createMapObject(FeatureId.EMPTY,MapObject.SEARCH,"bad","",c[0],c[1]));route.onPoiSelected(pick(false));noEffects();
    }
  }
  @Test public void duplicateMyPositionCannotDeleteExistingEndpoint()throws Exception{
    prepare(RouteMarkType.Finish);RouteMarkData p=NativeRoute.points[0];NativeRoute.points[0]=new RouteMarkData(p.mTitle,p.mSubtitle,p.mPointType,0,true,true,false,p.mLat,p.mLon);
    assertTrue(begin());route.onPoiSelected(pick(true));noEffects();assertEquals(1,NativeRoute.points.length);
  }
  @Test public void uniqueMyPositionSelectionIsExplicitAndAllowed()throws Exception{
    prepare(RouteMarkType.Finish);assertTrue(begin());route.onPoiSelected(pick(true));assertEquals(2,NativeRoute.points.length);assertTrue(NativeRoute.points[0].mIsMyPosition);assertEquals(1,Collections.frequency(NativeRoute.calls,"build"));
  }
  @Test public void malformedExistingEndpointAndNonRoadRoutersRefused()throws Exception{
    prepare(RouteMarkType.Start);field(route,"mLastRouterType",Router.Transit);assertFalse(begin());field(route,"mLastRouterType",Router.Ruler);assertFalse(begin());field(route,"mLastRouterType",Router.Vehicle);
    RouteMarkData p=NativeRoute.points[0];NativeRoute.points[0]=new RouteMarkData(p.mTitle,p.mSubtitle,p.mPointType,0,true,false,true,p.mLat,p.mLon);assertFalse(begin());noEffects();
  }
  @Test public void rearmingDoesNotOverwriteCurrentAuthority()throws Exception{prepare(RouteMarkType.Start);assertTrue(begin());assertFalse(begin());commit(RouteMarkType.Finish);}
  @Test public void failedNativeAddAndApplyRollbackWithoutRebuild()throws Exception{
    for(boolean apply:new boolean[]{false,true}){
      setup();prepare(RouteMarkType.Start);RouteMarkData p=NativeRoute.points[0];assertTrue(begin());NativeRoute.failAdd=!apply;NativeRoute.failApply=apply;
      try{route.onPoiSelected(pick(false));fail("Expected injected failure");}catch(IllegalStateException expected){}
      assertEquals(1,NativeRoute.points.length);assertSame(p,NativeRoute.points[0]);assertFalse(NativeRoute.calls.contains("build"));assertEquals("rollback",NativeRoute.calls.get(NativeRoute.calls.size()-1));assertFalse(route.isWaitingPoiPick());
    }
  }
}
