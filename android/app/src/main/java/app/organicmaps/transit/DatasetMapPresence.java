package app.organicmaps.transit;

import android.content.Context;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.transit.DatasetStatus;
import app.organicmaps.sdk.transit.RegisteredWalkingMaps;

/**
 * Production read of the registered-map registry for the S1 status. Main thread only (the registry capture
 * is a UI-thread call), once per resume, never per frame.
 *
 * Host-test safe by construction: unless the process application is the real MwmApplication AND its native
 * platform/core report initialized, nothing native is touched and the answer is UNKNOWN. A plain
 * Robolectric Application therefore never reaches JNI. A process restored straight into a screen before the
 * core is initialized also answers UNKNOWN rather than risking an uncatchable native fault.
 */
final class DatasetMapPresence
{
  private DatasetMapPresence() {}

  /** Presence only. Kept for existing callers; it is the presence of captureFacts(), never a second registry read policy. */
  static DatasetStatus.MapPresence capture(Context context)
  {
    return captureFacts(context).presence;
  }

  /**
   * One registry capture -> immutable facts (presence plus the actual registered map versions). Callers take exactly
   * one per resume. An unreadable registry is UNKNOWN: neither "missing" nor "ready". Version numbers are stated as
   * registered; they are not a Berlin coverage or compatibility claim.
   */
  static DatasetStatus.MapFacts captureFacts(Context context)
  {
    try
    {
      Context application = context.getApplicationContext();
      if (!(application instanceof MwmApplication)) return DatasetStatus.MapFacts.UNKNOWN;
      if (!((MwmApplication) application).getOrganicMaps().arePlatformAndCoreInitialized())
        return DatasetStatus.MapFacts.UNKNOWN;
      return DatasetStatus.MapFacts.of(RegisteredWalkingMaps.capture());
    }
    catch (RuntimeException | LinkageError unavailable)
    {
      return DatasetStatus.MapFacts.UNKNOWN; // never "ready", never "missing", when the registry cannot be read
    }
  }
}
