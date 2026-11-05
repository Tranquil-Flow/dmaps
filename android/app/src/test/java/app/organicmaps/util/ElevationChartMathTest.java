/*
 * Copyright 2020 My.com B.V. (Mail.Ru Group)
 * Copyright 2020-2026 Organic Maps Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified for DMaps: primitive-array contracts and additional missing/invalid/extreme-input
 * controls. Axis/interpolation scenarios adapted from Organic Maps ElevationChartUtilsTest
 * at 6d7c1b39d4e64403865051f31afb61b9e6e1e03d. Preserve the accompanying NOTICE.
 */
package app.organicmaps.util;

import static org.junit.Assert.*;

import java.util.OptionalDouble;
import org.junit.Test;

/** Pure JVM tests; scenario provenance and modification notice: see lane ATTRIBUTION.md. */
public class ElevationChartMathTest
{
  private static final double EPS = 0.0001;

  private static ElevationChartMath.AxisBounds bounds(double low, double high)
  {
    return ElevationChartMath.axisBounds(low, high, false);
  }

  private static void assertBounds(ElevationChartMath.AxisBounds b, double low, double high, int steps)
  {
    assertEquals(low, b.lowerMetres, EPS);
    assertEquals(high, b.upperMetres, EPS);
    assertEquals(steps, b.stepCount);
  }

  private static double at(double[] x, double[] y, double q)
  {
    OptionalDouble result = ElevationChartMath.interpolateAltitude(x, y, q);
    assertTrue(result.isPresent());
    return result.getAsDouble();
  }

  private static void invalid(Runnable call)
  {
    assertThrows(IllegalArgumentException.class, call::run);
  }

  @Test public void nearFlatHasRoomAbovePeak()
  {
    assertBounds(bounds(98, 99), 50, 150, 2);
    assertEquals(0.49, (99 - bounds(98, 99).lowerMetres) / 100.0, EPS);
  }

  @Test public void subMetreRangeStaysTight() { assertBounds(bounds(98.5, 99), 50, 150, 2); }
  @Test public void flatIsCentered() { assertBounds(bounds(100, 100), 50, 150, 2); }
  @Test public void flatZeroDoesNotCollapse() { assertBounds(bounds(0, 0), -50, 50, 2); }
  @Test public void negativeElevations() { assertBounds(bounds(-120, -80), -150, -50, 2); }
  @Test public void mixedElevations() { assertBounds(bounds(-5, 3), -50, 50, 2); }
  @Test public void ordinaryRange() { assertBounds(bounds(120, 180), 100, 200, 2); }
  @Test public void wideRangeCapsSteps() { assertBounds(bounds(1200, 2400), 1050, 2650, 4); }
  @Test public void imperialBoundsRemainMetres()
  {
    assertBounds(ElevationChartMath.axisBounds(98, 99, true), 60.96, 121.92, 2);
  }
  @Test public void imperialFlatZero() { assertBounds(ElevationChartMath.axisBounds(0, 0, true), -30.48, 30.48, 2); }
  @Test public void emptyAxisHasNoInventedRange()
  {
    assertNull(ElevationChartMath.axisBounds(new double[0], false));
  }
  @Test public void singleAxisAndUnorderedHeights()
  {
    assertBounds(ElevationChartMath.axisBounds(new double[] {100}, false), 50, 150, 2);
    assertBounds(ElevationChartMath.axisBounds(new double[] {180, 120, 150}, false), 100, 200, 2);
  }
  @Test public void axisRejectsInvalidInput()
  {
    invalid(() -> bounds(2, 1));
    invalid(() -> ElevationChartMath.axisBounds(null, false));
    for (double bad : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
    {
      invalid(() -> bounds(bad, 100));
      invalid(() -> bounds(0, bad));
      invalid(() -> ElevationChartMath.axisBounds(new double[] {0, bad}, true));
    }
  }
  @Test public void axisRejectsUnrepresentableFloatPresentation()
  {
    invalid(() -> bounds(-Double.MAX_VALUE, Double.MAX_VALUE));
    invalid(() -> bounds(Double.MAX_VALUE, Double.MAX_VALUE));
    invalid(() -> bounds(-Float.MAX_VALUE, Float.MAX_VALUE));
    invalid(() -> bounds(Float.MAX_VALUE, Float.MAX_VALUE));
  }
  @Test public void axisEnclosesSamplesInBothUnits()
  {
    for (boolean imperial : new boolean[] {false, true})
      for (int min = -300; min <= 3000; min += 37)
        for (double range : new double[] {0, 0.5, 1, 5, 42, 60, 700, 2000})
        {
          ElevationChartMath.AxisBounds b = ElevationChartMath.axisBounds(min, min + range, imperial);
          assertTrue(b.lowerMetres <= min);
          assertTrue(b.upperMetres >= min + range);
          assertTrue(Float.isFinite(b.upperMetres - b.lowerMetres));
          assertTrue(b.upperMetres > b.lowerMetres);
          assertTrue(b.stepCount >= 1 && b.stepCount <= 6);
        }
  }
  @Test public void largeRepresentableAxisStaysFinite()
  {
    ElevationChartMath.AxisBounds b = bounds(-1e30, 1e30);
    assertTrue(b.lowerMetres <= -1e30);
    assertTrue(b.upperMetres >= 1e30);
    assertTrue(Float.isFinite(b.upperMetres - b.lowerMetres));
  }
  @Test public void emptyInterpolationHasNoInventedAltitude()
  {
    assertFalse(ElevationChartMath.interpolateAltitude(new double[0], new double[0], 10).isPresent());
  }
  @Test public void singleInterpolationClamps()
  {
    for (double q : new double[] {-1, 10, 20})
      assertEquals(120, at(new double[] {10}, new double[] {120}, q), 0);
  }
  @Test public void interpolationClampsBothEnds()
  {
    assertEquals(100, at(new double[] {10, 30}, new double[] {100, 200}, -10), 0);
    assertEquals(200, at(new double[] {10, 30}, new double[] {100, 200}, 40), 0);
  }
  @Test public void ascendingDescendingAndFlat()
  {
    double[] x = {10, 30, 70, 90}, y = {100, 200, 0, 0};
    assertEquals(125, at(x, y, 15), EPS);
    assertEquals(150, at(x, y, 40), EPS);
    assertEquals(0, at(x, y, 80), 0);
  }
  @Test public void exactVerticesArePreserved()
  {
    double[] x = {0, 5e-10, 70}, y = {100, 200, -20};
    for (int i = 0; i < x.length; i++) assertEquals(y[i], at(x, y, x[i]), 0);
  }
  @Test public void duplicateDistanceUsesFirstMatchThenOutgoingSegment()
  {
    double[] x = {0, 10, 10, 20}, y = {0, 100, 200, 300};
    assertEquals(50, at(x, y, 5), EPS);
    assertEquals(100, at(x, y, 10), 0);
    assertEquals(250, at(x, y, 15), EPS);
  }
  @Test public void duplicateEndpointsAndDegenerateProfile()
  {
    double[] x = {5, 5, 5}, y = {10, 20, 30};
    assertEquals(10, at(x, y, 4), 0);
    assertEquals(10, at(x, y, 5), 0);
    assertEquals(30, at(x, y, 6), 0);
    assertEquals(20, at(new double[] {0, 10, 10}, new double[] {0, 20, 30}, 10), 0);
  }
  @Test public void tinyInteriorSegmentHoldsPreviousButNotExactVertex()
  {
    assertEquals(100, at(new double[] {0, 5e-10}, new double[] {100, 200}, 2.5e-10), 0);
    assertEquals(200, at(new double[] {0, 5e-10}, new double[] {100, 200}, 5e-10), 0);
  }
  @Test public void thresholdSegmentInterpolates()
  {
    assertEquals(150, at(new double[] {0, 1e-9}, new double[] {100, 200}, 5e-10), EPS);
  }
  @Test public void rejectsMalformedArraysEvenBeyondClampedTarget()
  {
    invalid(() -> at(null, new double[0], 0));
    invalid(() -> at(new double[0], null, 0));
    invalid(() -> at(new double[] {0}, new double[0], 0));
    invalid(() -> at(new double[] {0, 3, 2}, new double[] {0, 1, 2}, -1));
    invalid(() -> at(new double[] {-1, 0}, new double[] {0, 1}, 0));
    for (double bad : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
    {
      invalid(() -> at(new double[] {0, bad}, new double[] {0, 1}, -1));
      invalid(() -> at(new double[] {0, 1}, new double[] {0, bad}, -1));
      invalid(() -> at(new double[] {0}, new double[] {0}, bad));
      invalid(() -> ElevationChartMath.interpolateAltitude(new double[0], new double[0], bad));
    }
  }
  @Test public void oppositeExtremeHeightsDoNotOverflow()
  {
    assertEquals(0, at(new double[] {0, 2}, new double[] {-Double.MAX_VALUE, Double.MAX_VALUE}, 1), 0);
    assertEquals(0, at(new double[] {0, 2}, new double[] {Double.MAX_VALUE, -Double.MAX_VALUE}, 1), 0);
  }
  @Test public void extremeDistancesAndEqualHeightsStayFinite()
  {
    assertEquals(5, at(new double[] {0, Double.MAX_VALUE}, new double[] {0, 10}, Double.MAX_VALUE / 2), EPS);
    assertEquals(Double.MAX_VALUE, at(new double[] {0, 2}, new double[] {Double.MAX_VALUE, Double.MAX_VALUE}, 1), 0);
  }
  @Test public void monotoneProfileIsBoundedAndMonotone()
  {
    double previous = -100;
    for (int q = -5; q <= 45; q++)
    {
      double h = at(new double[] {0, 10, 10, 40}, new double[] {-100, 0, 10, 300}, q);
      assertTrue(h >= previous && h >= -100 && h <= 300);
      previous = h;
    }
  }
  @Test public void sourceMetresNeverMutated()
  {
    double[] x = {0, 10, 20}, y = {-5, 99, 3};
    double[] beforeX = x.clone(), beforeY = y.clone();
    ElevationChartMath.axisBounds(y, true);
    at(x, y, 12);
    assertArrayEquals(beforeX, x, 0);
    assertArrayEquals(beforeY, y, 0);
  }
}
