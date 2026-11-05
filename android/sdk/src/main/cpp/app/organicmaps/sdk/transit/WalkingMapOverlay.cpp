#include "app/organicmaps/sdk/Framework.hpp"
#include "base/thread_log_guard.hpp"
#include "drape_frontend/drape_api.hpp"
#include "drape_frontend/walking_preview_overlay.hpp"
#include "map/framework.hpp"
#include <jni.h>
#include <stdexcept>
#include <vector>

namespace
{
char const kLineId[]="dmaps.transit.walking.preview.v1";
::Framework * Frame(){return g_framework?g_framework->NativeFramework():nullptr;}
// Enforce the Framework owner at the JNI boundary too, not only in the managed wrapper.
bool OnMain(JNIEnv * env)
{
  if(env->ExceptionCheck())return false;
  jclass cls=env->FindClass("android/os/Looper");if(!cls)return false;
  jmethodID current=env->GetStaticMethodID(cls,"myLooper","()Landroid/os/Looper;");
  if(!current){env->DeleteLocalRef(cls);return false;}
  jmethodID main=env->GetStaticMethodID(cls,"getMainLooper","()Landroid/os/Looper;");
  if(!main){env->DeleteLocalRef(cls);return false;}
  jobject a=env->CallStaticObjectMethod(cls,current);
  if(env->ExceptionCheck()){if(a)env->DeleteLocalRef(a);env->DeleteLocalRef(cls);return false;}
  jobject b=env->CallStaticObjectMethod(cls,main);
  bool ok=!env->ExceptionCheck()&&a&&b&&env->IsSameObject(a,b);
  if(a)env->DeleteLocalRef(a);if(b)env->DeleteLocalRef(b);env->DeleteLocalRef(cls);return ok;
}
class Backend final : public df::WalkingPreviewOverlay::Backend
{
public:
  bool ReadyAndRouteIdle()const override
  {
    auto frame=Frame();if(!frame||frame->GetDrapeEngine()==nullptr)return false;
    auto const & routing=frame->GetRoutingManager();
    return !routing.IsRoutingActive()&&!routing.IsRouteBuilding()&&!routing.IsRouteBuilt()&&routing.GetRoutePointsCount()==0;
  }
  bool PutLine(std::vector<m2::PointD> const & points)override
  {
    if(!ReadyAndRouteIdle())return false;
    return Frame()->GetDrapeApi().TryAddLine(kLineId,df::DrapeApiLineData(points,dp::Color(32,160,224,255)).Width(7.0f));
  }
  bool LineIsPresent()const override{auto frame=Frame();return frame&&frame->GetDrapeApi().HasLine(kLineId);}
  void RemoveLine()override{if(auto frame=Frame())frame->GetDrapeApi().RemoveLine(kLineId);}
  void Focus(m2::RectD const & bounds)override
  {
    if(!ReadyAndRouteIdle())throw std::runtime_error("walking preview unavailable");
    // Release viewport following (including a pending wait whose returning fix would re-follow);
    // keep location acquisition and routing untouched.
    Frame()->StopLocationFollowForPreview();
    Frame()->ShowRect(bounds,18,false);
  }
};
df::WalkingPreviewOverlay & Overlay(){static Backend backend;static df::WalkingPreviewOverlay overlay(backend);return overlay;}
}
extern "C" JNIEXPORT jlong JNICALL
Java_app_organicmaps_sdk_transit_WalkingMapOverlay_nativeShow(JNIEnv * env,jclass,jdoubleArray input)
{
  base::ScopedThreadLogSilencer quiet;
  if(!OnMain(env)||!input)return 0;
  jsize count=env->GetArrayLength(input);if(count<4||count>65536||count%2)return 0;
  try
  {
    std::vector<jdouble> values(static_cast<size_t>(count));env->GetDoubleArrayRegion(input,0,count,values.data());if(env->ExceptionCheck())return 0;
    std::vector<ms::LatLon> points;points.reserve(static_cast<size_t>(count)/2);
    for(jsize i=0;i<count;i+=2)points.emplace_back(values[i],values[i+1]);
    return static_cast<jlong>(Overlay().Show(points));
  }
  catch(...){return 0;}
}
extern "C" JNIEXPORT jboolean JNICALL
Java_app_organicmaps_sdk_transit_WalkingMapOverlay_nativeCurrent(JNIEnv * env,jclass,jlong token)
{
  base::ScopedThreadLogSilencer quiet;
  return OnMain(env)&&token>0&&Overlay().IsCurrent(static_cast<uint64_t>(token))?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_app_organicmaps_sdk_transit_WalkingMapOverlay_nativeRemove(JNIEnv * env,jclass,jlong token)
{
  base::ScopedThreadLogSilencer quiet;
  if(OnMain(env)&&token>0)Overlay().Remove(static_cast<uint64_t>(token));
}
