#pragma once

#include "geometry/latlon.hpp"
#include "geometry/point2d.hpp"
#include "geometry/rect2d.hpp"
#include <cstdint>
#include <thread>
#include <vector>

namespace df
{
// Temporary presentation only. The consumer owns map/pack currency and access uncertainty.
// No routing session, saved marks, file storage, network, location or audio dependency.
class WalkingPreviewOverlay
{
public:
  class Backend
  {
  public:
    virtual ~Backend() = default;
    virtual bool ReadyAndRouteIdle() const = 0;
    virtual bool PutLine(std::vector<m2::PointD> const & points) = 0;
    virtual bool LineIsPresent() const = 0;
    virtual void RemoveLine() = 0;
    virtual void Focus(m2::RectD const & bounds) = 0;
  };
  explicit WalkingPreviewOverlay(Backend & backend);
  // Latitude/longitude, not screen/Mercator coordinates. Zero is refusal, never a lease.
  uint64_t Show(std::vector<ms::LatLon> const & points);
  // A stale owner cannot remove a newer preview. Cleanup is allowed while renderer/route changes.
  bool Remove(uint64_t expected);
  bool IsCurrent(uint64_t expected) const;
private:
  Backend & m_backend;
  std::thread::id const m_owner;
  uint64_t m_generation = 0;
  uint64_t m_active = 0;
};
}
