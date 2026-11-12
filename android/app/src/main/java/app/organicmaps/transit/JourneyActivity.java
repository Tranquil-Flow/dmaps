package app.organicmaps.transit;

import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.*;
import java.time.*;
import java.time.format.*;

/** Reachable local scheduled preview; rendering accepts explicitly map-bound walking results.
 * Explicit bounded mapped action adds one platform pair; never mutates the global map router.
 * Visual roles (2026-11 UX panel): facts render at 16sp default colour, caveats at 13sp muted
 * (#595959, >= 4.5:1), the whole-trip summary leads, material warnings stay visible while purely
 * technical mechanics collapse behind labelled expanders, and a result footer keeps the mapped-walk
 * action and an honest "Edit trip" reachable without scrolling. */
public class JourneyActivity extends AppCompatActivity {
  private JourneyViewModel model;
  private EditText originQuery,destinationQuery;
  private Button originSearch,destinationSearch,plan,walkingPlan;
  private TextView scope,status,pack,originLabel,destinationLabel,dataset,dataDetails;
  private Button aboutData;
  private DatasetStatus.MapFacts mapFacts=DatasetStatus.MapFacts.UNKNOWN; // the one registry capture of the last resume
  private DatasetStatus.MapFacts capturedMapFacts; // written only by the production datasetMapPresence() inside datasetMapFacts()
  private LinearLayout candidates,platformChoices,legs,footer;
  private ScrollView scroll;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private boolean resumed;
  // Identity of the result already brought into view; periodic revalidation rebinds the same result and must not jump.
  private JourneyPlanner.Result revealed;
  private final Runnable refreshVisible=new Runnable(){@Override public void run(){
    if(!resumed)return;model.checkVisible();handler.postDelayed(this,1000);
  }};
  JourneyViewModel createModel(){return new ViewModelProvider(this).get(JourneyViewModel.class);}
  @Override protected void onCreate(Bundle saved){
    super.onCreate(saved);setTitle(R.string.journey_title);model=createModel();
    getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    // Fixed viewport column: the scroller (weight 1) plus the result footer. Inset the fixed
    // viewport, not the scrolling child: content must never scroll over status/navigation bars,
    // and focused inputs/buttons must clear the IME. The scroller clears only the bars; the
    // footer clears the taller of bars/keyboard so actions stay reachable with the keyboard open.
    scroll=new ScrollView(this);LinearLayout form=column();int pad=dp(12);form.setPadding(pad,pad,pad,dp(24));
    scroll.setClipToPadding(true);
    footer=column();footer.setOrientation(LinearLayout.HORIZONTAL);footer.setVisibility(View.GONE);
    footer.setPadding(dp(8),dp(8),dp(8),0);
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll,(view,insets)->{
      androidx.core.graphics.Insets bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
      androidx.core.graphics.Insets keyboard=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
      view.setPadding(bars.left,bars.top,bars.right,bars.bottom);
      footer.setPadding(footer.getPaddingLeft(),footer.getPaddingTop(),footer.getPaddingRight(),Math.max(bars.bottom,keyboard.bottom));
      return insets;
    });
    // Status cluster first: what this screen is, current state, and the pack trust/validity line.
    dataset=caveat(form,"");dataset.setTag("dmaps-dataset-status");dataset.setVisibility(View.GONE); // S1 header, above the status text
    status=text(form,"");status.setTag("dmaps-journey-status");
    // Q10: status and dataset text change in place (errors, refresh, a changed result), so a screen reader hears them politely.
    androidx.core.view.ViewCompat.setAccessibilityLiveRegion(status,androidx.core.view.ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);
    androidx.core.view.ViewCompat.setAccessibilityLiveRegion(dataset,androidx.core.view.ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);
    pack=caveat(form,"");
    // Attribution and authentication details are technical: behind a labelled toggle. The unsigned
    // disclosure is material and stays visible in the pack line itself (charter: material warnings visible).
    dataDetails=caveat(form,"");dataDetails.setVisibility(View.GONE);
    aboutData=flat(form,R.string.journey_about_data_show,()->{});
    aboutData.setTag("dmaps-about-data");
    aboutData.setOnClickListener(v->{boolean open=dataDetails.getVisibility()!=View.VISIBLE;dataDetails.setVisibility(open?View.VISIBLE:View.GONE);
      aboutData.setText(open?R.string.journey_about_data_hide:R.string.journey_about_data_show);
      androidx.core.view.ViewCompat.setStateDescription(aboutData,getString(open?R.string.journey_details_expanded:R.string.journey_details_collapsed));});
    androidx.core.view.ViewCompat.setStateDescription(aboutData,getString(R.string.journey_details_collapsed));
    originLabel=text(form,getString(R.string.journey_origin));originQuery=query(form,R.string.journey_origin,true);
    originSearch=flat(form,R.string.journey_search_origin,()->model.search(true,originQuery.getText().toString()));
    destinationLabel=text(form,getString(R.string.journey_destination));destinationQuery=query(form,R.string.journey_destination,false);
    destinationSearch=flat(form,R.string.journey_search_destination,()->model.search(false,destinationQuery.getText().toString()));
    candidates=column();form.addView(candidates);platformChoices=column();form.addView(platformChoices);
    plan=button(form,getString(R.string.journey_plan),model::plan);
    plan.setBackgroundResource(R.drawable.journey_btn_primary);plan.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.journey_primary_text));
    walkingPlan=button(form,getString(R.string.journey_plan_walking),model::planWithWalking);walkingPlan.setTag("dmaps-plan-walking");
    walkingPlan.setBackgroundResource(R.drawable.journey_btn_outline);walkingPlan.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.journey_primary));
    caveat(form,getString(R.string.journey_walk_action_detail_lead));
    // v0.1 item 3: long technical search mechanics behind an accessible details action; material warnings stay visible.
    TextView mechanics=caveat(form,getString(R.string.journey_walk_action_scope));mechanics.setTag("dmaps-walk-action-scope");mechanics.setVisibility(View.GONE);
    Button details=button(form,getString(R.string.journey_details_show),()->{});details.setTag("dmaps-walk-action-details");
    details.setOnClickListener(v->{boolean open=mechanics.getVisibility()!=View.VISIBLE;mechanics.setVisibility(open?View.VISIBLE:View.GONE);detailsState(details,open);});
    form.removeView(details);form.addView(details,form.indexOfChild(mechanics));detailsState(details,false);
    flat(form,R.string.journey_reload,model::refresh);
    flat(form,android.R.string.cancel,()->{model.invalidate(true);model.invalidate(false);});
    // Honest scope prose is material but must not precede the fields and actions in reading order.
    scope=caveat(form,getString(R.string.journey_scope));
    legs=column();form.addView(legs);
    scroll.addView(form);
    LinearLayout viewport=new LinearLayout(this);viewport.setOrientation(LinearLayout.VERTICAL);
    viewport.addView(scroll,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f));
    viewport.addView(footer,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT));
    setContentView(viewport);androidx.core.view.ViewCompat.requestApplyInsets(scroll);model.getState().observe(this,this::bind);
  }
  private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
  private void detailsState(Button details,boolean open){
    details.setText(open?R.string.journey_details_hide:R.string.journey_details_show);
    androidx.core.view.ViewCompat.setStateDescription(details,getString(open?R.string.journey_details_expanded:R.string.journey_details_collapsed));
  }
  private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
  private TextView text(LinearLayout parent,String value){
    TextView t=new TextView(this);t.setText(value);t.setTextSize(16);t.setPadding(0,dp(6),0,dp(6));t.setSaveEnabled(false);parent.addView(t);return t;
  }
  /** Caveat role: limitations and honesty text recede without losing visibility or contrast. */
  private TextView caveat(LinearLayout parent,String value){
    TextView t=text(parent,value);t.setTextSize(13);t.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.journey_muted));t.setTag("dmaps-caveat");return t;
  }
  private Button button(LinearLayout parent,String title,Runnable action){
    Button b=new Button(this);b.setText(title);b.setMinHeight(dp(48));b.setOnClickListener(v->action.run());parent.addView(b);return b;
  }
  /** Secondary actions: text-only, no filled chrome, so the primary action is unambiguous. */
  private Button flat(LinearLayout parent,int title,Runnable action){
    Button b=button(parent,getString(title),action);
    b.setBackgroundColor(android.graphics.Color.TRANSPARENT);b.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.journey_primary));
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT);
    p.setMargins(0,0,0,dp(4));b.setLayoutParams(p);return b;
  }
  private EditText query(LinearLayout root,int hint,boolean origin){
    EditText e=new EditText(this);e.setId(View.generateViewId());e.setHint(hint);e.setSaveEnabled(false);
    e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
    e.setImeOptions(EditorInfo.IME_ACTION_SEARCH|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    // Only a real content change retires the endpoint: a reconnecting keyboard re-commits the identical
    // composing word after an app switch, which must not silently drop the chosen platform.
    e.addTextChangedListener(new TextWatcher(){private String previous="";
      public void beforeTextChanged(CharSequence s,int start,int count,int after){previous=s.toString();}
      public void onTextChanged(CharSequence s,int start,int before,int count){if(!previous.contentEquals(s))model.invalidate(origin);}
      public void afterTextChanged(Editable s){} });
    e.setOnEditorActionListener((v,id,event)->{if(id==EditorInfo.IME_ACTION_SEARCH){model.search(origin,e.getText().toString());return true;}return false;});
    root.addView(e);return e;
  }
  /** S1 registry seam, read once per resume on main. Host tests override it; production never reaches native
   * code unless the real application's core is initialized (see DatasetMapPresence). */
  DatasetStatus.MapPresence datasetMapPresence(){capturedMapFacts=DatasetMapPresence.captureFacts(this);return capturedMapFacts.presence;}
  /** One immutable capture per resume. Reads the presence seam exactly once, so an overriding host test still works. */
  DatasetStatus.MapFacts datasetMapFacts(){
    capturedMapFacts=null;DatasetStatus.MapPresence presence=datasetMapPresence();DatasetStatus.MapFacts captured=capturedMapFacts;capturedMapFacts=null;
    return captured!=null&&captured.presence==presence?captured:DatasetStatus.MapFacts.ofPresence(presence);
  }
  private void renderDataset(JourneyViewModel.State s){
    boolean unresolved=!model.datasetResolved();
    // First load in flight: say nothing rather than guess. A finished-but-failed load is PACK_UNAVAILABLE.
    if(unresolved&&s.busy){DatasetStatusText.render(dataset,null);return;}
    DatasetStatusText.render(dataset,DatasetStatusText.forJourneyWithMap(model.datasetPack(),unresolved,model.datasetEnvelope(),model.now(),mapFacts));
  }
  static String bounded(String value){return value==null?"":value.substring(0,Math.min(512,value.length()));}
  private String stop(Stop s){return bounded(s.name)+(s.platform==null||s.platform.isEmpty()?"":" · "+getString(R.string.transit_platform,bounded(s.platform)));}
  private String stop(TripStop s){return bounded(s.stopName)+(s.platform==null||s.platform.isEmpty()?"":" · "+getString(R.string.transit_platform,bounded(s.platform)));}
  private String time(Instant at,String zone){
    if(at==null)return getString(R.string.journey_unknown_time);
    try{
      android.content.res.Configuration config=getResources().getConfiguration();
      java.util.Locale locale=Build.VERSION.SDK_INT>=24&&!config.getLocales().isEmpty()?config.getLocales().get(0):config.locale;
      if(locale==null)locale=java.util.Locale.getDefault();
      return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT).withLocale(locale).withZone(ZoneId.of(zone)).format(at)+" ("+zone+")";
    }
    catch(RuntimeException invalid){return at.toString();}
  }
  private String clock(Instant at,String zone){
    if(at==null)return getString(R.string.journey_unknown_time);
    try{return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(getResources().getConfiguration().locale).withZone(ZoneId.of(zone)).format(at);}
    catch(RuntimeException invalid){return at.toString();}
  }
  /** UX01: whole-trip summary first; the dated detail and every limitation still follow unchanged. */
  private void summary(JourneyPlanner.Result result,boolean mapped,String zone){
    StringBuilder lines=new StringBuilder();int rides=0,walkMinutes=0;boolean walking=false;
    for(JourneyPlanner.Leg leg:result.legs){
      if(leg.isWalking()){walking=true;if(mapped&&result.mapSnapshotId.equals(leg.walk.mapSnapshotId))walkMinutes+=(leg.walk.seconds+59)/60;continue;}
      if(rides++>0)lines.append(" → ");lines.append(bounded(leg.board.line));
    }
    String changes=rides<=1?getString(R.string.journey_summary_direct):rides==2?getString(R.string.journey_summary_one_change):getString(R.string.journey_summary_changes,rides-1);
    String text=getString(walking?R.string.journey_summary_estimated:R.string.journey_summary,clock(result.legs.get(0).departure,zone),clock(result.arrival,zone),lines.toString(),changes);
    if(walkMinutes>0)text+=getString(R.string.journey_summary_walk,walkMinutes);
    TextView t=text(legs,text);t.setTextSize(20);t.setTypeface(t.getTypeface(),android.graphics.Typeface.BOLD);t.setTag("dmaps-journey-summary");
  }
  private int issue(JourneyPlanner.Issue issue){
    switch(issue){
      case BOARD_LIMIT:return R.string.journey_issue_board;
      case LABEL_LIMIT:return R.string.journey_issue_labels;
      case TRIP_LIMIT:return R.string.journey_issue_trips;
      case ROW_LIMIT:return R.string.journey_issue_rows;
      case WORK_LIMIT:return R.string.journey_issue_work;
      case CONFLICTING_RULES:return R.string.journey_issue_conflict;
      case LINKED_TRIP_UNSUPPORTED:return R.string.journey_issue_linked;
      case DATA_WINDOW_END:return R.string.journey_issue_window;
      case WALK_TIME_UNAVAILABLE:return R.string.journey_issue_walk_time;
      case WALK_QUERY_LIMIT:return R.string.journey_issue_walk_queries;
      default:throw new IllegalArgumentException("unhandled journey limitation");
    }
  }
  private String walkStop(JourneyViewModel.State s,long id){
    for(JourneyViewModel.Endpoint endpoint:new JourneyViewModel.Endpoint[]{s.from,s.to})if(endpoint!=null)
      for(Stop platform:endpoint.selection.platforms)if(platform.id==id)return stop(platform);
    if(s.result!=null)for(JourneyPlanner.Leg leg:s.result.legs){
      if(leg.board!=null&&leg.board.stopId==id)return stop(leg.board);
      if(leg.alight!=null&&leg.alight.stopId==id)return stop(leg.alight);
    }
    return getString(R.string.journey_walk_stop_unknown);
  }
  private void platformChoices(JourneyViewModel.Endpoint endpoint,boolean origin,boolean busy){
    if(endpoint==null||endpoint.selection.platforms.size()<2)return;
    caveat(platformChoices,getString(origin?R.string.journey_choose_origin_platform:R.string.journey_choose_destination_platform));
    for(Stop platform:endpoint.selection.platforms){
      String label=stop(platform);if(platform.platform==null||platform.platform.isEmpty())label+=" · "+getString(R.string.journey_platform_unknown);
      Button pick=button(platformChoices,getString(R.string.journey_platform_choice,label,bounded(platform.sourceId)),()->model.selectPlatform(endpoint.selection,platform.id,origin));
      pick.setAllCaps(false);pick.setTag((origin?"dmaps-origin-platform-":"dmaps-destination-platform-")+platform.id);pick.setEnabled(!busy);
    }
  }
  /** Result footer: the mapped-walk action and an honest way back to the form stay reachable
   * without scrolling. No result, nothing to act on: the footer disappears. */
  private void renderFooter(JourneyViewModel.State s,JourneyPlanner.Result result,boolean mapped){
    footer.removeAllViews();
    if(result==null||!result.found()||result.legs.isEmpty()){footer.setVisibility(View.GONE);return;}
    footer.setVisibility(View.VISIBLE);
    for(JourneyPlanner.Leg leg:result.legs)if(leg.isWalking()&&mapped&&result.mapSnapshotId.equals(leg.walk.mapSnapshotId)){
      Button mapAction=button(footer,getString(R.string.journey_map_action),()->{});
      mapAction.setTag("dmaps-walk-map-"+leg.walk.fromStopId+"-"+leg.walk.toStopId);
      mapAction.setEnabled(!s.busy);
      mapAction.setBackgroundResource(R.drawable.journey_btn_primary);mapAction.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.journey_primary_text));
      mapAction.setLayoutParams(weighted());
      mapAction.setOnClickListener(view->{
        mapAction.setEnabled(false);model.prepareWalkingMap(s,leg.walk,token->{
          if(token==null){if(resumed)mapAction.setEnabled(true);return;}
          if(!resumed){WalkingMapPreviewSession.cancelPending(token);return;}
          try{startActivity(new android.content.Intent(this,app.organicmaps.MwmActivity.class)
              .putExtra(WalkingMapPreviewSession.EXTRA_TOKEN,token)
              .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));}
          catch(RuntimeException failed){WalkingMapPreviewSession.cancelPending(token);status.setText(R.string.journey_map_unavailable);}
        });
      });
    }
    Button edit=button(footer,getString(R.string.journey_edit_trip),()->scroll.scrollTo(0,0));
    edit.setBackgroundResource(R.drawable.journey_btn_outline);edit.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.journey_primary));
    edit.setLayoutParams(weighted());
  }
  private LinearLayout.LayoutParams weighted(){
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f);
    p.setMargins(dp(4),0,dp(4),0);return p;
  }
  private void bind(JourneyViewModel.State s){
    if(s==null)return;boolean usable=model.displayable(s)&&s.info.formatVersion==2;
    boolean mapped=usable&&s.result!=null&&s.result.mapSnapshotId!=null;
    scope.setText(mapped?R.string.journey_mapped_scope:R.string.journey_scope);
    originSearch.setEnabled(usable&&!s.busy);destinationSearch.setEnabled(usable&&!s.busy);
    plan.setEnabled(usable&&!s.busy&&s.from!=null&&s.to!=null);walkingPlan.setEnabled(usable&&model.canPlanWithWalking(s));
    originLabel.setText(getString(R.string.journey_origin)+(s.from==null?"":" · "+stop(s.from.stop)));
    destinationLabel.setText(getString(R.string.journey_destination)+(s.to==null?"":" · "+stop(s.to.stop)));
    status.setText(s.message);pack.setText("");dataDetails.setText("");renderDataset(s);
    candidates.removeAllViews();platformChoices.removeAllViews();legs.removeAllViews();footer.setVisibility(View.GONE);footer.removeAllViews();
    if(!usable){if(s.result!=null)status.setText(R.string.journey_changed);return;}
    PackInfo p=s.info;
    // Publisher + exact validity stay visible; the unsigned disclosure is material and stays with them.
    StringBuilder packLine=new StringBuilder(getString(R.string.transit_pack_line,bounded(p.publisher),p.validFrom,p.validUntil));
    if(!p.authenticated)packLine.append("\n").append(getString(R.string.transit_unsigned_pack_disclosure));
    pack.setText(packLine.toString());
    if(p.authenticated)dataDetails.setText(getString(R.string.transit_authenticated_pack,p.signingKeyId,p.officialSequence,p.authorizationExpires.toString())
        +"\n"+bounded(p.attribution)+" · "+bounded(p.license));
    if(s.candidates!=null&&!s.busy)for(Stop candidate:s.candidates.stops){
      String kind=getString(candidate.locationType==1?R.string.journey_candidate_station:R.string.journey_candidate_boarding);
      Button choice=button(candidates,getString(R.string.journey_platform_choice,stop(candidate)+"\n"+kind,bounded(candidate.sourceId)),
          ()->model.select(s.candidates,candidate.id,s.choosingFrom));
      choice.setAllCaps(false);
    }
    platformChoices(s.from,true,s.busy);platformChoices(s.to,false,s.busy);
    JourneyPlanner.Result result=s.result;if(result==null)return;
    if(result.found()&&!result.legs.isEmpty()){
      summary(result,mapped,p.timezone);
      TextView od=text(legs,getString(R.string.journey_od_header,stop(s.from.stop),stop(s.to.stop)));od.setTag("dmaps-od-header");
      // UX01: a new plan's summary must not land below the fold; reveal it once per result.
      if(result!=revealed){revealed=result;final View first=legs.getChildAt(0);scroll.post(()->{if(legs.indexOfChild(first)==0)scroll.smoothScrollTo(0,legs.getTop());});}
    }
    caveat(legs,getString(mapped?R.string.journey_mapped_connection_scope:R.string.journey_connection_scope));
    if(!result.completeWithinSuppliedConnections())caveat(legs,getString(R.string.journey_limited));
    for(JourneyPlanner.Issue i:result.issues)caveat(legs,getString(issue(i)));
    if(!result.found())return;
    if(result.legs.isEmpty()){caveat(legs,getString(R.string.journey_same_platform));return;}
    TripStop previous=null;boolean walking=false;
    for(JourneyPlanner.Leg leg:result.legs){
      if(leg.isWalking()){
        walking=true;JourneyPlanner.Walk walk=leg.walk;
        if(!mapped||!result.mapSnapshotId.equals(walk.mapSnapshotId)){caveat(legs,getString(R.string.journey_walking_unavailable));continue;}
        text(legs,getString(R.string.journey_walk,walkStop(s,walk.fromStopId),walkStop(s,walk.toStopId),(walk.seconds+59)/60,walk.distanceMeters));
        text(legs,getString(R.string.journey_walk_times,time(leg.departure,p.timezone),time(leg.arrival,p.timezone)));
        caveat(legs,getString(R.string.journey_walk_access));
        if(walk.directionsAvailable){
          text(legs,getString(R.string.journey_walk_directions));int stepNumber=0;
          for(MappedWalking.Direction direction:walk.directions()){
            int title;switch(direction.maneuver){
              case GO_STRAIGHT:title=R.string.journey_walk_direction_straight;break;
              case RIGHT:title=R.string.journey_walk_direction_right;break;
              case LEFT:title=R.string.journey_walk_direction_left;break;
              case FINISH:title=R.string.journey_walk_direction_finish;break;
              default:throw new IllegalArgumentException("unhandled mapped pedestrian maneuver");
            }
            TextView instruction=text(legs,getString(R.string.journey_walk_direction_step,++stepNumber,Math.round(direction.distanceFromStartMeters),getString(title)));
            instruction.setTag("dmaps-walk-direction-"+walk.fromStopId+"-"+walk.toStopId+"-"+direction.pointIndex);
          }
        }else caveat(legs,getString(R.string.journey_walk_directions_unavailable));
        // How-to-read-the-preview notes are technical: behind a labelled toggle (same pattern as
        // Search details). Material caveats above stay visible.
        LinearLayout notes=column();notes.setVisibility(View.GONE);
        caveat(notes,getString(R.string.journey_walk_directions_scope));
        caveat(notes,getString(R.string.journey_walk_preview));
        caveat(notes,getString(R.string.journey_walk_preview_legend));
        Button walkNotes=button(legs,getString(R.string.journey_walkmap_show),()->{});
        walkNotes.setTag("dmaps-walkmap-details-"+walk.fromStopId+"-"+walk.toStopId);
        walkNotes.setBackgroundColor(android.graphics.Color.TRANSPARENT);walkNotes.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.journey_primary));
        walkNotes.setOnClickListener(v->{boolean open=notes.getVisibility()!=View.VISIBLE;notes.setVisibility(open?View.VISIBLE:View.GONE);
          walkNotes.setText(open?R.string.journey_walkmap_hide:R.string.journey_walkmap_show);
          androidx.core.view.ViewCompat.setStateDescription(walkNotes,getString(open?R.string.journey_walkmap_expanded:R.string.journey_walkmap_collapsed));});
        androidx.core.view.ViewCompat.setStateDescription(walkNotes,getString(R.string.journey_walkmap_collapsed));
        legs.addView(notes);
        try{
          WalkingPathPreview preview=new WalkingPathPreview(this,walk.latLonGeometry());
          preview.setTag("dmaps-walk-preview-"+walk.fromStopId+"-"+walk.toStopId);
          preview.setContentDescription(getString(R.string.journey_walk_preview_description)+" "+getString(R.string.journey_walk_preview_endpoints,walkStop(s,walk.fromStopId),walkStop(s,walk.toStopId)));
          legs.addView(preview,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(240)));
        }catch(IllegalArgumentException unsupported){caveat(legs,getString(R.string.journey_walk_preview_unavailable));}
        continue;
      }
      if(previous!=null)text(legs,getString(R.string.journey_transfer,stop(leg.board)));
      text(legs,getString(R.string.journey_ride,bounded(leg.board.line),bounded(leg.board.destination),stop(leg.board),time(leg.board.departure,leg.board.timezone)));
      text(legs,getString(R.string.journey_alight,stop(leg.alight),time(leg.alight.arrival,leg.alight.timezone)));
      if(!Integer.valueOf(1).equals(leg.board.timepoint)||!Integer.valueOf(1).equals(leg.alight.timepoint))caveat(legs,getString(R.string.journey_approximate));
      previous=leg.alight;
    }
    text(legs,getString(walking?R.string.journey_estimated_arrival:R.string.journey_arrival,time(result.arrival,previous==null?p.timezone:previous.timezone)));
    renderFooter(s,result,mapped);
  }
  @Override protected void onNewIntent(android.content.Intent intent){super.onNewIntent(intent);setIntent(intent);}
  @Override protected void onResume(){
    super.onResume();resumed=true;mapFacts=datasetMapFacts();
    android.content.Intent intent=getIntent();
    if(intent!=null&&intent.hasExtra(WalkingMapPreviewSession.EXTRA_RETURN_TOKEN)){
      String token=intent.getStringExtra(WalkingMapPreviewSession.EXTRA_RETURN_TOKEN);
      intent.removeExtra(WalkingMapPreviewSession.EXTRA_RETURN_TOKEN);model.restoreWalkingReturn(token);
    }else model.refresh();
    handler.postDelayed(refreshVisible,1000);
  }
  @Override protected void onPause(){resumed=false;handler.removeCallbacks(refreshVisible);model.suspend();super.onPause();}
}
