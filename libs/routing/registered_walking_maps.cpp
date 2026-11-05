#include "routing/registered_walking_maps.hpp"
#include "indexer/mwm_set.hpp"
#include "base/thread_log_guard.hpp"
#include "geometry/mercator.hpp"
#include <algorithm>
#include <cmath>
#include <filesystem>
#include <tuple>
namespace routing {
RegisteredWalkingMaps CaptureRegisteredWalkingMaps(MwmSet const & source)
{
  base::ScopedThreadLogSilencer quiet;
  try {
    std::vector<std::shared_ptr<MwmInfo>> infos;source.GetMwmsInfo(infos);
    if(infos.size()>RegisteredWalkingMaps::kLimit)return {};
    RegisteredWalkingMaps result;
    for(auto const & info:infos){
      if(!info||!info->IsRegistered()||info->GetType()!=MwmInfo::COUNTRY)continue;
      auto const & file=info->GetLocalFile();auto const & dir=file.GetDirectory();auto const & name=file.GetCountryName();auto const & b=info->m_bordersRect;
      if(dir.empty()||dir.size()>12288||dir.find('\0')!=std::string::npos||!std::filesystem::path(dir).is_absolute()
          ||name.empty()||name.size()>200||name=="."||name==".."||name.find_first_of("/\\")!=std::string::npos||name.find('\0')!=std::string::npos
          ||file.GetVersion()<0||file.GetSize(MapFileType::Map)==0)return {};
      double minX=b.minX(),minY=b.minY(),maxX=b.maxX(),maxY=b.maxY();
      if(!std::isfinite(minX)||!std::isfinite(minY)||!std::isfinite(maxX)||!std::isfinite(maxY)
          ||minX>maxX||minY>maxY||!mercator::ValidX(minX)||!mercator::ValidX(maxX)||!mercator::ValidY(minY)||!mercator::ValidY(maxY))return {};
      // Header quantization margin only; neither political borders nor pedestrian connectivity.
      m2::RectD bounds(std::max(-180.0,minX-1e-5),std::max(-180.0,minY-1e-5),std::min(180.0,maxX+1e-5),std::min(180.0,maxY+1e-5));
      if(info->IsRegistered())result.maps.push_back({dir,name,file.GetVersion(),file.GetSize(MapFileType::Map),bounds});
    }
    std::sort(result.maps.begin(),result.maps.end(),[](auto const & a,auto const & b){return std::tie(a.countryName,a.version,a.directory)<std::tie(b.countryName,b.version,b.directory);});
    result.complete=true;return result;
  }catch(...){return {};}
}
}
