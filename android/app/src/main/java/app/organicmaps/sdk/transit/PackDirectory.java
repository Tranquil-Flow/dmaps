package app.organicmaps.sdk.transit;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Directory-wide writer guard: one canonical-path mutex across repository instances, plus a
 * kernel file lock across processes. Paths come from trusted application storage, never metadata.
 * Acquire before staging, recovery, migration or activation. Nested use is owner-thread/LIFO only.
 * Do not unlink/replace pack-writer.lock: changing its inode would split cross-process authority.
 */
final class PackDirectory {
  // The application has one fixed pack directory. Trusted test callers use isolated directories.
  private static final ConcurrentMap<String, ReentrantLock> MUTEXES = new ConcurrentHashMap<>();
  private final File directory;
  private final ReentrantLock mutex;

  PackDirectory(File directory) throws IOException {
    if (directory == null) throw new IOException("Pack storage unavailable");
    try { this.directory = directory.getCanonicalFile(); }
    catch (IOException | SecurityException failure) { throw new IOException("Pack storage unavailable"); }
    ReentrantLock candidate = new ReentrantLock(true);
    ReentrantLock existing = MUTEXES.putIfAbsent(this.directory.getPath(), candidate);
    mutex = existing == null ? candidate : existing;
  }

  Guard acquire() throws IOException {
    try { mutex.lockInterruptibly(); }
    catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Pack storage lock interrupted");
    }
    RandomAccessFile file = null;
    FileLock kernel = null;
    boolean admitted = false;
    try {
      Guard guard;
      int depth = mutex.getHoldCount();
      if (depth > 1) {
        // The outermost guard already owns the process-wide file lock.
        guard = new Guard(null, null, depth);
      } else {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
          throw new IOException("Pack storage unavailable");
        file = new RandomAccessFile(new File(directory, "pack-writer.lock"), "rw");
        kernel = file.getChannel().lock();
        guard = new Guard(file, kernel, depth);
      }
      admitted = true;
      return guard;
    } catch (IOException | OverlappingFileLockException | SecurityException failure) {
      throw new IOException("Pack storage lock unavailable");
    } finally {
      if (!admitted) {
        // No caller was admitted. Preserve the refusal even if secondary cleanup also fails.
        try { if (kernel != null) kernel.release(); } catch (IOException ignored) { }
        try { if (file != null) file.close(); } catch (IOException ignored) { }
        mutex.unlock();
      }
    }
  }

  final class Guard implements AutoCloseable {
    private final RandomAccessFile file;
    private final FileLock kernel;
    private final Thread owner;
    private final int depth;
    private boolean closed;

    private Guard(RandomAccessFile file, FileLock kernel, int depth) {
      this.file = file;
      this.kernel = kernel;
      this.depth = depth;
      owner = Thread.currentThread();
    }

    @Override public void close() throws IOException {
      if (Thread.currentThread() != owner)
        throw new IllegalStateException("Pack storage guard belongs to another thread");
      if (closed) return;
      if (!mutex.isHeldByCurrentThread() || mutex.getHoldCount() != depth)
        throw new IllegalStateException("Pack storage guards must close in reverse order");
      boolean cleanupFailed = false;
      try {
        try { if (kernel != null) kernel.release(); }
        catch (IOException failure) { cleanupFailed = true; }
        try { if (file != null) file.close(); }
        catch (IOException failure) { cleanupFailed = true; }
      } finally {
        closed = true;
        mutex.unlock();
      }
      if (cleanupFailed) throw new IOException("Pack storage lock cleanup failed");
    }
  }
}
