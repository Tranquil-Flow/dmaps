package app.organicmaps.help;

import static org.junit.Assert.*;

import android.app.Application;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Static-artifact contract on the application's real assets/copyright.html (the file the About > Copyright screen
 * loads), not UI, speech or browser acceptance. It reads the merged application asset, so the same test runs unchanged
 * against a sealed APK's bytes and the corrected live bytes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class CopyrightAttributionTest
{
  private static final String OSM_HREF = "https://www.openstreetmap.org/";
  private static final String ORGANIC_MAPS_LINK = "<a href=\"https://organicmaps.app\">Organic Maps Project</a>";
  private static final Pattern PARAGRAPH = Pattern.compile("<p lang=\"([^\"]+)\">(.*?)</p>", Pattern.DOTALL);
  private static final Pattern ANCHOR = Pattern.compile("<a href=\"([^\"]*)\">([^<]*)</a>");

  private static String copyrightHtml() throws IOException
  {
    try (InputStream in = RuntimeEnvironment.getApplication().getAssets().open("copyright.html"))
    {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      for (int n; (n = in.read(buf)) > 0;)
        out.write(buf, 0, n);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  /** The one paragraph in {@code lang} whose text contains {@code marker}. */
  private static String paragraph(String html, String lang, String marker)
  {
    List<String> hits = new ArrayList<>();
    Matcher m = PARAGRAPH.matcher(html);
    while (m.find())
      if (m.group(1).equals(lang) && m.group(2).contains(marker))
        hits.add(m.group(2));
    assertEquals("exactly one <p lang=\"" + lang + "\"> map-data paragraph containing " + marker, 1, hits.size());
    return hits.get(0);
  }

  /** hrefs of the anchors whose text is OpenStreetMap, in a paragraph. */
  private static List<String> osmHrefs(String paragraph)
  {
    List<String> hrefs = new ArrayList<>();
    Matcher m = ANCHOR.matcher(paragraph);
    while (m.find())
      if (m.group(2).equals("OpenStreetMap"))
        hrefs.add(m.group(1));
    return hrefs;
  }

  @Test public void germanOsmAttributionLinksToTheExactOpenStreetMapHost() throws IOException
  {
    List<String> hrefs = osmHrefs(paragraph(copyrightHtml(), "de", "Kartendaten &copy;"));
    assertEquals("German OSM attribution href", List.of(OSM_HREF), hrefs);
  }

  @Test public void englishOsmAttributionStillLinksToTheExactOpenStreetMapHost() throws IOException
  {
    List<String> hrefs = osmHrefs(paragraph(copyrightHtml(), "en", "Map data &copy;"));
    assertEquals("English OSM attribution href", List.of(OSM_HREF), hrefs);
  }

  @Test public void organicMapsAttributionLinkIsRetained() throws IOException
  {
    String html = copyrightHtml();
    int at = html.indexOf(ORGANIC_MAPS_LINK);
    assertTrue("Organic Maps Project link retained", at >= 0);
    assertEquals("exactly one Organic Maps Project link", -1, html.indexOf(ORGANIC_MAPS_LINK, at + 1));
  }
}
