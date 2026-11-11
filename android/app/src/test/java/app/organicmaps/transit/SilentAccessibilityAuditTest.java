package app.organicmaps.transit;
import android.app.Application;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
public class SilentAccessibilityAuditTest {
 @Test public void aStatusLineThatIsNotALiveRegionFailsTheLint(){TextView t=new TextView(RuntimeEnvironment.getApplication());t.setText("Import failed");try{SilentAccessibilityAudit.announcesChanges(t,"negative-status");fail("silent status accepted");}catch(AssertionError e){assertTrue(e.getMessage(),e.getMessage().contains("polite live region"));}}
 @Test public void anAssertiveStatusLineFailsTheLintAndAPoliteOnePasses(){TextView t=new TextView(RuntimeEnvironment.getApplication());androidx.core.view.ViewCompat.setAccessibilityLiveRegion(t,androidx.core.view.ViewCompat.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);try{SilentAccessibilityAudit.announcesChanges(t,"assertive-status");fail("interrupting status accepted");}catch(AssertionError e){assertTrue(e.getMessage(),e.getMessage().contains("polite"));}
  androidx.core.view.ViewCompat.setAccessibilityLiveRegion(t,androidx.core.view.ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);SilentAccessibilityAudit.announcesChanges(t,"polite-status");}
 @Test public void blankIconOnlyButtonFailsTheLint(){Button b=new Button(RuntimeEnvironment.getApplication());try{SilentAccessibilityAudit.check(b,"negative-control");fail("unlabelled button accepted");}catch(AssertionError e){assertTrue(e.getMessage().contains("colour-only"));}}
 @Test public void typedTextDoesNotSubstituteForAStableFormLabel(){EditText e=new EditText(RuntimeEnvironment.getApplication());e.setText("a private query is not a label");try{SilentAccessibilityAudit.check(e,"negative-control");fail("unlabelled edit accepted");}catch(AssertionError err){assertTrue(err.getMessage().contains("stable hint"));}}
 @Test public void namedDisabledControlsAndOrderedFormFieldsPass(){var c=RuntimeEnvironment.getApplication();LinearLayout root=new LinearLayout(c);EditText a=new EditText(c);a.setHint("Origin");root.addView(a);EditText b=new EditText(c);b.setHint("Destination");root.addView(b);Button button=new Button(c);button.setText("Plan");button.setEnabled(false);root.addView(button);assertEquals(3,SilentAccessibilityAudit.check(root,"positive-control"));SilentAccessibilityAudit.fieldsInOrder(root,"Origin","Destination");}
}
