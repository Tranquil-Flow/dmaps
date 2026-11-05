package app.organicmaps.transit;

import android.app.Application;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** WI-07: the S7 catalogue and S6 matrix are data (src/test/resources/transit/*.json). This test keeps them honest from
 * inside the unit-test gate: every state x disruption cell exists once, every cited selector is a real public @Test method
 * on the test classpath, classifier ids equal catalogue ids, and a cell only counts as EXERCISED when it names a test.
 * It runs no UI and exercises no cell itself; the cited tests do. The Python twin (tools/check_matrix.py) checks the same
 * rules against source text before Gradle. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,manifest=Config.NONE)
public class ScreenStateCatalogueTest {
  private static JSONObject load(String name)throws Exception {
    try(InputStream in=ScreenStateCatalogueTest.class.getResourceAsStream("/transit/"+name)){
      assertNotNull(name+" must be on the test classpath",in);return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));
    }
  }
  private static Set<String> ids(JSONArray a)throws Exception {Set<String> s=new TreeSet<>();for(int i=0;i<a.length();i++)s.add(a.getJSONObject(i).getString("id"));return s;}
  private static void assertTestMethod(String selector)throws Exception {
    String[] parts=selector.split("#");assertEquals(selector,2,parts.length);
    Class<?> c=Class.forName(parts[0]);Method m=c.getMethod(parts[1]);
    assertNotNull(selector+" must be annotated @Test",m.getAnnotation(Test.class));
  }
  @Test public void catalogueIdsEqualTheClassifierIds()throws Exception {
    JSONObject cat=load("screen-state-catalogue.json");
    Set<String> journey=new TreeSet<>(Arrays.asList(ScreenStateCatalogue.JOURNEY_CLASSIFIED));journey.addAll(Arrays.asList(ScreenStateCatalogue.JOURNEY_MAP_SIDE));
    assertEquals(journey,ids(cat.getJSONObject("screens").getJSONArray("journey")));
    assertEquals(new TreeSet<>(Arrays.asList(ScreenStateCatalogue.TIMETABLE_CLASSIFIED)),ids(cat.getJSONObject("screens").getJSONArray("timetable")));
  }
  @Test public void everyStateByDisruptionCellExistsOnceAndEveryExercisedCellNamesARealTest()throws Exception {
    JSONObject cat=load("screen-state-catalogue.json"),mat=load("disruption-matrix.json");
    Set<String> states=new TreeSet<>();states.addAll(ids(cat.getJSONObject("screens").getJSONArray("journey")));states.addAll(ids(cat.getJSONObject("screens").getJSONArray("timetable")));
    Set<String> dis=ids(mat.getJSONArray("disruptions"));assertEquals(7,dis.size());
    JSONArray cells=mat.getJSONArray("cells");Map<String,Integer> seen=new HashMap<>();int exercised=0;
    for(int i=0;i<cells.length();i++){
      JSONObject c=cells.getJSONObject(i);String key=c.getString("state")+"x"+c.getString("disruption");seen.merge(key,1,Integer::sum);
      assertTrue(key,states.contains(c.getString("state")));assertTrue(key,dis.contains(c.getString("disruption")));
      String status=c.getString("status"),contract=c.getString("contract");JSONArray tests=c.getJSONArray("tests");
      assertEquals(key,"NOT_APPLICABLE".equals(contract),"NOT_APPLICABLE".equals(status));
      assertEquals(key,"UNDECIDED".equals(contract),"DECISION".equals(status));
      assertFalse(key+" needs a note",c.getString("note").isEmpty());
      if(status.startsWith("EXERCISED")){assertTrue(key+" exercised without a test",tests.length()>0);exercised++;}
      else if(!"DERIVED".equals(status))assertEquals(key+" non-exercised cell must not claim tests",0,tests.length());
      for(int t=0;t<tests.length();t++)assertTestMethod(tests.getString(t));
    }
    assertEquals(states.size()*dis.size(),cells.length());
    for(Map.Entry<String,Integer> e:seen.entrySet())assertEquals(e.getKey(),Integer.valueOf(1),e.getValue());
    assertTrue("the matrix must exercise something",exercised>0);
    JSONArray catalogueTests=mat.getJSONArray("catalogue_tests");for(int i=0;i<catalogueTests.length();i++)assertTestMethod(catalogueTests.getString(i));
  }
  @Test public void everyNewMatrixTestIsCitedByACellSoNoHookIsCountedWithoutBeingExercised()throws Exception {
    JSONObject mat=load("disruption-matrix.json");Set<String> cited=new HashSet<>();
    JSONArray cells=mat.getJSONArray("cells");
    for(int i=0;i<cells.length();i++){JSONArray tests=cells.getJSONObject(i).getJSONArray("tests");for(int t=0;t<tests.length();t++)cited.add(tests.getString(t));}
    JSONArray cat=mat.getJSONArray("catalogue_tests");for(int i=0;i<cat.length();i++)cited.add(cat.getString(i));
    JSONArray classes=mat.getJSONArray("new_test_classes");
    for(int i=0;i<classes.length();i++){
      Class<?> c=Class.forName(classes.getString(i));
      for(Method m:c.getDeclaredMethods())if(m.getAnnotation(Test.class)!=null)assertTrue("orphan new test "+c.getSimpleName()+"#"+m.getName(),cited.contains(c.getName()+"#"+m.getName()));
    }
  }
}
