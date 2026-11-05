package app.organicmaps.sdk.transit;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

/** Public Android-test assets through the real envelope/history readers and independent JDK
 * signatures. Not Android/JNI/SQLite execution and not production signer authority. */
public class AndroidAuthenticationAssetsTest {
  private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
  private final Path root=Paths.get("src/androidTest/assets/transit/pack-auth-fixture");
  private Map<String,Object> key()throws Exception {Map<String,Object> k=StrictPackJson.object(Files.readAllBytes(root.resolve("root.json")));assertEquals(Boolean.TRUE,k.get("fixture_only"));return k;}
  private PackTrustPolicy policy()throws Exception {
    Map<String,Object> k=key();byte[] raw=Base64.getDecoder().decode((String)k.get("public_key_base64"));PublicKey pub=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode((String)k.get("public_key_spki_base64"))));
    assertArrayEquals(Arrays.copyOfRange(pub.getEncoded(),pub.getEncoded().length-32,pub.getEncoded().length),raw);
    return new PackTrustPolicy(Collections.singletonList(new PackTrustPolicy.Root((String)k.get("key_id"),(String)k.get("namespace"),(String)k.get("channel"),(String)k.get("pack_id"),raw,false)),(p,m,s)->{try{if(!Arrays.equals(p,raw))return false;Signature v=Signature.getInstance("Ed25519");v.initVerify(pub);v.update(m);return v.verify(s);}catch(GeneralSecurityException e){return false;}});
  }
  private PackEnvelope.Result read(String name,ImportMode mode,PackTrustPolicy policy)throws Exception {
    try(ZipFile zip=new ZipFile(root.resolve(name).toFile());InputStream in=zip.getInputStream(zip.getEntry("manifest.json"));InputStream database=zip.getInputStream(zip.getEntry("timetable.sqlite"))){
      assertEquals(2,zip.size());byte[] payload=database.readAllBytes();PackEnvelope.Result r=PackEnvelope.decode(in.readAllBytes(),mode,policy,Instant.parse("2026-10-21T00:00:00Z"));assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(payload)),r.manifest.get("database_sha256"));assertEquals(Long.valueOf(payload.length),r.manifest.get("database_bytes"));return r;
    }
  }
  @Test public void packagedFixtureInventoryContainsPublicMaterialOnly()throws Exception {
    Set<String> names=new TreeSet<>();try(java.util.stream.Stream<Path> paths=Files.list(root)){paths.forEach(p->names.add(p.getFileName().toString()));}
    assertEquals(new TreeSet<>(Arrays.asList("root.json","official-v7.ctpack","official-v8.ctpack","rollback-v6.ctpack","conflict-v7.ctpack","bad-signature-v9.ctpack","wrong-scope-v9.ctpack")),names);
    assertEquals(new HashSet<>(Arrays.asList("fixture_only","namespace","channel","pack_id","key_id","public_key_base64","public_key_spki_base64","payload_sha256","payload_bytes")),key().keySet());
  }
  @Test public void nativePositiveArchivesHaveRealIndependentSignaturesAndPayloadBindings()throws Exception {
    PackTrustPolicy p=policy();for(String name:Arrays.asList("official-v7.ctpack","official-v8.ctpack","rollback-v6.ctpack","conflict-v7.ctpack")){PackEnvelope.Result r=read(name,ImportMode.OFFICIAL_REQUIRED,p);assertTrue(r.authenticated);assertEquals("fixture-android-1",r.keyId);}
  }
  @Test public void badSignatureAndWrongScopeAreRefusedWithoutImplicitDemotion()throws Exception {
    PackTrustPolicy p=policy();for(String name:Arrays.asList("bad-signature-v9.ctpack","wrong-scope-v9.ctpack")){try{read(name,ImportMode.OFFICIAL_REQUIRED,p);fail("expected refusal");}catch(IOException expected){}assertFalse(read(name,ImportMode.USER_UNTRUSTED,p).authenticated);}
  }
  @Test public void nativeAssetSequencesExerciseRollbackConflictAndRetainedHistory()throws Exception {
    PackTrustPolicy p=policy();PackTrustState state=PackTrustState.empty().withPack(read("official-v7.ctpack",ImportMode.OFFICIAL_REQUIRED,p));
    for(String name:Arrays.asList("rollback-v6.ctpack","conflict-v7.ctpack")){try{state.withPack(read(name,ImportMode.OFFICIAL_REQUIRED,p));fail("expected sequence refusal");}catch(IOException expected){}}
    state=state.withPack(read("official-v8.ctpack",ImportMode.OFFICIAL_REQUIRED,p)).withPack(read("bad-signature-v9.ctpack",ImportMode.USER_UNTRUSTED,p));assertEquals(8,state.history.get("test.dmaps/fixture/fixture-vbb").sequence);assertEquals(ImportMode.USER_UNTRUSTED,state.active.mode);
  }
}
