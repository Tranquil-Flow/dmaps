package app.organicmaps.transit;

import app.organicmaps.sdk.util.Ed25519;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic public JCA vectors; no production roots, private keys, network or repository writes. */
final class PackCryptoChecks
{
  private final TransitPilotInstrumentation runner;
  PackCryptoChecks(TransitPilotInstrumentation runner) { this.runner = runner; }
  private static void require(boolean value) { if (!value) throw new AssertionError(); }
  private static byte[] bytes(JSONObject vector, String name) throws Exception
  { return Base64.decode(vector.getString(name), Base64.NO_WRAP); }

  void run() throws Exception
  {
    byte[] raw;
    try (InputStream in = runner.getContext().getAssets().open("test_ed25519_vectors.json"))
    {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] block = new byte[8192];
      int n;
      while ((n = in.read(block)) != -1)
      {
        require(out.size() + n <= 1024 * 1024);
        out.write(block, 0, n);
      }
      raw = out.toByteArray();
    }
    JSONObject fixture = new JSONObject(new String(raw, StandardCharsets.UTF_8));
    require(fixture.getBoolean("fixture_only"));
    JSONArray vectors = fixture.getJSONArray("vectors");
    require(vectors.length() == 15);
    for (int i=0; i<vectors.length(); ++i)
    {
      JSONObject v = vectors.getJSONObject(i);
      String id = v.getString("id");
      require(id.matches("(valid|altered-message|altered-signature|wrong-key|noncanonical-scalar)-[012]"));
      byte[] key=bytes(v,"public_key"), message=bytes(v,"message"), sig=bytes(v,"signature");
      boolean expected=v.getBoolean("valid");
      runner.check("synthetic Ed25519 JNI " + id,
          () -> require(Ed25519.verify(key,message,sig) == expected));
    }
    JSONObject v=vectors.getJSONObject(0);
    byte[] key=bytes(v,"public_key"), message=bytes(v,"message"), sig=bytes(v,"signature");
    runner.check("Ed25519 wrapper refuses null key", () -> require(!Ed25519.verify(null,message,sig)));
    runner.check("Ed25519 wrapper refuses short key", () -> require(!Ed25519.verify(new byte[31],message,sig)));
    runner.check("Ed25519 wrapper refuses long key", () -> require(!Ed25519.verify(new byte[33],message,sig)));
    runner.check("Ed25519 wrapper refuses null message", () -> require(!Ed25519.verify(key,null,sig)));
    runner.check("Ed25519 wrapper refuses over-limit message", () -> require(!Ed25519.verify(key,new byte[Ed25519.MAX_MESSAGE_BYTES+1],sig)));
    runner.check("Ed25519 wrapper refuses null signature", () -> require(!Ed25519.verify(key,message,null)));
    runner.check("Ed25519 wrapper refuses short signature", () -> require(!Ed25519.verify(key,message,new byte[63])));
    runner.check("Ed25519 wrapper refuses long signature", () -> require(!Ed25519.verify(key,message,new byte[65])));
    Method method=Ed25519.class.getDeclaredMethod("nativeVerify",byte[].class,byte[].class,byte[].class);
    method.setAccessible(true);
    runner.check("Ed25519 native refuses null key", () -> require(!(Boolean)method.invoke(null,null,message,sig)));
    runner.check("Ed25519 native refuses short key", () -> require(!(Boolean)method.invoke(null,new byte[31],message,sig)));
    runner.check("Ed25519 native refuses null message", () -> require(!(Boolean)method.invoke(null,key,null,sig)));
    runner.check("Ed25519 native refuses over-limit message", () -> require(!(Boolean)method.invoke(null,key,new byte[Ed25519.MAX_MESSAGE_BYTES+1],sig)));
    runner.check("Ed25519 native refuses short signature", () -> require(!(Boolean)method.invoke(null,key,message,new byte[63])));
  }
}
