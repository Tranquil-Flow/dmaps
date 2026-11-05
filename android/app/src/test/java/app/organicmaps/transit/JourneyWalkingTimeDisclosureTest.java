package app.organicmaps.transit;

import android.app.Application;
import app.organicmaps.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Compiled-resource disclosure at the existing JourneyActivity summary/walk-time callsites.
 * No arrival rebasing or added lifetime: walking clock times remain explicitly those estimated at planning.
 * Host resource evidence, not ART/GPU/phone visual acceptance. All resource keys exist on sealed v61. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
public class JourneyWalkingTimeDisclosureTest {
  private String summary(boolean walking,String locale){
    RuntimeEnvironment.setQualifiers(locale);
    return RuntimeEnvironment.getApplication().getString(walking?R.string.journey_summary_estimated:R.string.journey_summary,"09:00","09:30","U5","direct");
  }
  @Test public void mappedSummaryUsesPlannedTimestampsInEnglishAndGerman(){
    String en=summary(true,"en");
    assertTrue("mapped summary identifies planning estimates",en.startsWith("Planned start 09:00 · planned arrival ~09:30"));
    String de=summary(true,"de");
    assertTrue("mapped summary identifies planning estimates (de)",de.startsWith("Geplanter Start 09:00 · geplante Ankunft etwa 09:30"));
    assertTrue("aapt decodes the line break",en.contains("\nU5"));
    assertFalse("no literal escaped newline reaches the rider",en.contains("\\n"));
  }
  @Test public void walkTimesAreQualifiedAsPlanningEstimates(){
    RuntimeEnvironment.setQualifiers("en");
    String en=RuntimeEnvironment.getApplication().getString(R.string.journey_walk_times,"START","ARRIVAL");
    assertTrue("walk times identify planning estimates",en.startsWith("Walking times estimated when this route was planned:"));
    assertTrue(en.contains("\nStart: START\nArrival: ARRIVAL"));
    RuntimeEnvironment.setQualifiers("de");
    String de=RuntimeEnvironment.getApplication().getString(R.string.journey_walk_times,"START","ARRIVAL");
    assertTrue("walk times identify planning estimates (de)",de.startsWith("Zum Planungszeitpunkt geschätzte Gehzeiten:"));
    assertTrue(de.contains("\nStart: START\nAnkunft: ARRIVAL"));
  }
  @Test public void ordinaryScheduledSummaryKeepsTheDepartureWording(){
    assertTrue(summary(false,"en").startsWith("Leave 09:00 · arrive 09:30"));
    assertTrue(summary(false,"de").startsWith("Abfahrt 09:00 · Ankunft 09:30"));
  }
}
