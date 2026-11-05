#pragma once
#include <jni.h>
#include "routing/registered_walking_maps.hpp"
namespace walking_jni {
// Shared production marshaller; callers preserve pending Java exceptions.
jobject ToRegisteredWalkingMaps(JNIEnv * env,routing::RegisteredWalkingMaps const & maps);
}
