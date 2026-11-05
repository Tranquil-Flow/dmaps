package app.organicmaps.sdk.transit.rt;

import java.net.HttpURLConnection;

/** One revocable local consent/context ticket. It owns at most one admitted connection. */
public final class RealtimeRequest
{
  private boolean mCanceled;
  private HttpURLConnection mConnection;

  public synchronized boolean isCanceled() { return mCanceled; }

  /** Called before any connection headers/body are requested; queued revoked work refuses here. */
  public synchronized boolean attach(HttpURLConnection connection)
  {
    if (mCanceled)
    {
      connection.disconnect();
      return false;
    }
    if (mConnection != null) throw new IllegalStateException("ticket already owns a connection");
    mConnection = connection;
    return true;
  }

  public void cancel()
  {
    HttpURLConnection connection;
    synchronized (this)
    {
      mCanceled = true;
      connection = mConnection;
      mConnection = null;
    }
    if (connection != null) connection.disconnect();
  }
}
