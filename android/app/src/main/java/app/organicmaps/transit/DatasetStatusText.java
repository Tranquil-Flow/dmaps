package app.organicmaps.transit;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import app.organicmaps.R;
import app.organicmaps.sdk.transit.DatasetStatus;
import app.organicmaps.sdk.transit.PackInfo;
import java.time.Instant;

/**
 * The single place S1 turns into words and into consumer inputs. Timetable and Journey both call this, so the
 * same facts always read the same, in every locale. Additive: it never replaces the existing trust, validity,
 * attribution or import-failure text, which stay exactly as the screens render them today.
 */
final class DatasetStatusText
{
  private DatasetStatusText() {}

  static final class Presentation
  {
    final DatasetStatus.Kind kind;
    final String headline;
    /** The one action that helps, as a sentence; null when there is nothing to do. */
    final String action;
    /** The registered map versions of the one capture, or null when there are none to state. Never a coverage claim. */
    final String mapNote;
    Presentation(DatasetStatus.Kind kind, String headline, String action)
    { this(kind, headline, action, null); }
    Presentation(DatasetStatus.Kind kind, String headline, String action, String mapNote)
    { this.kind = kind; this.headline = headline; this.action = action; this.mapNote = mapNote; }
    String text()
    {
      String t = action == null ? headline : headline + "\n" + action;
      return mapNote == null ? t : t + "\n" + mapNote;
    }
  }

  // ---- consumer adapters: facts each screen already holds -> the same S1 inputs ------------------------------------

  /** Timetable: ScreenState facts. Returns null while the first pack read is still pending. */
  static DatasetStatus forTimetable(TimetableViewModel.ScreenState state, Instant now,
                                    DatasetStatus.MapPresence map)
  { return forTimetableWithMap(state, now, DatasetStatus.MapFacts.ofPresence(map)); }

  /** Same inputs, with the facts of the one registry capture taken at this resume. */
  static DatasetStatus forTimetableWithMap(TimetableViewModel.ScreenState state, Instant now,
                                           DatasetStatus.MapFacts map)
  {
    if (state == null) return null;
    final PackInfo info = state.packInfo;
    if (state.status == TimetableViewModel.Status.IDLE
        || (state.status == TimetableViewModel.Status.WORKING && info == null && !state.packPresent))
      return null;
    final boolean unavailable = info == null && (state.packPresent || state.status == TimetableViewModel.Status.ERROR);
    return DatasetStatus.computeWith(DatasetStatus.Scope.BOARDS, info, unavailable, map, now,
        state.importFailedFlag() ? DatasetStatus.Activation.REPLACE_FAILED : DatasetStatus.Activation.NONE, null);
  }

  /** Journey: the last pack facts the model resolved. Never derived from State.info, which is dropped on expiry. */
  static DatasetStatus forJourney(PackInfo resolvedPack, boolean unresolved, String envelope, Instant now,
                                  DatasetStatus.MapPresence map)
  { return forJourneyWithMap(resolvedPack, unresolved, envelope, now, DatasetStatus.MapFacts.ofPresence(map)); }

  static DatasetStatus forJourneyWithMap(PackInfo resolvedPack, boolean unresolved, String envelope, Instant now,
                                         DatasetStatus.MapFacts map)
  {
    return DatasetStatus.computeWith(DatasetStatus.Scope.JOURNEY, resolvedPack, unresolved && resolvedPack == null, map, now,
        DatasetStatus.Activation.NONE, envelope);
  }

  // ---- words --------------------------------------------------------------------------------------------------------

  static Presentation of(Context c, DatasetStatus s)
  {
    final String until = s.validUntil == null ? "" : s.validUntil;
    final boolean untrusted = s.trust == DatasetStatus.Trust.UNTRUSTED;
    String head;
    switch (s.kind)
    {
    case READY: head = c.getString(R.string.dataset_ready, until); break;
    case READY_UNTRUSTED: head = c.getString(R.string.dataset_ready_untrusted, until); break;
    case EXPIRING:
      head = s.daysLeft == 0 ? c.getString(R.string.dataset_expiring_today, until)
          : c.getResources().getQuantityString(R.plurals.dataset_expiring_days, s.daysLeft, s.daysLeft, until);
      if (untrusted) head += " " + c.getString(R.string.dataset_untrusted_short);
      break;
    case EXPIRED:
      head = s.expiryCause == DatasetStatus.ExpiryCause.AUTHORIZATION
          ? c.getString(R.string.dataset_expired_authorization) : c.getString(R.string.dataset_expired, until);
      break;
    case NOT_YET_VALID: head = c.getString(R.string.dataset_not_yet_valid, s.validFrom == null ? "" : s.validFrom); break;
    case NO_PACK: head = c.getString(R.string.dataset_no_pack); break;
    case PACK_UNAVAILABLE: head = c.getString(R.string.dataset_pack_unavailable); break;
    case INCOMPATIBLE: head = c.getString(R.string.dataset_incompatible); break;
    case MAP_MISSING: head = c.getString(R.string.dataset_map_missing); break;
    case MAP_UNKNOWN: head = c.getString(R.string.dataset_map_unknown); break;
    case KEPT_PREVIOUS_AFTER_FAILED_REPLACE:
      head = c.getString(R.string.dataset_kept_previous, until);
      if (untrusted) head += " " + c.getString(R.string.dataset_untrusted_short);
      break;
    default: throw new IllegalArgumentException("unhandled dataset status kind");
    }
    // A failed replacement stays visible beside any other state; the previous pack's own warnings are untouched.
    if (s.replaceFailed && s.kind != DatasetStatus.Kind.KEPT_PREVIOUS_AFTER_FAILED_REPLACE)
      head += "\n" + c.getString(R.string.dataset_replace_failed_note);
    String action;
    switch (s.action())
    {
    case NONE: action = null; break;
    case IMPORT_PACK: action = c.getString(R.string.dataset_action_import); break;
    case IMPORT_NEWER_PACK: action = c.getString(R.string.dataset_action_import_newer); break;
    case REIMPORT_PACK: action = c.getString(R.string.dataset_action_reimport); break;
    case INSTALL_MAP: action = c.getString(R.string.dataset_action_install_map); break;
    case RECHECK: action = c.getString(R.string.dataset_action_recheck); break;
    default: throw new IllegalArgumentException("unhandled dataset action");
    }
    return new Presentation(s.kind, head, action, mapNote(c, s));
  }

  /** The registered map versions of the one capture, stated as registered. Null unless a present capture has versions. */
  private static String mapNote(Context c, DatasetStatus s)
  {
    final DatasetStatus.MapFacts f = s.mapFacts;
    if (f == null || f.presence != DatasetStatus.MapPresence.PRESENT || f.versions.isEmpty()) return null;
    final StringBuilder list = new StringBuilder();
    for (Long version : f.versions)
    {
      if (list.length() > 0) list.append(", ");
      list.append(version.longValue());
    }
    final int unlisted = f.distinctVersions - f.versions.size();
    final String shown = unlisted > 0 ? c.getString(R.string.dataset_map_versions_more, list.toString(), unlisted) : list.toString();
    return c.getResources().getQuantityString(R.plurals.dataset_map_versions, f.distinctVersions, shown);
  }

  /**
   * UX06 manual setup guide: text only. It quotes the real labels of the existing Download Maps entry and the existing
   * import chooser, so it cannot drift from them, and states no size, percentage or progress.
   */
  static String setupGuide(Context c)
  {
    return c.getString(R.string.dataset_setup_guide, c.getString(R.string.download_maps),
                       c.getString(R.string.transit_import_pack), c.getString(R.string.transit_import_untrusted),
                       c.getString(R.string.transit_import_official));
  }

  /** The guide is shown exactly while S1 has an action to offer. A null status or a ready one hides it. */
  static void renderGuide(TextView view, DatasetStatus status)
  {
    if (view == null) return;
    if (status == null || status.action() == DatasetStatus.Action.NONE)
    {
      view.setText("");
      view.setVisibility(View.GONE);
      return;
    }
    view.setText(setupGuide(view.getContext()));
    view.setVisibility(View.VISIBLE);
  }

  /** Text is the accessible name; there is no colour or icon status. A null status hides the line. */
  static void render(TextView view, DatasetStatus status)
  {
    if (view == null) return;
    if (status == null) { view.setText(""); view.setVisibility(View.GONE); return; }
    view.setText(of(view.getContext(), status).text());
    view.setVisibility(View.VISIBLE);
  }
}
