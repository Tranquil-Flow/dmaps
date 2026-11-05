#include "RegisteredWalkingMaps.hpp"
#include "geometry/mercator.hpp"
#include "3party/utfcpp/source/utf8.h"
#include <iterator>
namespace walking_jni {
namespace {
jstring String(JNIEnv * env,std::string const & value){
  std::u16string text;utf8::utf8to16(value.begin(),value.end(),std::back_inserter(text));
  return env->NewString(reinterpret_cast<jchar const *>(text.data()),static_cast<jsize>(text.size()));
}
}
jobject ToRegisteredWalkingMaps(JNIEnv * env,routing::RegisteredWalkingMaps const & maps){
  if(env->ExceptionCheck()||maps.maps.size()>routing::RegisteredWalkingMaps::kLimit||(!maps.complete&&!maps.maps.empty()))return nullptr;
  jclass candidate=env->FindClass("app/organicmaps/sdk/transit/RegisteredWalkingMaps$Candidate");if(!candidate)return nullptr;
  jmethodID ctor=env->GetMethodID(candidate,"<init>","(Ljava/lang/String;Ljava/lang/String;JJDDDD)V");if(!ctor){env->DeleteLocalRef(candidate);return nullptr;}
  jobjectArray array=env->NewObjectArray(static_cast<jsize>(maps.maps.size()),candidate,nullptr);if(!array){env->DeleteLocalRef(candidate);return nullptr;}
  for(size_t i=0;i<maps.maps.size();++i){
    auto const & map=maps.maps[i];jstring dir=String(env,map.directory);if(!dir||env->ExceptionCheck()){if(dir)env->DeleteLocalRef(dir);break;}
    jstring name=String(env,map.countryName);if(!name||env->ExceptionCheck()){env->DeleteLocalRef(dir);if(name)env->DeleteLocalRef(name);break;}
    jobject row=env->NewObject(candidate,ctor,dir,name,static_cast<jlong>(map.version),static_cast<jlong>(map.cachedBytes),mercator::YToLat(map.bounds.minY()),map.bounds.minX(),mercator::YToLat(map.bounds.maxY()),map.bounds.maxX());
    env->DeleteLocalRef(dir);env->DeleteLocalRef(name);if(!row||env->ExceptionCheck()){if(row)env->DeleteLocalRef(row);break;}
    env->SetObjectArrayElement(array,static_cast<jsize>(i),row);env->DeleteLocalRef(row);if(env->ExceptionCheck())break;
  }
  env->DeleteLocalRef(candidate);if(env->ExceptionCheck()){env->DeleteLocalRef(array);return nullptr;}
  jclass inventory=env->FindClass("app/organicmaps/sdk/transit/RegisteredWalkingMaps$Inventory");if(!inventory){env->DeleteLocalRef(array);return nullptr;}
  jmethodID inventoryCtor=env->GetMethodID(inventory,"<init>","(Z[Lapp/organicmaps/sdk/transit/RegisteredWalkingMaps$Candidate;)V");
  jobject result=inventoryCtor?env->NewObject(inventory,inventoryCtor,maps.complete?JNI_TRUE:JNI_FALSE,array):nullptr;
  env->DeleteLocalRef(array);env->DeleteLocalRef(inventory);return result;
}
}
