package app.organicmaps.sdk.transit;

import java.io.IOException;

/** An import/remove committed; callers must not claim the previous generation was retained. */
public final class PackCommitException extends IOException {
  public enum Operation { IMPORT, REMOVE }
  public final Operation operation;
  public PackCommitException(Operation operation) {
    super("Pack changed; follow-up unavailable");
    this.operation = operation;
  }
}
