#include "platform/crypto/ed25519.hpp"

#include <jni.h>

#include <array>
#include <cstdint>
#include <new>
#include <vector>

// Primitive verification only. Root authorization, domain separation, metadata
// validation and rollback policy belong to the pack verifier, not this bridge.
extern "C"
JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_util_Ed25519_nativeVerify(
    JNIEnv * env, jclass, jbyteArray publicKey, jbyteArray message, jbyteArray signature)
{
  constexpr jsize kMaxMessageBytes = 65536;
  if (publicKey == nullptr || message == nullptr || signature == nullptr)
    return JNI_FALSE;
  if (env->GetArrayLength(publicKey) != 32 || env->GetArrayLength(signature) != 64)
    return JNI_FALSE;
  jsize const length = env->GetArrayLength(message);
  if (length < 0 || length > kMaxMessageBytes)
    return JNI_FALSE;

  std::array<uint8_t, 32> key;
  std::array<uint8_t, 64> sig;
  try
  {
    std::vector<uint8_t> payload(static_cast<size_t>(length));
    env->GetByteArrayRegion(publicKey, 0, 32, reinterpret_cast<jbyte *>(key.data()));
    if (env->ExceptionCheck()) return JNI_FALSE;
    env->GetByteArrayRegion(signature, 0, 64, reinterpret_cast<jbyte *>(sig.data()));
    if (env->ExceptionCheck()) return JNI_FALSE;
    if (length != 0)
      env->GetByteArrayRegion(message, 0, length, reinterpret_cast<jbyte *>(payload.data()));
    if (env->ExceptionCheck()) return JNI_FALSE;
    return platform::crypto::VerifyEd25519(key.data(), payload.data(), payload.size(), sig.data())
               ? JNI_TRUE : JNI_FALSE;
  }
  catch (std::bad_alloc const &)
  {
    return JNI_FALSE; // Refuse rather than crossing the JNI boundary with a C++ exception.
  }
}
