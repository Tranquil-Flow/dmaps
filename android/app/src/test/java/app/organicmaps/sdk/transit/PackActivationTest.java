package app.organicmaps.sdk.transit;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Real filesystem transaction checks, not Android/power-loss qualification. */
public class PackActivationTest
{
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private File dir() throws IOException { return temporary.newFolder(); }
  private void put(File dir, String name, String value) throws IOException
  { Files.write(new File(dir, name).toPath(), value.getBytes(StandardCharsets.US_ASCII)); }
  private String read(File dir, String name) throws IOException
  { return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.US_ASCII); }
  private void old(File dir) throws IOException
  { put(dir, "timetable.sqlite", "old-db"); put(dir, "manifest.json", "old-manifest"); }
  private void staged(File dir) throws IOException
  { put(dir, "staging.timetable.sqlite", "new-db"); put(dir, "staging.manifest.json", "new-manifest"); }
  private void move(File dir, String a, String b) throws IOException
  { Files.move(new File(dir, a).toPath(), new File(dir, b).toPath()); }

  @Test public void firstAndReplacementCommitsKeepNewPair() throws Exception
  {
    File dir = dir(); staged(dir); new PackActivation(dir).activate();
    assertEquals("new-db", read(dir, "timetable.sqlite"));
    put(dir, "staging.timetable.sqlite", "third-db"); put(dir, "staging.manifest.json", "third-manifest");
    new PackActivation(dir).activate(); new PackActivation(dir).recover();
    assertEquals("third-db", read(dir, "timetable.sqlite"));
    assertEquals("third-manifest", read(dir, "manifest.json"));
    assertFalse(new File(dir, "activation.pending").exists());
  }
  @Test public void everyPrecommitBoundaryRollsBackAndRecoveryIsIdempotent() throws Exception
  {
    for (int phase = 0; phase <= 4; phase++)
    {
      File dir = dir(); old(dir); staged(dir); put(dir, "activation.pending", "previous\n");
      if (phase >= 1) move(dir,"timetable.sqlite","backup.timetable.sqlite");
      if (phase >= 2) move(dir,"manifest.json","backup.manifest.json");
      if (phase >= 3) move(dir,"staging.manifest.json","manifest.json");
      if (phase >= 4) move(dir,"staging.timetable.sqlite","timetable.sqlite");
      new PackActivation(dir).recover(); new PackActivation(dir).recover();
      assertEquals("old-db",read(dir,"timetable.sqlite"));
      assertEquals("old-manifest",read(dir,"manifest.json"));
    }
  }
  @Test public void interruptedFirstInstallReturnsToEmpty() throws Exception
  {
    File dir = dir(); staged(dir); put(dir,"activation.pending","empty\n");
    move(dir,"staging.manifest.json","manifest.json");
    new PackActivation(dir).recover();
    assertFalse(new File(dir,"manifest.json").exists());
    assertFalse(new File(dir,"timetable.sqlite").exists());
  }
  @Test public void interruptedRecoveryCanFinishOtherHalf() throws Exception
  {
    File dir = dir(); old(dir); put(dir,"activation.pending","previous\n");
    put(dir,"manifest.json","new-manifest");put(dir,"backup.manifest.json","old-manifest");
    new PackActivation(dir).recover();
    assertEquals("old-db",read(dir,"timetable.sqlite"));assertEquals("old-manifest",read(dir,"manifest.json"));
  }
  @Test public void renameFailureRestoresPreviousAndRetainsNoMixedPair() throws Exception
  {
    File dir = dir(); old(dir); staged(dir);
    // A nonempty directory in the target backup slot refuses cleanup, before active mutation.
    File blocked=new File(dir,"backup.timetable.sqlite");assertTrue(blocked.mkdir());
    put(blocked,"child","refuse");
    try { new PackActivation(dir).activate(); fail("must refuse"); } catch (IOException expected) { }
    assertEquals("old-db",read(dir,"timetable.sqlite"));assertEquals("old-manifest",read(dir,"manifest.json"));
  }
  @Test public void invalidJournalFailsClosedWithoutDeletingAuthority() throws Exception
  {
    File dir = dir(); old(dir); put(dir,"activation.pending","garbage");
    try { new PackActivation(dir).recover(); fail("must refuse"); } catch (IOException expected) { }
    assertEquals("old-db",read(dir,"timetable.sqlite"));assertTrue(new File(dir,"activation.pending").exists());
  }
  @Test public void missingStageCannotRemovePrevious() throws Exception
  {
    File dir = dir(); old(dir);
    try { new PackActivation(dir).activate(); fail("must refuse"); } catch (IOException expected) { }
    assertEquals("old-manifest",read(dir,"manifest.json"));
  }
  @Test public void uncommittedPreparationCannotMutateActive() throws Exception
  {
    File dir = dir(); old(dir); put(dir,"activation.preparing","partial");
    new PackActivation(dir).recover();assertEquals("old-db",read(dir,"timetable.sqlite"));
    assertFalse(new File(dir,"activation.preparing").exists());
  }
}
