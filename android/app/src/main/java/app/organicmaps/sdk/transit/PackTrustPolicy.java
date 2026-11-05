package app.organicmaps.sdk.transit;

import app.organicmaps.sdk.util.Ed25519;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiled composition only; metadata cannot install roots. */
final class PackTrustPolicy {
  interface Verifier {
    boolean verify(byte[] publicKey, byte[] message, byte[] signature);
  }

  static final class Root {
    private final String keyId, namespace, channel, packId;
    private final byte[] publicKey;
    private final boolean revoked;

    Root(String keyId, String namespace, String channel, String packId,
         byte[] publicKey, boolean revoked) throws IOException {
      if (!identifier(keyId) || !identifier(namespace) || !identifier(channel)
          || !identifier(packId) || publicKey == null || publicKey.length != 32)
        throw new IOException("Invalid root");
      this.keyId = keyId;
      this.namespace = namespace;
      this.channel = channel;
      this.packId = packId;
      this.publicKey = publicKey.clone();
      this.revoked = revoked;
    }
  }

  private final Map<String, Root> roots;
  private final Verifier verifier;

  PackTrustPolicy(List<Root> roots, Verifier verifier) throws IOException {
    if (roots == null || verifier == null) throw new IOException("Invalid policy");
    Map<String, Root> owned = new LinkedHashMap<String, Root>();
    for (Root root : roots) {
      if (root == null || owned.containsKey(root.keyId)) throw new IOException("Invalid policy");
      owned.put(root.keyId, root);
    }
    this.roots = Collections.unmodifiableMap(owned);
    this.verifier = verifier;
  }

  static PackTrustPolicy production() {
    try {
      return new PackTrustPolicy(Collections.<Root>emptyList(), Ed25519::verify);
    } catch (IOException impossible) {
      throw new AssertionError("Invalid built-in policy");
    }
  }

  static boolean identifier(String value) {
    return value != null && value.matches("[a-z0-9][a-z0-9._-]{0,63}");
  }

  boolean authenticate(String keyId, String namespace, String channel, String packId,
                       byte[] message, byte[] signature) throws IOException {
    Root root = roots.get(keyId);
    if (root == null || root.revoked || !root.namespace.equals(namespace)
        || !root.channel.equals(channel) || !root.packId.equals(packId)) return false;
    try {
      // Even an injected verifier receives no mutable policy-owned authority.
      return verifier.verify(root.publicKey.clone(), message.clone(), signature.clone());
    } catch (RuntimeException | LinkageError failure) {
      throw new IOException("Verification unavailable");
    }
  }
}
