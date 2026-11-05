package app.organicmaps.sdk.transit;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** Canonical host-suite integration; the independently replayed leaf has broader 206-case controls. */
public class StrictPackJsonTest
{
  private static Map<String,Object> parse(String value) throws IOException
  { return StrictPackJson.object(value.getBytes(StandardCharsets.UTF_8)); }
  private static void bad(String value) throws IOException
  {
    try { parse(value); fail("invalid wire input admitted"); }
    catch (IOException expected) { assertNull(expected.getCause()); }
  }

  @Test public void exactTypesAndDecodedStrings() throws Exception
  {
    Map<String,Object> result=parse("{\"n\":1,\"b\":true,\"z\":null,\"s\":\"ä\\uD83D\\uDE00\"}");
    assertEquals(Long.valueOf(1),result.get("n"));
    assertEquals(Boolean.TRUE,result.get("b"));
    assertTrue(result.containsKey("z")); assertNull(result.get("z"));
    assertEquals("ä😀",result.get("s"));
  }
  @Test public void duplicateDecodedNamesReject() throws Exception
  {
    bad("{\"a\":0,\"a\":1}"); bad("{\"a\":null,\"\\u0061\":2}");
    bad("{\"x\":{\"a\":0,\"a\":1}}");
  }
  @Test public void malformedUtf8RejectsBeforeJson() throws Exception
  {
    try { StrictPackJson.object(new byte[]{(byte)0xff}); fail("bad UTF-8 admitted"); }
    catch (IOException expected) { assertEquals("Invalid UTF-8",expected.getMessage()); }
  }
  @Test public void longOnlyNumberGrammar() throws Exception
  {
    for(String value:new String[]{"1.0","1e0","01","+1","9223372036854775808"})
      bad("{\"n\":"+value+"}");
    assertEquals(Long.MIN_VALUE,parse("{\"n\":-9223372036854775808}").get("n"));
  }
  @SuppressWarnings("unchecked")
  @Test public void nestedCollectionsCannotBeChanged() throws Exception
  {
    Map<String,Object> result=parse("{\"a\":[{\"b\":0}]}");
    try { result.put("x",0L); fail("mutable root"); }
    catch(UnsupportedOperationException expected) { }
    Map<String,Object> child=(Map<String,Object>)((List<?>)result.get("a")).get(0);
    try { child.put("x",0L); fail("mutable child"); }
    catch(UnsupportedOperationException expected) { }
  }
  @Test public void depthIsBounded() throws Exception
  {
    String value="0";
    for(int i=0;i<7;i++) value="["+value+"]";
    parse("{\"a\":"+value+"}");
    bad("{\"a\":["+value+"]}");
  }
  @Test public void byteBudgetPrecedesDecoding() throws Exception
  {
    byte[] over=new byte[32769]; java.util.Arrays.fill(over,(byte)0xff);
    try { StrictPackJson.object(over); fail("over-budget input admitted"); }
    catch(IOException expected) { assertEquals("Input limit",expected.getMessage()); }
  }
  @Test public void rootAndTrailingGrammarAreStrict() throws Exception
  {
    for(String value:new String[]{"[]","{}{}","{}x","{\"a\":0,}","{/*comment*/}","{\"s\":\"\\uD800\"}"}) bad(value);
  }
}
