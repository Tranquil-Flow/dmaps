#include <jni.h>
#include "routing/offline_walking_provider.hpp"
#include "geometry/mercator.hpp"
#include "indexer/classificator.hpp"
#include "base/thread_log_guard.hpp"
#include <atomic>
#include <cmath>
#include <filesystem>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <stdexcept>

namespace
{
using Provider=routing::OfflineWalkingProvider;
using Status=Provider::Status;
std::atomic<bool> busy{false};
struct Lease
{
  Lease(){bool expected=false;if(!busy.compare_exchange_strong(expected,true))throw std::runtime_error("walking busy");}
  ~Lease(){busy.store(false);}
  Lease(Lease const &)=delete;
  Lease & operator=(Lease const &)=delete;
};
struct Session
{
  explicit Session(std::unique_ptr<Lease> reservation):lease(std::move(reservation)),owner(std::this_thread::get_id()){}
  // Owner-thread close resets provider explicitly before a foreign cancel reference can retire Session.
  std::unique_ptr<Lease> lease;
  std::thread::id owner;
  std::unique_ptr<Provider> provider;
  std::mutex lifecycle;
  std::shared_ptr<routing::RouterDelegate> active;
  uint64_t revision=0;
  bool closed=false;
  jlong id=0;
  bool OnOwner()const{return owner==std::this_thread::get_id();}
};
std::mutex registry;
std::shared_ptr<Session> current;
jlong nextId=1; // Never a pointer and never recycled. Refuse before signed overflow.
std::shared_ptr<Session> Lookup(jlong id){std::lock_guard<std::mutex> lock(registry);return current&&id>0&&current->id==id?current:nullptr;}
bool Read(JNIEnv * env,jstring input,jsize limit,std::string & text)
{
  if(!input)return false;
  jsize n=env->GetStringLength(input);if(n<0||n>limit)return false;
  auto chars=env->GetStringChars(input,nullptr);if(!chars)return false;
  bool valid=true;for(jsize i=0;i<n;++i)if(chars[i]==0){valid=false;break;}
  env->ReleaseStringChars(input,chars);if(!valid)return false;
  auto bytes=env->GetStringUTFChars(input,nullptr);if(!bytes)return false;
  try{text=bytes;}catch(...){env->ReleaseStringUTFChars(input,bytes);throw;}
  env->ReleaseStringUTFChars(input,bytes);return true;
}
bool Digest(std::string const & value){if(value.size()!=64)return false;for(char c:value)if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))return false;return true;}
bool Coordinate(double lat,double lon){return std::isfinite(lat)&&std::isfinite(lon)&&std::abs(lat)<=mercator::YToLat(mercator::Bounds::kMaxY)&&mercator::ValidLon(lon);}
int Code(Status status)
{
  switch(status){case Status::Ok:return 0;case Status::InvalidRequest:return 1;case Status::OutsideMap:return 2;case Status::MapChanged:return 3;case Status::NoPath:return 4;case Status::Cancelled:return 5;case Status::LimitReached:return 6;case Status::Unavailable:return 7;}
  return 7;
}
Status Cancellation(routing::RouterDelegate const & delegate){return delegate.GetCancellable().CancellationStatus()==base::Cancellable::Status::DeadlineExceeded?Status::LimitReached:Status::Cancelled;}
jobject Marshal(JNIEnv * env,Provider::Result const & result)
{
  auto type=env->FindClass("app/organicmaps/sdk/transit/MappedWalking$Result");if(!type)return nullptr;
  auto ctor=env->GetMethodID(type,"<init>","(ILjava/lang/String;Ljava/lang/String;[DIDZ[I[I[DZ)V");if(!ctor)return nullptr;
  auto map=env->NewStringUTF(result.mapSnapshotId.c_str());if(!map)return nullptr;
  auto token=env->NewStringUTF(result.pathToken.c_str());if(!token)return nullptr;
  if(result.geometry.size()>32768)return nullptr;
  std::vector<jdouble> points;points.reserve(result.geometry.size()*2);
  for(auto const & point:result.geometry){auto latlon=mercator::ToLatLon(point);points.push_back(latlon.m_lat);points.push_back(latlon.m_lon);}
  auto geometry=env->NewDoubleArray(static_cast<jsize>(points.size()));if(!geometry)return nullptr;
  if(!points.empty()){env->SetDoubleArrayRegion(geometry,0,static_cast<jsize>(points.size()),points.data());if(env->ExceptionCheck())return nullptr;}
  if(result.directions.size()>256)return nullptr;
  std::vector<jint> indices,codes;std::vector<jdouble> meters;
  for(auto const & direction:result.directions){indices.push_back(static_cast<jint>(direction.pointIndex));codes.push_back(static_cast<jint>(direction.maneuver));meters.push_back(direction.distanceFromStartMeters);}
  auto turnIndices=env->NewIntArray(static_cast<jsize>(indices.size()));if(!turnIndices)return nullptr;
  auto turnCodes=env->NewIntArray(static_cast<jsize>(codes.size()));if(!turnCodes)return nullptr;
  auto turnMeters=env->NewDoubleArray(static_cast<jsize>(meters.size()));if(!turnMeters)return nullptr;
  if(!indices.empty()){
    env->SetIntArrayRegion(turnIndices,0,static_cast<jsize>(indices.size()),indices.data());if(env->ExceptionCheck())return nullptr;
    env->SetIntArrayRegion(turnCodes,0,static_cast<jsize>(codes.size()),codes.data());if(env->ExceptionCheck())return nullptr;
    env->SetDoubleArrayRegion(turnMeters,0,static_cast<jsize>(meters.size()),meters.data());if(env->ExceptionCheck())return nullptr;
  }
  return env->NewObject(type,ctor,static_cast<jint>(Code(result.status)),map,token,geometry,static_cast<jint>(result.estimatedSeconds),result.distanceMeters,result.unverifiedEndpointAccess?JNI_TRUE:JNI_FALSE,turnIndices,turnCodes,turnMeters,result.directionsAvailable?JNI_TRUE:JNI_FALSE);
}
jobject Failure(JNIEnv * env,Status status){Provider::Result result;result.status=status;return Marshal(env,result);}
struct ActiveQuery
{
  std::shared_ptr<Session> session;
  std::shared_ptr<routing::RouterDelegate> delegate;
  uint64_t revision=0;
  explicit ActiveQuery(std::shared_ptr<Session> value):session(std::move(value)),delegate(std::make_shared<routing::RouterDelegate>()){
    delegate->SetTimeout(5);std::lock_guard<std::mutex> lock(session->lifecycle);revision=session->revision;session->active=delegate;
  }
  ~ActiveQuery(){std::lock_guard<std::mutex> lock(session->lifecycle);session->active.reset();}
};
}

extern "C"
{
JNIEXPORT jlong JNICALL Java_app_organicmaps_sdk_transit_MappedWalking_nativeOpen(JNIEnv * env,jclass,jstring directory,jstring name,jlong version,jstring cache,jint mode,jlong budget)
{
  base::ScopedThreadLogSilencer quiet;
  try{
    std::string dir,country,privateCache;
    if(version<0||budget<0||(mode!=0&&mode!=1)||!Read(env,directory,4096,dir)||!Read(env,name,200,country)||!Read(env,cache,4096,privateCache))return 0;
    if(!std::filesystem::path(dir).is_absolute()||!std::filesystem::path(privateCache).is_absolute()||!classif().HasTypesMapping())return 0;
    auto lease=std::make_unique<Lease>();jlong id;
    {std::lock_guard<std::mutex> lock(registry);if(nextId>=std::numeric_limits<jlong>::max())return 0;id=nextId++;}
    auto session=std::make_shared<Session>(std::move(lease));session->id=id;
    platform::LocalCountryFile map(dir,platform::CountryFile(country),version);map.SyncWithDisk();
    session->provider=std::make_unique<Provider>(map,privateCache,mode==1?Provider::SnapshotMode::PrivateCopy:Provider::SnapshotMode::HardLink,static_cast<uint64_t>(budget));
    {std::lock_guard<std::mutex> lock(registry);current=std::move(session);}return id;
  }catch(...){return 0;}
}
JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_transit_MappedWalking_nativeSnapshotId(JNIEnv * env,jclass,jlong id)
{
  base::ScopedThreadLogSilencer quiet;
  try{auto s=Lookup(id);if(!s||!s->OnOwner()||!s->provider)return nullptr;return env->NewStringUTF(s->provider->GetMapSnapshotId().c_str());}catch(...){return nullptr;}
}
JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_transit_MappedWalking_nativeIsCurrent(JNIEnv * env,jclass,jlong id,jstring expected)
{
  base::ScopedThreadLogSilencer quiet;
  try{auto s=Lookup(id);std::string digest;if(!s||!s->OnOwner()||!s->provider||!Read(env,expected,64,digest))return JNI_FALSE;return s->provider->IsCurrent(digest)?JNI_TRUE:JNI_FALSE;}catch(...){return JNI_FALSE;}
}
JNIEXPORT jobject JNICALL Java_app_organicmaps_sdk_transit_MappedWalking_nativeQuery(JNIEnv * env,jclass,jlong id,jdouble fromLat,jdouble fromLon,jdouble toLat,jdouble toLon,jlong utc,jstring expected)
{
  base::ScopedThreadLogSilencer quiet;
  try{
    auto session=Lookup(id);if(!session)return Failure(env,Status::Unavailable);
    if(!session->OnOwner())return Failure(env,Status::InvalidRequest);
    std::string digest;if(!Coordinate(fromLat,fromLon)||!Coordinate(toLat,toLon)||utc<0||!Read(env,expected,64,digest)||!Digest(digest)){if(env->ExceptionCheck())return nullptr;return Failure(env,Status::InvalidRequest);}
    ActiveQuery query(session);
    auto result=session->provider->Query(mercator::FromLatLon(fromLat,fromLon),mercator::FromLatLon(toLat,toLon),utc,digest,*query.delegate);
    jobject value=Marshal(env,result);if(env->ExceptionCheck()||!value)return nullptr;
    // Marshal first, then observe source currency and cancellation/deadline before crossing JNI.
    Status refusal=Status::Ok;
    if(result.status==Status::Ok&&!session->provider->IsCurrent(digest))refusal=Status::MapChanged;
    if(query.delegate->IsCancelled())refusal=Cancellation(*query.delegate);
    {std::lock_guard<std::mutex> lock(session->lifecycle);if(session->closed)refusal=Status::Unavailable;else if(query.revision!=session->revision)refusal=Status::Cancelled;}
    if(refusal!=Status::Ok){env->DeleteLocalRef(value);return Failure(env,refusal);}return value;
  }catch(...){if(env->ExceptionCheck())return nullptr;try{return Failure(env,Status::Unavailable);}catch(...){return nullptr;}}
}
JNIEXPORT void JNICALL Java_app_organicmaps_sdk_transit_MappedWalking_nativeCancel(JNIEnv *,jclass,jlong id)
{
  base::ScopedThreadLogSilencer quiet;
  try{auto s=Lookup(id);if(!s)return;std::shared_ptr<routing::RouterDelegate> delegate;{std::lock_guard<std::mutex> lock(s->lifecycle);++s->revision;delegate=s->active;}if(delegate)delegate->Cancel();}catch(...){}
}
JNIEXPORT void JNICALL Java_app_organicmaps_sdk_transit_MappedWalking_nativeClose(JNIEnv *,jclass,jlong id)
{
  base::ScopedThreadLogSilencer quiet;
  try{
    auto s=Lookup(id);if(!s||!s->OnOwner())return;
    {std::lock_guard<std::mutex> lock(s->lifecycle);s->closed=true;}
    {std::lock_guard<std::mutex> lock(registry);if(current==s)current.reset();}
    // No filesystem teardown under registry/lifecycle locks, and never on a foreign cancel thread.
    s->provider.reset();
  }catch(...){}
}
}
