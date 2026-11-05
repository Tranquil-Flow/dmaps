// SPDX-License-Identifier: Apache-2.0
// Copyright 2020 My.com B.V. (Mail.Ru Group)
// Copyright 2020-2026 Organic Maps Contributors
// Modified for DMaps: snapshot-only API, validation, finite strict comparisons and copied metadata.
// Adapted from Organic Maps 6d7c1b39d4e64403865051f31afb61b9e6e1e03d,
// libs/map/routing_manager.cpp:1295-1358 (explicit, added == nullptr path).
// See route_order_optimizer.NOTICE for retained upstream attribution.
#include "map/route_order_optimizer.hpp"

#include "routing/checkpoint_predictor.hpp"
#include "geometry/mercator.hpp"

#include <cmath>
#include <utility>

namespace route_order
{
namespace
{
bool ValidSnapshot(std::vector<RouteMarkData> const & points)
{
  if (points.size() < 2 || points.size() - 2 > RoutePointsLayout::kMaxIntermediatePointsCount)
    return false;
  size_t myPositionCount = 0;
  for (size_t i = 0; i < points.size(); ++i)
  {
    auto const & p = points[i];
    // RoutingManager removes the prior My Position mark when adding another.
    if (p.m_isMyPosition && ++myPositionCount > 1)
      return false;
    auto const expected = i == 0 ? RouteMarkType::Start :
                          i + 1 == points.size() ? RouteMarkType::Finish : RouteMarkType::Intermediate;
    if (p.m_pointType != expected ||
        (expected == RouteMarkType::Intermediate && p.m_intermediateIndex != i - 1))
      return false;
    if (!std::isfinite(p.m_position.x) || !std::isfinite(p.m_position.y) ||
        !mercator::ValidX(p.m_position.x) || !mercator::ValidY(p.m_position.y))
      return false;
  }
  return true;
}

double Length(std::vector<RouteMarkData> const & points, size_t first)
{
  double length = 0.0;
  for (size_t i = first; i + 1 < points.size(); ++i)
    length += mercator::DistanceOnEarth(points[i].m_position, points[i + 1].m_position);
  return length;
}

bool StrictlyShorter(double candidate, double original)
{
  return std::isfinite(candidate) && std::isfinite(original) && candidate < original;
}
}  // namespace

Proposal ProposeShorterOrder(std::vector<RouteMarkData> const & snapshot)
{
  if (!ValidSnapshot(snapshot))
    return {};

  size_t lastPassed = 0;
  for (size_t i = 1; i + 1 < snapshot.size(); ++i)
    if (snapshot[i].m_isPassed)
      lastPassed = i;

  Proposal unchanged{ProposalStatus::NotShorter, {}};
  if (snapshot.size() - lastPassed - 2 < 2)
    return unchanged;

  std::vector<size_t> ordered;
  std::vector<m2::PointD> positions;
  ordered.reserve(snapshot.size());
  positions.reserve(snapshot.size());
  routing::CheckpointPredictor const predictor(snapshot[lastPassed].m_position, snapshot.back().m_position);
  for (size_t i = lastPassed + 1; i + 1 < snapshot.size(); ++i)
  {
    auto const index = predictor.PredictPosition(positions, snapshot[i].m_position);
    positions.insert(positions.begin() + index, snapshot[i].m_position);
    ordered.insert(ordered.begin() + index, i);
  }

  auto candidate = snapshot;
  for (size_t i = 0; i < ordered.size(); ++i)
  {
    auto const slot = lastPassed + 1 + i;
    candidate[slot] = snapshot[ordered[i]];
    candidate[slot].m_intermediateIndex = slot - 1;
  }
  // Also compare the full route: an improvement lost when adding a large frozen prefix
  // must not be advertised as a strictly shorter complete order in floating point.
  if (!StrictlyShorter(Length(candidate, lastPassed), Length(snapshot, lastPassed)) ||
      !StrictlyShorter(Length(candidate, 0), Length(snapshot, 0)))
    return unchanged;
  return {ProposalStatus::Shorter, std::move(candidate)};
}
}  // namespace route_order
