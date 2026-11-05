package app.organicmaps.sdk.transit;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.*;
import java.util.*;

/** Offline scheduled platform-to-platform search. No proximity-derived walking, live prediction,
 * persistence, network or claim of global optimality outside the supplied connection graph. */
public final class JourneyPlanner {
  static final int BOARD_LIMIT=300, LABEL_LIMIT=512, TRIP_LIMIT=1024, ROW_LIMIT=32768, CANDIDATE_LIMIT=4096;
  public enum Issue { BOARD_LIMIT, LABEL_LIMIT, TRIP_LIMIT, ROW_LIMIT, WORK_LIMIT, CONFLICTING_RULES, LINKED_TRIP_UNSUPPORTED, DATA_WINDOW_END, WALK_TIME_UNAVAILABLE, WALK_QUERY_LIMIT }
  /** Platforms and their transit identity are selected under the same repository guard. */
  public static final class Selection {
    public final List<Stop> platforms; public final String packEnvelopeSha256; public final PackInfo packInfo;
    Selection(List<Stop> platforms,String envelope){this(platforms,envelope,null);}
    Selection(List<Stop> platforms,String envelope,PackInfo info){this.platforms=Collections.unmodifiableList(new ArrayList<>(platforms));packEnvelopeSha256=envelope;packInfo=info;}
  }

  /** Search hits bound before the user chooses; numeric IDs cannot survive pack replacement. */
  public static final class Candidates {
    public final List<Stop> stops; public final String packEnvelopeSha256; public final PackInfo packInfo;
    Candidates(List<Stop> stops,String envelope,PackInfo info){this.stops=Collections.unmodifiableList(new ArrayList<>(stops));packEnvelopeSha256=envelope;packInfo=info;}
  }

  /** Caller-supplied already-routed pedestrian connection; token identifies the local mapped path.
   * Timetable transfer rules and straight-line proximity must NEVER manufacture these objects. */
  public static final class Walk {
    public final long fromStopId,toStopId; public final int seconds; public final String pathToken;
    public final String packEnvelopeSha256,mapSnapshotId;
    public final Instant routedAt;
    public final double distanceMeters;
    public final boolean unverifiedEndpointAccess;
    private final double[] geometry;
    public final boolean directionsAvailable;private final List<MappedWalking.Direction> directions;
    public List<MappedWalking.Direction> directions(){return directions;}
    public double[] latLonGeometry(){return geometry.clone();}
    /** Owner-worker only. Query coordinates are taken from the actual atomic selections, never
     * supplied separately and stamped with a later digest. Caller owns provider cleanup and the
     * eventual transit/map/UI publication fence. Native routing time has UTC-second precision. */
    public static Walk route(MappedWalking provider,Selection from,long fromId,Selection to,long toId,Instant departure)throws IOException {
      return routed(provider,from,fromId,to,toId,departure,false);
    }
    /** Null means the current native map reports no path/outside coverage, never synthetic success.
     * Cancellation, stale identities, invalid inputs and exhausted native budgets still fail. */
    public static Walk routeIfAvailable(MappedWalking provider,Selection from,long fromId,Selection to,long toId,Instant departure)throws IOException {
      return routed(provider,from,fromId,to,toId,departure,true);
    }
    private static Walk routed(MappedWalking provider,Selection from,long fromId,Selection to,long toId,Instant departure,boolean optional)throws IOException {
      String envelope=Request.generation(from,to);
      if(provider==null||departure==null||fromId==toId)throw new IllegalArgumentException("mapped walking inputs required");
      Stop a=selected(from,fromId),b=selected(to,toId);
      if(a.locationType!=0||b.locationType!=0||a.lat==null||a.lon==null||b.lat==null||b.lon==null)throw new IOException("mapped walking coordinates unavailable");
      if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("mapped walking cancelled");
      // Coincident GTFS points do not prove that two distinct platforms are connected.
      // No zero-length native request and no fabricated positive walking time.
      if(optional&&Double.isFinite(a.lat)&&Double.isFinite(a.lon)&&Math.abs(a.lat)<90&&Math.abs(a.lon)<=180&&a.lat.doubleValue()==b.lat.doubleValue()&&a.lon.doubleValue()==b.lon.doubleValue()){
        if(!provider.isCurrent())throw new IOException("mapped walking source changed");return null;
      }
      MappedWalking.Result result=provider.query(a.lat,a.lon,b.lat,b.lon,departure.getEpochSecond(),provider.mapSnapshotId);
      if(optional&&(result.status==MappedWalking.Status.NO_PATH||result.status==MappedWalking.Status.OUTSIDE_MAP)){
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("mapped walking cancelled");
        if(!provider.isCurrent())throw new IOException("mapped walking source changed");return null;
      }
      if(result.status!=MappedWalking.Status.OK||!provider.mapSnapshotId.equals(result.mapSnapshotId))throw new IOException("mapped walking unavailable: "+result.status.name());
      Walk walk=new Walk(fromId,toId,result.estimatedSeconds,result.pathToken,envelope,result.mapSnapshotId,departure,result.distanceMeters,result.latLonGeometry(),result.unverifiedEndpointAccess,result.directionsAvailable,result.directions());
      if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("mapped walking cancelled");
      if(!provider.isCurrent())throw new IOException("mapped walking source changed");
      return walk;
    }
    private static Stop selected(Selection selection,long id){
      Stop found=null;for(Stop stop:selection.platforms)if(stop!=null&&stop.id==id){if(found!=null)throw new IllegalArgumentException("ambiguous walking endpoint");found=stop;}
      if(found==null)throw new IllegalArgumentException("walking endpoint not selected");return found;
    }
    /** Legacy trusted local, time-independent connection; not evidence of native geometry. */
    public Walk(long from,long to,int seconds,String pathToken){this(from,to,seconds,pathToken,null);}
    public Walk(long from,long to,int seconds,String pathToken,String envelope){this(from,to,seconds,pathToken,envelope,null,null,0,new double[0],true);}
    private Walk(long from,long to,int seconds,String pathToken,String envelope,String map,Instant at,double distance,double[] points,boolean uncertain){
      this(from,to,seconds,pathToken,envelope,map,at,distance,points,uncertain,false,Collections.emptyList());
    }
    private Walk(long from,long to,int seconds,String pathToken,String envelope,String map,Instant at,double distance,double[] points,boolean uncertain,boolean available,List<MappedWalking.Direction> steps){
      if(steps==null||steps.size()>256||available!=!steps.isEmpty())throw new IllegalArgumentException("bounded mapped walking instructions required");
      directionsAvailable=available;directions=Collections.unmodifiableList(new ArrayList<>(steps));
      if(from<1||to<1||from==to||seconds<1||seconds>3600||pathToken==null||pathToken.isEmpty()||pathToken.length()>256)
        throw new IllegalArgumentException("bounded mapped walking connection required");
      fromStopId=from;toStopId=to;this.seconds=seconds;this.pathToken=pathToken;
      if(envelope!=null&&!envelope.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("canonical generation digest required");packEnvelopeSha256=envelope;
      mapSnapshotId=map;routedAt=at;distanceMeters=distance;geometry=points.clone();unverifiedEndpointAccess=uncertain;
    }
  }
  public static final class Request {
    public final List<Long> origins,destinations; public final Instant earliest;
    public final int horizonMinutes,maxBoardings; public final List<Walk> walks;
    public final String expectedEnvelopeSha256;
    /** Unbound pure-network request; repository planning requires the selection-bound overload. */
    public Request(List<Long> origins,List<Long> destinations,Instant earliest,int horizonMinutes,int maxBoardings,List<Walk> walks){
      this(origins,destinations,earliest,horizonMinutes,maxBoardings,walks,null);
    }
    public Request(Selection origins,Selection destinations,Instant earliest,int horizonMinutes,int maxBoardings,List<Walk> walks){
      this(selectionIds(origins),selectionIds(destinations),earliest,horizonMinutes,maxBoardings,walks,generation(origins,destinations));
    }
    private Request(List<Long> origins,List<Long> destinations,Instant earliest,int horizonMinutes,int maxBoardings,List<Walk> walks,String generation){
      this.origins=ids(origins);this.destinations=ids(destinations);
      if(earliest==null||earliest.isBefore(Instant.parse("1900-01-01T00:00:00Z"))||earliest.isAfter(Instant.parse("2201-01-08T00:00:00Z"))
          ||horizonMinutes<1||horizonMinutes>1440||maxBoardings<1||maxBoardings>4||walks==null||walks.size()>4096||walks.contains(null))
        throw new IllegalArgumentException("bounded journey request required");
      this.earliest=earliest;this.horizonMinutes=horizonMinutes;this.maxBoardings=maxBoardings;
      this.walks=Collections.unmodifiableList(new ArrayList<>(walks));
      expectedEnvelopeSha256=generation;
    }
    private static List<Long> selectionIds(Selection selection){
      if(selection==null)throw new IllegalArgumentException("journey selection required");List<Long> ids=new ArrayList<>();for(Stop s:selection.platforms)ids.add(s.id);return ids;
    }
    private static String generation(Selection from,Selection to){
      if(from==null||to==null||from.packEnvelopeSha256==null||!from.packEnvelopeSha256.matches("[0-9a-f]{64}")||!from.packEnvelopeSha256.equals(to.packEnvelopeSha256))throw new IllegalArgumentException("journey selections use different generations");
      return from.packEnvelopeSha256;
    }
    private static List<Long> ids(List<Long> ids){
      if(ids==null||ids.isEmpty()||ids.size()>32)throw new IllegalArgumentException("1..32 platform IDs required");
      LinkedHashSet<Long> unique=new LinkedHashSet<>();for(Long id:ids){if(id==null||id<1)throw new IllegalArgumentException("positive platform ID required");unique.add(id);}
      return Collections.unmodifiableList(new ArrayList<>(unique));
    }
  }
  public static final class Leg {
    public final TripStop board,alight; public final Walk walk;
    /** The selected published constraint, not a live guarantee or mapped walking path. */
    public final TransferRule transferRule;
    public final Instant departure,arrival;
    private Leg(TripStop board,TripStop alight,Walk walk,TransferRule rule){this(board,alight,walk,rule,board.departure,alight.arrival);}
    private Leg(TripStop board,TripStop alight,Walk walk,TransferRule rule,Instant departure,Instant arrival){this.board=board;this.alight=alight;this.walk=walk;transferRule=rule;this.departure=departure;this.arrival=arrival;}
    public boolean isWalking(){return walk!=null;}
  }
  public static final class Result {
    public final List<Leg> legs; public final Instant arrival; public final Set<Issue> issues;
    /** Bound by the repository after fresh final authority/expiry validation; null in pure tests. */
    public final PackInfo packInfo; public final String packEnvelopeSha256;
    public final String mapSnapshotId;
    public final String connectionScope="same platforms plus explicitly routed scope-limited walking connections";
    Result(List<Leg> legs,Instant arrival,Set<Issue> issues){
      this(legs,arrival,issues,null,null);
    }
    Result(List<Leg> legs,Instant arrival,Set<Issue> issues,PackInfo info,String envelope){this(legs,arrival,issues,info,envelope,null);}
    Result(List<Leg> legs,Instant arrival,Set<Issue> issues,PackInfo info,String envelope,String map){
      mapSnapshotId=map;
      this.legs=Collections.unmodifiableList(new ArrayList<>(legs));this.arrival=arrival;
      this.issues=Collections.unmodifiableSet(issues.isEmpty()?EnumSet.noneOf(Issue.class):EnumSet.copyOf(issues));
      packInfo=info;packEnvelopeSha256=envelope;
    }
    /** Retain all bound output/uncertainty when an optional direct native attempt hit a cap. */
    public Result withWalkingQueryLimit(){
      EnumSet<Issue> bounded=EnumSet.noneOf(Issue.class);bounded.addAll(issues);bounded.add(Issue.WALK_QUERY_LIMIT);
      return new Result(legs,arrival,bounded,packInfo,packEnvelopeSha256,mapSnapshotId);
    }
    public boolean found(){return arrival!=null;}
    /** Not a claim about unprovided walking edges, unlimited boardings or the whole city network. */
    public boolean completeWithinSuppliedConnections(){return issues.isEmpty();}
  }
  interface Network {
    /** At most one real local initial link, only at the untouched origin label. */
    default List<Walk> initialWalks(long id,Instant at)throws IOException{return Collections.emptyList();}
    /** Internal local mapped resolver; a scheduled arrival, not request departure, sets walk time. */
    default List<Walk> finalWalks(long id,Instant at)throws IOException{return Collections.emptyList();}
    default boolean walkLimitReached(){return false;}
    Stop stop(long id)throws IOException;
    List<Departure> departures(long id,Instant at,int limit,int minutes)throws IOException;
    List<TripStop> trip(String id,LocalDate date)throws IOException;
    List<TransferRule> rules()throws IOException;
  }
  static Result solve(Network network,Request request)throws IOException {
    return solve(network,request,System::nanoTime);
  }
  /** Trusted package-only deterministic clock seam; never a request/metadata capability. */
  static Result solve(Network network,Request request,java.util.function.LongSupplier nanos)throws IOException {
    if(network==null||request==null)throw new IllegalArgumentException("network and request required");
    return new Search(network,request,nanos).run();
  }

  private static final class Label {
    final long stop;final Instant time;final int rides;final TripStop incoming;final List<Leg> legs;
    Label(long stop,Instant time,int rides,TripStop incoming,List<Leg> legs){this.stop=stop;this.time=time;this.rides=rides;this.incoming=incoming;this.legs=legs;}
    String key(){return stop+":"+rides+":"+(incoming==null?"origin":incoming.tripId+":"+incoming.serviceDate+":"+incoming.stopSequence);}
  }
  private static final class TripKey {
    final String id;final LocalDate day;
    TripKey(String id,LocalDate day){this.id=id;this.day=day;}
    @Override public boolean equals(Object other){if(!(other instanceof TripKey))return false;TripKey k=(TripKey)other;return id.equals(k.id)&&day.equals(k.day);}
    @Override public int hashCode(){return 31*id.hashCode()+day.hashCode();}
  }
  private static final class Resolution {
    final TransferRule rule;final boolean conflict;
    Resolution(TransferRule rule,boolean conflict){this.rule=rule;this.conflict=conflict;}
  }
  private static final class WorkStopped extends RuntimeException { }
  private static final class Search {
    final Network net;final Request request;final Instant end;final long start;final java.util.function.LongSupplier nanos;
    final EnumSet<Issue> issues=EnumSet.noneOf(Issue.class);
    final Map<Long,Stop> stops=new HashMap<>();
    final Map<TripKey,List<TripStop>> trips=new HashMap<>();
    final Map<Long,List<TransferRule>> byFrom=new HashMap<>();
    final Map<Long,List<Walk>> walks=new HashMap<>();
    final Map<String,Instant> reached=new HashMap<>();final Set<Long> destinations;
    // PriorityQueue(Comparator) is API 24 (minSdk 21); (initialCapacity, Comparator) is API 1. 11 is the JDK default capacity, so heap behaviour is unchanged.
    final PriorityQueue<Label> queue=new PriorityQueue<>(11,Comparator.comparing((Label l)->l.time).thenComparingInt(l->l.rides).thenComparingLong(l->l.stop));
    int rows,candidates,offered;Label best;
    Search(Network net,Request request,java.util.function.LongSupplier nanos){this.net=net;this.request=request;this.nanos=Objects.requireNonNull(nanos);start=nanos.getAsLong();end=request.earliest.plusSeconds(request.horizonMinutes*60L);destinations=new HashSet<>(request.destinations);}
    void cancelled()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("journey search cancelled");}
    void checkWork()throws InterruptedIOException {
      cancelled();
      if(nanos.getAsLong()-start>=10_000_000_000L){issues.add(Issue.WORK_LIMIT);throw new WorkStopped();}
    }
    Stop stop(long id)throws IOException {
      Stop found=stops.get(id);if(found!=null)return found;
      checkWork();found=net.stop(id);checkWork();if(found==null)throw new IOException("journey stop unavailable");
      if(stops.size()>=8192)throw new IOException("journey stop bound exceeded");stops.put(id,found);return found;
    }
    void platform(long id)throws IOException {if(stop(id).locationType!=0)throw new IOException("journey endpoints must be platforms");}
    Long station(long id)throws IOException {
      Stop s=stop(id);if(s.parentId==null)return null;
      Stop parent=stop(s.parentId);if(parent.locationType!=1)throw new IOException("unsupported journey stop hierarchy");return parent.id;
    }
    boolean matchesStop(Long selector,long id)throws IOException {return selector==null||selector==id||selector.equals(station(id));}
    boolean match(Long selector,long value){return selector==null||selector==value;}
    int rank(TransferRule r){
      if(r.fromTripId!=null&&r.toTripId!=null)return 6;
      if((r.fromTripId!=null&&r.toRouteId!=null)||(r.toTripId!=null&&r.fromRouteId!=null))return 5;
      if(r.fromTripId!=null||r.toTripId!=null)return 4;
      if(r.fromRouteId!=null&&r.toRouteId!=null)return 3;
      return r.fromRouteId!=null||r.toRouteId!=null?2:1;
    }
    Resolution rule(TripStop from,TripStop to)throws IOException {
      if(from==null)return new Resolution(null,false);
      int highest=0,count=0;TransferRule winner=null;
      List<Long> buckets=new ArrayList<>();buckets.add(from.stopId);Long parent=station(from.stopId);if(parent!=null)buckets.add(parent);buckets.add(null);
      for(Long bucket:buckets)for(TransferRule r:byFrom.getOrDefault(bucket,Collections.emptyList())){
        checkWork();
        if(!matchesStop(r.fromStopId,from.stopId)||!matchesStop(r.toStopId,to.stopId)
            ||!match(r.fromTripId,from.tripId)||!match(r.toTripId,to.tripId)
            ||!match(r.fromRouteId,from.routeId)||!match(r.toRouteId,to.routeId))continue;
        int rank=rank(r);if(rank>highest){highest=rank;winner=r;count=1;}else if(rank==highest)count++;
      }
      return new Resolution(winner,count>1);
    }
    List<TripStop> trip(Departure departure)throws IOException {
      final LocalDate day;
      try{day=LocalDate.parse(departure.serviceDate);}catch(RuntimeException bad){throw new IOException("invalid journey service date");}
      TripKey key=new TripKey(departure.tripSourceId,day);List<TripStop> found=trips.get(key);if(found!=null)return found;
      if(trips.size()>=TRIP_LIMIT){issues.add(Issue.TRIP_LIMIT);return Collections.emptyList();}
      checkWork();found=net.trip(key.id,key.day);checkWork();
      if(found.size()>TimetableRepository.MAX_TRIP_STOPS||rows+found.size()>ROW_LIMIT){issues.add(Issue.ROW_LIMIT);return Collections.emptyList();}
      rows+=found.size();trips.put(key,found);return found;
    }
    void offer(Label label){
      if(label.time.isAfter(end))return;
      if(destinations.contains(label.stop)&&(best==null||label.time.isBefore(best.time)))best=label;
      // Every admitted ride/walk is time-nondecreasing. This label cannot improve the
      // incumbent arrival, so do not spend the bounded frontier on a dominated future.
      if(best!=null&&!label.time.isBefore(best.time))return;
      Instant prior=reached.get(label.key());if(prior!=null&&!label.time.isBefore(prior))return;
      if(offered>=LABEL_LIMIT){issues.add(Issue.LABEL_LIMIT);return;}
      offered++;reached.put(label.key(),label.time);queue.add(label);
    }
    Result run()throws IOException {
      try{expand();}catch(WorkStopped exhausted){/* Preserve feasible work, never completeness. */}
      cancelled();return new Result(best==null?Collections.emptyList():best.legs,best==null?null:best.time,issues);
    }
    void expand()throws IOException {
      checkWork();for(long id:request.origins)platform(id);for(long id:request.destinations)platform(id);
      checkWork();List<TransferRule> rules=net.rules();checkWork();if(rules.size()>TimetableRepository.MAX_TRANSFER_RULES)throw new IOException("journey rule bound exceeded");
      for(TransferRule r:rules){checkWork();byFrom.computeIfAbsent(r.fromStopId,k->new ArrayList<>()).add(r);if(r.transferType==4)issues.add(Issue.LINKED_TRIP_UNSUPPORTED);}
      for(Walk w:request.walks){checkWork();platform(w.fromStopId);platform(w.toStopId);walks.computeIfAbsent(w.fromStopId,k->new ArrayList<>()).add(w);}
      for(long id:request.origins)offer(new Label(id,request.earliest,0,null,Collections.emptyList()));
      while(!queue.isEmpty()){
        checkWork();if(candidates>=CANDIDATE_LIMIT){issues.add(Issue.WORK_LIMIT);break;}
        Label label=queue.remove();if(best!=null&&!label.time.isBefore(best.time))break;
        if(label.time.isAfter(reached.get(label.key())))continue;
        if(label.rides<request.maxBoardings)board(label,label.stop);
        if(candidates>=CANDIDATE_LIMIT){issues.add(Issue.WORK_LIMIT);break;}
        List<Walk> outgoing=new ArrayList<>(walks.getOrDefault(label.stop,Collections.emptyList()));
        if(label.rides==0&&label.legs.isEmpty()&&request.origins.contains(label.stop)){
          checkWork();List<Walk> routed=net.initialWalks(label.stop,label.time);checkWork();
          if(net.walkLimitReached())issues.add(Issue.WALK_QUERY_LIMIT);
          if(routed==null||routed.size()>1)throw new IOException("invalid mapped initial connections");
          for(Walk walk:routed){
            if(walk==null||walk.fromStopId!=label.stop||walk.toStopId==label.stop||!label.time.equals(walk.routedAt)
                ||(request.expectedEnvelopeSha256!=null&&!request.expectedEnvelopeSha256.equals(walk.packEnvelopeSha256)))throw new IOException("mapped initial connection identity mismatch");
            platform(walk.toStopId);outgoing.add(walk);
          }
        }
        if(label.rides>0&&!destinations.contains(label.stop)){
          checkWork();List<Walk> routed=net.finalWalks(label.stop,label.time);checkWork();
          if(net.walkLimitReached())issues.add(Issue.WALK_QUERY_LIMIT);
          if(routed==null||routed.size()>request.destinations.size())throw new IOException("invalid mapped final connections");
          for(Walk walk:routed){
            if(walk==null||walk.fromStopId!=label.stop||!destinations.contains(walk.toStopId)||!label.time.equals(walk.routedAt)
                ||(request.expectedEnvelopeSha256!=null&&!request.expectedEnvelopeSha256.equals(walk.packEnvelopeSha256)))throw new IOException("mapped final connection identity mismatch");
            platform(walk.toStopId);outgoing.add(walk);
          }
        }
        for(Walk walk:outgoing){
          checkWork();if(candidates>=CANDIDATE_LIMIT){issues.add(Issue.WORK_LIMIT);break;}candidates++;
          if(walk.routedAt!=null&&label.time.isAfter(walk.routedAt)){issues.add(Issue.WALK_TIME_UNAVAILABLE);continue;}
          Instant departure=walk.routedAt==null?label.time:walk.routedAt;
          Instant arrival=departure.plusSeconds(walk.seconds);if(arrival.isAfter(end))continue;
          List<Leg> legs=new ArrayList<>(label.legs);legs.add(new Leg(null,null,walk,null,departure,arrival));
          offer(new Label(walk.toStopId,arrival,label.rides,label.incoming,legs));
        }
      }
      checkWork();
    }
    void board(Label label,long platform)throws IOException {
      checkWork();
      Instant ready=label.time;if(ready.isAfter(end))return;
      long seconds=Duration.between(ready,end).getSeconds();int minutes=(int)Math.max(1,Math.min(1440,(seconds+59)/60));
      checkWork();List<Departure> departures=net.departures(platform,ready,BOARD_LIMIT,minutes);checkWork();
      if(departures.size()>=BOARD_LIMIT)issues.add(Issue.BOARD_LIMIT);
      if(departures.size()>BOARD_LIMIT)throw new IOException("journey board contract exceeded");
      for(Departure departure:departures){
        checkWork();
        // A nonnegative-time continuation cannot arrive before its boarding departure.
        // Keep earlier express/transfer choices; never assume the board is sorted here.
        if(best!=null&&departure.when!=null&&!departure.when.isBefore(best.time))continue;
        if(++candidates>CANDIDATE_LIMIT){issues.add(Issue.WORK_LIMIT);return;}
        if(departure.pickupType!=0||departure.when==null||departure.when.isBefore(ready)||departure.when.isAfter(end))continue;
        if(departure.stopSequence==null)throw new IOException("journey boarding occurrence unavailable");
        List<TripStop> trip=trip(departure);int boarding=-1;
        for(int i=0;i<trip.size();i++){
          checkWork();
          TripStop s=trip.get(i);
          if(s.stopId==platform&&s.stopSequence==departure.stopSequence&&departure.when.equals(s.departure)){boarding=i;break;}
        }
        if(boarding<0){if(!trip.isEmpty())throw new IOException("journey boarding occurrence mismatch");continue;}
        TripStop from=trip.get(boarding);if(from.pickupType!=0||from.stopLocationType!=0)continue;
        Resolution resolution=rule(label.incoming,from);
        if(resolution.conflict){issues.add(Issue.CONFLICTING_RULES);continue;}
        TransferRule rule=resolution.rule;
        if(rule!=null&&(rule.transferType==3||rule.transferType==4))continue;
        if(label.incoming!=null&&rule!=null&&rule.minTransferSeconds!=null
            &&departure.when.isBefore(label.incoming.arrival.plusSeconds(rule.minTransferSeconds)))continue;
        for(int i=boarding+1;i<trip.size();i++){
          checkWork();
          TripStop to=trip.get(i);
          if(to.dropOffType!=0||to.stopLocationType!=0||to.arrival==null||to.arrival.isBefore(from.departure)||to.arrival.isAfter(end))continue;
          // Remaining on this occurrence does not need permission to alight at intermediate stops.
          List<Leg> legs=new ArrayList<>(label.legs);legs.add(new Leg(from,to,null,rule));
          offer(new Label(to.stopId,to.arrival,label.rides+1,to,legs));
        }
      }
    }
  }
  private JourneyPlanner(){}
}
