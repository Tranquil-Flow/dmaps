package app.organicmaps.sdk.transit;

import java.time.Instant;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class JourneyDirectLimitResultTest {
  @Test public void preservesBoundFoundAndUnfoundResultsWithoutMutatingOriginal(){
    for(Instant arrival:new Instant[]{null,Instant.parse("2026-10-21T08:12:33Z")}){
      JourneyPlanner.Result original=new JourneyPlanner.Result(Collections.emptyList(),arrival,EnumSet.of(JourneyPlanner.Issue.LABEL_LIMIT),null,"a".repeat(64),"b".repeat(64));
      JourneyPlanner.Result limited=original.withWalkingQueryLimit();
      assertEquals(original.found(),limited.found());assertEquals(original.arrival,limited.arrival);assertEquals(original.legs,limited.legs);
      assertSame(original.packInfo,limited.packInfo);assertEquals(original.packEnvelopeSha256,limited.packEnvelopeSha256);assertEquals(original.mapSnapshotId,limited.mapSnapshotId);
      assertEquals(EnumSet.of(JourneyPlanner.Issue.LABEL_LIMIT),original.issues);
      assertEquals(EnumSet.of(JourneyPlanner.Issue.LABEL_LIMIT,JourneyPlanner.Issue.WALK_QUERY_LIMIT),limited.issues);assertFalse(limited.completeWithinSuppliedConnections());
      assertEquals(limited.issues,limited.withWalkingQueryLimit().issues);
      try{limited.issues.clear();fail("published issues mutable");}catch(UnsupportedOperationException expected){}
    }
  }
}
