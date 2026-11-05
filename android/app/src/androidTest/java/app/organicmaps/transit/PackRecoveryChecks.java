package app.organicmaps.transit;

import android.content.Context;
import android.content.ContextWrapper;
import app.organicmaps.sdk.transit.TimetableRepository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Isolated crash-layout witnesses; NEVER moves/removes the owner's installed pack. */
final class PackRecoveryChecks
{
  private final TransitPilotInstrumentation runner;
  PackRecoveryChecks(TransitPilotInstrumentation runner) { this.runner = runner; }
  void run() throws Exception
  {
    // Each layout represents a process-death boundary in the production activation protocol.
    for (int phase = 0; phase < 5; phase++)
    {
      final int boundary = phase;
      runner.check("isolated pack restart recovers previous pair at activation boundary " + phase, () -> {
        File root = new File(runner.getTargetContext().getCacheDir(), "dmaps-recovery-" + java.util.UUID.randomUUID());
        File dir = new File(root, "transit_pack");
        if (!dir.mkdirs()) throw new IOException("fixture directory");
        Context context = new ContextWrapper(runner.getTargetContext()) {
          @Override public Context getApplicationContext() { return this; }
          @Override public File getFilesDir() { return root; }
        };
        try
        {
          write(dir, "timetable.sqlite", "old-db"); write(dir, "manifest.json", "old-manifest");
          write(dir, "staging.timetable.sqlite", "new-db"); write(dir, "staging.manifest.json", "new-manifest");
          write(dir, "activation.pending", "previous\n");
          if (boundary >= 1) move(dir, "timetable.sqlite", "backup.timetable.sqlite");
          if (boundary >= 2) move(dir, "manifest.json", "backup.manifest.json");
          if (boundary >= 3) move(dir, "staging.manifest.json", "manifest.json");
          if (boundary >= 4) move(dir, "staging.timetable.sqlite", "timetable.sqlite");
          try (TimetableRepository repo = new TimetableRepository(context))
          {
            if (!repo.hasPack()) throw new AssertionError("previous pair not recovered");
            if (!read(dir, "timetable.sqlite").equals("old-db")
                || !read(dir, "manifest.json").equals("old-manifest")) throw new AssertionError("mixed generation");
          }
          try (TimetableRepository again = new TimetableRepository(context))
          { if (!again.hasPack()) throw new AssertionError("restart not idempotent"); }
        }
        finally
        {
          File[] files = dir.listFiles(); if (files != null) for (File file : files) if (!file.delete()) throw new IOException("fixture cleanup");
          if (!dir.delete() || !root.delete()) throw new IOException("fixture cleanup");
        }
      });
    }
  }
  private static void write(File dir, String name, String value) throws IOException
  { Files.write(new File(dir, name).toPath(), value.getBytes(StandardCharsets.UTF_8)); }
  private static String read(File dir, String name) throws IOException
  { return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8); }
  private static void move(File dir, String from, String to) throws IOException
  { if (!new File(dir, from).renameTo(new File(dir, to))) throw new IOException("fixture move"); }
}
