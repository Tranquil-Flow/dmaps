package app.organicmaps.sdk.transit;

import java.lang.reflect.Constructor;

/**
 * Test-only builder for registry captures without native code. Candidate and Inventory have private
 * constructors (the native capture is their only production source), so this uses the same reflection the
 * S1 registry-conversion test already uses. Not a second registry model: it only constructs the real classes.
 */
public final class DatasetMapFactsFixture
{
  private DatasetMapFactsFixture() {}

  /** Berlin-ish box and a box far from Berlin, so tests can show that bounds never change an S1 answer. */
  public static final double[] BERLIN = {52.3, 13.0, 52.7, 13.8};
  public static final double[] BAVARIA = {47.2, 8.9, 50.6, 13.9};

  public static RegisteredWalkingMaps.Candidate candidate(String name, long version, double[] box) throws Exception
  {
    Constructor<RegisteredWalkingMaps.Candidate> c = RegisteredWalkingMaps.Candidate.class.getDeclaredConstructor(
        String.class, String.class, long.class, long.class, double.class, double.class, double.class, double.class);
    c.setAccessible(true);
    return c.newInstance("/maps/" + name, name, version, 1L, box[0], box[1], box[2], box[3]);
  }

  public static RegisteredWalkingMaps.Inventory inventory(boolean complete, RegisteredWalkingMaps.Candidate... maps)
      throws Exception
  {
    Constructor<RegisteredWalkingMaps.Inventory> c = RegisteredWalkingMaps.Inventory.class.getDeclaredConstructor(
        boolean.class, RegisteredWalkingMaps.Candidate[].class);
    c.setAccessible(true);
    return c.newInstance(complete, maps);
  }

  /** A complete capture with one map per given version (distinct names), all inside the given box. */
  public static RegisteredWalkingMaps.Inventory versions(double[] box, long... versions) throws Exception
  {
    RegisteredWalkingMaps.Candidate[] list = new RegisteredWalkingMaps.Candidate[versions.length];
    for (int i = 0; i < versions.length; i++) list[i] = candidate("Map_" + i, versions[i], box);
    return inventory(true, list);
  }
}
