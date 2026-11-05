#pragma once

#include "geometry/point2d.hpp"
#include "platform/local_country_file.hpp"
#include "routing/router_delegate.hpp"
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace routing
{
/** Local single-map pedestrian snapshot. Owns independent map/router caches and an ephemeral
 * private hard link or explicitly budgeted pinned copy, never the active routing session.
 * HardLink is the zero-copy default; PrivateCopy supports POSIX map/cache filesystems without
 * hard links and requires a nonzero copyByteBudget (also capped at 2GiB). It holds the original
 * read-only descriptor through teardown to detect retirement without inode-reuse ABA. Copying
 * uses bounded buffers and available-space admission, not a hard constructor time limit; the
 * caller must admit its disk/time budget before constructing. Windows copy is unsupported.
 * Installed map writers must
 * replace files atomically, not modify live MWM in place. No network, query persistence or global
 * route/settings mutation. Snapshot identity is content binding, NOT publisher authentication.
 * Initialize the app classificator first. Construction/hash/open and Query belong on one
 * worker, never concurrently or on the UI thread. Constructor failure throws a sanitized
 * runtime_error; Query returns a status without partial geometry. The owned delegate has a
 * five-second cooperative deadline, NOT a hard-preemption guarantee. Caller cancellation is
 * observed at native callbacks and before publication. Snapping does not prove station access;
 * consumers must preserve unverifiedEndpointAccess. Identity uses BLAKE2b-256, not SHA-256. */
class OfflineWalkingProvider
{
public:
  enum class Status { Ok, InvalidRequest, OutsideMap, MapChanged, NoPath, Cancelled, LimitReached, Unavailable };
  enum class SnapshotMode { HardLink, PrivateCopy };
  struct Direction
  {
    uint32_t pointIndex=0;
    // Native turns::PedestrianDirection values: 1 straight,2 right,3 left,4 destination.
    uint32_t maneuver=0;
    double distanceFromStartMeters=0;
  };
  struct Result
  {
    Status status=Status::Unavailable;
    std::string mapSnapshotId,pathToken;
    std::vector<m2::PointD> geometry;
    std::vector<Direction> directions;
    // False means unavailable/over-bound guidance, not permission to infer bends as turns.
    bool directionsAvailable=false;
    uint32_t estimatedSeconds=0;
    double distanceMeters=0;
    // The native router may snap endpoints to roads. This is NOT a platform-access/accessibility proof.
    bool unverifiedEndpointAccess=true;
  };
  OfflineWalkingProvider(platform::LocalCountryFile const & map,std::string const & privateCacheRoot,
                         SnapshotMode mode=SnapshotMode::HardLink,uint64_t copyByteBudget=0);
  ~OfflineWalkingProvider();
  OfflineWalkingProvider(OfflineWalkingProvider const &)=delete;
  OfflineWalkingProvider & operator=(OfflineWalkingProvider const &)=delete;
  std::string const & GetMapSnapshotId() const;
  // Fresh worker-side observation for adapter publication after geometry marshalling.
  bool IsCurrent(std::string const & expectedSnapshot) const;
  /** Mercator endpoints, UTC departure seconds; bounded <=5km chord, <=1h returned estimate,
   * <=32768 geometry points. Delegate cancellation/time budget is cooperative. No geometry on error.
   * expectedSnapshot must be the identity selected when preparing the walking endpoints. */
  Result Query(m2::PointD const & from,m2::PointD const & to,int64_t departureUtc,
               std::string const & expectedSnapshot,RouterDelegate const & delegate);
private:
  struct Impl;
  std::unique_ptr<Impl> m_impl;
};
inline std::string DebugPrint(OfflineWalkingProvider::Status status){return std::to_string(static_cast<int>(status));}
}
