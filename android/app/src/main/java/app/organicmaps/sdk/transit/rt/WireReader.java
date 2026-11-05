package app.organicmaps.sdk.transit.rt;

import java.io.UnsupportedEncodingException;

/**
 * Minimal protobuf wire-format reader.
 *
 * Deliberately not the protobuf runtime. A realtime feed is untrusted input
 * arriving over the network, and this reader is the trust boundary: everything
 * downstream assumes it either produced correct records or threw. Only the wire
 * types the GTFS-RT schema actually uses are supported — anything else raises,
 * so an unfamiliar schema revision fails loudly instead of silently yielding
 * records with shifted field values.
 *
 * Structural counterpart of timetable_rt/wire.py; both are exercised against the
 * same live feed.
 */
public final class WireReader
{
  public static final int VARINT = 0;
  public static final int FIXED64 = 1;
  public static final int LEN = 2;
  public static final int FIXED32 = 5;

  /** Raised on malformed or unsupported wire data. Never caught and ignored. */
  public static final class WireException extends RuntimeException
  {
    public WireException(String message) { super(message); }
  }

  private final byte[] mBuf;
  private int mPos;
  private final int mLimit;

  public WireReader(byte[] buf) { this(buf, 0, buf.length); }

  public WireReader(byte[] buf, int start, int limit)
  {
    if (buf == null || start < 0 || limit > buf.length || start > limit)
      throw new WireException("invalid buffer bounds");
    mBuf = buf;
    mPos = start;
    mLimit = limit;
  }

  public boolean hasRemaining() { return mPos < mLimit; }

  public int position() { return mPos; }

  /** Reads a base-128 varint. The 70-bit cap rejects pathological input. */
  public long readVarint()
  {
    long result = 0;
    int shift = 0;
    while (true)
    {
      if (mPos >= mLimit)
        throw new WireException("varint truncated before limit");
      if (shift >= 70)
        throw new WireException("varint too long");
      int b = mBuf[mPos++] & 0xFF;
      if (shift == 63 && (b & 0xfe) != 0)
        throw new WireException("varint exceeds 64 bits");
      result |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0)
        return result;
      shift += 7;
    }
  }

  /** Reads a field tag, returning the field number and setting outWireType. */
  public int readTag(OutInt outWireType)
  {
    long key = readVarint();
    if (key < 0 || key > 0xffffffffL)
      throw new WireException("tag exceeds protobuf field range");
    int field = (int) (key >>> 3);
    int wtype = (int) (key & 0x07);
    if (field == 0)
      throw new WireException("field number 0 is invalid");
    if (wtype != VARINT && wtype != LEN && wtype != FIXED32 && wtype != FIXED64)
      throw new WireException("unsupported wire type " + wtype);
    outWireType.value = wtype;
    return field;
  }

  public byte[] readBytes()
  {
    long len = readVarint();
    if (len < 0 || len > Integer.MAX_VALUE || mPos + len > mLimit)
      throw new WireException("length-delimited field overruns message: claims "
          + len + " bytes but only " + (mLimit - mPos) + " remain");
    byte[] out = new byte[(int) len];
    System.arraycopy(mBuf, mPos, out, 0, (int) len);
    mPos += (int) len;
    return out;
  }

  public void skip(int wireType)
  {
    switch (wireType)
    {
      case VARINT: readVarint(); break;
      case LEN: readBytes(); break;
      case FIXED64: require(8); mPos += 8; break;
      case FIXED32: require(4); mPos += 4; break;
      default:
        // Groups (3/4) are legacy and absent from GTFS-RT. Refuse rather than
        // guess, so a future schema change surfaces as an error.
        throw new WireException("unsupported wire type " + wireType);
    }
  }

  private void require(int n)
  {
    if (mPos + n > mLimit)
      throw new WireException("fixed-width field truncated");
  }

  public static String utf8(byte[] raw)
  {
    if (raw == null)
      return null;
    try
    {
      return new String(raw, "UTF-8");
    }
    catch (UnsupportedEncodingException e)
    {
      // UTF-8 is required on every JVM; reaching here means a broken runtime.
      throw new WireException("UTF-8 unavailable: " + e.getMessage());
    }
  }

  /** Simple mutable int carrier, since Java has no out-parameters. */
  public static final class OutInt
  {
    public int value;
  }
}
