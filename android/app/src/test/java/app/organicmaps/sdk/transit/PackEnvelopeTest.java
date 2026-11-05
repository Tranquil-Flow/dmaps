package app.organicmaps.sdk.transit;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import static org.junit.Assert.*;

/** Real JDK signatures; fixture keys exist only in each test's JVM memory. No payload/store claim. */
public class PackEnvelopeTest {
  private static final Instant NOW=Instant.parse("2026-10-21T00:00:00Z");
  private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
  private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
  private static String pack() {
    String zero=String.join("",Collections.nCopies(64,"0"));
    return "{\"format_version\":1,\"publisher_id\":\"vbb\",\"publisher\":\"synthetic\",\"license\":\"CC BY 4.0\",\"source_url\":\"https://example.invalid/source\",\"license_url\":\"https://example.invalid/license\",\"attribution\":\"fixture\",\"created_utc\":\"2026-10-01T00:00:00Z\",\"timezone\":\"Europe/Berlin\",\"valid_from\":\"2026-10-01\",\"valid_until\":\"2027-01-01\",\"source_sha256\":\""+zero+"\",\"database_sha256\":\""+zero+"\",\"database_bytes\":0,\"max_departure_seconds\":86400,\"counts\":{}}";
  }
  static String metadata() {
    return "{\"pack\":"+pack()+",\"namespace\":\"test.dmaps\",\"channel\":\"fixture\",\"pack_id\":\"fixture-vbb\",\"sequence\":1,\"key_id\":\"fixture-1\",\"suite\":\"Ed25519\",\"reader_min\":2,\"reader_max\":2,\"issued_utc\":\"2026-01-01T00:00:00Z\",\"expires_utc\":\"2027-01-01T00:00:00Z\"}";
  }
  static final class Fixture {
    final KeyPair pair;
    final PackTrustPolicy policy;
    Fixture() throws Exception {
      pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      byte[] encoded=pair.getPublic().getEncoded();assertEquals(44,encoded.length);
      byte[] raw=Arrays.copyOfRange(encoded,12,44);
      policy=new PackTrustPolicy(Collections.singletonList(new PackTrustPolicy.Root("fixture-1","test.dmaps","fixture","fixture-vbb",raw,false)),(key,message,sig)->{
        if(!Arrays.equals(raw,key))return false;
        try { Signature verifier=Signature.getInstance("Ed25519");verifier.initVerify(pair.getPublic());verifier.update(message);return verifier.verify(sig); }
        catch(GeneralSecurityException e){return false;}
      });
    }
    byte[] envelope(String metadata, boolean wrongDomain) throws Exception {
      Signature s=Signature.getInstance("Ed25519");s.initSign(pair.getPrivate());
      s.update(bytes(wrongDomain?"wrong domain\0":"DMaps timetable-pack envelope v2\0"));s.update(bytes(metadata));
      return bytes("{\"envelope_version\":2,\"metadata\":\""+b64(bytes(metadata))+"\",\"signature\":\""+b64(s.sign())+"\"}");
    }
  }
  @Test public void officialRequiresActualScopedSignature() throws Exception {
    Fixture f=new Fixture();
    PackEnvelope.Result result=PackEnvelope.decode(f.envelope(metadata(),false),ImportMode.OFFICIAL_REQUIRED,f.policy,NOW);
    assertTrue(result.authenticated);assertEquals("fixture-vbb",result.packId);
    try {PackEnvelope.decode(f.envelope(metadata(),true),ImportMode.OFFICIAL_REQUIRED,f.policy,NOW);fail("wrong domain admitted");}
    catch(IOException expected) {assertNull(expected.getCause());}
  }
  @Test public void explicitUserModeDoesNotGainAuthority() throws Exception {
    Fixture f=new Fixture();
    assertFalse(PackEnvelope.decode(f.envelope(metadata(),true),ImportMode.USER_UNTRUSTED,f.policy,NOW).authenticated);
    assertFalse(PackEnvelope.decode(bytes(pack()),ImportMode.USER_UNTRUSTED,f.policy,NOW).authenticated);
    try {PackEnvelope.decode(bytes(pack()),ImportMode.OFFICIAL_REQUIRED,f.policy,NOW);fail("unsigned official admitted");}
    catch(IOException expected) { }
  }
  @Test public void productionHasNoFixtureRoots() throws Exception {
    Fixture f=new Fixture();
    try {PackEnvelope.decode(f.envelope(metadata(),false),ImportMode.OFFICIAL_REQUIRED,PackTrustPolicy.production(),NOW);fail("fixture root became production authority");}
    catch(IOException expected) { }
  }
  @Test public void expiryIsExclusiveAndNoAutomaticDemotion() throws Exception {
    Fixture f=new Fixture();byte[] raw=f.envelope(metadata(),false);
    try {PackEnvelope.decode(raw,ImportMode.OFFICIAL_REQUIRED,f.policy,Instant.parse("2027-01-01T00:00:00Z"));fail("expired official admitted");}
    catch(IOException expected) { }
    assertFalse(PackEnvelope.decode(raw,ImportMode.USER_UNTRUSTED,f.policy,Instant.parse("2027-01-01T00:00:00Z")).authenticated);
  }
  @Test public void ownedResultAndNoMetadataReserialization() throws Exception {
    Fixture f=new Fixture();String text=" "+metadata()+"\n";byte[] raw=f.envelope(text,false);
    PackEnvelope.Result result=PackEnvelope.decode(raw,ImportMode.OFFICIAL_REQUIRED,f.policy,NOW);raw[0]=0;
    assertArrayEquals(bytes(text),result.signedMetadata());assertEquals('{',result.rawEnvelope()[0]);
    try {result.manifest.put("trusted",true);fail("mutable manifest");}
    catch(UnsupportedOperationException expected) { }
  }
}
