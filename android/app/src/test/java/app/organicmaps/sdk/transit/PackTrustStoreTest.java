package app.organicmaps.sdk.transit;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Actual temporary files, real ephemeral JDK signatures and production generation transactions.
 * Payloads are deliberately opaque bytes here: no SQLite/schema or Android claim. */
public class PackTrustStoreTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  static final Instant NOW=Instant.parse("2026-10-21T00:00:00Z");
  static final String SCOPE="test.dmaps/fixture/fixture-vbb";
  static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
  static String sha(byte[] b) throws Exception {
    StringBuilder out=new StringBuilder();
    for(byte v:MessageDigest.getInstance("SHA-256").digest(b)) out.append(String.format(Locale.ROOT,"%02x",v&255));
    return out.toString();
  }
  static final class MutableClock extends Clock {
    Instant value=NOW;
    public ZoneId getZone(){return ZoneOffset.UTC;}
    public Clock withZone(ZoneId z){if(!z.equals(ZoneOffset.UTC))throw new IllegalArgumentException();return this;}
    public Instant instant(){return value;}
  }
  final class Fixture {
    final File dir=temporary.newFolder();
    final PackEnvelopeTest.Fixture signer=new PackEnvelopeTest.Fixture();
    final MutableClock clock=new MutableClock();
    final byte[] payload=bytes("opaque synthetic timetable bytes");
    final PackTrustStore store=new PackTrustStore(dir,signer.policy,clock);
    Fixture() throws Exception { }
    void put(String name,byte[] value)throws Exception{Files.write(new File(dir,name).toPath(),value);}
    byte[] get(String name)throws Exception{return Files.readAllBytes(new File(dir,name).toPath());}
    String metadata(long sequence)throws Exception {
      String zero=String.join("",Collections.nCopies(64,"0"));
      return PackEnvelopeTest.metadata().replace("\"sequence\":1,","\"sequence\":"+sequence+",")
          .replace("\"database_sha256\":\""+zero+"\"","\"database_sha256\":\""+sha(payload)+"\"")
          .replace("\"database_bytes\":0,","\"database_bytes\":"+payload.length+",");
    }
    byte[] legacy()throws Exception {
      String meta=metadata(1);return bytes(meta.substring("{\"pack\":".length(),meta.indexOf(",\"namespace\":")));
    }
    void legacyPair()throws Exception {put("timetable.sqlite",payload);put("manifest.json",legacy());}
    PackEnvelope.Result staged(long sequence,ImportMode mode)throws Exception {
      byte[] raw=signer.envelope(metadata(sequence),false);
      put("staging.timetable.sqlite",payload);put("staging.manifest.json",raw);
      return PackEnvelope.decode(raw,mode,signer.policy,clock.instant());
    }
    void install(long sequence,ImportMode mode)throws Exception {
      try(PackDirectory.Guard ignored=store.acquire()) {store.load();store.install(staged(sequence,mode));}
    }
    Map<String,byte[]> snapshot()throws Exception {
      Map<String,byte[]> out=new LinkedHashMap<>();
      for(String n:Arrays.asList("timetable.sqlite","manifest.json","trust-state.json","trust.initialized"))
        if(new File(dir,n).isFile())out.put(n,get(n));
      return out;
    }
    void unchanged(Map<String,byte[]> snapshot)throws Exception {
      assertEquals(snapshot.keySet(),snapshot().keySet());
      for(String n:snapshot.keySet())assertArrayEquals(n,snapshot.get(n),get(n));
    }
  }
  interface Action {void run()throws Exception;}
  static IOException refused(Action action)throws Exception {
    try{action.run();fail("operation admitted");return null;}catch(IOException e){assertNull(e.getCause());return e;}
  }
  @Test public void genesisIsDurableAndDoesNotTouchUnrelatedFiles()throws Exception {
    Fixture f=new Fixture();f.put("unrelated-sentinel",bytes("keep"));
    assertNull(f.store.load().active);assertTrue(f.store.load().history.isEmpty());
    Map<String,byte[]> saved=f.snapshot();assertTrue(saved.containsKey("trust-state.json"));assertTrue(saved.containsKey("trust.initialized"));
    assertNull(new PackTrustStore(f.dir,f.signer.policy,f.clock).load().active);f.unchanged(saved);
    assertArrayEquals(bytes("keep"),f.get("unrelated-sentinel"));assertTrue(new File(f.dir,"pack-writer.lock").isFile());
  }
  @Test public void legacyMigrationPreservesExactBytesAndIsAlwaysUntrusted()throws Exception {
    Fixture f=new Fixture();f.legacyPair();byte[] before=f.get("manifest.json");
    PackTrustState s=f.store.load();assertEquals(ImportMode.USER_UNTRUSTED,s.active.mode);assertTrue(s.history.isEmpty());
    assertFalse(f.store.verifiedActive(true).authenticated);assertArrayEquals(before,f.get("manifest.json"));assertArrayEquals(f.payload,f.get("timetable.sqlite"));
  }
  @Test public void envelopeWithoutStateCannotBecomeLegacyOrOfficialAutomatically()throws Exception {
    Fixture f=new Fixture();f.put("manifest.json",f.signer.envelope(f.metadata(7),false));f.put("timetable.sqlite",f.payload);
    Map<String,byte[]> saved=f.snapshot();refused(()->f.store.load());f.unchanged(saved);
  }
  @Test public void missingInitializedStateNeverResetsTheFloorEvenForRemoval()throws Exception {
    Fixture f=new Fixture();f.install(7,ImportMode.OFFICIAL_REQUIRED);assertTrue(new File(f.dir,"trust-state.json").delete());
    Map<String,byte[]> saved=f.snapshot();refused(()->f.store.load());refused(()->f.store.remove());f.unchanged(saved);
  }
  @Test public void corruptStateIsNotReplacedByStagingOrBackup()throws Exception {
    Fixture f=new Fixture();f.install(7,ImportMode.OFFICIAL_REQUIRED);f.put("backup.trust-state.json",f.get("trust-state.json"));f.put("trust-state.json",bytes("{}"));
    Map<String,byte[]> saved=f.snapshot();refused(()->f.store.install(f.staged(8,ImportMode.OFFICIAL_REQUIRED)));f.unchanged(saved);
  }
  @Test public void missingMarkerWithAuthenticatedHistoryIsNotBootstrap()throws Exception {
    Fixture f=new Fixture();f.install(7,ImportMode.OFFICIAL_REQUIRED);assertTrue(new File(f.dir,"trust.initialized").delete());
    Map<String,byte[]> saved=f.snapshot();refused(()->f.store.load());f.unchanged(saved);
  }
  @Test public void corruptMarkerAndNonregularStateFailClosed()throws Exception {
    Fixture f=new Fixture();f.store.load();f.put("trust.initialized",bytes("wrong"));Map<String,byte[]> saved=f.snapshot();
    refused(()->f.store.load());f.unchanged(saved);
    Fixture other=new Fixture();assertTrue(new File(other.dir,"trust-state.json").mkdir());refused(()->other.store.load());assertTrue(new File(other.dir,"trust-state.json").isDirectory());
  }
  @Test public void interruptedBootstrapFinishesOnlyTheValidatedLegacyBaseline()throws Exception {
    Fixture f=new Fixture();f.legacyPair();
    PackTrustStore failing=new PackTrustStore(f.dir,f.signer.policy,f.clock,p->{if(p.equals("bootstrap-state-published"))throw new IOException("injected");});
    refused(()->failing.load());assertTrue(new File(f.dir,"trust-state.json").isFile());assertFalse(new File(f.dir,"trust.initialized").exists());
    assertEquals(ImportMode.USER_UNTRUSTED,f.store.load().active.mode);assertFalse(f.store.verifiedActive(true).authenticated);
  }
  @Test public void interruptedBootstrapCannotBlessChangedPayloadOrNonlegacyState()throws Exception {
    Fixture f=new Fixture();f.legacyPair();PackTrustStore failing=new PackTrustStore(f.dir,f.signer.policy,f.clock,p->{if(p.equals("bootstrap-state-published"))throw new IOException("injected");});
    refused(()->failing.load());byte[] changed=f.payload.clone();changed[0]^=1;f.put("timetable.sqlite",changed);Map<String,byte[]> saved=f.snapshot();refused(()->f.store.load());f.unchanged(saved);
    Fixture other=new Fixture();other.install(1,ImportMode.USER_UNTRUSTED);assertTrue(new File(other.dir,"trust.initialized").delete());refused(()->other.store.load());
  }
  @Test public void initializedStateTooLargeOrPartialLegacyIsUnavailableWithoutDeletion()throws Exception {
    Fixture f=new Fixture();f.store.load();f.put("trust-state.json",new byte[32769]);Map<String,byte[]> saved=f.snapshot();refused(()->f.store.load());f.unchanged(saved);
    Fixture partial=new Fixture();partial.put("timetable.sqlite",partial.payload);Map<String,byte[]> old=partial.snapshot();refused(()->partial.store.load());partial.unchanged(old);
  }
  @Test public void persistedOfficialFloorRejectsRollbackAndEqualSequenceConflict()throws Exception {
    Fixture f=new Fixture();f.install(7,ImportMode.OFFICIAL_REQUIRED);Map<String,byte[]> saved=f.snapshot();
    refused(()->f.store.install(f.staged(6,ImportMode.OFFICIAL_REQUIRED)));f.unchanged(saved);
    byte[] conflict=f.signer.envelope(f.metadata(7).replace("\"synthetic\"","\"changed\""),false);f.put("staging.manifest.json",conflict);
    refused(()->f.store.install(PackEnvelope.decode(conflict,ImportMode.OFFICIAL_REQUIRED,f.signer.policy,NOW)));f.unchanged(saved);
    f.install(7,ImportMode.OFFICIAL_REQUIRED);assertEquals(7,f.store.load().history.get(SCOPE).sequence);assertTrue(f.store.verifiedActive(true).authenticated);
  }
  @Test public void untrustedReplacementAndRemoveRetainOfficialHistoryAcrossRestart()throws Exception {
    Fixture f=new Fixture();f.install(7,ImportMode.OFFICIAL_REQUIRED);f.install(99,ImportMode.USER_UNTRUSTED);
    assertFalse(f.store.verifiedActive(true).authenticated);assertEquals(7,f.store.load().history.get(SCOPE).sequence);
    f.store.remove();PackTrustStore reopened=new PackTrustStore(f.dir,f.signer.policy,f.clock);
    assertNull(reopened.load().active);assertEquals(7,reopened.load().history.get(SCOPE).sequence);assertFalse(new File(f.dir,"timetable.sqlite").exists());assertFalse(new File(f.dir,"manifest.json").exists());
    Map<String,byte[]> saved=f.snapshot();reopened.remove();f.unchanged(saved);refused(()->f.store.install(f.staged(6,ImportMode.OFFICIAL_REQUIRED)));f.unchanged(saved);
  }
  @Test public void reopenChecksPayloadAndExactEnvelopeBinding()throws Exception {
    Fixture f=new Fixture();f.install(7,ImportMode.OFFICIAL_REQUIRED);byte[] changed=f.payload.clone();changed[0]^=1;f.put("timetable.sqlite",changed);refused(()->f.store.verifiedActive(true));
    f.put("timetable.sqlite",f.payload);f.put("manifest.json",bytes(" "+new String(f.get("manifest.json"),StandardCharsets.UTF_8)));refused(()->f.store.verifiedActive(false));
  }
  @Test public void currentPolicyNeverFallsBackToUntrustedButRemovalKeepsHistory()throws Exception {
    Fixture f=new Fixture();f.install(7,ImportMode.OFFICIAL_REQUIRED);PackTrustStore noRoot=new PackTrustStore(f.dir,PackTrustPolicy.production(),f.clock);
    refused(()->noRoot.verifiedActive(true));assertEquals(ImportMode.OFFICIAL_REQUIRED,noRoot.load().active.mode);noRoot.remove();assertEquals(7,noRoot.load().history.get(SCOPE).sequence);
  }
  @Test public void authorizationExpiryAtPublicationRollsBackTheWholeGeneration()throws Exception {
    Fixture f=new Fixture();f.install(7,ImportMode.OFFICIAL_REQUIRED);Map<String,byte[]> saved=f.snapshot();
    PackTrustStore delayed=new PackTrustStore(f.dir,f.signer.policy,f.clock,p->{if(p.equals("before-commit"))f.clock.value=Instant.parse("2027-01-01T00:00:00Z");});
    PackEnvelope.Result next=f.staged(8,ImportMode.OFFICIAL_REQUIRED);IOException ex=refused(()->delayed.install(next));assertFalse(ex instanceof PackCommitException);f.unchanged(saved);
    assertEquals(7,f.store.load().history.get(SCOPE).sequence);f.clock.value=NOW;assertTrue(f.store.verifiedActive(true).authenticated);
  }
  @Test public void failuresAfterImportOrRemoveCommitAreTruthfullyTyped()throws Exception {
    Fixture f=new Fixture();f.store.load();PackTrustStore failing=new PackTrustStore(f.dir,f.signer.policy,f.clock,p->{if(p.startsWith("store-")&&p.endsWith("committed"))throw new IOException("injected");});
    IOException imported=refused(()->failing.install(f.staged(7,ImportMode.OFFICIAL_REQUIRED)));assertTrue(imported instanceof PackCommitException);assertEquals(PackCommitException.Operation.IMPORT,((PackCommitException)imported).operation);
    assertTrue(f.store.verifiedActive(true).authenticated);IOException removed=refused(()->failing.remove());assertTrue(removed instanceof PackCommitException);assertEquals(PackCommitException.Operation.REMOVE,((PackCommitException)removed).operation);assertNull(f.store.load().active);assertEquals(7,f.store.load().history.get(SCOPE).sequence);
  }
  @Test public void legacyRecoveryJournalCannotDeleteAnInitializedGeneration()throws Exception {
    Fixture f=new Fixture();f.install(7,ImportMode.OFFICIAL_REQUIRED);f.put("activation.pending",bytes("empty\n"));
    Map<String,byte[]> saved=f.snapshot();refused(()->f.store.load());f.unchanged(saved);assertTrue(new File(f.dir,"activation.pending").isFile());
  }

  @Test public void independentInstancesSerializeStagingThroughPublication()throws Exception {
    Fixture f=new Fixture();f.store.load();PackTrustStore second=new PackTrustStore(f.dir,f.signer.policy,f.clock);ExecutorService executor=Executors.newSingleThreadExecutor();CountDownLatch attempting=new CountDownLatch(1);Future<?> update=null;
    try {
      try(PackDirectory.Guard ignored=f.store.acquire()) {
        update=executor.submit(()->{attempting.countDown();try(PackDirectory.Guard g=second.acquire()){second.install(f.staged(8,ImportMode.OFFICIAL_REQUIRED));}catch(Exception e){throw new RuntimeException(e);}});
        assertTrue(attempting.await(5,TimeUnit.SECONDS));f.store.install(f.staged(7,ImportMode.OFFICIAL_REQUIRED));assertFalse(update.isDone());
      }
      update.get(10,TimeUnit.SECONDS);assertEquals(8,f.store.load().history.get(SCOPE).sequence);assertEquals(8,f.store.verifiedActive(true).sequence);
    } finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
  }
}
