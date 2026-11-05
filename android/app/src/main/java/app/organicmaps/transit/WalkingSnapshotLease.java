package app.organicmaps.transit;

import app.organicmaps.sdk.transit.MappedWalking;
import app.organicmaps.sdk.transit.RegisteredWalkingMaps;
import java.io.File;

/** One process snapshot, confined to its serial worker. A model holds an anchor while resumed;
 * plans borrow leases. Never move a native router across workers or close another worker's map.
 * CoMaps downloads to .ready then renames; diffs write .diff-applied then rename (WI-05 evidence).
 * A hard-link failure (e.g. different filesystems) explicitly tries the original bounded copy.
 * Both modes still hash the content and recheck source incarnation in the native provider. */
final class WalkingSnapshotLease implements AutoCloseable {
  private static final Object LOCK=new Object();
  private static Entry current;
  private static final class Entry {
    final Thread owner=Thread.currentThread();
    final RegisteredWalkingMaps.Candidate candidate;
    final MappedWalking provider;
    int refs;
    Entry(RegisteredWalkingMaps.Candidate candidate,MappedWalking provider){this.candidate=candidate;this.provider=provider;}
  }
  private final Entry entry;private boolean closed;
  private WalkingSnapshotLease(Entry entry){this.entry=entry;entry.refs++;}
  static WalkingSnapshotLease acquire(RegisteredWalkingMaps.Candidate candidate,File cache,long budget){
    synchronized(LOCK){
      if(current!=null){
        if(current.owner!=Thread.currentThread()||!same(current,candidate)||!current.provider.isCurrent())return null;
        return new WalkingSnapshotLease(current);
      }
      MappedWalking p=candidate.open(cache,MappedWalking.SnapshotMode.HARD_LINK,budget);
      if(p==null)p=candidate.open(cache,MappedWalking.SnapshotMode.PRIVATE_COPY,budget);
      if(p==null)return null;
      current=new Entry(candidate,p);return new WalkingSnapshotLease(current);
    }
  }
  private static boolean same(Entry e,RegisteredWalkingMaps.Candidate c){return e.candidate==c||e.candidate.sameGeneration(c);}
  boolean matches(RegisteredWalkingMaps.Candidate candidate){requireOwner();return !closed&&same(entry,candidate)&&entry.provider.isCurrent();}
  MappedWalking provider(){requireOwner();if(closed)throw new IllegalStateException("walking lease closed");return entry.provider;}
  private void requireOwner(){if(entry.owner!=Thread.currentThread())throw new IllegalStateException("walking requires its owning worker");}
  @Override public void close(){
    requireOwner();synchronized(LOCK){if(closed)return;closed=true;if(--entry.refs==0){try{entry.provider.close();}finally{if(current==entry)current=null;}}}
  }
}
