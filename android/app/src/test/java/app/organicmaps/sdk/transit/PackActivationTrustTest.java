package app.organicmaps.sdk.transit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** Real private temporary files; enumerated process-crash layouts, not power-loss claims. */
public class PackActivationTrustTest {
  @Rule public final TemporaryFolder temporary=new TemporaryFolder();
  private static final String[] ACTIVE={"timetable.sqlite","manifest.json","trust-state.json"};
  private static final String[] STAGED={"staging.timetable.sqlite","staging.manifest.json","staging.trust-state.json"};
  private static final String[] BACKUP={"backup.timetable.sqlite","backup.manifest.json","backup.trust-state.json"};
  private static void write(File dir,String file,String value) throws IOException {Files.write(new File(dir,file).toPath(),value.getBytes(StandardCharsets.UTF_8));}
  private static String read(File dir,String file) throws IOException {return new String(Files.readAllBytes(new File(dir,file).toPath()),StandardCharsets.UTF_8);}
  private static void move(File dir,String from,String to) {assertTrue(new File(dir,from).renameTo(new File(dir,to)));}
  private static boolean present(int mask,int index) {return (mask&(1<<(2-index)))!=0;}
  private static void old(File dir,int mask) throws IOException {for(int i=0;i<3;i++)if(present(mask,i))write(dir,ACTIVE[i],"old-"+i);write(dir,"unrelated-sentinel","keep");}
  private static void staged(File dir) throws IOException {for(int i=0;i<3;i++)write(dir,STAGED[i],"new-"+i);}
  private static void journal(File dir,int mask) throws IOException {write(dir,"activation.pending","state1:"+String.format(Locale.ROOT,"%3s",Integer.toBinaryString(mask)).replace(' ','0')+"\n");}
  private static void assertOld(File dir,int mask) throws IOException {
    for(int i=0;i<3;i++){assertEquals(present(mask,i),new File(dir,ACTIVE[i]).isFile());if(present(mask,i))assertEquals("old-"+i,read(dir,ACTIVE[i]));assertFalse(new File(dir,STAGED[i]).exists());assertFalse(new File(dir,BACKUP[i]).exists());}
    assertFalse(new File(dir,"activation.pending").exists());assertEquals("keep",read(dir,"unrelated-sentinel"));
  }
  @Test public void interruptedActivationRestoresCompleteOldGeneration() throws Exception {
    int layouts=0;
    for(int mask:new int[]{0,1,6,7}){
      List<String[]> operations=new ArrayList<>();
      for(int i=0;i<3;i++)if(present(mask,i))operations.add(new String[]{ACTIVE[i],BACKUP[i]});
      operations.add(new String[]{STAGED[1],ACTIVE[1]});operations.add(new String[]{STAGED[0],ACTIVE[0]});operations.add(new String[]{STAGED[2],ACTIVE[2]});
      for(int phase=0;phase<=operations.size();phase++){
        File dir=temporary.newFolder();old(dir,mask);staged(dir);journal(dir,mask);
        for(int i=0;i<phase;i++)move(dir,operations.get(i)[0],operations.get(i)[1]);
        PackActivation activation=new PackActivation(dir);activation.recover();activation.recover();assertOld(dir,mask);layouts++;
      }
    }
    assertEquals(22,layouts);System.out.println("TRUST_ACTIVATION_CRASH_LAYOUTS "+layouts);
  }
  @Test public void interruptedRemovalPreservesPayloadAndHistory() throws Exception {
    for(int phase=0;phase<=4;phase++){
      File dir=temporary.newFolder();old(dir,7);write(dir,STAGED[2],"new-history");journal(dir,7);
      for(int i=0;i<Math.min(phase,3);i++)move(dir,ACTIVE[i],BACKUP[i]);
      if(phase==4)move(dir,STAGED[2],ACTIVE[2]);
      PackActivation activation=new PackActivation(dir);activation.recover();activation.recover();assertOld(dir,7);
    }
    System.out.println("TRUST_REMOVAL_CRASH_LAYOUTS 5");
  }
  @Test public void malformedJournalCannotMutateHistoryOrPayload() throws Exception {
    for(String marker:new String[]{"state1:100\n","state1:010\n","state1:111\njunk","state1:xyz\n"}){
      File dir=temporary.newFolder();old(dir,7);write(dir,"activation.pending",marker);
      try{new PackActivation(dir).recover();fail("invalid journal admitted");}catch(IOException expected){}
      for(int i=0;i<3;i++)assertEquals("old-"+i,read(dir,ACTIVE[i]));
      assertEquals(marker,read(dir,"activation.pending"));
    }
  }
  @Test public void commitsAllMembersAndReplacesAsOneGeneration() throws Exception {
    File dir=temporary.newFolder();staged(dir);new PackActivation(dir).activateWithState();
    for(int i=0;i<3;i++)assertEquals("new-"+i,read(dir,ACTIVE[i]));
    for(int i=0;i<3;i++)write(dir,STAGED[i],"third-"+i);
    new PackActivation(dir).activateWithState();new PackActivation(dir).recover();
    for(int i=0;i<3;i++){assertEquals("third-"+i,read(dir,ACTIVE[i]));assertFalse(new File(dir,BACKUP[i]).exists());}
    assertFalse(new File(dir,"activation.pending").exists());
  }
  @Test public void removeCommitsHistoryWithoutRetainingActivePayload() throws Exception {
    File dir=temporary.newFolder();old(dir,7);write(dir,STAGED[2],"retained-history");
    new PackActivation(dir).removeWithState();new PackActivation(dir).recover();
    assertFalse(new File(dir,ACTIVE[0]).exists());assertFalse(new File(dir,ACTIVE[1]).exists());
    assertEquals("retained-history",read(dir,ACTIVE[2]));assertEquals("keep",read(dir,"unrelated-sentinel"));
    for(String file:BACKUP)assertFalse(new File(dir,file).exists());
    write(dir,STAGED[2],"retained-history");new PackActivation(dir).removeWithState();
    assertEquals("retained-history",read(dir,ACTIVE[2]));
  }
  @Test public void activationFaultAtEveryPrecommitStepRestoresAllMembers() throws Exception {
    String[] points={"journal-published","backed-up-0","backed-up-1","backed-up-2","installed-manifest","installed-database","installed-state","before-commit"};
    for(String point:points){File dir=temporary.newFolder();old(dir,7);staged(dir);
      PackActivation activation=new PackActivation(dir,checkpoint->{if(point.equals(checkpoint))throw new IOException("injected precommit fault");});
      try{activation.activateWithState();fail("fault was not reached");}catch(IOException expected){assertEquals("injected precommit fault",expected.getMessage());}
      new PackActivation(dir).recover();assertOld(dir,7);
    }
    System.out.println("TRUST_ACTIVATION_INJECTED_FAULTS "+points.length);
  }
  @Test public void removalFaultAtEveryPrecommitStepRestoresAllMembers() throws Exception {
    String[] points={"journal-published","backed-up-0","backed-up-1","backed-up-2","installed-state","before-commit"};
    for(String point:points){File dir=temporary.newFolder();old(dir,7);write(dir,STAGED[2],"retained-history");
      PackActivation activation=new PackActivation(dir,checkpoint->{if(point.equals(checkpoint))throw new IOException("injected removal fault");});
      try{activation.removeWithState();fail("fault was not reached");}catch(IOException expected){assertEquals("injected removal fault",expected.getMessage());}
      new PackActivation(dir).recover();assertOld(dir,7);
    }
    System.out.println("TRUST_REMOVAL_INJECTED_FAULTS "+points.length);
  }
  @Test public void interruptionDuringRecoveryKeepsJournalRetryable() throws Exception {
    for(int member=0;member<3;member++){File dir=temporary.newFolder();old(dir,7);staged(dir);journal(dir,7);
      for(int i=0;i<3;i++)move(dir,ACTIVE[i],BACKUP[i]);
      for(int i=0;i<3;i++)move(dir,STAGED[i],ACTIVE[i]);
      final String point="restored-"+member;
      try{new PackActivation(dir,checkpoint->{if(point.equals(checkpoint))throw new IOException("injected recovery fault");}).recover();fail("fault not reached");}
      catch(IOException expected){assertEquals("injected recovery fault",expected.getMessage());}
      assertTrue(new File(dir,"activation.pending").isFile());new PackActivation(dir).recover();assertOld(dir,7);
    }
    System.out.println("TRUST_RECOVERY_INTERRUPTION_LAYOUTS 3");
  }
  @Test public void missingStagedMemberCannotMutatePreviousGeneration() throws Exception {
    for(int missing=0;missing<3;missing++){File dir=temporary.newFolder();old(dir,7);
      for(int i=0;i<3;i++)if(i!=missing)write(dir,STAGED[i],"new-"+i);
      try{new PackActivation(dir).activateWithState();fail("missing member admitted");}catch(IOException expected){}
      for(int i=0;i<3;i++)assertEquals("old-"+i,read(dir,ACTIVE[i]));
      assertFalse(new File(dir,"activation.pending").exists());
    }
  }
  @Test public void invalidActiveSlotCannotEraseHistory() throws Exception {
    File dir=temporary.newFolder();old(dir,7);staged(dir);assertTrue(new File(dir,ACTIVE[0]).delete());assertTrue(new File(dir,ACTIVE[0]).mkdir());
    write(new File(dir,ACTIVE[0]),"child","keep");
    try{new PackActivation(dir).activateWithState();fail("directory admitted");}catch(IOException expected){}
    assertEquals("old-2",read(dir,ACTIVE[2]));assertEquals("old-1",read(dir,ACTIVE[1]));
    assertEquals("keep",read(new File(dir,ACTIVE[0]),"child"));assertFalse(new File(dir,"activation.pending").exists());
  }
  @Test public void legacyOperationsCannotBypassKnownTrustState() throws Exception {
    for(String witness:new String[]{ACTIVE[2],STAGED[2],BACKUP[2],"trust.initialized"}){
      File dir=temporary.newFolder();old(dir,6);write(dir,STAGED[0],"new-db");write(dir,STAGED[1],"new-manifest");write(dir,witness,"history-witness");
      try{new PackActivation(dir).activate();fail("legacy activation bypassed state");}catch(IOException expected){}
      assertEquals("old-0",read(dir,ACTIVE[0]));assertEquals("old-1",read(dir,ACTIVE[1]));
      write(dir,"activation.pending","previous\n");
      try{new PackActivation(dir).removeJournal();fail("legacy deletion bypassed state");}catch(IOException expected){}
      assertEquals("previous\n",read(dir,"activation.pending"));assertEquals("history-witness",read(dir,witness));
    }
  }
  @Test public void incompletePreviousGenerationRefusesBeforeDestructiveRecovery() throws Exception {
    File dir=temporary.newFolder();old(dir,7);journal(dir,7);assertTrue(new File(dir,ACTIVE[1]).delete());write(dir,BACKUP[0],"backup-db-witness");
    try{new PackActivation(dir).recover();fail("incomplete previous generation admitted");}catch(IOException expected){}
    assertEquals("old-0",read(dir,ACTIVE[0]));assertEquals("old-2",read(dir,ACTIVE[2]));assertEquals("backup-db-witness",read(dir,BACKUP[0]));assertTrue(new File(dir,"activation.pending").exists());
  }
  @Test public void obsoleteBackupsCannotUndoCommittedStateOrContaminateNextRollback() throws Exception {
    File dir=temporary.newFolder();old(dir,7);staged(dir);new PackActivation(dir).activateWithState();
    for(int i=0;i<3;i++)write(dir,BACKUP[i],"obsolete-"+i);
    new PackActivation(dir).recover();for(int i=0;i<3;i++)assertEquals("new-"+i,read(dir,ACTIVE[i]));
    for(int i=0;i<3;i++)write(dir,STAGED[i],"third-"+i);
    try{new PackActivation(dir,checkpoint->{if("before-commit".equals(checkpoint))throw new IOException("injected next fault");}).activateWithState();fail("fault not reached");}catch(IOException expected){}
    new PackActivation(dir).recover();for(int i=0;i<3;i++)assertEquals("new-"+i,read(dir,ACTIVE[i]));
  }
  @Test public void actualBackupCleanupFailurePreservesPreviousGeneration() throws Exception {
    File dir=temporary.newFolder();old(dir,7);staged(dir);File blocked=new File(dir,BACKUP[2]);assertTrue(blocked.mkdir());write(blocked,"child","keep");
    try{new PackActivation(dir).activateWithState();fail("blocked backup admitted");}catch(IOException expected){}
    for(int i=0;i<3;i++)assertEquals("old-"+i,read(dir,ACTIVE[i]));assertEquals("keep",read(blocked,"child"));assertFalse(new File(dir,"activation.pending").exists());
  }
}
