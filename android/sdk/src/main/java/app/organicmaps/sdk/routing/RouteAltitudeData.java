package app.organicmaps.sdk.routing;

import androidx.annotation.Keep;

/** Immutable computed-route samples in metres. No track identity, geometry or statistics. */
@Keep
public final class RouteAltitudeData
{
  // Keep in sync with the JNI presentation limit. Oversized profiles retain the bitmap.
  public static final int MAX_SAMPLES = 4096;
  private final double[] mDistances;
  private final double[] mAltitudes;

  // Called by JNI: ([D[D)V. Validate before making bounded defensive copies.
  public RouteAltitudeData(double[] distances, double[] altitudes)
  {
    if (distances == null || altitudes == null || distances.length != altitudes.length
        || distances.length < 2 || distances.length > MAX_SAMPLES)
      throw new IllegalArgumentException("Missing or oversized route profile");
    for (int i = 0; i < distances.length; ++i)
    {
      if (!Double.isFinite(distances[i]) || !Double.isFinite(altitudes[i]) || distances[i] < 0
          || (i > 0 && distances[i] < distances[i - 1]))
        throw new IllegalArgumentException("Invalid route profile");
    }
    if (distances[0] != 0 || distances[distances.length - 1] <= 0)
      throw new IllegalArgumentException("No route distance span");
    mDistances = distances.clone();
    mAltitudes = altitudes.clone();
  }

  public int size() { return mDistances.length; }
  public double distanceAt(int index) { return mDistances[index]; }
  public double altitudeAt(int index) { return mAltitudes[index]; }
}
