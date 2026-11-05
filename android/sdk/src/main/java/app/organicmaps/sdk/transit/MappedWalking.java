package app.organicmaps.sdk.transit;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;
import java.util.*;

/**
 * Local mapped walking, not station/entrance/accessibility proof or map publisher authentication.
 * Open/query/isCurrent/close on ONE caller-owned background worker after map core initialization.
 * cancel() alone may run on another thread. Construction may copy/hash a map: the caller must
 * admit its byte/disk/time budget. A query deadline is cooperative, not hard native preemption.
 * This bridge does not select installed maps, grant trust, bind a timetable, or mutate navigation.
 * The eventual journey consumer must bind transit AND map identity and retain access uncertainty.
 */
public final class MappedWalking implements AutoCloseable
{
  public enum SnapshotMode { HARD_LINK, PRIVATE_COPY }
  public enum Status { OK, INVALID_REQUEST, OUTSIDE_MAP, MAP_CHANGED, NO_PATH, CANCELLED, LIMIT_REACHED, UNAVAILABLE }
  public enum Maneuver { GO_STRAIGHT, RIGHT, LEFT, FINISH }
  /** Actual native pedestrian maneuver at a path vertex; distances measured from path start. */
  public static final class Direction {
    public final int pointIndex;public final Maneuver maneuver;public final double distanceFromStartMeters;
    private Direction(int index,int code,double meters){pointIndex=index;maneuver=Maneuver.values()[code-1];distanceFromStartMeters=meters;}
  }
  public static final class Result
  {
    public final Status status;
    public final String mapSnapshotId, pathToken;
    public final int estimatedSeconds;
    public final double distanceMeters;
    public final boolean unverifiedEndpointAccess;
    private final double[] geometry;
    public final boolean directionsAvailable;
    private final List<Direction> directions;
    // Legacy host/JNI consumers remain geometry-only, never silently fabricate guidance.
    private Result(int code,String mapId,String token,double[] points,int seconds,double distance,boolean unverified){
      this(code,mapId,token,points,seconds,distance,unverified,new int[0],new int[0],new double[0],false);
    }
    private Result(int code, String mapId, String token, double[] points, int seconds, double distance, boolean unverified,
                   int[] indices,int[] maneuvers,double[] meters,boolean available)
    {
      if (code < 0 || code >= Status.values().length || mapId == null || token == null || points == null
          || points.length > 65536 || points.length % 2 != 0 || !Double.isFinite(distance))
        throw new IllegalArgumentException("invalid native walking result");
      status = Status.values()[code];
      if (status == Status.OK)
      {
        if (!digest(mapId) || !digest(token) || points.length < 4 || seconds <= 0 || seconds > 3600 || distance <= 0)
          throw new IllegalArgumentException("invalid native walking result");
        for (int i = 0; i < points.length; i += 2)
          if (!coordinate(points[i], points[i + 1])) throw new IllegalArgumentException("invalid walking geometry");
      }
      else if (!mapId.isEmpty() || !token.isEmpty() || points.length != 0 || seconds != 0 || distance != 0)
        throw new IllegalArgumentException("partial walking refusal");
      if(indices==null||maneuvers==null||meters==null||indices.length>256||indices.length!=maneuvers.length||indices.length!=meters.length||(!available&&indices.length!=0)||(available&&(status!=Status.OK||indices.length==0)))throw new IllegalArgumentException("invalid native walking instructions");
      List<Direction> steps=new ArrayList<>();int previous=-1;double previousMeters=-1;
      for(int i=0;i<indices.length;i++){
        if(indices[i]<=previous||indices[i]>=points.length/2||maneuvers[i]<1||maneuvers[i]>4||!Double.isFinite(meters[i])||meters[i]<0||meters[i]<previousMeters||meters[i]>distance+0.001||((maneuvers[i]==4)!=(i==indices.length-1)))throw new IllegalArgumentException("invalid native walking instructions");
        steps.add(new Direction(indices[i],maneuvers[i],meters[i]));previous=indices[i];previousMeters=meters[i];
      }
      if(available&&(maneuvers[indices.length-1]!=4||indices[indices.length-1]!=points.length/2-1))throw new IllegalArgumentException("incomplete native walking instructions");
      directionsAvailable=available;directions=Collections.unmodifiableList(steps);
      mapSnapshotId = mapId; pathToken = token; geometry = points.clone(); estimatedSeconds = seconds;
      distanceMeters = distance; unverifiedEndpointAccess = unverified;
    }
    /** Defensive copy of [latitude, longitude, ...], NOT Mercator x/y. */
    public double[] latLonGeometry() { return geometry.clone(); }
    public List<Direction> directions(){return directions;}
    private static Result failure(Status status) { return new Result(status.ordinal(), "", "", new double[0], 0, 0, true); }
  }
  private final Thread owner;
  private final AtomicLong revision = new AtomicLong();
  private volatile long handle;
  public final String mapSnapshotId;
  private MappedWalking(long id, String snapshot) { owner = Thread.currentThread(); handle = id; mapSnapshotId = snapshot; }

  /** Null means unavailable/busy/uninitialized core. Paths are internal app inputs, never an exported URI API. */
  public static MappedWalking open(File mapDirectory, String countryName, long mapVersion,
                                   File privateCacheRoot, SnapshotMode mode, long copyByteBudget)
  {
    if (mapDirectory == null || privateCacheRoot == null || !mapDirectory.isAbsolute() || !privateCacheRoot.isAbsolute()
        || !path(mapDirectory.getPath()) || !path(privateCacheRoot.getPath()) || countryName == null
        || countryName.isEmpty() || countryName.length() > 200 || countryName.equals(".") || countryName.equals("..")
        || countryName.indexOf('/') >= 0 || countryName.indexOf('\\') >= 0 || countryName.indexOf('\0') >= 0
        || mapVersion < 0 || mode == null || copyByteBudget < 0 || (mode == SnapshotMode.PRIVATE_COPY && copyByteBudget == 0))
      throw new IllegalArgumentException("invalid walking snapshot request");
    if (Thread.currentThread().isInterrupted()) return null;
    Library.ensureLoaded();
    long id = nativeOpen(mapDirectory.getPath(), countryName, mapVersion, privateCacheRoot.getPath(),
                         mode == SnapshotMode.PRIVATE_COPY ? 1 : 0, copyByteBudget);
    if (id == 0) return null;
    boolean adopted = false;
    try
    {
      // Future.cancel(true) can arrive during uninterruptible construction/copy/hash. Do not
      // abandon a newly registered native lease when its worker's result is no longer usable.
      if (Thread.currentThread().isInterrupted()) return null;
      String snapshot = nativeSnapshotId(id);
      if (!digest(snapshot)) return null;
      MappedWalking provider = new MappedWalking(id, snapshot); adopted = true; return provider;
    }
    finally { if (!adopted) nativeClose(id); }
  }
  /** Latitude/longitude endpoints, UTC seconds, and the map identity used to prepare those endpoints. */
  public Result query(double fromLat, double fromLon, double toLat, double toLon, long departureUtc, String expectedMap)
  {
    requireOwner(); long id = handle;
    if (id == 0) return Result.failure(Status.UNAVAILABLE);
    if (!coordinate(fromLat, fromLon) || !coordinate(toLat, toLon) || departureUtc < 0 || !digest(expectedMap))
      return Result.failure(Status.INVALID_REQUEST);
    long generation = revision.get();
    Result result = nativeQuery(id, fromLat, fromLon, toLat, toLon, departureUtc, expectedMap);
    if (generation != revision.get()) return Result.failure(Status.CANCELLED);
    return result == null ? Result.failure(Status.UNAVAILABLE) : result;
  }
  /** A fresh worker-side observation, not an atomic screen-render lease or recall after return. */
  public boolean isCurrent() { requireOwner(); long id = handle; return id != 0 && nativeIsCurrent(id, mapSnapshotId); }
  public void cancel() { revision.incrementAndGet(); long id = handle; if (id != 0) nativeCancel(id); }
  @Override public void close()
  {
    requireOwner(); long id = handle; if (id == 0) return;
    handle = 0; revision.incrementAndGet(); nativeClose(id);
  }
  private void requireOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("walking requires its owning worker"); }
  private static boolean coordinate(double lat, double lon) { return Double.isFinite(lat) && Double.isFinite(lon) && lat > -90 && lat < 90 && lon >= -180 && lon <= 180; }
  private static boolean path(String value) { return value != null && value.length() > 0 && value.length() <= 4096 && value.indexOf('\0') < 0; }
  private static boolean digest(String value)
  {
    if (value == null || value.length() != 64) return false;
    for (int i = 0; i < value.length(); ++i) { char c = value.charAt(i); if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return false; }
    return true;
  }
  private static final class Library { static { System.loadLibrary("organicmaps"); } static void ensureLoaded() {} }
  private static native long nativeOpen(String directory, String name, long version, String cache, int mode, long budget);
  private static native String nativeSnapshotId(long handle);
  private static native boolean nativeIsCurrent(long handle, String expected);
  private static native Result nativeQuery(long handle, double fromLat, double fromLon, double toLat, double toLon, long utc, String expected);
  private static native void nativeCancel(long handle);
  private static native void nativeClose(long handle);
}
