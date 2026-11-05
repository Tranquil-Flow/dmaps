/*
 * Copyright 2020 My.com B.V. (Mail.Ru Group)
 * Copyright 2020-2026 Organic Maps Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified for DMaps: Android-independent metre-only helper, validated primitive arrays,
 * explicit missing-data result, exact-vertex handling and finite presentation guards.
 * Axis policy and interpolation edge cases derive from Organic Maps ElevationChartUtils
 * at 6d7c1b39d4e64403865051f31afb61b9e6e1e03d. Preserve the accompanying NOTICE.
 */
package app.organicmaps.util;

import java.util.OptionalDouble;

/** Presentation maths only: no route geometry, track mutation or ascent/descent calculation. */
public final class ElevationChartMath
{
  private static final double METRIC_STEP_METRES = 50.0;
  private static final double IMPERIAL_STEP_METRES = 30.48; // 100 feet, not a conversion of source data.
  private static final int MAX_STEP_COUNT = 6;
  private static final double TINY_SEGMENT_METRES = 1e-9;

  private ElevationChartMath() {}

  /** Bounds are metres suitable for the retained chart's float axis; label count is stepCount + 1. */
  public static final class AxisBounds
  {
    public final float lowerMetres;
    public final float upperMetres;
    public final int stepCount;

    private AxisBounds(float lower, float upper, int steps)
    {
      lowerMetres = lower;
      upperMetres = upper;
      stepCount = steps;
    }
  }

  /**
   * Returns null for no samples (caller retains/hides the bitmap fallback, never fabricates a profile).
   * Heights need not be ordered. Throws IllegalArgumentException for null, nonfinite or unrenderable
   * samples. All source heights remain metres and the input is never modified.
   */
  public static AxisBounds axisBounds(double[] altitudeMetres, boolean imperial)
  {
    if (altitudeMetres == null)
      throw new IllegalArgumentException("Missing altitude array");
    if (altitudeMetres.length == 0)
      return null;
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    for (double altitude : altitudeMetres)
    {
      requireFinite(altitude);
      min = Math.min(min, altitude);
      max = Math.max(max, altitude);
    }
    return axisBounds(min, max, imperial);
  }

  /**
   * Pads by 10%, with at least one base step of vertical room for a flat/near-flat profile.
   * Rounds outward to 50m / 100ft boundaries, doubling the interval until at most six steps remain.
   * Rejects a range that cannot be represented by a finite, nonzero float chart axis (including
   * float range subtraction). Callers must use their missing/invalid-data fallback on rejection.
   */
  public static AxisBounds axisBounds(double minMetres, double maxMetres, boolean imperial)
  {
    requireFinite(minMetres);
    requireFinite(maxMetres);
    if (minMetres > maxMetres || Math.abs(minMetres) > Float.MAX_VALUE
        || Math.abs(maxMetres) > Float.MAX_VALUE)
      throw new IllegalArgumentException("Invalid altitude range");

    double step = imperial ? IMPERIAL_STEP_METRES : METRIC_STEP_METRES;
    double range = maxMetres - minMetres;
    double padding = Math.max(range * 0.1, (step - range) * 0.5);
    double lower = Math.floor((minMetres - padding) / step) * step;
    double upper = Math.ceil((maxMetres + padding) / step) * step;
    // At extreme magnitudes a flat profile's padding can disappear even in double precision.
    if (!(lower < minMetres && upper > maxMetres))
      throw new IllegalArgumentException("Altitude padding is not representable");

    double effectiveStep = step;
    while (Math.ceil((upper - lower) / effectiveStep) > MAX_STEP_COUNT)
      effectiveStep *= 2;
    int steps = (int) Math.ceil((upper - lower) / effectiveStep);
    upper = lower + steps * effectiveStep;

    float floatLower = (float) lower;
    float floatUpper = (float) upper;
    // Outward conversion prevents float rounding from clipping a double-precision sample.
    if (floatLower > lower)
      floatLower = Math.nextDown(floatLower);
    if (floatUpper < upper)
      floatUpper = Math.nextUp(floatUpper);
    float floatRange = floatUpper - floatLower;
    if (!Float.isFinite(floatLower) || !Float.isFinite(floatUpper)
        || !Float.isFinite(floatRange) || floatRange <= 0 || steps < 1 || steps > MAX_STEP_COUNT)
      throw new IllegalArgumentException("Altitude bounds exceed float chart capacity");
    return new AxisBounds(floatLower, floatUpper, steps);
  }

  /**
   * Interpolates altitude, NOT a geographic point. Distances must be finite, nonnegative cumulative
   * metres in nondecreasing order; altitudes must be finite with equal array length. Rejects malformed
   * arrays before endpoint clamping, including invalid unused tails. Caller must not mutate arrays
   * concurrently. Validation is O(n); the lower-bound search is O(log n). No copies or writes occur.
   *
   * Empty input returns OptionalDouble.empty(), not an invented sea-level sample. A single point
   * clamps everywhere. Finite targets outside the profile clamp to its first/last altitude. At an
   * exact repeated distance the FIRST vertex wins; just after it the LAST duplicate is the outgoing
   * segment's start. Thus equal distances with different heights can cause a deliberate discontinuity.
   * An all-equal profile uses the first height at/before that distance and the last height after it.
   * For interiors shorter than 1e-9m, hold the preceding height (donor noise guard), but exact vertices
   * always preserve their height. Nonfinite targets throw, including for empty input.
   */
  public static OptionalDouble interpolateAltitude(double[] distanceMetres, double[] altitudeMetres,
                                                    double targetMetres)
  {
    requireFinite(targetMetres);
    if (distanceMetres == null || altitudeMetres == null || distanceMetres.length != altitudeMetres.length)
      throw new IllegalArgumentException("Missing or unequal profile arrays");
    for (int i = 0; i < distanceMetres.length; i++)
    {
      requireFinite(distanceMetres[i]);
      requireFinite(altitudeMetres[i]);
      if (distanceMetres[i] < 0 || (i > 0 && distanceMetres[i] < distanceMetres[i - 1]))
        throw new IllegalArgumentException("Distances must be nonnegative and nondecreasing");
    }
    int count = distanceMetres.length;
    if (count == 0)
      return OptionalDouble.empty();
    if (targetMetres <= distanceMetres[0])
      return OptionalDouble.of(altitudeMetres[0]);
    if (targetMetres > distanceMetres[count - 1])
      return OptionalDouble.of(altitudeMetres[count - 1]);

    int low = 0;
    int high = count - 1;
    while (low < high)
    {
      int mid = low + (high - low) / 2;
      if (distanceMetres[mid] < targetMetres)
        low = mid + 1;
      else
        high = mid;
    }
    if (distanceMetres[low] == targetMetres)
      return OptionalDouble.of(altitudeMetres[low]);
    double length = distanceMetres[low] - distanceMetres[low - 1];
    double before = altitudeMetres[low - 1];
    double after = altitudeMetres[low];
    if (length < TINY_SEGMENT_METRES)
      return OptionalDouble.of(before);
    double fraction = (targetMetres - distanceMetres[low - 1]) / length;
    // Opposite-sign subtraction can overflow even though the convex combination is finite.
    double value = (before < 0) != (after < 0)
        ? (1 - fraction) * before + fraction * after
        : before + fraction * (after - before);
    return OptionalDouble.of(Math.max(Math.min(before, after), Math.min(Math.max(before, after), value)));
  }

  private static void requireFinite(double value)
  {
    if (!Double.isFinite(value))
      throw new IllegalArgumentException("Nonfinite chart value");
  }
}
