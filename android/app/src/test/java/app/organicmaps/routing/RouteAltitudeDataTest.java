package app.organicmaps.routing;

import app.organicmaps.sdk.routing.RouteAltitudeData;
import org.junit.Test;
import static org.junit.Assert.*;

public class RouteAltitudeDataTest
{
  @Test public void rejectsMissingSingleMismatchedNonfiniteAndReversedSamples()
  {
    double[][] badDistances = {null, {}, {0}, {0, 1}, {0, Double.NaN}, {0, -1}, {0, 2, 1}, {1, 2}, {0, 0}};
    double[][] badHeights = {{1, 2}, {}, {1}, {1}, {1, 2}, {1, 2}, {1, 2, 3}, {1, 2}, {1, 2}};
    for (int i = 0; i < badDistances.length; ++i)
    {
      final int index = i;
      assertThrows(IllegalArgumentException.class, () -> new RouteAltitudeData(badDistances[index], badHeights[index]));
    }
    assertThrows(IllegalArgumentException.class, () -> new RouteAltitudeData(new double[] {0, 1}, new double[] {0, Double.POSITIVE_INFINITY}));
  }

  @Test public void boundsCopiesBeforeAllocationAndPreservesMetres()
  {
    assertThrows(IllegalArgumentException.class, () -> new RouteAltitudeData(
        new double[RouteAltitudeData.MAX_SAMPLES + 1], new double[RouteAltitudeData.MAX_SAMPLES + 1]));
    double[] d = {0, 100};
    double[] h = {-30, -20};
    RouteAltitudeData p = new RouteAltitudeData(d, h);
    d[1] = 2; h[0] = 900;
    assertEquals(100, p.distanceAt(1), 0);
    assertEquals(-30, p.altitudeAt(0), 0);
    assertEquals(2, p.size());
  }
}
