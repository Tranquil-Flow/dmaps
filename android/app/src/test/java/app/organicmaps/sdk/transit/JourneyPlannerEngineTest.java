package app.organicmaps.sdk.transit;

import org.junit.Test;
import java.io.*;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;

/** Deterministic synthetic scheduled graph: no Android, network, device or inferred walking. */
public class JourneyPlannerEngineTest {
  private static final LocalDate DAY=LocalDate.parse("2026-10-21");
  private static Instant at(String time){return Instant.parse("2026-10-21T"+time+":00Z");}
  private static final class Graph implements JourneyPlanner.Network {
    final Map<Long,Stop> stops=new HashMap<>();final Map<String,List<TripStop>> trips=new LinkedHashMap<>();
    final List<TransferRule> rules=new ArrayList<>();
    java.util.function.BiFunction<Long,Instant,List<JourneyPlanner.Walk>> egress=(id,time)->Collections.emptyList();
    java.util.function.BiFunction<Long,Instant,List<JourneyPlanner.Walk>> access=(id,time)->Collections.emptyList();
    public List<JourneyPlanner.Walk> initialWalks(long id,Instant time){return access.apply(id,time);}
    boolean walkingLimited;
    public List<JourneyPlanner.Walk> finalWalks(long id,Instant time){return egress.apply(id,time);}
    public boolean walkLimitReached(){return walkingLimited;}
    void node(long id,Long parent,int type){stops.put(id,new Stop(id,"S"+id,"Stop "+id,"P"+id,parent,null,null,type,Double.NaN));}
    void trip(String name,long id,long route,long[] ids,String...times){
      List<TripStop> rows=new ArrayList<>();for(int i=0;i<ids.length;i++){
        if(!stops.containsKey(ids[i]))node(ids[i],null,0);Stop s=stops.get(ids[i]);Instant t=times[i]==null?null:at(times[i]);
        rows.add(new TripStop(id,route,name,name,"Destination",i+1,s.id,s.sourceId,s.name,s.platform,t,t,0,0,1,s.locationType,DAY,"Europe/Berlin"));
      }trips.put(name,rows);
    }
    void restrict(String id,int index,int pickup,int drop){
      List<TripStop> list=trips.get(id);TripStop s=list.get(index);
      list.set(index,new TripStop(s.tripId,s.routeId,s.tripSourceId,s.line,s.destination,s.stopSequence,s.stopId,s.stopSourceId,s.stopName,s.platform,s.arrival,s.departure,pickup,drop,s.timepoint,s.stopLocationType,s.serviceDate,s.timezone));
    }
    public Stop stop(long id){return stops.get(id);}
    public List<TransferRule> rules(){return rules;}
    int tripReads;
    public List<TripStop> trip(String id,LocalDate date){tripReads++;List<TripStop> rows=trips.get(id);return rows!=null&&!rows.isEmpty()&&rows.get(0).serviceDate.equals(date)?rows:Collections.emptyList();}
    public List<Departure> departures(long id,Instant now,int limit,int minutes){
      List<Departure> rows=new ArrayList<>();Instant end=now.plusSeconds(minutes*60L);
      for(List<TripStop> trip:trips.values())for(TripStop s:trip)if(s.stopId==id&&s.departure!=null&&!s.departure.isBefore(now)&&!s.departure.isAfter(end)&&s.pickupType!=1)
        rows.add(new Departure(s.tripSourceId,s.line,s.destination,s.stopName,s.platform,3,s.pickupType,s.timepoint,s.departure,s.timezone,s.stopId,s.stopSourceId,s.serviceDate.toString(),s.stopSequence));
      rows.sort(Comparator.comparing((Departure d)->d.when).thenComparing(d->d.tripSourceId));return new ArrayList<>(rows.subList(0,Math.min(limit,rows.size())));
    }
  }
  private static JourneyPlanner.Request request(long from,long to,int boardings,List<JourneyPlanner.Walk> walks){
    return new JourneyPlanner.Request(Collections.singletonList(from),Collections.singletonList(to),at("09:59"),60,boardings,walks);
  }
  private static JourneyPlanner.Result plan(Graph g,long from,long to)throws IOException{return JourneyPlanner.solve(g,request(from,to,4,Collections.emptyList()));}
  private static Graph interchange(){Graph g=new Graph();g.trip("A",1,101,new long[]{1,2},"10:00","10:10");g.trip("B",2,202,new long[]{2,3},"10:12","10:20");return g;}
  private static TransferRule rule(long id,Long from,Long to,Long fr,Long tr,Long ft,Long tt,int type,Integer seconds){return new TransferRule(id,from,to,fr,tr,ft,tt,type,seconds);}

  private static JourneyPlanner.Walk timed(long from,long to,Instant time)throws Exception {
    var ctor=JourneyPlanner.Walk.class.getDeclaredConstructor(long.class,long.class,int.class,String.class,String.class,String.class,Instant.class,double.class,double[].class,boolean.class);ctor.setAccessible(true);
    return ctor.newInstance(from,to,120,"synthetic-timed-egress",null,null,time,100.0,new double[]{0,0,0,0.001},true);
  }
  private static Graph egressGraph(){Graph g=new Graph();g.trip("A",1,101,new long[]{1,2},"10:00","10:10");g.node(3,null,0);return g;}
  private static Graph accessGraph(){Graph g=new Graph();g.node(1,null,0);g.trip("B",1,101,new long[]{2,3},"10:03","10:10");return g;}
  @Test public void initialMappedPathIsTimedAtDepartureAndBoardsOnlyAfterItsArrival()throws Exception {
    Graph g=accessGraph();List<Instant> queried=new ArrayList<>();g.access=(id,time)->{assertEquals(1L,(long)id);queried.add(time);try{return Collections.singletonList(timed(1,2,time));}catch(Exception e){throw new RuntimeException(e);}};
    JourneyPlanner.Result r=JourneyPlanner.solve(g,request(1,3,1,Collections.emptyList()));assertTrue("initial native connection was not consumed",r.found());assertEquals(Collections.singletonList(at("09:59")),queried);assertEquals(2,r.legs.size());assertTrue(r.legs.get(0).isWalking());assertFalse(r.legs.get(1).isWalking());assertEquals(at("10:01"),r.legs.get(0).arrival);assertEquals(at("10:03"),r.legs.get(1).departure);
  }
  @Test public void initialPathCannotCatchVehicleBeforeWalkingArrival()throws Exception {
    Graph g=accessGraph();g.trip("B",1,101,new long[]{2,3},"10:00","10:10");g.access=(id,time)->{try{return Collections.singletonList(timed(1,2,time));}catch(Exception e){throw new RuntimeException(e);}};assertFalse(JourneyPlanner.solve(g,request(1,3,1,Collections.emptyList())).found());
  }
  @Test public void initialPathMustMatchOriginExactTimeAndOneLinkBound()throws Exception {
    for(int invalid=0;invalid<3;invalid++){
      Graph g=accessGraph();int variant=invalid;g.access=(id,time)->{try{return Collections.singletonList(timed(variant==0?2:1,variant==0?3:2,variant==1?time.plusSeconds(1):time));}catch(Exception e){throw new RuntimeException(e);}};
      // Third variant exceeds the one-link contract, not an identity failure hidden by a limit.
      if(invalid==2)g.access=(id,time)->{try{return Arrays.asList(timed(1,2,time),timed(1,3,time));}catch(Exception e){throw new RuntimeException(e);}};
      try{JourneyPlanner.solve(g,request(1,3,1,Collections.emptyList()));fail("invalid initial path accepted: "+invalid);}catch(IOException expected){}
    }
  }
  @Test public void initialResolverCrossingExistingWorkBudgetCannotPublishPath()throws Exception {
    Graph g=accessGraph();long[] now={0};g.access=(id,time)->{now[0]=10_000_000_000L;try{return Collections.singletonList(timed(1,2,time));}catch(Exception e){throw new RuntimeException(e);}};
    JourneyPlanner.Result r=JourneyPlanner.solve(g,request(1,3,1,Collections.emptyList()),()->now[0]);assertFalse(r.found());assertTrue(r.issues.contains(JourneyPlanner.Issue.WORK_LIMIT));
  }
  @Test public void dynamicFinalWalkUsesActualAlightingTimeAfterLastAllowedBoarding()throws Exception {
    Graph g=egressGraph();List<Instant> times=new ArrayList<>();g.egress=(id,time)->{assertEquals(2L,(long)id);times.add(time);try{return Collections.singletonList(timed(2,3,time));}catch(Exception e){throw new RuntimeException(e);}};
    JourneyPlanner.Result r=JourneyPlanner.solve(g,request(1,3,1,Collections.emptyList()));assertTrue("mapped final-hop resolver was not consumed",r.found());assertEquals(Collections.singletonList(at("10:10")),times);assertEquals(2,r.legs.size());assertEquals(at("10:10"),r.legs.get(1).departure);assertEquals(at("10:12"),r.arrival);
  }
  @Test public void laterArrivalMustRequestAnotherTimedWalkRatherThanReuseOldOne()throws Exception {
    Graph g=egressGraph();g.trip("B",2,101,new long[]{1,2},"10:01","10:11");List<Instant> times=new ArrayList<>();g.egress=(id,time)->{times.add(time);try{return Collections.singletonList(timed(id,3,time));}catch(Exception e){throw new RuntimeException(e);}};
    assertTrue(JourneyPlanner.solve(g,request(1,3,1,Collections.emptyList())).found());assertEquals(Arrays.asList(at("10:10"),at("10:11")),times);
  }
  @Test public void dynamicWalkCannotInventDifferentSourceOrDestination()throws Exception {
    Graph g=egressGraph();g.node(4,null,0);g.egress=(id,time)->{try{return Collections.singletonList(timed(4,3,time));}catch(Exception e){throw new RuntimeException(e);}};
    try{JourneyPlanner.solve(g,request(1,3,1,Collections.emptyList()));fail("foreign dynamic source accepted");}catch(IOException expected){}
  }
  @Test public void resolverQuotaMustMarkAnUnfoundJourneyIncomplete()throws Exception {
    Graph g=egressGraph();g.egress=(id,time)->{g.walkingLimited=true;return Collections.emptyList();};JourneyPlanner.Result r=JourneyPlanner.solve(g,request(1,3,1,Collections.emptyList()));assertFalse(r.found());assertTrue(r.issues.stream().anyMatch(i->i.name().equals("WALK_QUERY_LIMIT")));assertFalse(r.completeWithinSuppliedConnections());
  }
  @Test public void resolverCrossingWorkDeadlineCannotPublishUnfencedFinalWalk()throws Exception {
    Graph g=egressGraph();long[] now={0};g.egress=(id,time)->{now[0]=10_000_000_000L;try{return Collections.singletonList(timed(id,3,time));}catch(Exception e){throw new RuntimeException(e);}};
    JourneyPlanner.Result r=JourneyPlanner.solve(g,request(1,3,1,Collections.emptyList()),()->now[0]);assertFalse(r.found());assertTrue(r.issues.contains(JourneyPlanner.Issue.WORK_LIMIT));
  }
  @Test public void directTripHasRealTimesPlatformsAndImmutableLegs()throws Exception {
    Graph g=new Graph();g.trip("A",1,101,new long[]{1,2},"10:00","10:10");JourneyPlanner.Result r=plan(g,1,2);
    assertTrue(r.found());assertEquals(at("10:10"),r.arrival);assertEquals(1,r.legs.size());assertEquals("P1",r.legs.get(0).board.platform);assertEquals(2,r.legs.get(0).alight.stopId);assertTrue(r.completeWithinSuppliedConnections());
    try{r.legs.clear();fail("mutable legs");}catch(UnsupportedOperationException expected){}
  }
  @Test public void laterDepartureCanArriveEarlierThanFirstBoard()throws Exception {
    Graph g=new Graph();g.trip("slow",1,101,new long[]{1,2},"10:00","10:40");g.trip("fast",2,102,new long[]{1,2},"10:05","10:15");
    JourneyPlanner.Result r=plan(g,1,2);assertEquals("fast",r.legs.get(0).board.tripSourceId);assertEquals(at("10:15"),r.arrival);
  }
  @Test public void departuresAfterKnownArrivalDoNotSpendTheLabelBudget()throws Exception {
    Graph g=new Graph();g.trip("A-direct",1,101,new long[]{1,9},"10:00","10:10");
    for(int i=0;i<260;i++)g.trip("late"+i,2+i,102,new long[]{1,100+i*2,101+i*2},"10:11","10:12","10:13");
    JourneyPlanner.Result r=plan(g,1,9);assertEquals(at("10:10"),r.arrival);
    assertFalse("departures already too late to improve the feasible arrival must not exhaust labels",r.issues.contains(JourneyPlanner.Issue.LABEL_LIMIT));
    assertEquals("do not read trips whose departures cannot beat the known arrival",1,g.tripReads);
    assertTrue(r.completeWithinSuppliedConnections());
  }
  @Test public void alightingsAfterKnownArrivalDoNotSpendTheLabelBudget()throws Exception {
    Graph g=new Graph();g.trip("A-direct",1,101,new long[]{1,9},"10:00","10:10");
    for(int i=0;i<260;i++)g.trip("slow"+i,2+i,102,new long[]{1,100+i*2,101+i*2},"10:01","10:20","10:21");
    JourneyPlanner.Result r=plan(g,1,9);assertEquals(at("10:10"),r.arrival);
    assertFalse("a later alighting cannot lead to a better nonnegative-time journey",r.issues.contains(JourneyPlanner.Issue.LABEL_LIMIT));
    assertTrue(r.completeWithinSuppliedConnections());
  }
  @Test public void incumbentArrivalDoesNotPruneAnEarlierTransferImprovement()throws Exception {
    Graph g=new Graph();g.trip("A-direct",1,101,new long[]{1,9},"09:59","10:30");
    g.trip("B-connect",2,102,new long[]{1,2},"10:00","10:05");
    g.trip("C-finish",3,103,new long[]{2,9},"10:06","10:10");
    JourneyPlanner.Result r=plan(g,1,9);assertEquals(at("10:10"),r.arrival);assertEquals(2,r.legs.size());
    assertEquals("B-connect",r.legs.get(0).board.tripSourceId);assertEquals("C-finish",r.legs.get(1).board.tripSourceId);
  }
  @Test public void competitiveLabelsBeforeIncumbentStillReportTheUnchangedCap()throws Exception {
    Graph g=new Graph();
    for(int i=0;i<260;i++)g.trip("early"+i,1+i,102,new long[]{1,100+i*2,101+i*2},"10:00","10:01","10:02");
    g.trip("later-destination",999,103,new long[]{1,9},"10:15","10:45");
    JourneyPlanner.Result r=plan(g,1,9);assertTrue(r.found());
    assertTrue("real competitive overflow must remain incomplete",r.issues.contains(JourneyPlanner.Issue.LABEL_LIMIT));
    assertFalse(r.completeWithinSuppliedConnections());assertEquals(512,JourneyPlanner.LABEL_LIMIT);
  }
  @Test public void samePlatformMinimumIsEnforcedWithoutFabricatingWalk()throws Exception {
    Graph g=interchange();g.rules.add(rule(1,2L,2L,null,null,null,null,2,180));assertFalse(plan(g,1,3).found());
    g.rules.clear();g.rules.add(rule(2,2L,2L,null,null,null,null,2,120));JourneyPlanner.Result r=plan(g,1,3);assertTrue(r.found());assertEquals(2,r.legs.size());assertEquals(at("10:20"),r.arrival);
  }
  @Test public void forbiddenAndMostSpecificTripRouteRulesApply()throws Exception {
    Graph g=interchange();g.rules.add(rule(1,2L,2L,null,null,null,null,3,null));assertFalse(plan(g,1,3).found());
    g.rules.add(rule(2,2L,2L,null,null,1L,null,3,null));g.rules.add(rule(3,2L,2L,null,202L,1L,null,2,120));
    assertTrue("trip+opposite route outranks one-trip and generic prohibition",plan(g,1,3).found());
  }
  @Test public void stationApplicabilityAndEqualMaximalConflictRefuseFallback()throws Exception {
    Graph g=interchange();g.node(22,null,1);g.node(2,22L,0);
    g.rules.add(rule(1,22L,22L,null,null,null,null,3,null));assertFalse(plan(g,1,3).found());
    g.rules.add(rule(2,2L,2L,null,null,1L,2L,2,60));assertTrue(plan(g,1,3).found());
    g.rules.add(rule(3,22L,22L,null,null,1L,2L,2,60));JourneyPlanner.Result r=plan(g,1,3);assertFalse(r.found());assertTrue(r.issues.contains(JourneyPlanner.Issue.CONFLICTING_RULES));
  }
  @Test public void cannotAlightEarlyButCanRemainAboardToAllowedStop()throws Exception {
    Graph g=new Graph();g.trip("A",1,101,new long[]{1,2,3},"10:00","10:10","10:20");g.restrict("A",1,0,1);
    assertFalse(plan(g,1,2).found());assertTrue(plan(g,1,3).found());
  }
  @Test public void coordinationAndUnknownArrivalAreNotUnrestrictedJourneys()throws Exception {
    Graph g=new Graph();g.trip("A",1,101,new long[]{1,2},"10:00","10:10");
    for(int restriction:new int[]{1,2,3}){g.restrict("A",1,0,restriction);assertFalse(plan(g,1,2).found());}
    g.restrict("A",1,0,0);for(int pickup:new int[]{1,2,3}){g.restrict("A",0,pickup,0);assertFalse(plan(g,1,2).found());}
    g.restrict("A",0,0,0);TripStop s=g.trips.get("A").get(1);g.trips.get("A").set(1,new TripStop(s.tripId,s.routeId,s.tripSourceId,s.line,s.destination,s.stopSequence,s.stopId,s.stopSourceId,s.stopName,s.platform,null,s.departure,0,0,1,0,DAY,s.timezone));assertFalse(plan(g,1,2).found());
  }
  @Test public void walkingRequiresSuppliedPathAndMinimumIncludesWalkingBuffer()throws Exception {
    Graph g=new Graph();g.trip("A",1,101,new long[]{1,2},"10:00","10:10");g.trip("B",2,202,new long[]{3,4},"10:13","10:20");
    g.rules.add(rule(1,2L,3L,null,null,null,null,2,180));assertFalse(plan(g,1,4).found());
    JourneyPlanner.Walk walk=new JourneyPlanner.Walk(2,3,120,"synthetic-mapped-path");
    JourneyPlanner.Result r=JourneyPlanner.solve(g,request(1,4,4,Collections.singletonList(walk)));assertTrue(r.found());assertEquals(3,r.legs.size());assertTrue(r.legs.get(1).isWalking());assertEquals("synthetic-mapped-path",r.legs.get(1).walk.pathToken);
  }
  @Test public void directWalkingCanBeTheWholeJourney()throws Exception {
    Graph g=new Graph();g.node(1,null,0);g.node(2,null,0);
    JourneyPlanner.Result r=JourneyPlanner.solve(g,request(1,2,1,Collections.singletonList(new JourneyPlanner.Walk(1,2,120,"fixture-only-mapped-path"))));
    assertTrue(r.found());assertEquals(at("10:01"),r.arrival);assertEquals(1,r.legs.size());assertTrue(r.legs.get(0).isWalking());
  }
  @Test public void finalWalkingRemainsAvailableAfterLastAllowedBoarding()throws Exception {
    Graph g=new Graph();g.trip("A",1,101,new long[]{1,2},"10:00","10:10");g.node(3,null,0);
    JourneyPlanner.Result r=JourneyPlanner.solve(g,request(1,3,1,Collections.singletonList(new JourneyPlanner.Walk(2,3,120,"fixture-only-mapped-path"))));
    assertTrue(r.found());assertEquals(at("10:12"),r.arrival);assertEquals(2,r.legs.size());assertTrue(r.legs.get(1).isWalking());
  }
  @Test public void chainedWalkingKeepsPublishedTransferMinimumAtOriginalAlighting()throws Exception {
    Graph g=new Graph();g.trip("A",1,101,new long[]{1,2},"10:00","10:10");g.node(3,null,0);g.trip("B",2,202,new long[]{4,5},"10:13","10:20");
    g.rules.add(rule(1,2L,4L,null,null,null,null,2,180));
    List<JourneyPlanner.Walk> walks=Arrays.asList(new JourneyPlanner.Walk(2,3,60,"fixture-path-a"),new JourneyPlanner.Walk(3,4,60,"fixture-path-b"));
    JourneyPlanner.Result r=JourneyPlanner.solve(g,request(1,5,2,walks));assertTrue(r.found());assertEquals(at("10:20"),r.arrival);assertEquals(4,r.legs.size());
    g.rules.clear();g.rules.add(rule(2,2L,4L,null,null,null,null,3,null));assertFalse(JourneyPlanner.solve(g,request(1,5,2,walks)).found());
  }
  @Test public void fasterWalkChainCanImproveAnAlreadyQueuedPlatform()throws Exception {
    Graph g=new Graph();g.node(1,null,0);g.node(2,null,0);g.trip("B",1,101,new long[]{3,4},"10:02","10:10");
    List<JourneyPlanner.Walk> walks=Arrays.asList(new JourneyPlanner.Walk(1,3,600,"fixture-slow"),new JourneyPlanner.Walk(1,2,60,"fixture-short-a"),new JourneyPlanner.Walk(2,3,60,"fixture-short-b"));
    JourneyPlanner.Result r=JourneyPlanner.solve(g,request(1,4,1,walks));assertTrue(r.found());assertEquals(at("10:10"),r.arrival);assertEquals(3,r.legs.size());assertEquals(2,r.legs.get(0).walk.toStopId);
  }
  @Test public void walkingCyclesTerminateAndHorizonStillBoundsFinalLeg()throws Exception {
    Graph g=new Graph();g.node(1,null,0);g.node(2,null,0);g.node(3,null,0);
    List<JourneyPlanner.Walk> walks=Arrays.asList(new JourneyPlanner.Walk(1,2,120,"fixture-a"),new JourneyPlanner.Walk(2,1,120,"fixture-b"));
    assertFalse(JourneyPlanner.solve(g,request(1,3,1,walks)).found());
    JourneyPlanner.Request bounded=new JourneyPlanner.Request(Collections.singletonList(1L),Collections.singletonList(2L),at("09:59"),1,1,walks);assertFalse(JourneyPlanner.solve(g,bounded).found());
  }
  @Test public void repeatedPlatformUsesActualBoardingOccurrence()throws Exception {
    Graph g=new Graph();g.trip("loop",1,101,new long[]{1,2,1,3},"09:50","09:55","10:00","10:10");
    JourneyPlanner.Result r=plan(g,1,3);assertTrue(r.found());assertEquals(3,r.legs.get(0).board.stopSequence);assertEquals(at("10:00"),r.legs.get(0).board.departure);
  }
  @Test public void boardingCapIsNotBypassedAndLinkedTripsAreNotInvented()throws Exception {
    Graph g=interchange();assertFalse(JourneyPlanner.solve(g,request(1,3,1,Collections.emptyList())).found());
    g.rules.add(rule(1,null,null,null,null,1L,2L,4,null));JourneyPlanner.Result r=plan(g,1,3);assertFalse(r.found());assertTrue(r.issues.contains(JourneyPlanner.Issue.LINKED_TRIP_UNSUPPORTED));
  }
  @Test public void saturatedBoardStillReturnsFeasibleButNotExhaustiveResult()throws Exception {
    Graph g=new Graph();for(int i=1;i<=300;i++)g.trip("T"+i,i,101,new long[]{1,2},"10:00","10:10");
    JourneyPlanner.Result r=plan(g,1,2);assertTrue(r.found());assertTrue(r.issues.contains(JourneyPlanner.Issue.BOARD_LIMIT));assertFalse(r.completeWithinSuppliedConnections());
  }
  @Test public void exactCandidateBoundaryCannotHideSkippedFinalWalkingConnection()throws Exception {
    Graph g=new Graph();List<Long> origins=new ArrayList<>();for(long id=1;id<=14;id++){g.node(id,null,0);origins.add(id);}
    g.trip("reachable",1,101,new long[]{15,999},"10:05","10:10");int[] examined={0};int[] walkingBoards={0};
    JourneyPlanner.Network network=new JourneyPlanner.Network(){
      public Stop stop(long id){return g.stop(id);}
      public List<TransferRule> rules(){return g.rules();}
      public List<TripStop> trip(String id,LocalDate date){return g.trip(id,date);}
      public List<Departure> departures(long id,Instant now,int limit,int minutes){
        if(id==15){walkingBoards[0]++;return g.departures(id,now,limit,minutes);}
        int count=id<=13?299:JourneyPlanner.CANDIDATE_LIMIT-13*299;examined[0]+=count;
        List<Departure> rows=new ArrayList<>();for(int i=0;i<count;i++)rows.add(new Departure("reserved","X","Destination","Stop","",3,2,1,at("10:00"),"Europe/Berlin",id,"S"+id,DAY.toString(),1));return rows;
      }
    };
    JourneyPlanner.Request request=new JourneyPlanner.Request(origins,Collections.singletonList(999L),at("09:59"),60,2,Collections.singletonList(new JourneyPlanner.Walk(14,15,120,"synthetic-mapped-path")));
    JourneyPlanner.Result result=JourneyPlanner.solve(network,request);
    assertEquals(JourneyPlanner.CANDIDATE_LIMIT,examined[0]);assertEquals(0,walkingBoards[0]);assertFalse(result.found());
    assertTrue("skipped supplied connection must set WORK_LIMIT even with an empty queue",result.issues.contains(JourneyPlanner.Issue.WORK_LIMIT));
    assertFalse(result.completeWithinSuppliedConnections());
  }
  @Test public void budgetCrossedByFinalBoardReadIsFlaggedWithoutAnotherQueueIteration()throws Exception {
    Graph g=new Graph();g.node(1,null,0);g.node(2,null,0);long[] now={0};int[] tripReads={0};
    JourneyPlanner.Network network=new JourneyPlanner.Network(){
      public Stop stop(long id){return g.stop(id);}
      public List<TransferRule> rules(){return Collections.emptyList();}
      public List<TripStop> trip(String id,LocalDate day){tripReads[0]++;return Collections.emptyList();}
      public List<Departure> departures(long id,Instant at,int limit,int minutes){
        now[0]=10_000_000_000L;
        return Collections.singletonList(new Departure("reserved","X","Destination","Stop","",3,2,1,JourneyPlannerEngineTest.at("10:00"),"Europe/Berlin",1,"S1",DAY.toString(),1));
      }
    };
    JourneyPlanner.Result result=JourneyPlanner.solve(network,request(1,2,2,Collections.emptyList()),()->now[0]);
    assertEquals(0,tripReads[0]);assertFalse(result.found());assertTrue(result.issues.contains(JourneyPlanner.Issue.WORK_LIMIT));assertFalse(result.completeWithinSuppliedConnections());
  }
  @Test public void cancelledSearchDoesNotReturnUsablePartialResult()throws Exception {
    Graph g=interchange();Thread.currentThread().interrupt();try{JourneyPlanner.solve(g,request(1,3,4,Collections.emptyList()));fail("ignored cancellation");}catch(InterruptedIOException expected){assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
  }
  @Test public void completeSpecificityLadderOverridesOnlyLessSpecificRules()throws Exception {
    List<TransferRule> ladder=Arrays.asList(
        rule(1,2L,2L,null,null,null,null,3,null),
        rule(2,2L,2L,101L,null,null,null,3,null),
        rule(3,2L,2L,101L,202L,null,null,3,null),
        rule(4,2L,2L,null,null,1L,null,3,null),
        rule(5,2L,2L,null,202L,1L,null,3,null),
        rule(6,2L,2L,null,null,1L,2L,3,null));
    for(int level=0;level<ladder.size();level++){
      Graph g=interchange();g.rules.addAll(ladder.subList(0,level));TransferRule r=ladder.get(level);
      g.rules.add(rule(r.id,r.fromStopId,r.toStopId,r.fromRouteId,r.toRouteId,r.fromTripId,r.toTripId,2,60));
      assertTrue("specificity level "+level,plan(g,1,3).found());
      if(level+1<ladder.size()){g.rules.add(ladder.get(level+1));assertFalse(plan(g,1,3).found());}
    }
  }
  @Test public void afterMidnightUsesDepartureServiceDateRatherThanRequestDate()throws Exception {
    Graph g=new Graph();g.trip("night",1,101,new long[]{1,2},"00:05","00:15");List<TripStop> rows=g.trips.get("night");
    for(int i=0;i<rows.size();i++){TripStop s=rows.get(i);rows.set(i,new TripStop(s.tripId,s.routeId,s.tripSourceId,s.line,s.destination,s.stopSequence,s.stopId,s.stopSourceId,s.stopName,s.platform,s.arrival,s.departure,s.pickupType,s.dropOffType,s.timepoint,s.stopLocationType,DAY.minusDays(1),s.timezone));}
    JourneyPlanner.Request request=new JourneyPlanner.Request(Collections.singletonList(1L),Collections.singletonList(2L),at("00:00"),60,2,Collections.emptyList());
    JourneyPlanner.Result r=JourneyPlanner.solve(g,request);assertTrue(r.found());assertEquals(DAY.minusDays(1),r.legs.get(0).board.serviceDate);assertEquals(at("00:15"),r.arrival);
  }
  @Test public void labelCapKeepsAFeasibleDiscoveredJourneyAndReportsIncomplete()throws Exception {
    Graph g=new Graph();long[] ids=new long[600];String[] times=new String[600];for(int i=0;i<ids.length;i++){ids[i]=i+1;times[i]=i==0?"10:00":"10:10";}
    g.trip("long",1,101,ids,times);JourneyPlanner.Result r=plan(g,1,600);
    assertTrue(r.found());assertTrue(r.issues.contains(JourneyPlanner.Issue.LABEL_LIMIT));assertFalse(r.completeWithinSuppliedConnections());
  }
}
