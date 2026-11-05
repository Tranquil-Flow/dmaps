// SPDX-License-Identifier: Apache-2.0
#pragma once

#include "map/routing_mark.hpp"

#include <vector>

namespace route_order
{
enum class ProposalStatus { InvalidSnapshot, NotShorter, Shorter };

struct Proposal
{
  ProposalStatus m_status = ProposalStatus::InvalidSnapshot;
  // Complete copied route, populated ONLY for Shorter. No live mark identifiers.
  std::vector<RouteMarkData> m_points;
};

// Pure, bounded geometric proposal; never commits, rebuilds, reads settings or resolves GPS.
// Input must be complete and in route order: Start, Intermediate[0..n-1], Finish.
// Endpoint intermediateIndex values are ignored by RoutePointsLayout and preserved verbatim.
// All positions must be finite, in Mercator world bounds, and already resolved by the caller.
// At most one mark may represent My Position, matching RoutingManager's native invariant.
// Freeze the entire prefix through the LAST passed intermediate (including unpassed holes).
// Copy all metadata; change only suffix intermediate order/indices. Duplicate positions are valid.
// Greedy insertion visits suffix points in input order; exact insertion ties choose earliest slot
// (CheckpointPredictor). Equal/worse/nonfinite lengths never publish an order. Repeated calls
// may find further improvements, but cannot accept equal-length cycles or worsening orders.
// Not a road-network/travel-time optimizer and not a global optimum guarantee.
Proposal ProposeShorterOrder(std::vector<RouteMarkData> const & snapshot);
}  // namespace route_order
