package app.organicmaps.transit;

import android.app.Application;
import app.organicmaps.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Compiled resource text for the UX01 summary row; aapt trims unescaped leading/trailing XML whitespace. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
public class JourneySummaryStringsTest {
  private String walk(String locale){
    RuntimeEnvironment.setQualifiers(locale);
    return RuntimeEnvironment.getApplication().getString(R.string.journey_summary_walk,2);
  }
  @Test public void walkingSuffixKeepsItsSeparatorSpaceInEveryShippedLocale(){
    assertEquals(" · 2 min mapped walk",walk("en"));
    assertEquals(" · 2 Min. kartierter Fußweg",walk("de"));
  }
}
