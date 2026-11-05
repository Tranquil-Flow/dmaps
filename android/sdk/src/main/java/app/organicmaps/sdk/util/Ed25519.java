package app.organicmaps.sdk.util;

/** Bounded signature primitive only; does not establish pack authority or trust. */
public final class Ed25519
{
  public static final int MAX_MESSAGE_BYTES = 65536;
  private Ed25519() {}

  public static boolean verify(byte[] publicKey, byte[] message, byte[] signature)
  {
    if (publicKey == null || publicKey.length != 32 || message == null
        || message.length > MAX_MESSAGE_BYTES || signature == null || signature.length != 64)
      return false;
    Library.ensureLoaded();
    return nativeVerify(publicKey, message, signature);
  }

  private static native boolean nativeVerify(byte[] publicKey, byte[] message, byte[] signature);

  // Loading the existing JNI library does not initialize a map Framework or make a network request.
  // Malformed input is refused before loading it; no provider/unsigned fallback is permitted.
  private static final class Library
  {
    static { System.loadLibrary("organicmaps"); }
    static void ensureLoaded() {}
  }
}
