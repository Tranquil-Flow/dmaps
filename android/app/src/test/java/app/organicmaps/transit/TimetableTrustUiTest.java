package app.organicmaps.transit;

import android.app.Activity;
import android.app.Application;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.*;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import java.lang.reflect.Field;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual activity/dialog/SAF result/recreation/binding under Robolectric. ViewModel is mocked;
 * separate tests cover actual ViewModel transitions and actual archive/native-host-SQLite paths. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
public class TimetableTrustUiTest {
  static TimetableViewModel model;
  static Instant now;
  public static class Surface extends TimetableActivity {
    boolean noPicker, forbiddenPicker;
    @Override public ViewModelProvider.Factory getDefaultViewModelProviderFactory(){
      return new ViewModelProvider.Factory(){@NonNull public <T extends ViewModel>T create(@NonNull Class<T> type){return type.cast(model);}};
    }
    @Override Instant presentationNow(){return now;}
    @Override public void startActivityForResult(Intent intent,int code){if(noPicker)throw new ActivityNotFoundException();if(forbiddenPicker)throw new SecurityException("synthetic denial");super.startActivityForResult(intent,code);}
  }
  private ActivityController<Surface> controller;
  private Surface activity;
  private MutableLiveData<TimetableViewModel.ScreenState> live;
  private TimetableViewModel.ScreenState state;
  @Before public void setup(){
    now=Instant.parse("2026-10-21T00:00:00Z");model=mock(TimetableViewModel.class);state=new TimetableViewModel.ScreenState();state.status=TimetableViewModel.Status.NO_PACK;
    live=new MutableLiveData<>(state);when(model.getState()).thenReturn(live);when(model.getQueryText()).thenReturn("");start(null);
  }
  private void start(Bundle saved){controller=Robolectric.buildActivity(Surface.class);activity=controller.get();activity.setTheme(R.style.MwmTheme);controller.create(saved).start().resume().visible();Shadows.shadowOf(Looper.getMainLooper()).idle();}
  @After public void cleanup(){if(controller!=null)controller.pause().stop().destroy();}
  private <T>T field(String name)throws Exception{Field f=TimetableActivity.class.getDeclaredField(name);f.setAccessible(true);return (T)f.get(activity);}
  private Button button(String field)throws Exception{return field(field);}
  private String text(String field)throws Exception{return ((TextView)field(field)).getText().toString();}
  private void publish(){state.revision++;live.setValue(state);Shadows.shadowOf(Looper.getMainLooper()).idle();}
  private ShadowActivity.IntentForResult choose(int choice)throws Exception {
    button("mImportBtn").performClick();android.app.Dialog dialog=ShadowDialog.getLatestDialog();assertTrue("mode choice must precede SAF",dialog instanceof AlertDialog);AlertDialog picker=(AlertDialog)dialog;assertTrue(picker.isShowing());
    assertEquals(2,picker.getListView().getAdapter().getCount());picker.getListView().performItemClick(null,choice,choice);Shadows.shadowOf(Looper.getMainLooper()).idle();
    ShadowActivity.IntentForResult result=Shadows.shadowOf(activity).getNextStartedActivityForResult();assertNotNull(result);assertEquals(Intent.ACTION_OPEN_DOCUMENT,result.intent.getAction());assertTrue(result.intent.hasCategory(Intent.CATEGORY_OPENABLE));return result;
  }
  private void result(int code,int status,Uri uri){activity.onActivityResult(code,status,uri==null?null:new Intent().setData(uri));Shadows.shadowOf(Looper.getMainLooper()).idle();}
  private PackInfo untrusted(){return new PackInfo("Official authenticated publisher claim","fixture","CC BY 4.0","https://example.invalid",String.join("",Collections.nCopies(64,"0")),String.join("",Collections.nCopies(64,"0")),"2026-10-01T00:00:00Z","2026-01-01","2027-01-01","Europe/Berlin",0,86400);}
  private void ready(PackInfo info){state.packPresent=true;state.packInfo=info;state.status=TimetableViewModel.Status.READY;publish();}
  @Test public void explicitOfficialSelectionBindsUriAndModeWithoutFallback()throws Exception {
    ShadowActivity.IntentForResult pick=choose(0);Uri uri=Uri.parse("content://fixture.invalid/official");result(pick.requestCode,Activity.RESULT_OK,uri);
    verify(model).importPack(uri,ImportMode.OFFICIAL_REQUIRED);verify(model,never()).importPack(any(Uri.class));verify(model,never()).importPack(uri,ImportMode.USER_UNTRUSTED);
  }
  @Test public void explicitUserSelectionAndCancellationDoNotChooseOfficial()throws Exception {
    ShadowActivity.IntentForResult cancelled=choose(0);result(cancelled.requestCode,Activity.RESULT_CANCELED,null);
    verify(model,never()).importPack(any(Uri.class),any(ImportMode.class));assertTrue(button("mImportBtn").isEnabled());
    ShadowActivity.IntentForResult user=choose(1);Uri uri=Uri.parse("content://fixture.invalid/user");result(user.requestCode,Activity.RESULT_OK,uri);verify(model).importPack(uri,ImportMode.USER_UNTRUSTED);
  }
  @Test public void staleAndUnsolicitedResultsCannotConsumeANewerRequestMode()throws Exception {
    result(47001,Activity.RESULT_OK,Uri.parse("content://fixture.invalid/unsolicited"));verify(model,never()).importPack(any(Uri.class));verify(model,never()).importPack(any(Uri.class),any(ImportMode.class));
    ShadowActivity.IntentForResult old=choose(0);result(old.requestCode,Activity.RESULT_CANCELED,null);ShadowActivity.IntentForResult current=choose(1);assertNotEquals(old.requestCode,current.requestCode);
    Uri stale=Uri.parse("content://fixture.invalid/stale"),fresh=Uri.parse("content://fixture.invalid/fresh");result(old.requestCode,Activity.RESULT_OK,stale);result(current.requestCode,Activity.RESULT_OK,fresh);
    verify(model,never()).importPack(eq(stale),any(ImportMode.class));verify(model).importPack(fresh,ImportMode.USER_UNTRUSTED);
  }
  @Test public void pendingOfficialModeSurvivesActivityRecreation()throws Exception {
    ShadowActivity.IntentForResult pick=choose(0);Bundle saved=new Bundle();controller.saveInstanceState(saved).pause().stop().destroy();controller=null;start(saved);
    assertFalse(button("mImportBtn").isEnabled());Uri uri=Uri.parse("content://fixture.invalid/recreated");result(pick.requestCode,Activity.RESULT_OK,uri);verify(model).importPack(uri,ImportMode.OFFICIAL_REQUIRED);
  }
  @Test public void outstandingPickerAndWorkingStateRefuseAnotherPicker()throws Exception {
    ShadowActivity.IntentForResult pending=choose(1);button("mImportBtn").performClick();assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
    // Cancellation releases the slot while the activity remains resumed/observing LiveData.
    result(pending.requestCode,Activity.RESULT_CANCELED,null);
    state.status=TimetableViewModel.Status.WORKING;publish();assertFalse(button("mImportBtn").isEnabled());button("mImportBtn").performClick();assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
  }
  @Test public void missingDocumentPickerReleasesModeWithoutImport()throws Exception {
    activity.noPicker=true;button("mImportBtn").performClick();AlertDialog d=(AlertDialog)ShadowDialog.getLatestDialog();assertNotNull(d);d.getListView().performItemClick(null,0,0);assertTrue(button("mImportBtn").isEnabled());assertTrue(text("mStatusLine").contains(activity.getString(R.string.transit_error_no_document_picker)));verify(model,never()).importPack(any(Uri.class),any(ImportMode.class));
  }
  @Test public void securityDenialReleasesPickerWithoutSelectingAnotherMode()throws Exception {
    activity.forbiddenPicker=true;button("mImportBtn").performClick();AlertDialog d=(AlertDialog)ShadowDialog.getLatestDialog();d.getListView().performItemClick(null,1,1);
    assertTrue(button("mImportBtn").isEnabled());assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());verify(model,never()).importPack(any(Uri.class),any(ImportMode.class));
  }
  @Test public void corruptRestoredModeCannotFallbackToUserImport()throws Exception {
    controller.pause().stop().destroy();controller=null;Bundle saved=new Bundle();saved.putInt("transit.picker_next",47002);saved.putInt("transit.picker_code",47001);saved.putString("transit.picker_mode","unknown");start(saved);
    result(47001,Activity.RESULT_OK,Uri.parse("content://fixture.invalid/corrupt-mode"));verify(model,never()).importPack(any(Uri.class),any(ImportMode.class));verify(model,never()).importPack(any(Uri.class));assertTrue(button("mImportBtn").isEnabled());
  }
  @Test public void exhaustedRequestCodesDoNotWrapIntoAStalePicker()throws Exception {
    controller.pause().stop().destroy();controller=null;Bundle saved=new Bundle();saved.putInt("transit.picker_next",65535);start(saved);button("mImportBtn").performClick();assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());assertEquals(activity.getString(R.string.transit_picker_exhausted),text("mStatusLine"));
  }
  @Test public void publisherClaimsCannotPromoteUntrustedUi()throws Exception {
    ready(untrusted());assertEquals(activity.getString(R.string.transit_unsigned_pack_disclosure),text("mTrustLine"));assertTrue(button("mSearchBtn").isEnabled());
  }
  @Test public void officialUiUsesDerivedKeySequenceAndExpiryNotUnsignedDisclosure()throws Exception {
    PackInfo info=VerifiedUiFixture.official();ready(info);String trust=text("mTrustLine");assertTrue(trust.contains(info.signingKeyId));assertTrue(trust.contains(info.authorizationExpires.toString()));assertFalse(trust.contains(activity.getString(R.string.transit_unsigned_pack_disclosure)));assertTrue(button("mSearchBtn").isEnabled());
  }
  @Test public void expiryTimerRetiresCachedUiWithoutDemotionAndStopsAfterDestroy()throws Exception {
    PackInfo info=VerifiedUiFixture.official();ready(info);clearInvocations(model);now=info.authorizationExpires;Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(1));
    assertFalse(button("mSearchBtn").isEnabled());assertFalse(button("mRefreshBtn").isEnabled());assertTrue(button("mRemoveBtn").isEnabled());assertFalse(text("mTrustLine").contains(activity.getString(R.string.transit_unsigned_pack_disclosure)));verify(model).refreshPackState(null);
    controller.pause().stop().destroy();controller=null;clearInvocations(model);Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofHours(2));verifyNoInteractions(model);
  }
  private Departure scheduledRow() {
    java.time.Instant when=java.time.Instant.now().plusSeconds(300);
    return new Departure("T-preview","fixture","fixture","fixture","1",3,0,1,when,"Europe/Berlin",1,"stop-preview",java.time.LocalDate.ofInstant(when,java.time.ZoneOffset.UTC).toString(),1,1);
  }
  private void setField(String name,Object value)throws Exception {Field f=TimetableActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
  @Test public void offlinePreviewIgnoresCachedLiveEvidenceEvenIfOldConsentIsTrue()throws Exception {
    Departure row=scheduledRow();state.board=java.util.Collections.singletonList(row);state.selectedStop=new Stop(1,"stop-preview","fixture","1",null,null,null,0,Double.NaN);ready(untrusted());
    long timestamp=java.time.Instant.now().getEpochSecond();app.organicmaps.sdk.transit.rt.GtfsRealtime.FeedMessage feed=app.organicmaps.sdk.transit.rt.CachedLiveUiFixture.cancellation(row,timestamp);
    assertTrue("synthetic cached cancellation must be actionable in the preserved pure projector",app.organicmaps.sdk.transit.rt.RealtimeBoard.project(state.board,feed,timestamp).rows.get(row).hasLiveData());
    setField("mLiveOn",true);setField("mLiveFeed",feed);java.lang.reflect.Method render=TimetableActivity.class.getDeclaredMethod("renderBoard",TimetableViewModel.ScreenState.class);render.setAccessible(true);render.invoke(activity,state);
    java.util.Map<Departure,app.organicmaps.sdk.transit.rt.RealtimeMerger.DepartureRow> rows=field("mLiveRows");assertFalse(rows.get(row)!=null&&rows.get(row).hasLiveData());assertEquals(android.view.View.GONE,button("mLiveToggle").getVisibility());
  }
  @Test public void offlinePreviewForcedToggleAndOldCallbackCannotDispatchTransport()throws Exception {
    java.util.concurrent.atomic.AtomicInteger dispatched=new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.ThreadPoolExecutor noNetwork=new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(1)){
      @Override public void execute(Runnable task){dispatched.incrementAndGet();} // Never runs a network body, even before the guard exists.
    };
    try {
      Departure row=scheduledRow();state.board=java.util.Collections.singletonList(row);state.selectedStop=new Stop(1,"stop-preview","fixture","1",null,null,null,0,Double.NaN);ready(untrusted());setField("mLiveExecutor",noNetwork);
      button("mLiveToggle").performClick();setField("mLiveOn",true);java.lang.reflect.Method fetch=TimetableActivity.class.getDeclaredMethod("fetchLive",java.util.List.class);fetch.setAccessible(true);fetch.invoke(activity,state.board);
      assertEquals(0,dispatched.get());
    } finally {noNetwork.shutdownNow();assertTrue(noNetwork.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS));}
  }

  @Test public void offlinePreviewResumeClearsOldConsentWithoutANewBoardRevision()throws Exception {
    Departure row=scheduledRow();state.board=Collections.singletonList(row);
    state.selectedStop=new Stop(1,"stop-preview","fixture","1",null,null,null,0,Double.NaN);
    ready(untrusted());
    app.organicmaps.sdk.transit.rt.GtfsRealtime.FeedMessage feed=
        app.organicmaps.sdk.transit.rt.CachedLiveUiFixture.cancellation(row,Instant.now().getEpochSecond());
    setField("mLiveOn",true);setField("mNeedLiveFetch",true);setField("mLiveFeed",feed);
    int revision=state.revision;
    controller.pause().stop().restart().start().resume();
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertEquals("resume must not depend on a newly published board",revision,state.revision);
    assertEquals(revision,((Integer)field("mLastBoundRevision")).intValue());
    assertFalse("offline preview must revoke stale consent on resume",(Boolean)field("mLiveOn"));
    assertFalse((Boolean)field("mNeedLiveFetch"));assertNull(field("mLiveFeed"));
    assertNull("resume must not allocate a transport executor",field("mLiveExecutor"));
    assertEquals(activity.getString(R.string.transit_offline_preview),text("mLiveStatusLine"));
    java.util.Map<Departure,app.organicmaps.sdk.transit.rt.RealtimeMerger.DepartureRow> rows=field("mLiveRows");
    assertNotNull(rows.get(row));assertFalse(rows.get(row).hasLiveData());
  }

  @Test public void candidateRowsKeepExactIdentityWhenNamesAndPlatformsCollide()throws Exception {
    assertCandidateIdentity();
  }
  @Test @Config(qualifiers="de") public void germanCandidateRowsKeepExactIdentityWhenNamesAndPlatformsCollide()throws Exception {
    assertCandidateIdentity();
  }
  @SuppressWarnings({"rawtypes","unchecked"}) private void assertCandidateIdentity()throws Exception {
    Stop station=new Stop(20,"fixture-station","Same station","",null,null,null,1,Double.NaN);
    Stop missing=new Stop(21,"fixture-station::10","Same station","",20L,null,null,0,Double.NaN);
    Stop unknown=new Stop(22,"fixture-station::11","Same station",null,20L,null,null,0,Double.NaN);
    Stop positionOne=new Stop(23,"fixture-station::4","Same station","Pos. 1",20L,null,null,0,Double.NaN);
    Stop alsoPositionOne=new Stop(24,"fixture-station::5","Same station","Pos. 1",20L,null,null,0,Double.NaN);
    state.candidates=Arrays.asList(station,missing,unknown,positionOne,alsoPositionOne);ready(untrusted());
    androidx.recyclerview.widget.RecyclerView list=field("mList");
    androidx.recyclerview.widget.RecyclerView.Adapter adapter=list.getAdapter();assertEquals(5,adapter.getItemCount());
    Set<String> labels=new HashSet<>();
    for(int i=0;i<state.candidates.size();i++){
      Stop stop=state.candidates.get(i);
      androidx.recyclerview.widget.RecyclerView.ViewHolder holder=adapter.onCreateViewHolder(list,adapter.getItemViewType(i));adapter.onBindViewHolder(holder,i);
      TextView row=(TextView)holder.itemView;String label=row.getText().toString();
      assertTrue("exact ID must be displayed without interpreting its suffix",label.contains(stop.sourceId));
      assertTrue("different identities must remain distinguishable",labels.add(label));
      assertEquals(activity.getString(R.string.transit_stop_row,label),row.getContentDescription().toString());
      if(stop.locationType==1)assertTrue(label.contains(activity.getString(R.string.transit_station)));
      else if(stop.platform==null||stop.platform.isEmpty())assertTrue(label.contains(activity.getString(R.string.journey_platform_unknown)));
      else assertTrue(label.contains(activity.getString(R.string.transit_platform,stop.platform)));
      clearInvocations(model);assertTrue(row.performClick());verify(model).selectStop(same(stop));
    }
  }

  @Test public void unavailablePointerRemainsRemovableButNeverQueryableOrReportedAbsent()throws Exception {
    state.packPresent=true;state.packInfo=null;state.status=TimetableViewModel.Status.ERROR;publish();assertTrue(button("mRemoveBtn").isEnabled());assertFalse(button("mSearchBtn").isEnabled());assertFalse(text("mPackLine").equals(activity.getString(R.string.transit_no_pack)));assertFalse(text("mTrustLine").equals(activity.getString(R.string.transit_unsigned_pack_disclosure)));
  }
}
