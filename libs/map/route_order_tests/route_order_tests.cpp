// SPDX-License-Identifier: Apache-2.0
// Source-only proposal: author did not compile or execute these tests.
#include "testing/testing.hpp"
#include "map/route_order_optimizer.hpp"
#include "routing/checkpoint_predictor.hpp"
#include "geometry/mercator.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <numeric>
#include <string>
#include <vector>

namespace
{
using Points = std::vector<RouteMarkData>;
using route_order::ProposalStatus;
using route_order::ProposeShorterOrder;

Points Route(std::vector<m2::PointD> const & positions)
{
  Points points;
  for (size_t i = 0; i < positions.size(); ++i)
  {
    RouteMarkData p;
    p.m_title = "point-" + std::to_string(i);
    p.m_subTitle = "metadata-" + std::to_string(i);
    p.m_pointType = i == 0 ? RouteMarkType::Start :
                    i + 1 == positions.size() ? RouteMarkType::Finish : RouteMarkType::Intermediate;
    p.m_intermediateIndex = p.m_pointType == RouteMarkType::Intermediate ? i - 1 : 0;
    p.m_position = positions[i];
    points.push_back(p);
  }
  return points;
}

Points Shortenable()
{
  return Route({{0, 0}, {8, 0}, {2, 0}, {10, 0}});
}

void Same(RouteMarkData const & a, RouteMarkData const & b)
{
  TEST_EQUAL(a.m_title, b.m_title, ());
  TEST_EQUAL(a.m_subTitle, b.m_subTitle, ());
  TEST(a.m_pointType == b.m_pointType, ());
  TEST_EQUAL(a.m_intermediateIndex, b.m_intermediateIndex, ());
  TEST_EQUAL(a.m_isVisible, b.m_isVisible, ());
  TEST_EQUAL(a.m_isMyPosition, b.m_isMyPosition, ());
  TEST_EQUAL(a.m_isPassed, b.m_isPassed, ());
  TEST_EQUAL(a.m_replaceWithMyPositionAfterRestart, b.m_replaceWithMyPositionAfterRestart, ());
  TEST_EQUAL(a.m_position, b.m_position, ());
}

void Same(Points const & a, Points const & b)
{
  TEST_EQUAL(a.size(), b.size(), ());
  for (size_t i = 0; i < a.size(); ++i)
    Same(a[i], b[i]);
}

double Length(Points const & points)
{
  double total = 0;
  for (size_t i = 1; i < points.size(); ++i)
    total += mercator::DistanceOnEarth(points[i - 1].m_position, points[i].m_position);
  return total;
}

void Refused(Points const & points, ProposalStatus status)
{
  auto const proposal = ProposeShorterOrder(points);
  TEST(proposal.m_status == status, ());
  TEST(proposal.m_points.empty(), ());
}

void Accepted(Points const & original, route_order::Proposal const & proposal)
{
  TEST(proposal.m_status == ProposalStatus::Shorter, ());
  auto const & result = proposal.m_points;
  TEST_EQUAL(result.size(), original.size(), ());
  TEST(std::isfinite(Length(result)), ());
  TEST(Length(result) < Length(original), ());
  Same(result.front(), original.front());
  Same(result.back(), original.back());
  size_t lastPassed = 0;
  for (size_t i = 1; i + 1 < original.size(); ++i)
    if (original[i].m_isPassed)
      lastPassed = i;
  for (size_t i = 0; i <= lastPassed; ++i)
    Same(result[i], original[i]);

  // Unique authored labels establish a true permutation, not just equal geometry.
  std::vector<std::string> before, after;
  for (auto const & p : original) before.push_back(p.m_title);
  for (auto const & p : result) after.push_back(p.m_title);
  std::sort(before.begin(), before.end());
  std::sort(after.begin(), after.end());
  TEST_EQUAL(before, after, ());
  for (size_t i = lastPassed + 1; i + 1 < result.size(); ++i)
  {
    auto it = std::find_if(original.begin(), original.end(), [&](auto const & p)
                           { return p.m_title == result[i].m_title; });
    TEST(it != original.end(), ());
    auto expected = *it;
    expected.m_intermediateIndex = i - 1;
    Same(result[i], expected);
  }
}

// Unguarded donor-shaped construction ONLY to establish a worse-candidate witness.
// The acceptance assertion below always calls the production helper, not this oracle.
Points RawGreedy(Points const & input)
{
  std::vector<m2::PointD> positions;
  Points middle;
  routing::CheckpointPredictor predictor(input.front().m_position, input.back().m_position);
  for (size_t i = 1; i + 1 < input.size(); ++i)
  {
    size_t slot = predictor.PredictPosition(positions, input[i].m_position);
    positions.insert(positions.begin() + slot, input[i].m_position);
    middle.insert(middle.begin() + slot, input[i]);
  }
  Points result{input.front()};
  for (size_t i = 0; i < middle.size(); ++i)
  {
    middle[i].m_intermediateIndex = i;
    result.push_back(middle[i]);
  }
  result.push_back(input.back());
  return result;
}
}  // namespace

UNIT_TEST(RouteOrder_StrongShorterAndInputUnchanged)
{
  auto input = Shortenable();
  auto const before = input;
  auto const p = ProposeShorterOrder(input);
  Accepted(input, p);
  TEST_EQUAL(p.m_points[1].m_title, input[2].m_title, ());
  TEST_EQUAL(p.m_points[2].m_title, input[1].m_title, ());
  TEST(Length(p.m_points) < 0.6 * Length(input), ());
  Same(input, before);
}

UNIT_TEST(RouteOrder_MetadataAndEndpoints)
{
  auto input = Shortenable();
  input.front().m_intermediateIndex = 71;  // Endpoints ignore this field; never normalize it.
  input.back().m_intermediateIndex = 93;
  input.front().m_isPassed = true;
  input.back().m_isPassed = true;
  input[1].m_isVisible = false;
  input[1].m_isMyPosition = true;  // Use the snapshot pivot, not live GPS.
  input[1].m_replaceWithMyPositionAfterRestart = true;
  input[2].m_subTitle = "non-ASCII: München";
  auto const before = input;
  Accepted(input, ProposeShorterOrder(input));
  Same(input, before);
}

UNIT_TEST(RouteOrder_LastPassedFreezesUnpassedHole)
{
  auto input = Route({{0, 0}, {7, 0}, {1, 0}, {8, 0}, {2, 0}, {10, 0}});
  input[2].m_isPassed = true;
  input[1].m_isVisible = false;
  auto const p = ProposeShorterOrder(input);
  Accepted(input, p);
  Same(p.m_points[1], input[1]); // Not passed, but before the LAST passed stop.
  Same(p.m_points[2], input[2]);
  TEST_EQUAL(p.m_points[3].m_title, input[4].m_title, ());
}

UNIT_TEST(RouteOrder_FrozenTailAndTrivialCompleteRoutes)
{
  auto input = Shortenable();
  input[1].m_isPassed = true;  // Only one remaining stop.
  Refused(input, ProposalStatus::NotShorter);
  input[2].m_isPassed = true;
  Refused(input, ProposalStatus::NotShorter);
  Refused(Route({{0, 0}, {10, 0}}), ProposalStatus::NotShorter);
  Refused(Route({{0, 0}, {8, 0}, {10, 0}}), ProposalStatus::NotShorter);
}

UNIT_TEST(RouteOrder_EqualAndOptimalRefused)
{
  Refused(Route({{0, 0}, {0, 0}, {0, 0}, {0, 0}}), ProposalStatus::NotShorter);
  Refused(Route({{0, 0}, {2, 0}, {8, 0}, {10, 0}}), ProposalStatus::NotShorter);
}

UNIT_TEST(RouteOrder_DuplicatePositionTieIsDeterministic)
{
  auto const input = Route({{0, 0}, {8, 0}, {8, 0}, {2, 0}, {10, 0}});
  auto const first = ProposeShorterOrder(input);
  Accepted(input, first);
  TEST_EQUAL(first.m_points[1].m_title, input[3].m_title, ());
  TEST_EQUAL(first.m_points[2].m_title, input[2].m_title, ()); // Earliest equal insertion.
  TEST_EQUAL(first.m_points[3].m_title, input[1].m_title, ());
  for (size_t i = 0; i < 10; ++i)
  {
    auto const repeat = ProposeShorterOrder(input);
    TEST(repeat.m_status == first.m_status, ());
    Same(repeat.m_points, first.m_points);
  }
  Refused(first.m_points, ProposalStatus::NotShorter);
}

UNIT_TEST(RouteOrder_DuplicateMyPositionRejected)
{
  auto const good = Shortenable();
  for (size_t i = 0; i < good.size(); ++i)
    for (size_t j = i + 1; j < good.size(); ++j)
    {
      auto bad = good;
      bad[i].m_isMyPosition = bad[j].m_isMyPosition = true;
      Refused(bad, ProposalStatus::InvalidSnapshot);
    }
}

UNIT_TEST(RouteOrder_MalformedStructure)
{
  Refused({}, ProposalStatus::InvalidSnapshot);
  Refused(Points{Shortenable().front()}, ProposalStatus::InvalidSnapshot);
  auto const good = Shortenable();
  // Every slot must have precisely its canonical role; includes unknown enum and duplicate endpoints.
  for (size_t i = 0; i < good.size(); ++i)
    for (auto type : {RouteMarkType::Start, RouteMarkType::Intermediate, RouteMarkType::Finish,
                      static_cast<RouteMarkType>(255)})
    {
      if (type == good[i].m_pointType) continue;
      auto bad = good;
      bad[i].m_pointType = type;
      Refused(bad, ProposalStatus::InvalidSnapshot);
    }
  for (size_t i = 1; i + 1 < good.size(); ++i)
    for (size_t index : {size_t{0}, size_t{1}, size_t{2}, std::numeric_limits<size_t>::max()})
    {
      if (index == good[i].m_intermediateIndex) continue;
      auto bad = good;
      bad[i].m_intermediateIndex = index;
      Refused(bad, ProposalStatus::InvalidSnapshot);
    }
  auto bad = good;
  std::swap(bad[1], bad[2]);  // Do not silently sort a malformed/unsorted snapshot.
  Refused(bad, ProposalStatus::InvalidSnapshot);
  bad = good;
  bad.erase(bad.begin());
  Refused(bad, ProposalStatus::InvalidSnapshot);
  bad = good;
  bad.pop_back();
  Refused(bad, ProposalStatus::InvalidSnapshot);
}

UNIT_TEST(RouteOrder_NonfiniteAndWorldBounds)
{
  auto const good = Shortenable();
  auto const infinity = std::numeric_limits<double>::infinity();
  for (size_t i = 0; i < good.size(); ++i)
    for (bool x : {false, true})
      for (double value : {std::numeric_limits<double>::quiet_NaN(), infinity, -infinity,
                           std::nextafter(mercator::Bounds::kMaxX, infinity),
                           std::nextafter(mercator::Bounds::kMinX, -infinity)})
      {
        auto bad = good;
        (x ? bad[i].m_position.x : bad[i].m_position.y) = value;
        Refused(bad, ProposalStatus::InvalidSnapshot);
      }
  // Inclusive world edges, not latitude degrees: y=180 is valid Mercator.
  Refused(Route({{mercator::Bounds::kMinX, mercator::Bounds::kMinY},
                 {mercator::Bounds::kMaxX, mercator::Bounds::kMaxY}}), ProposalStatus::NotShorter);
}

UNIT_TEST(RouteOrder_ActualLayoutCapacity)
{
  size_t const capacity = RoutePointsLayout::kMaxIntermediatePointsCount;
  TEST(capacity >= 2, ());
  std::vector<m2::PointD> positions{{0, 0}, {8, 0}};
  for (size_t i = 1; i < capacity; ++i) positions.emplace_back(2, 0);
  positions.emplace_back(10, 0);
  auto const full = Route(positions);
  TEST_EQUAL(full.size() - 2, capacity, ());
  Accepted(full, ProposeShorterOrder(full));
  positions.insert(positions.end() - 1, m2::PointD(2, 0));
  Refused(Route(positions), ProposalStatus::InvalidSnapshot);
}

UNIT_TEST(RouteOrder_WorseGreedyWitnessAndRepeatDescent)
{
  // Authored geometry, no map/GPS fixtures. Bounded exhaustive input-order controls.
  std::vector<m2::PointD> const pivots{{1, 4}, {3, -3}, {5, 5}, {6, -4}, {8, 2}, {9, -1}};
  std::vector<size_t> permutation(pivots.size());
  std::iota(permutation.begin(), permutation.end(), 0);
  size_t permutationCount = 1;
  for (size_t i = 2; i <= pivots.size(); ++i) permutationCount *= i;
  bool sawWorse = false;
  bool sawShorter = false;
  do
  {
    std::vector<m2::PointD> positions{{0, 0}};
    for (auto i : permutation) positions.push_back(pivots[i]);
    positions.emplace_back(10, 0);
    auto input = Route(positions);
    auto const raw = RawGreedy(input);
    if (Length(raw) > Length(input))
    {
      sawWorse = true;
      Refused(input, ProposalStatus::NotShorter);
    }
    bool stopped = false;
    std::vector<std::vector<std::string>> seen;
    for (size_t iteration = 0; iteration <= permutationCount; ++iteration)
    {
      auto const p = ProposeShorterOrder(input);
      if (p.m_status != ProposalStatus::Shorter)
      {
        TEST(p.m_status == ProposalStatus::NotShorter, ());
        TEST(p.m_points.empty(), ());
        stopped = true;
        break;
      }
      sawShorter = true;
      Accepted(input, p);
      std::vector<std::string> ids;
      for (auto const & point : p.m_points) ids.push_back(point.m_title);
      TEST(std::find(seen.begin(), seen.end(), ids) == seen.end(), ());
      seen.push_back(ids);
      input = p.m_points;
    }
    TEST(stopped, ()); // Finite strict descent over permutations must terminate.
  } while (std::next_permutation(permutation.begin(), permutation.end()));
  TEST(sawWorse, ("Required discriminating precondition: raw greedy must actually worsen a case"));
  TEST(sawShorter, ("Required positive control in the authored non-collinear corpus"));
}
