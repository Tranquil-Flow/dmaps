package app.organicmaps.sdk.transit;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.security.*;
import java.util.*;
import static org.junit.Assert.*;

/** Real signed-metadata controls for the local immutable model, not disk/Android acceptance. */
public class PackTrustStateTest {
  private static final String SCOPE="test.dmaps/fixture/fixture-vbb";
  private static final Instant NOW=Instant.parse("2026-10-21T00:00:00Z");
  private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
  private static String metadata(long sequence){return PackEnvelopeTest.metadata().replace("\"sequence\":1","\"sequence\":"+sequence);}
  private static PackEnvelope.Result envelope(PackEnvelopeTest.Fixture f,long sequence,ImportMode mode) throws Exception {return PackEnvelope.decode(f.envelope(metadata(sequence),false),mode,f.policy,NOW);}
  private interface IOAction {void run() throws Exception;}
  private static void refused(IOAction action) throws Exception {try{action.run();fail("unsafe state admitted");}catch(IOException expected){assertNull(expected.getCause());assertTrue(expected.getMessage().length()<160);}}

  @Test public void emptyStateRoundTripsWithoutActivePack() throws Exception {
    PackTrustState s=PackTrustState.parse(PackTrustState.empty().encode());assertNull(s.active);assertTrue(s.history.isEmpty());
  }
  @Test public void verifiedMetadataEstablishesFloorAndBoundActiveIdentity() throws Exception {
    PackEnvelopeTest.Fixture f=new PackEnvelopeTest.Fixture();PackEnvelope.Result r=envelope(f,7,ImportMode.OFFICIAL_REQUIRED);
    PackTrustState s=PackTrustState.empty().withPack(r);s.requireActive(r);
    assertEquals(7,s.history.get(SCOPE).sequence);assertEquals(r.signedMetadataSha256,s.history.get(SCOPE).metadataSha256);assertEquals(r.envelopeSha256,s.active.envelopeSha256);
    PackTrustState.parse(s.encode()).requireActive(r);
  }
  @Test public void downgradeAndEqualSequenceConflictCannotMutatePreviousState() throws Exception {
    PackEnvelopeTest.Fixture f=new PackEnvelopeTest.Fixture();PackTrustState s=PackTrustState.empty().withPack(envelope(f,7,ImportMode.OFFICIAL_REQUIRED));byte[] before=s.encode();
    refused(()->s.withPack(envelope(f,6,ImportMode.OFFICIAL_REQUIRED)));
    PackEnvelope.Result conflict=PackEnvelope.decode(f.envelope(metadata(7).replace("synthetic","changed"),false),ImportMode.OFFICIAL_REQUIRED,f.policy,NOW);
    refused(()->s.withPack(conflict));assertArrayEquals(before,s.encode());
  }
  @Test public void equalExactMetadataReinstallCanChangeOnlyOuterEncoding() throws Exception {
    PackEnvelopeTest.Fixture f=new PackEnvelopeTest.Fixture();PackEnvelope.Result first=envelope(f,7,ImportMode.OFFICIAL_REQUIRED);
    PackEnvelope.Result outer=PackEnvelope.decode(bytes(" "+new String(first.rawEnvelope(),StandardCharsets.UTF_8)),ImportMode.OFFICIAL_REQUIRED,f.policy,NOW);
    assertNotEquals(first.envelopeSha256,outer.envelopeSha256);assertEquals(first.signedMetadataSha256,outer.signedMetadataSha256);
    PackTrustState s=PackTrustState.empty().withPack(first).withPack(outer);assertEquals(7,s.history.get(SCOPE).sequence);s.requireActive(outer);refused(()->s.requireActive(first));
  }
  @Test public void signedWhitespaceChangeAtEqualSequenceIsAConflict() throws Exception {
    PackEnvelopeTest.Fixture f=new PackEnvelopeTest.Fixture();PackTrustState s=PackTrustState.empty().withPack(envelope(f,7,ImportMode.OFFICIAL_REQUIRED));
    PackEnvelope.Result alternate=PackEnvelope.decode(f.envelope(" "+metadata(7),false),ImportMode.OFFICIAL_REQUIRED,f.policy,NOW);refused(()->s.withPack(alternate));
  }
  @Test public void removeAndUntrustedReplacementNeverEraseOrAdvanceFloor() throws Exception {
    PackEnvelopeTest.Fixture f=new PackEnvelopeTest.Fixture();PackEnvelope.Result official=envelope(f,7,ImportMode.OFFICIAL_REQUIRED),user=envelope(f,99,ImportMode.USER_UNTRUSTED);
    PackTrustState trusted=PackTrustState.empty().withPack(official),removed=trusted.withoutPack();assertNull(removed.active);assertEquals(7,removed.history.get(SCOPE).sequence);refused(()->removed.requireActive(official));
    PackTrustState untrusted=removed.withPack(user);assertEquals(ImportMode.USER_UNTRUSTED,untrusted.active.mode);assertEquals(7,untrusted.history.get(SCOPE).sequence);untrusted.requireActive(user);
    PackTrustState restarted=PackTrustState.parse(untrusted.encode());refused(()->restarted.withPack(envelope(f,6,ImportMode.OFFICIAL_REQUIRED)));assertEquals(7,restarted.history.get(SCOPE).sequence);
    assertTrue(PackTrustState.empty().withPack(user).history.isEmpty());
  }
  @Test public void activeOfficialMustMatchHighestCommittedFloor() throws Exception {
    PackEnvelopeTest.Fixture f=new PackEnvelopeTest.Fixture();PackEnvelope.Result old=envelope(f,7,ImportMode.OFFICIAL_REQUIRED),newer=envelope(f,8,ImportMode.OFFICIAL_REQUIRED);
    PackTrustState s=PackTrustState.empty().withPack(old).withPack(newer);s.requireActive(newer);refused(()->s.requireActive(old));
    String tampered=new String(s.encode(),StandardCharsets.UTF_8).replace("\"sequence\":8","\"sequence\":9");PackTrustState altered=PackTrustState.parse(bytes(tampered));refused(()->altered.requireActive(newer));
  }
  @Test public void activeModePayloadAndEnvelopeClaimsAreAllBound() throws Exception {
    PackEnvelopeTest.Fixture f=new PackEnvelopeTest.Fixture();PackEnvelope.Result r=envelope(f,7,ImportMode.OFFICIAL_REQUIRED);PackTrustState s=PackTrustState.empty().withPack(r);String original=new String(s.encode(),StandardCharsets.UTF_8);
    String differentEnvelope=(r.envelopeSha256.charAt(0)=='0'?"1":"0")+r.envelopeSha256.substring(1);
    for(String changed:new String[]{original.replace("OFFICIAL_REQUIRED","USER_UNTRUSTED"),original.replace("\"database_bytes\":0","\"database_bytes\":1"),original.replace("\"database_sha256\":\"0","\"database_sha256\":\"1"),original.replace(r.envelopeSha256,differentEnvelope)}){
      assertNotEquals("control did not alter its field",original,changed);PackTrustState parsed=PackTrustState.parse(bytes(changed));refused(()->parsed.requireActive(r));
    }
  }
  @Test public void historyIsImmutableAndLongMaximumDoesNotWrap() throws Exception {
    PackEnvelopeTest.Fixture f=new PackEnvelopeTest.Fixture();PackTrustState s=PackTrustState.empty().withPack(envelope(f,Long.MAX_VALUE,ImportMode.OFFICIAL_REQUIRED));
    assertEquals(Long.MAX_VALUE,PackTrustState.parse(s.encode()).history.get(SCOPE).sequence);
    refused(()->s.withPack(envelope(f,1,ImportMode.OFFICIAL_REQUIRED)));
    try{s.history.clear();fail("mutable history");}catch(UnsupportedOperationException expected){}
  }
  @Test public void malformedOrCoercedStateNeverBecomesFreshEmptyState() throws Exception {
    PackEnvelopeTest.Fixture f=new PackEnvelopeTest.Fixture();String good=new String(PackTrustState.empty().withPack(envelope(f,7,ImportMode.OFFICIAL_REQUIRED)).encode(),StandardCharsets.UTF_8);
    for(String bad:new String[]{"{}",good.replace("\"state_version\":1","\"state_version\":\"1\""),good.replace("\"sequence\":7","\"sequence\":\"7\""),good.replace("\"sequence\":7","\"sequence\":0"),good.replace("test.dmaps/fixture/fixture-vbb","../fixture/fixture-vbb"),good.replace("\"history\":{","\"history\":null,\"extra\":{ "),"{\"state_version\":1,"+good.substring(1),good.replace("\"mode\":","\"authenticated\":true,\"mode\":")})refused(()->PackTrustState.parse(bytes(bad)));
    refused(()->PackTrustState.parse(null));refused(()->PackTrustState.parse(new byte[32769]));
  }
  @Test public void scopeHistoriesAreIndependentAndSurviveRoundTrip() throws Exception {
    PackEnvelopeTest.Fixture f=new PackEnvelopeTest.Fixture();byte[] encoded=f.pair.getPublic().getEncoded(),key=Arrays.copyOfRange(encoded,12,44);
    PackTrustPolicy p=new PackTrustPolicy(Arrays.asList(new PackTrustPolicy.Root("fixture-1","test.dmaps","fixture","fixture-vbb",key,false),new PackTrustPolicy.Root("fixture-2","other.fixture","fixture","fixture-vbb",key,false)),(k,m,s)->{try{Signature verifier=Signature.getInstance("Ed25519");verifier.initVerify(f.pair.getPublic());verifier.update(m);return verifier.verify(s);}catch(GeneralSecurityException e){return false;}});
    String other=metadata(2).replace("test.dmaps","other.fixture").replace("fixture-1","fixture-2");PackEnvelope.Result r=PackEnvelope.decode(f.envelope(other,false),ImportMode.OFFICIAL_REQUIRED,p,NOW);
    PackTrustState s=PackTrustState.empty().withPack(envelope(f,7,ImportMode.OFFICIAL_REQUIRED)).withPack(r);PackTrustState restarted=PackTrustState.parse(s.encode());assertEquals(2,restarted.history.size());assertEquals(7,restarted.history.get(SCOPE).sequence);assertEquals(2,restarted.history.get("other.fixture/fixture/fixture-vbb").sequence);restarted.requireActive(r);
  }
  private static byte[] localHistory(int count){
    String hash=String.join("",Collections.nCopies(64,"a"));StringBuilder s=new StringBuilder("{\"state_version\":1,\"active\":null,\"history\":{");
    for(int i=0;i<count;i++){if(i>0)s.append(',');s.append("\"ns").append(i).append("/ch/p\":{\"sequence\":1,\"metadata_sha256\":\"").append(hash).append("\"}");}return bytes(s.append("}}").toString());
  }
  @Test public void boundedHistoryRefusesOverflowInsteadOfPruningOldFloors() throws Exception {
    PackTrustState s=PackTrustState.parse(localHistory(128));assertEquals(128,PackTrustState.parse(s.encode()).history.size());
    refused(()->PackTrustState.parse(localHistory(129)));assertEquals(128,s.history.size());
  }
  @Test public void storedModeDoesNotSupplyCurrentRootAuthority() throws Exception {
    PackEnvelopeTest.Fixture f=new PackEnvelopeTest.Fixture();PackEnvelope.Result r=envelope(f,7,ImportMode.OFFICIAL_REQUIRED);PackTrustState.parse(PackTrustState.empty().withPack(r).encode()).requireActive(r);
    refused(()->PackEnvelope.decode(r.rawEnvelope(),ImportMode.OFFICIAL_REQUIRED,PackTrustPolicy.production(),NOW));
  }
}
