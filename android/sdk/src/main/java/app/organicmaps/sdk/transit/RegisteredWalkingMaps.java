package app.organicmaps.sdk.transit;

import android.os.Looper;
import androidx.annotation.Keep;
import androidx.annotation.UiThread;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

/** Read-only metadata from registered country maps. No storage scan, map download or navigation change.
 * Capture on the Framework/UI owner; pass immutable candidates to the walking worker.
 * Registration at capture is NOT a continuing lease, publisher trust or a content identity.
 * A candidate's bounds do not prove graph availability, connectivity or station access.
 */
public final class RegisteredWalkingMaps {
  public static final int MAX_MAPS=128;
  @Keep public static final class Candidate {
    private final File directory;
    public final String countryName;
    public final long mapVersion,cachedBytes;
    public final double minLatitude,minLongitude,maxLatitude,maxLongitude;
    @Keep private Candidate(String directory,String name,long version,long bytes,double minLat,double minLon,double maxLat,double maxLon) {
      if(directory==null||directory.isEmpty()||directory.length()>4096||directory.indexOf('\0')>=0||!new File(directory).isAbsolute()
          ||name==null||name.isEmpty()||name.length()>200||name.equals(".")||name.equals("..")||name.indexOf('/')>=0||name.indexOf('\\')>=0||name.indexOf('\0')>=0
          ||version<0||bytes<=0||!coordinate(minLat,minLon)||!coordinate(maxLat,maxLon)||minLat>maxLat||minLon>maxLon)
        throw new IllegalArgumentException("invalid registered walking map metadata");
      this.directory=new File(directory);countryName=name;mapVersion=version;cachedBytes=bytes;
      minLatitude=minLat;minLongitude=minLon;maxLatitude=maxLat;maxLongitude=maxLon;
    }
    /** Metadata key only; callers must also recheck the opened provider's inode/content currency. */
    public boolean sameGeneration(Candidate other) {
      return other!=null&&directory.equals(other.directory)&&countryName.equals(other.countryName)
          &&mapVersion==other.mapVersion&&cachedBytes==other.cachedBytes;
    }
    public boolean boundsContain(double latitude,double longitude) {
      return coordinate(latitude,longitude)&&latitude>=minLatitude&&latitude<=maxLatitude&&longitude>=minLongitude&&longitude<=maxLongitude;
    }
    /** Open only on the admitted owner worker; retain/close the result in that worker's finally.
     * Native open rechecks real file size, pins/hashes actual content and may refuse a stale descriptor.
     * cachedBytes is NOT current disk/space evidence. Do not return a provider through a cancelled Future.
     */
    public MappedWalking open(File privateCacheRoot,MappedWalking.SnapshotMode mode,long copyByteBudget) {
      return MappedWalking.open(directory,countryName,mapVersion,privateCacheRoot,mode,copyByteBudget);
    }
  }
  @Keep public static final class Inventory {
    public final boolean complete;
    public final List<Candidate> maps;
    @Keep private Inventory(boolean complete,Candidate[] maps) {
      if(maps==null||maps.length>MAX_MAPS||(!complete&&maps.length!=0))throw new IllegalArgumentException("invalid registered walking map inventory");
      for(Candidate map:maps)if(map==null)throw new IllegalArgumentException("invalid registered walking map inventory");
      this.complete=complete;this.maps=Collections.unmodifiableList(Arrays.asList(maps.clone()));
    }
    /** Conservative single-map candidate list, not a best-map ranking or cross-map routing promise.
     * Incomplete capture cannot be interpreted as 'no maps'. A byte budget is an explicit caller limit.
     */
    public List<Candidate> candidates(double fromLat,double fromLon,double toLat,double toLon,long byteBudget) {
      if(!complete)throw new IllegalStateException("registered walking map inventory unavailable");
      if(!coordinate(fromLat,fromLon)||!coordinate(toLat,toLon)||byteBudget<=0)throw new IllegalArgumentException("invalid walking map selection");
      List<Candidate> result=new ArrayList<>();
      for(Candidate map:maps)if(map.cachedBytes<=byteBudget&&map.boundsContain(fromLat,fromLon)&&map.boundsContain(toLat,toLon))result.add(map);
      return Collections.unmodifiableList(result);
    }
  }
  private RegisteredWalkingMaps() {}
  @UiThread public static Inventory capture() {
    if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("walking map capture requires Framework owner");
    Library.ensureLoaded();Inventory result=nativeCapture();return result==null?new Inventory(false,new Candidate[0]):result;
  }
  private static boolean coordinate(double lat,double lon) {return Double.isFinite(lat)&&Double.isFinite(lon)&&lat>-90&&lat<90&&lon>=-180&&lon<=180;}
  private static final class Library {static {System.loadLibrary("organicmaps");}static void ensureLoaded(){}}
  private static native Inventory nativeCapture();
}
