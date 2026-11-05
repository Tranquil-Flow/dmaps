package app.organicmaps.transit;

import app.organicmaps.R;
import app.organicmaps.sdk.transit.rt.RealtimeRequest;
import java.util.Collections;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Preview's public API must deny traffic; real loopback server is independently exercised by
 * retained prototype-transport controls. No public endpoints, device or arbitrary URI is contacted. */
public class OfflinePreviewCapabilityTest {
  private void denied(RealtimeClient.Result result){assertFalse(result.ok());assertEquals(R.string.transit_offline_preview,result.errorRes);assertNull(result.feed);assertFalse(result.notModified);assertTrue(result.joined.isEmpty());assertNull(result.etag);}
  @Test public void shippingCapabilityIsNotAUserPreference(){assertFalse(RealtimeClient.liveUpdatesAvailable());}
  @Test(timeout=10000) public void everyPublicOverloadInitiatesZeroLoopbackRequests()throws Exception {
    RealtimeClientTransportTest helper=new RealtimeClientTransportTest();
    try(RealtimeClientTransportTest.Relay relay=new RealtimeClientTransportTest.Relay(200,helper.gzip(helper.payload()),false)) {
      RealtimeClient.Result a=RealtimeClient.fetchAndJoin(relay.base(),"all",10800,Collections.singleton("T100"));
      RealtimeClient.Result b=RealtimeClient.fetchAndJoin(relay.base(),"all",10800,Collections.singleton("T100"),"previous-validator");
      RealtimeClient.Result c=RealtimeClient.fetchAndJoin(relay.base(),"all",10800,Collections.singleton("T100"),"previous-validator",new RealtimeRequest());
      assertEquals("preview must never reach HTTP",0,relay.requests.get());denied(a);denied(b);denied(c);
    }
  }
  @Test public void malformedEndpointsAndNullCallerStateStillReturnPreviewDenialBeforeParsing(){denied(RealtimeClient.fetchAndJoin("not a URL","ignored",-1,null,"untrusted-validator",null));}
  @Test(timeout=10000) public void queuedOrRestoredConsentCannotGrantTheCompiledCapability()throws Exception {
    ExecutorService queue=Executors.newSingleThreadExecutor();CountDownLatch blocker=new CountDownLatch(1),entered=new CountDownLatch(1);
    try {
      queue.submit(()->{entered.countDown();try{blocker.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(entered.await(3,TimeUnit.SECONDS));
      Future<RealtimeClient.Result> request=queue.submit(()->RealtimeClient.fetchAndJoin("not a URL","old-context",10800,Collections.singleton("old-trip"),"old-validator",new RealtimeRequest()));blocker.countDown();denied(request.get(3,TimeUnit.SECONDS));
    } finally {blocker.countDown();queue.shutdownNow();assertTrue(queue.awaitTermination(3,TimeUnit.SECONDS));}
  }
}
