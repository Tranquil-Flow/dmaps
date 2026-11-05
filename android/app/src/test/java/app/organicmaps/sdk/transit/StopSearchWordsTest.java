package app.organicmaps.sdk.transit;

import android.app.Application;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** Real untrusted import + native SQLite repository search. Observed phone defect: "Hauptbahnhof Berlin"
 * found nothing because the whole query was one substring. Names are authored, not VBB rows. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class StopSearchWordsTest {
  @Rule public org.junit.rules.TemporaryFolder temp=new org.junit.rules.TemporaryFolder();
  private JourneyUiFixture fixture;
  @Before public void setup()throws Exception {
    fixture=new JourneyUiFixture(RuntimeEnvironment.getApplication(),temp.newFolder(),Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"),ZoneOffset.UTC));
  }
  @After public void close()throws Exception {if(fixture!=null)fixture.repository.close();}
  private List<String> ids(String query)throws Exception {
    return fixture.repository.searchStops(query,50).stream().map(s->s.sourceId).collect(Collectors.toList());
  }
  @Test public void reorderedWordsFindStationAndPhraseRanksFirst()throws Exception {
    fixture.useMultiWordStationNames();
    List<String> reordered=ids("Hauptbahnhof Berlin");
    assertTrue(reordered.toString(),reordered.containsAll(List.of("H1","H1a","S1")));
    assertEquals("whole phrase match first",0,reordered.indexOf("S1"));
    List<String> literal=ids("Berlin  hauptbahnhof");
    assertTrue(literal.toString(),literal.containsAll(List.of("H1","H1a","S1")));
    assertTrue(literal.toString(),literal.indexOf("H1")<literal.indexOf("S1")&&literal.indexOf("H1a")<literal.indexOf("S1"));
    // Journey search uses the same bound generation-tagged path.
    var candidates=fixture.repository.searchJourneyStops("hauptbahnhof berlin",50);
    assertTrue(candidates.stops.stream().anyMatch(s->"H1".equals(s.sourceId)));
  }
  @Test public void everyWordIsRequiredAndWildcardsStayLiteral()throws Exception {
    fixture.useMultiWordStationNames();
    assertEquals(List.of(),ids("Berlin Alexanderplatz"));
    assertEquals(List.of(),ids("Hauptbahnhof Nirgendwo"));
    assertEquals(List.of(),ids("%"));assertEquals(List.of(),ids("_ hauptbahnhof"));
    assertEquals(List.of(),ids("s u berlin hauptbahnhof ost west nord sued mitte"));
    assertEquals(List.of(),ids("   "));
  }
  @Test public void singleWordKeepsPreviousMatchesAndOrder()throws Exception {
    assertEquals(List.of("P1","P1a","P1b"),ids("Alexanderplatz"));
    assertEquals(List.of("H1","H1a"),ids("HAUPTBAHNHOF"));
    assertEquals(List.of("S1"),ids("Südkreuz".replace("ü","ue")));
  }
}
