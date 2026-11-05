#pragma once
#include "geometry/rect2d.hpp"
#include <cstdint>
#include <string>
#include <vector>
class MwmSet;
namespace routing {
struct RegisteredWalkingMap {
  std::string directory,countryName;
  int64_t version=0;
  uint64_t cachedBytes=0;
  m2::RectD bounds;
};
struct RegisteredWalkingMaps {
  static constexpr size_t kLimit=128;
  bool complete=false;
  std::vector<RegisteredWalkingMap> maps;
};
// Capture registered COUNTRY metadata only. No file opening/sync, map downloading,
// query history or navigation changes. Bounds nominate candidates, not walkable access.
// This is registration-at-capture metadata, NOT a content identity or continuing lease.
RegisteredWalkingMaps CaptureRegisteredWalkingMaps(MwmSet const & source);
}
