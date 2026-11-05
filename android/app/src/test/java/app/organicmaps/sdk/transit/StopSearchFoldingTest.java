package app.organicmaps.sdk.transit;

import static org.junit.Assert.*;

import android.app.Application;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

/** A rider must find a German station however they type its letters. Real import and native-host SQLite; synthetic names. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class StopSearchFoldingTest {
  @Rule public TemporaryFolder temporary=new TemporaryFolder();
  private JourneyUiFixture fixture;private TimetableRepository repo;
  @Before public void setup()throws Exception {
    fixture=new JourneyUiFixture(RuntimeEnvironment.getApplication(),temporary.newFolder(),Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"),ZoneOffset.UTC));
    fixture.useGermanStationNames();repo=fixture.repository;
  }
  @After public void close(){repo.close();}
  private List<String> ids(String query)throws Exception {return repo.searchStops(query,20).stream().map(s->s.sourceId).collect(Collectors.toList());}
  private void finds(String query,String id)throws Exception {assertTrue(query+" -> "+ids(query),ids(query).contains(id));}

  @Test public void theSpellingOnTheStationSignFindsIt()throws Exception {finds("Südkreuz","S1");finds("Görlitzer Bahnhof","H1");}
  @Test public void lettersWithoutMarksFindIt()throws Exception {finds("Sudkreuz","S1");finds("Gorlitzer","H1");finds("barenplatz","P1");}
  @Test public void germanDigraphsFindIt()throws Exception {finds("Suedkreuz","S1");finds("Goerlitzer Bahnhof","H1");finds("Baerenplatz","P1");}
  @Test public void upperCaseUmlautsFindIt()throws Exception {finds("SÜDKREUZ","S1");finds("GÖRLITZER","H1");}
  @Test public void sharpSAndDoubleSFindEachOther()throws Exception {finds("Straße","P1");finds("strasse","P1");}
  @Test public void aFoldedSearchStaysWordBased()throws Exception {finds("Bahnhof Gorlitzer","H1");assertEquals(Collections.emptyList(),ids("Gorlitzer Nowhere"));}
  @Test public void plainNamesAreUnchanged()throws Exception {
    assertEquals(Collections.emptyList(),ids("xyzzy"));assertEquals(Collections.emptyList(),ids("   "));
  }
  @Test public void likeMetacharactersStayLiteral()throws Exception {assertEquals(Collections.emptyList(),ids("s%d"));assertEquals(Collections.emptyList(),ids("g_rlitzer"));}
  @Test public void journeySearchSharesTheFix()throws Exception {
    assertTrue(repo.searchJourneyStops("Südkreuz",5).stops.stream().anyMatch(s->s.sourceId.equals("S1")));
  }
}
