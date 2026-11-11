package app.organicmaps.transit;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.core.view.ViewCompat;
import java.util.*;
import static org.junit.Assert.*;

/** Silent host lint. It proves label/state structure, not speech, native GPU glyphs or real focus order. */
final class SilentAccessibilityAudit {
 private SilentAccessibilityAudit(){}
 static int check(View root,String state){List<EditText> fields=new ArrayList<>();int count=visit(root,state,fields);
  Set<String> names=new HashSet<>();for(EditText e:fields){String label=label(e);assertTrue(state+" duplicate form field accessibility name",names.add(label));}
  return count;
 }
 private static String label(View v){
  if(v.getContentDescription()!=null&&!v.getContentDescription().toString().trim().isEmpty())return v.getContentDescription().toString();
  if(v instanceof EditText){CharSequence h=((EditText)v).getHint();return h==null?"":h.toString().trim();}
  if(v instanceof TextView){CharSequence t=((TextView)v).getText();return t==null?"":t.toString().trim();}
  if(v instanceof ViewGroup){StringBuilder s=new StringBuilder();for(int i=0;i<((ViewGroup)v).getChildCount();i++){View child=((ViewGroup)v).getChildAt(i);if(child.getVisibility()==View.VISIBLE)s.append(label(child));}return s.toString();}
  return "";
 }
 private static int visit(View v,String state,List<EditText> fields){
  if(v.getVisibility()!=View.VISIBLE)return 0;
  int count=0;
  if(v instanceof EditText){assertFalse(state+" form field needs a stable hint or accessibility label",label(v).isEmpty());fields.add((EditText)v);count++;}
  else if(v.isClickable()||v instanceof Button){assertFalse(state+" clickable control must not be icon/colour-only",label(v).isEmpty());count++;}
  if("dmaps-walk-action-details".equals(v.getTag()))assertNotNull(state+" details toggle needs expanded/collapsed state",ViewCompat.getStateDescription(v));
  if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)count+=visit(((ViewGroup)v).getChildAt(i),state,fields);
  return count;
 }
 /** Q10: text that changes in place (status, errors, refresh outcome) is announced politely, never interrupting. */
 static void announcesChanges(View v,String state){assertNotNull(state+" status view missing",v);
  assertEquals(state+" status text that changes in place must be a polite live region",ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE,ViewCompat.getAccessibilityLiveRegion(v));}
 static void fieldsInOrder(View root,String...expected){List<EditText> fields=new ArrayList<>();visit(root,"form-order",fields);assertEquals("screen form traversal order",expected.length,fields.size());for(int i=0;i<expected.length;i++)assertEquals("screen form traversal order "+i,expected[i],label(fields.get(i)));}
}
