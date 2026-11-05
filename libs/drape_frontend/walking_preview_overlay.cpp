#include "drape_frontend/walking_preview_overlay.hpp"
#include "geometry/mercator.hpp"
#include <cmath>
#include <limits>

namespace df
{
WalkingPreviewOverlay::WalkingPreviewOverlay(Backend & backend) : m_backend(backend), m_owner(std::this_thread::get_id()) {}
uint64_t WalkingPreviewOverlay::Show(std::vector<ms::LatLon> const & points)
{
  if(std::this_thread::get_id()!=m_owner||points.size()<2||points.size()>32768||m_generation==static_cast<uint64_t>(std::numeric_limits<int64_t>::max()))return 0;
  bool attempted=false;
  try
  {
    std::vector<m2::PointD> geometry;geometry.reserve(points.size());m2::RectD bounds;
    bool distinct=false;double const maxLat=mercator::YToLat(mercator::Bounds::kMaxY);
    for(size_t i=0;i<points.size();++i)
    {
      auto const & p=points[i];
      if(!std::isfinite(p.m_lat)||!std::isfinite(p.m_lon)||std::abs(p.m_lat)>maxLat||!mercator::ValidLon(p.m_lon)||
         (i>0&&std::abs(p.m_lon-points[i-1].m_lon)>180))return 0;
      auto point=mercator::FromLatLon(p);
      if(!std::isfinite(point.x)||!std::isfinite(point.y)||!mercator::ValidX(point.x)||!mercator::ValidY(point.y))return 0;
      distinct|=i>0&&point!=geometry.front();geometry.push_back(point);bounds.Add(point);
    }
    if(!distinct||!m_backend.ReadyAndRouteIdle())return 0;
    // A failed attempt also consumes its generation; no token is ever reused.
    uint64_t token=++m_generation;attempted=true;
    if(!m_backend.PutLine(geometry))return 0;
    m_active=token;
    if(!m_backend.ReadyAndRouteIdle()||!m_backend.LineIsPresent()){Remove(token);return 0;}
    bounds.Inflate(1e-4,1e-4);
    bounds=m2::RectD(mercator::ClampX(bounds.minX()),mercator::ClampY(bounds.minY()),mercator::ClampX(bounds.maxX()),mercator::ClampY(bounds.maxY()));
    m_backend.Focus(bounds);return token;
  }
  catch(...)
  {
    if(attempted){try{m_backend.RemoveLine();}catch(...){}m_active=0;}
    return 0;
  }
}
bool WalkingPreviewOverlay::Remove(uint64_t expected)
{
  if(std::this_thread::get_id()!=m_owner||expected==0||expected!=m_active)return false;
  // Retire authority before external presentation cleanup; stale callbacks cannot own a successor.
  m_active=0;
  try{m_backend.RemoveLine();return true;}catch(...){return false;}
}
bool WalkingPreviewOverlay::IsCurrent(uint64_t expected) const
{
  if(std::this_thread::get_id()!=m_owner||expected==0||expected!=m_active)return false;
  try{return m_backend.ReadyAndRouteIdle()&&m_backend.LineIsPresent();}catch(...){return false;}
}
}
