package app.organicmaps.transit;

import androidx.annotation.StringRes;

import app.organicmaps.R;
import app.organicmaps.sdk.transit.rt.GtfsRealtime;
import app.organicmaps.sdk.transit.rt.RealtimeMerger;
import app.organicmaps.sdk.transit.rt.RealtimeRequest;
import app.organicmaps.sdk.transit.rt.WireReader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Fetches a realtime slice from a livefeed relay and joins it to the pack.
 *
 * The relay is contacted only for a REGION and a HORIZON, never for a stop, so
 * a relay operator cannot learn which stations a user cares about. That
 * property comes from the request shape, not from transport security — see
 * spec-site/livefeed-v1.md §2.
 *
 * Network access is deliberately confined to this class, so the privacy
 * surface of the timetable feature is auditable in one file.
 *
 * Conditionally fetched: the relay's ETag is returned to the caller and echoed
 * back as If-None-Match, so an unchanged slice costs one 304 rather than a
 * ~1.5 MB body on every refresh.
 */
public final class RealtimeClient
{
  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final int READ_TIMEOUT_MS = 30_000;
  private static final int MAX_BYTES = 64 * 1024 * 1024;

  /** Result of one fetch. Null payload means the fetch failed; see {@link #errorRes}. */
  public static final class Result
  {
    public final Map<String, GtfsRealtime.TripUpdate> joined;
    /** Full validated snapshot, retained for exact instance/occurrence projection. */
    public final GtfsRealtime.FeedMessage feed;
    public final Long feedTimestamp;
    public final long fetchedAtEpochSec;
    public final int feedTripUpdates;
    /** Relay ETag to echo on the next fetch; null when the relay sent none. */
    public final String etag;
    /** Relay's X-Schedule-Sha, surfaced for display/audit; null when absent. */
    public final String scheduleSha;
    /** True on a 304: reuse the previous result, do not treat as empty. */
    public final boolean notModified;
    /** Raw failure text for the log only. Never shown to the user. */
    public final String error;
    /** Bounded, localizable reason for the UI; 0 when the fetch succeeded. */
    @StringRes public final int errorRes;

    Result(Map<String, GtfsRealtime.TripUpdate> joined, Long feedTimestamp,
           long fetchedAtEpochSec, int feedTripUpdates, String etag,
           String scheduleSha, boolean notModified, String error, int errorRes)
    {
      this(joined, feedTimestamp, fetchedAtEpochSec, feedTripUpdates, etag, scheduleSha,
          notModified, error, errorRes, null);
    }

    Result(Map<String, GtfsRealtime.TripUpdate> joined, Long feedTimestamp, long fetchedAtEpochSec,
           int feedTripUpdates, String etag, String scheduleSha, boolean notModified,
           String error, int errorRes, GtfsRealtime.FeedMessage feed)
    {
      this.feed = feed;
      this.joined = joined;
      this.feedTimestamp = feedTimestamp;
      this.fetchedAtEpochSec = fetchedAtEpochSec;
      this.feedTripUpdates = feedTripUpdates;
      this.etag = etag;
      this.scheduleSha = scheduleSha;
      this.notModified = notModified;
      this.error = error;
      this.errorRes = errorRes;
    }

    public boolean ok() { return error == null; }
  }

  /** Offline-preview shipping capability. Not a preference, import field, intent extra or fallback.
   * A separately approved and qualified live release must change its source-bound build policy. */
  private static final boolean LIVE_CAPABILITY_ENABLED = false;
  public static boolean liveUpdatesAvailable() { return LIVE_CAPABILITY_ENABLED; }

  private RealtimeClient() {}

  /** Fetches and joins, without conditional revalidation. */
  public static Result fetchAndJoin(String relayBase, String region, int horizonSec,
                                    Set<String> packTripIds)
  {
    return fetchAndJoin(relayBase, region, horizonSec, packTripIds, null);
  }

  /**
   * Fetches a slice for {@code region} and joins it to {@code packTripIds}.
   *
   * Unresolved updates are dropped by {@link RealtimeMerger#joinToPack}, never
   * matched heuristically. Callers must not reintroduce a fallback join.
   *
   * @param previousEtag ETag from the prior fetch, or null for an unconditional GET
   */
  public static Result fetchAndJoin(String relayBase, String region, int horizonSec,
                                    Set<String> packTripIds, String previousEtag)
  {
    return fetchAndJoin(relayBase, region, horizonSec, packTripIds, previousEtag, new RealtimeRequest());
  }

  public static Result fetchAndJoin(String relayBase, String region, int horizonSec,
                                    Set<String> packTripIds, String previousEtag, RealtimeRequest ticket)
  {
    // Deny before parsing the endpoint, reading caller state, allocating a connection or its I/O.
    if (!liveUpdatesAvailable()) return fail("offline-preview", R.string.transit_offline_preview);
    return fetchLivePrototype(relayBase, region, horizonSec, packTripIds, previousEtag, ticket);
  }

  /** Retained live transport primitive for a separate release. No UI/import/intent can select it.
   * Host transport controls exercise this private body explicitly; that is not preview authority. */
  private static Result fetchLivePrototype(String relayBase, String region, int horizonSec,
                                          Set<String> packTripIds, String previousEtag, RealtimeRequest ticket)
  {
    long now = System.currentTimeMillis() / 1000L;
    HttpURLConnection conn = null;
    try
    {
      if (ticket.isCanceled()) return fail("revoked", R.string.transit_live_reason_offline);
      String url = relayBase + "/v1/slice?region=" + region + "&horizon=" + horizonSec;
      conn = (HttpURLConnection) new URL(url).openConnection();
      if (!ticket.attach(conn)) return fail("revoked", R.string.transit_live_reason_offline);
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(READ_TIMEOUT_MS);
      conn.setRequestMethod("GET");
      conn.setRequestProperty("Accept-Encoding", "gzip");
      conn.setUseCaches(false);
      if (previousEtag != null && !previousEtag.isEmpty())
        conn.setRequestProperty("If-None-Match", previousEtag);

      if (ticket.isCanceled()) return fail("revoked", R.string.transit_live_reason_offline);
      int code = conn.getResponseCode();
      if (code == HttpURLConnection.HTTP_NOT_MODIFIED)
      {
        if (previousEtag == null) return fail("unexpected 304", R.string.transit_live_reason_bad_feed);
        return new Result(new LinkedHashMap<>(), null, now, 0,
                          previousEtag, null, true, null, 0);
      }
      if (code != 200)
        return fail("relay returned HTTP " + code, R.string.transit_live_reason_refused);

      // The relay's ETag is an opaque validator (an MD5 hex in practice), not
      // an epoch. It is kept verbatim as a string and echoed back unchanged;
      // forcing it to a number would silently disable revalidation.
      String etag = boundedHeader(conn.getHeaderField("ETag"));
      String sha = boundedHeader(conn.getHeaderField("X-Schedule-Sha"));

      byte[] raw = readFully(conn, ticket);
      if (raw == null || raw.length == 0)
        return fail("empty response body", R.string.transit_live_reason_bad_feed);

      GtfsRealtime.FeedMessage feed;
      try
      {
        feed = GtfsRealtime.parse(raw);
      }
      catch (WireReader.WireException e)
      {
        // A headerless or malformed body is a fetch failure, never an empty
        // feed: "no updates" and "no response" must not look alike.
        return fail("unparseable feed", R.string.transit_live_reason_bad_feed);
      }

      Map<String, GtfsRealtime.TripUpdate> joined =
          RealtimeMerger.joinToPack(feed, packTripIds);
      return new Result(joined, feed.timestamp, now, feed.tripUpdates.size(),
                        etag, sha, false, null, 0, GtfsRealtime.forTripIds(feed, packTripIds));
    }
    catch (java.net.SocketTimeoutException e)
    {
      return fail("timeout", R.string.transit_live_reason_timeout);
    }
    catch (Exception e)
    {
      return fail(e.getClass().getSimpleName(), R.string.transit_live_reason_offline);
    }
    finally
    {
      if (conn != null)
        conn.disconnect();
    }
  }

  /**
   * Builds a failure result.
   *
   * The UI is given {@code errorRes}, a fixed localized reason — never {@code why}.
   * An endpoint's own text is arbitrary and must not reach the screen; the raw
   * value stays here for the debug log only.
   */
  private static Result fail(String why, @StringRes int errorRes)
  {
    return new Result(new LinkedHashMap<>(), null,
                      System.currentTimeMillis() / 1000L, 0, null, null, false,
                      why, errorRes);
  }

  /** Bounded: an unbounded upstream header value must not be stored or echoed. */
  private static String boundedHeader(String v)
  {
    return v != null && v.length() <= 128 ? v : null;
  }

  /** Reads the body, honouring gzip when the relay used it. */
  private static byte[] readFully(HttpURLConnection conn, RealtimeRequest ticket) throws java.io.IOException
  {
    String encoding = conn.getContentEncoding();
    InputStream in = conn.getInputStream();
    if (encoding != null && encoding.toLowerCase(java.util.Locale.ROOT).contains("gzip"))
      in = new GZIPInputStream(in);

    try (InputStream src = in; ByteArrayOutputStream out = new ByteArrayOutputStream())
    {
      byte[] buf = new byte[16 * 1024];
      int total = 0, n;
      while ((n = src.read(buf)) > 0)
      {
        if (ticket.isCanceled()) throw new java.io.IOException("request revoked");
        total += n;
        if (total > MAX_BYTES)
          throw new java.io.IOException("response exceeds " + MAX_BYTES + " bytes");
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    }
  }

  /** Trip ids present on the board, as a set for the join. */
  public static Set<String> tripIdsOf(Iterable<app.organicmaps.sdk.transit.Departure> board)
  {
    HashSet<String> ids = new HashSet<>();
    for (app.organicmaps.sdk.transit.Departure d : board)
      if (d.tripSourceId != null)
        ids.add(d.tripSourceId);
    return ids;
  }
}
