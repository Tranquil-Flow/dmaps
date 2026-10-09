package app.organicmaps.sdk.transit;

/** Immutable stop row. distanceMetres is NaN for text-search results without distance. */
public final class Stop
{
  public final long id;
  public final String sourceId, name, platform;
  public final Long parentId;      // nullable
  public final Double lat, lon;    // nullable
  public final int locationType;
  public final double distanceMetres;

  public Stop(long id, String sourceId, String name, String platform, Long parentId,
              Double lat, Double lon, int locationType, double distanceMetres)
  {
    this.id = id;
    this.sourceId = sourceId;
    this.name = name;
    this.platform = platform;
    this.parentId = parentId;
    this.lat = lat;
    this.lon = lon;
    this.locationType = locationType;
    this.distanceMetres = distanceMetres;
  }
}
