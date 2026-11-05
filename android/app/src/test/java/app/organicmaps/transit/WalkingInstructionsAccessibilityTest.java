package app.organicmaps.transit;
import android.app.Application;
import android.view.View;
import android.widget.*;
import app.organicmaps.R;
import androidx.core.view.ViewCompat;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Host View binding of the exact map-panel toggle seam; not native rendering or spoken acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
public class WalkingInstructionsAccessibilityTest {
 private void bind(Button b,View v)throws Exception {
  try{var method=WalkingMapPreviewController.class.getMethod("bindInstructionsToggle",Button.class,View.class);method.invoke(null,b,v);}
  catch(NoSuchMethodException absent){throw new AssertionError("walking instructions state binding absent");}
 }
 @Test public void toggleConveysExpandedAndCollapsedStateInEveryShippedLocale()throws Exception {
  for(String locale:new String[]{"en","de"}){
   RuntimeEnvironment.setQualifiers(locale);var context=RuntimeEnvironment.getApplication();Button b=new Button(context);b.setText(R.string.journey_walk_directions);ScrollView instructions=new ScrollView(context);instructions.setVisibility(View.GONE);bind(b,instructions);
   assertEquals(context.getString(R.string.journey_details_collapsed),String.valueOf(ViewCompat.getStateDescription(b)));assertFalse(b.getText().toString().isEmpty());
   b.performClick();assertEquals(View.VISIBLE,instructions.getVisibility());assertEquals(context.getString(R.string.journey_details_expanded),String.valueOf(ViewCompat.getStateDescription(b)));
   b.performClick();assertEquals(View.GONE,instructions.getVisibility());assertEquals(context.getString(R.string.journey_details_collapsed),String.valueOf(ViewCompat.getStateDescription(b)));
  }
 }
 @Test public void disabledDirectionsCannotOpenInstructionsThroughTheirHandler()throws Exception {
  var context=RuntimeEnvironment.getApplication();Button b=new Button(context);b.setText(R.string.journey_walk_directions);b.setEnabled(false);ScrollView instructions=new ScrollView(context);instructions.setVisibility(View.GONE);bind(b,instructions);b.performClick();assertEquals(View.GONE,instructions.getVisibility());assertFalse(b.isEnabled());
 }
}
