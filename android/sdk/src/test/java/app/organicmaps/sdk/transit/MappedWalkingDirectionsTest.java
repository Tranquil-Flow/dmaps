package app.organicmaps.sdk.transit;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
/** Synthetic wire validation only, not evidence of a native route. */
public class MappedWalkingDirectionsTest {
 private static MappedWalking.Result result(int[] points,int[] codes,double[] meters,boolean available)throws Exception {
  Constructor<MappedWalking.Result> c=MappedWalking.Result.class.getDeclaredConstructor(int.class,String.class,String.class,double[].class,int.class,double.class,boolean.class,int[].class,int[].class,double[].class,boolean.class);c.setAccessible(true);
  return c.newInstance(0,"0".repeat(64),"1".repeat(64),new double[]{0,0,0,0.001,0,0.002},60,100d,true,points,codes,meters,available);
 }
 private static void reject(int[] points,int[] codes,double[] meters,boolean available)throws Exception {
  try{result(points,codes,meters,available);fail("malformed native instructions accepted");}catch(InvocationTargetException e){assertTrue(e.getCause() instanceof IllegalArgumentException);}
 }
 @Test public void validNativeWireIsImmutableAndBoundToVertices()throws Exception {
  int[] points={1,2},codes={2,4};double[] meters={50,100};var r=result(points,codes,meters,true);points[0]=99;codes[0]=3;meters[0]=0;
  assertTrue(r.directionsAvailable);assertEquals(2,r.directions().size());assertEquals(1,r.directions().get(0).pointIndex);assertEquals(MappedWalking.Maneuver.RIGHT,r.directions().get(0).maneuver);assertEquals(50,r.directions().get(0).distanceFromStartMeters,0);try{r.directions().clear();fail();}catch(UnsupportedOperationException expected){}
 }
 @Test public void refusesInvalidVerticesAndCodes()throws Exception {
  reject(new int[]{-1,2},new int[]{2,4},new double[]{50,100},true);reject(new int[]{1,1},new int[]{2,4},new double[]{50,100},true);reject(new int[]{1,3},new int[]{2,4},new double[]{50,100},true);
  for(int code:new int[]{0,5})reject(new int[]{1,2},new int[]{code,4},new double[]{50,100},true);
 }
 @Test public void refusesInvalidDistancesAndIncompleteAdvice()throws Exception {
  for(double meters:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,101})reject(new int[]{1,2},new int[]{2,4},new double[]{meters,100},true);
  reject(new int[]{1,2},new int[]{2,4},new double[]{60,50},true);reject(new int[]{1,2},new int[]{4,4},new double[]{50,100},true);reject(new int[]{1,2},new int[]{2,1},new double[]{50,100},true);reject(new int[]{1},new int[]{4},new double[]{50},true);
 }
 @Test public void refusesShapeAndCountMismatchWithoutPartialFallback()throws Exception {
  reject(new int[]{1},new int[]{2,4},new double[]{50,100},true);reject(new int[257],new int[257],new double[257],true);reject(new int[]{2},new int[]{4},new double[]{100},false);reject(new int[0],new int[0],new double[0],true);
 }
 @Test public void geometryOnlyAndRefusalCarryNoInstructions()throws Exception {
  var r=result(new int[0],new int[0],new double[0],false);assertFalse(r.directionsAvailable);assertTrue(r.directions().isEmpty());
  Constructor<MappedWalking.Result> c=MappedWalking.Result.class.getDeclaredConstructor(int.class,String.class,String.class,double[].class,int.class,double.class,boolean.class);c.setAccessible(true);
  var old=c.newInstance(0,"0".repeat(64),"1".repeat(64),new double[]{0,0,0,0.002},60,100d,true);assertFalse(old.directionsAvailable);assertTrue(old.directions().isEmpty());var no=c.newInstance(4,"","",new double[0],0,0d,true);assertTrue(no.directions().isEmpty());
 }
}
