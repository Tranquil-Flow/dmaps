package app.organicmaps.sdk.transit.rt;

import static org.junit.Assert.*;
import app.organicmaps.sdk.transit.Departure;
import java.time.Instant;
import java.util.*;
import org.junit.Test;

/** Independent-review counterexamples: synthetic, never operator observations. */
public class ReviewedRealtimeBoundaryTest
{
  private Departure row(int sequence)
  {return new Departure("T","R","D","Station","S",2,0,1,Instant.ofEpochSecond(1200+sequence),"Europe/Berlin",1,"S","2026-10-14",sequence);}
  private GtfsRealtime.StopTimeUpdate stop(Integer sequence,int relationship)
  {return new GtfsRealtime.StopTimeUpdate("S",sequence,null,new GtfsRealtime.StopTimeEvent(null,240L),relationship);}
  private GtfsRealtime.FeedMessage feed(Long header,Long timestamp,GtfsRealtime.StopTimeUpdate...stops)
  {return new GtfsRealtime.FeedMessage(header,Collections.singletonList(new GtfsRealtime.TripUpdate("T","R",null,"20261014",0,null,timestamp,Arrays.asList(stops))),0,0);}
  @Test public void freshHeaderCannotFreshenOldPrediction() {
    Departure a=row(1);RealtimeBoard.Projection p=RealtimeBoard.project(Collections.singletonList(a),feed(1601L,1000L,stop(1,0)),1601);
    assertTrue(p.rows.get(a).stale);assertNull(p.nextExpiryEpochSecond);
  }
  @Test public void freshHeaderCannotRemoveStopUsingOldSkip() {
    Departure a=row(1);RealtimeBoard.Projection p=RealtimeBoard.project(Collections.singletonList(a),feed(1601L,1000L,stop(1,1)),1601);
    assertEquals(Collections.singletonList(a),p.departures);assertEquals(0,p.skipped);
  }
  @Test public void eitherOlderClockLimitsEvidenceAndMissingTripClockFallsBack() {
    Departure a=row(1);
    assertTrue(RealtimeBoard.project(Collections.singletonList(a),feed(1000L,1601L,stop(1,0)),1601).rows.get(a).stale);
    assertFalse(RealtimeBoard.project(Collections.singletonList(a),feed(1601L,null,stop(1,0)),1601).rows.get(a).stale);
    assertTrue(RealtimeBoard.project(Collections.singletonList(a),feed(1601L,1601L,stop(1,1)),1601).departures.isEmpty());
  }
  @Test public void unsequencedSkipCannotRemoveRepeatedVisits() {
    Departure a=row(1),b=row(5);RealtimeBoard.Projection p=RealtimeBoard.project(Arrays.asList(a,b),feed(1000L,1000L,stop(null,1)),1000);
    assertEquals(Arrays.asList(a,b),p.departures);assertEquals(0,p.coverage);
  }
  @Test public void unsequencedDelayCannotBeGuessedAcrossRepeatedVisits() {
    Departure a=row(1),b=row(5);RealtimeBoard.Projection p=RealtimeBoard.project(Arrays.asList(a,b),feed(1000L,1000L,stop(null,0)),1000);
    assertEquals(0,p.coverage);assertEquals(RealtimeMerger.LiveState.SCHEDULED_ONLY,p.rows.get(a).state);assertEquals(RealtimeMerger.LiveState.SCHEDULED_ONLY,p.rows.get(b).state);
  }
  @Test public void knownUniqueStopStillAcceptsAnIdOnlyPrediction() {
    Departure a=new Departure("T","R","D","Station","S",2,0,1,Instant.ofEpochSecond(1201),"Europe/Berlin",1,"S","2026-10-14",1,1);
    assertEquals(1,RealtimeBoard.project(Collections.singletonList(a),feed(1000L,1000L,stop(null,0)),1000).coverage);
  }
  @Test public void otherVisitOutsideBoardWindowStillRefusesIdOnlyEvidence() {
    Departure a=new Departure("T","R","D","Station","S",2,0,1,Instant.ofEpochSecond(1201),"Europe/Berlin",1,"S","2026-10-14",1,2);
    assertEquals(0,RealtimeBoard.project(Collections.singletonList(a),feed(1000L,1000L,stop(null,0)),1000).coverage);
  }
  @Test public void unknownMultiplicityCannotBeAssumedUnique() {
    Departure a=row(1);
    assertEquals(0,RealtimeBoard.project(Collections.singletonList(a),feed(1000L,1000L,stop(null,0)),1000).coverage);
  }
  @Test public void futureTripTimestampCannotHideBehindOlderHeader() {
    Departure a=row(1);
    assertEquals(0,RealtimeBoard.project(Collections.singletonList(a),feed(1000L,5000L,stop(1,0)),1000).coverage);
  }
  @Test public void explicitSequencesStillIndependentlyMatchRepeatedVisits() {
    Departure a=row(1),b=row(5);RealtimeBoard.Projection p=RealtimeBoard.project(Arrays.asList(a,b),feed(1000L,1000L,stop(1,1),stop(5,0)),1000);
    assertEquals(Collections.singletonList(b),p.departures);assertEquals(RealtimeMerger.LiveState.DELAYED,p.rows.get(b).state);
  }
}
