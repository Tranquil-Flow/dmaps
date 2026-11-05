package app.organicmaps.sdk.transit;

import java.io.*;
import java.nio.channels.FileLock;

/** Independent child-process kernel-lock observer, never an app or device entrypoint. */
public final class PackDirectoryProbe {
  public static void main(String[] args) throws Exception {
    try (RandomAccessFile file=new RandomAccessFile(new File(args[0],"pack-writer.lock"),"rw")) {
      FileLock lock=file.getChannel().tryLock();
      if(lock==null)System.out.println("BLOCKED");
      else {try {System.out.println("ACQUIRED");}finally{lock.release();}}
    }
  }
}
