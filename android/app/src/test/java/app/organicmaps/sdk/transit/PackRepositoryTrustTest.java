package app.organicmaps.sdk.transit;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

/** Real archive -> importer -> native host SQLite/schema -> generation -> repository queries.
 * Robolectric/framework hosting and ephemeral JDK signature root; not an Android device/JNI proof. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class PackRepositoryTrustTest {
  @Rule public TemporaryFolder temporary=new TemporaryFolder();
  private Context context;
  private File root;
  private byte[] payload,legacy;
  private PackEnvelopeTest.Fixture signer;
  private final Clock clock=Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"),ZoneOffset.UTC);
  @Before public void setup()throws Exception {
    root=temporary.newFolder();context=new ContextWrapper(RuntimeEnvironment.getApplication()) {
      @Override public Context getApplicationContext(){return this;}
      @Override public File getFilesDir(){return root;}
    };
    signer=new PackEnvelopeTest.Fixture();
    try(ZipFile z=new ZipFile("src/androidTest/assets/transit/synthetic.ctpack")) {
      try(InputStream in=z.getInputStream(z.getEntry("manifest.json"))){legacy=in.readAllBytes();}
      try(InputStream in=z.getInputStream(z.getEntry("timetable.sqlite"))){payload=in.readAllBytes();}
    }
  }
  private File file(String n){return new File(root,"transit_pack/"+n);}
  private TimetableRepository repo(){return new TimetableRepository(context,signer.policy,clock,p->{});}
  private byte[] envelope(long sequence)throws Exception {
    String body=PackEnvelopeTest.metadata();
    body=body.substring(0,body.indexOf("{\"format_version\""))+new String(legacy,StandardCharsets.UTF_8)+body.substring(body.indexOf(",\"namespace\":"));
    return signer.envelope(body.replace("\"sequence\":1,","\"sequence\":"+sequence+","),false);
  }
  private InputStream archive(byte[] manifest)throws Exception {
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    try(ZipOutputStream out=new ZipOutputStream(bytes)){
      out.putNextEntry(new ZipEntry("manifest.json"));out.write(manifest);out.closeEntry();
      out.putNextEntry(new ZipEntry("timetable.sqlite"));out.write(payload);out.closeEntry();
    }
    return new ByteArrayInputStream(bytes.toByteArray());
  }
  private void query(TimetableRepository repo)throws Exception {
    assertFalse(repo.searchStops("Hauptbahnhof",20).isEmpty());assertNotNull(repo.info());
  }
  private interface Action {void run()throws Exception;}
  private IOException refused(Action action)throws Exception {
    try{action.run();fail("operation admitted");return null;}catch(IOException expected){return expected;}
  }
  @Test public void legacyNativeSqliteCanaryAndExplicitOfficialModeRefusal()throws Exception {
    try(TimetableRepository r=repo()) {
      r.importPack(archive(legacy));query(r);byte[] before=Files.readAllBytes(file("timetable.sqlite").toPath());
      refused(()->r.importPack(archive(legacy),ImportMode.OFFICIAL_REQUIRED));assertArrayEquals(before,Files.readAllBytes(file("timetable.sqlite").toPath()));query(r);
    }
  }
  @Test public void officialArchiveQueriesAndPersistsFloorThroughReopenRemoveAndRollback()throws Exception {
    try(TimetableRepository r=repo()){r.importPack(archive(envelope(7)),ImportMode.OFFICIAL_REQUIRED);query(r);}
    try(TimetableRepository reopened=repo()){
      query(reopened);assertEquals(7,PackTrustState.parse(Files.readAllBytes(file("trust-state.json").toPath())).history.get("test.dmaps/fixture/fixture-vbb").sequence);
      byte[] old=Files.readAllBytes(file("trust-state.json").toPath());refused(()->reopened.importPack(archive(envelope(6)),ImportMode.OFFICIAL_REQUIRED));assertArrayEquals(old,Files.readAllBytes(file("trust-state.json").toPath()));query(reopened);
      reopened.removePack();assertFalse(reopened.hasPack());assertEquals(7,PackTrustState.parse(Files.readAllBytes(file("trust-state.json").toPath())).history.get("test.dmaps/fixture/fixture-vbb").sequence);
      refused(()->reopened.importPack(archive(envelope(6)),ImportMode.OFFICIAL_REQUIRED));assertFalse(reopened.hasPack());
    }
  }
  @Test public void missingHistoryIsUnavailableNotFreshEvenWithCachedConnection()throws Exception {
    try(TimetableRepository r=repo()){
      r.importPack(archive(legacy));query(r);assertTrue(file("trust-state.json").delete());refused(()->r.searchStops("Hauptbahnhof",20));assertFalse(r.hasPack());refused(()->r.removePack());assertTrue(file("timetable.sqlite").isFile());
    }
  }
  @Test public void twoRepositoriesRefreshCachedGenerationAndObserveRemoval()throws Exception {
    try(TimetableRepository first=repo();TimetableRepository second=repo()) {
      first.importPack(archive(legacy));query(first);
      JSONObject changed=new JSONObject(new String(legacy,StandardCharsets.UTF_8));changed.put("publisher","changed synthetic publisher");
      second.importPack(archive(changed.toString().getBytes(StandardCharsets.UTF_8)));assertEquals("changed synthetic publisher",first.info().publisher);
      second.removePack();refused(()->first.searchStops("Hauptbahnhof",20));
    }
  }
  @Test public void badSignatureNeverDemotesAndExplicitUserReplacementKeepsTheFloor()throws Exception {
    try(TimetableRepository r=repo()) {
      r.importPack(archive(envelope(7)),ImportMode.OFFICIAL_REQUIRED);
      assertTrue(r.info().authenticated);assertEquals(7,r.info().officialSequence);
      JSONObject bad=new JSONObject(new String(envelope(99),StandardCharsets.UTF_8));
      byte[] signature=Base64.getDecoder().decode(bad.getString("signature"));signature[0]^=1;bad.put("signature",Base64.getEncoder().encodeToString(signature));
      byte[] raw=bad.toString().getBytes(StandardCharsets.UTF_8),before=Files.readAllBytes(file("trust-state.json").toPath());
      refused(()->r.importPack(archive(raw),ImportMode.OFFICIAL_REQUIRED));assertArrayEquals(before,Files.readAllBytes(file("trust-state.json").toPath()));query(r);assertTrue(r.info().authenticated);
      r.importPack(archive(raw),ImportMode.USER_UNTRUSTED);assertFalse(r.info().authenticated);assertEquals(ImportMode.USER_UNTRUSTED,r.info().importMode);assertEquals(0,r.info().officialSequence);
      assertEquals(7,PackTrustState.parse(Files.readAllBytes(file("trust-state.json").toPath())).history.get("test.dmaps/fixture/fixture-vbb").sequence);
    }
  }
  @Test public void schemaFailureAfterAuthenticatingCandidateCannotAdvanceHistory()throws Exception {
    try(TimetableRepository r=repo()) {
      r.importPack(archive(envelope(7)),ImportMode.OFFICIAL_REQUIRED);byte[] oldState=Files.readAllBytes(file("trust-state.json").toPath()),oldDb=Files.readAllBytes(file("timetable.sqlite").toPath());
      byte[] from="stop_headsign".getBytes(StandardCharsets.UTF_8);boolean replaced=false;
      outer:for(int i=0;i<=payload.length-from.length;i++) {
        for(int j=0;j<from.length;j++)if(payload[i+j]!=from[j])continue outer;
        payload[i]='x';replaced=true;break;
      }
      assertTrue(replaced);JSONObject updated=new JSONObject(new String(legacy,StandardCharsets.UTF_8));updated.put("database_sha256",PackTrustStoreTest.sha(payload));legacy=updated.toString().getBytes(StandardCharsets.UTF_8);
      IOException error=refused(()->r.importPack(archive(envelope(8)),ImportMode.OFFICIAL_REQUIRED));assertFalse(error instanceof PackCommitException);assertTrue(error.getMessage().contains("columns"));
      assertArrayEquals(oldState,Files.readAllBytes(file("trust-state.json").toPath()));assertArrayEquals(oldDb,Files.readAllBytes(file("timetable.sqlite").toPath()));query(r);
    }
  }
  @Test public void expiredAuthorityIsRefusedEvenWhenConnectionIsCached()throws Exception {
    PackTrustStoreTest.MutableClock moving=new PackTrustStoreTest.MutableClock();
    try(TimetableRepository r=new TimetableRepository(context,signer.policy,moving,p->{})) {
      r.importPack(archive(envelope(7)),ImportMode.OFFICIAL_REQUIRED);query(r);moving.value=Instant.parse("2027-01-01T00:00:00Z");
      refused(()->r.searchStops("Hauptbahnhof",20));refused(()->r.info());assertTrue(r.hasPack());r.removePack();assertFalse(r.hasPack());
    }
  }
  @Test public void productionPolicyCannotUseFixtureAuthorityOrSilentlyDemoteExistingPack()throws Exception {
    try(TimetableRepository r=repo()){r.importPack(archive(envelope(7)),ImportMode.OFFICIAL_REQUIRED);}
    try(TimetableRepository r=new TimetableRepository(context)) {assertTrue(r.hasPack());refused(()->r.searchStops("Hauptbahnhof",20));r.removePack();assertFalse(r.hasPack());}
    assertEquals(7,PackTrustState.parse(Files.readAllBytes(file("trust-state.json").toPath())).history.get("test.dmaps/fixture/fixture-vbb").sequence);
  }
  @Test public void postCommitFailureSurvivesRepositoryExceptionTranslation()throws Exception {
    try(TimetableRepository r=new TimetableRepository(context,signer.policy,clock,p->{if(p.equals("store-import-committed"))throw new IOException("injected");})) {
      IOException ex=refused(()->r.importPack(archive(envelope(7)),ImportMode.OFFICIAL_REQUIRED));assertTrue(ex instanceof PackCommitException);assertEquals(PackCommitException.Operation.IMPORT,((PackCommitException)ex).operation);
    }
    try(TimetableRepository r=repo()){query(r);assertTrue(r.info().authenticated);assertEquals(7,r.info().officialSequence);}
  }

  @Test public void validSqliteSubstitutionIsRefusedOnReopen()throws Exception {
    try(TimetableRepository r=repo()){r.importPack(archive(legacy));query(r);}
    // Same-sized valid SQLite: replace a recorded text value directly, then prove integrity remains ok.
    byte[] changed=payload.clone(),from="Hauptbahnhof".getBytes(StandardCharsets.UTF_8),to="Xauptbahnhof".getBytes(StandardCharsets.UTF_8);boolean replaced=false;
    outer:for(int i=0;i<=changed.length-from.length;i++){
      for(int j=0;j<from.length;j++)if(changed[i+j]!=from[j])continue outer;
      System.arraycopy(to,0,changed,i,to.length);replaced=true;break;
    }
    assertTrue(replaced);Files.write(file("timetable.sqlite").toPath(),changed);
    android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(file("timetable.sqlite").getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
    try(android.database.Cursor c=db.rawQuery("PRAGMA integrity_check",null)){assertTrue(c.moveToFirst());assertEquals("ok",c.getString(0));}finally{db.close();}
    try(TimetableRepository r=repo()){refused(()->r.searchStops("Hauptbahnhof",20));}
  }
}
