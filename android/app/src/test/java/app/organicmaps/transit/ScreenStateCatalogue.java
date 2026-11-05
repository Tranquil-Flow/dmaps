package app.organicmaps.transit;

import app.organicmaps.R;

/** WI-07 / SYSTEM S7: the stable screen-state names, as a PURE classifier over the observable ViewModel state.
 * Test-tree only (no production change). It reads public/package state of {@link JourneyViewModel.State} and
 * {@link TimetableViewModel.ScreenState}; it never mutates, never opens a repository and never fabricates a state.
 * J6 (map preview open) and J7 (preview expired) live in WalkingMapPreviewSession/MwmActivity, not in the Journey
 * model, so they are catalogue entries without a classifier branch (evidence: WalkingMap* suites).
 * The JSON catalogue (screen-state-catalogue.json) must list exactly these ids; ScreenStateCatalogueTest enforces it. */
final class ScreenStateCatalogue {
  private ScreenStateCatalogue() {}
  /** Ids this classifier can return for the Journey screen. */
  static final String[] JOURNEY_CLASSIFIED = {"J0","J1","J1D","J2","J3","JC","J4","J5","J8","JD","JX","JS","JB","JN","JF"};
  /** Catalogue-only Journey ids: not observable from the Journey model (map side). */
  static final String[] JOURNEY_MAP_SIDE = {"J6","J7"};
  static final String[] TIMETABLE_CLASSIFIED = {"T0","T1","T2","T3","T4","T5","T6","T7"};

  /** Order matters: transient and retirement messages outrank the endpoint/result shape. */
  static String journey(JourneyViewModel.State s) {
    if (s.busy) return "JB";
    if (s.message == R.string.transit_no_pack) return "JN";
    if (s.message == R.string.journey_format_required) return "JF";
    if (s.info == null) {
      return s.message == R.string.journey_changed || s.message == R.string.journey_return_reselect ? "JX" : "JS";
    }
    if (s.result != null) return s.result.mapSnapshotId != null ? "J5" : "J4";
    if (s.candidates != null) return "JC";
    if (s.message == R.string.journey_departed) return "JD";
    if (s.message == R.string.journey_return_replan) return "J8";
    if (s.from == null && s.to == null) return "J0";
    if (s.to == null) return "J1";
    if (s.from == null) return "J1D";
    return s.from.selection.platforms.size() == 1 && s.to.selection.platforms.size() == 1 ? "J3" : "J2";
  }

  static String timetable(TimetableViewModel.ScreenState s) {
    switch (s.status) {
      case WORKING: return "T5";
      case NO_PACK: return "T0";
      case ERROR: return "T6";
      case NO_RESULTS: return "T4";
      default: break;
    }
    if (s.importFailed) return "T7";
    if (s.selectedStop != null && !s.board.isEmpty()) return "T1";
    if (s.selectedStop == null && !s.candidates.isEmpty()) return "T2";
    return s.packPresent ? "T3" : "T0";
  }
}
