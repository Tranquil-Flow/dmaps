package app.organicmaps.transit;

import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import app.organicmaps.R;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Real Activity -> actual ViewModel/worker -> SAF fixture -> importer -> native host SQLite -> UI.
 * Only the document-provider stream is synthetic. Not physical Android/native Ed25519 proof. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class TimetableUserJourneyTest {
  private ActivityController<TimetableActivity> controller;
  private TimetableActivity activity;
  private TimetableViewModel model;
  private Object field(Class<?> type,Object owner,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
  private Button button(String name)throws Exception{return (Button)field(TimetableActivity.class,activity,name);}
  private String text(String name)throws Exception{return ((TextView)field(TimetableActivity.class,activity,name)).getText().toString();}
  private void await(BooleanSupplier ready)throws Exception {
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
    while(System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();if(ready.getAsBoolean())return;Thread.sleep(10);}
    fail("host journey readiness deadline exceeded; not a performance qualification");
  }
  private void chooseAndReturn(int choice,Uri uri)throws Exception {
    button("mImportBtn").performClick();AlertDialog d=(AlertDialog)ShadowDialog.getLatestDialog();assertNotNull(d);d.getListView().performItemClick(null,choice,choice);
    ShadowActivity.IntentForResult request=Shadows.shadowOf(activity).getNextStartedActivityForResult();assertNotNull(request);activity.onActivityResult(request.requestCode,Activity.RESULT_OK,new Intent().setData(uri));
  }
  @After public void cleanup()throws Exception {
    if(controller!=null)controller.pause().stop().destroy();
    if(model!=null){ExecutorService worker=(ExecutorService)field(TimetableViewModel.class,model,"mExecutor");assertTrue("owned UI worker did not retire",worker.awaitTermination(10,TimeUnit.SECONDS));}
  }
  @Test public void officialRefusalThenExplicitUserImportSearchAndRemovalUseRealConsumers()throws Exception {
    controller=Robolectric.buildActivity(TimetableActivity.class);activity=controller.get();activity.setTheme(R.style.MwmTheme);controller.create().start().resume().visible();model=(TimetableViewModel)field(TimetableActivity.class,activity,"mModel");
    await(()->model.getState().getValue().status==TimetableViewModel.Status.NO_PACK);
    File sentinel=new File(RuntimeEnvironment.getApplication().getFilesDir(),"maps-favorites-tracks-journey-sentinel");byte[] saved=new byte[]{7,4,9};Files.write(sentinel.toPath(),saved);
    byte[] archive=Files.readAllBytes(new File("src/androidTest/assets/transit/synthetic.ctpack").toPath());Uri uri=Uri.parse("content://fixture.invalid/native-sqlite-user-journey");
    Shadows.shadowOf(RuntimeEnvironment.getApplication().getContentResolver()).registerInputStreamSupplier(uri,()->new ByteArrayInputStream(archive));
    chooseAndReturn(0,uri);await(()->model.getState().getValue().importFailedFlag());assertFalse(model.getState().getValue().packPresent);assertFalse(new File(RuntimeEnvironment.getApplication().getFilesDir(),"transit_pack/timetable.sqlite").exists());
    chooseAndReturn(1,uri);await(()->model.getState().getValue().status==TimetableViewModel.Status.READY);assertTrue(model.getState().getValue().packPresent);assertFalse(model.getState().getValue().packInfo.authenticated);assertEquals(activity.getString(R.string.transit_unsigned_pack_disclosure),text("mTrustLine"));
    ((EditText)field(TimetableActivity.class,activity,"mQuery")).setText("Hauptbahnhof");button("mSearchBtn").performClick();await(()->model.getState().getValue().status==TimetableViewModel.Status.READY&&!model.getState().getValue().candidates.isEmpty());
    Object repository=field(TimetableViewModel.class,model,"mRepository");assertFalse("real repository required",org.mockito.Mockito.mockingDetails(repository).isMock());
    Object trust=field(repository.getClass(),repository,"mTrust");File directory=(File)field(trust.getClass(),trust,"directory");
    assertEquals("consumer and preservation oracle must address the same test directory",new File(RuntimeEnvironment.getApplication().getFilesDir(),"transit_pack").getCanonicalFile(),directory.getCanonicalFile());
    assertTrue("history exists before remove",new File(directory,"trust-state.json").isFile());
    button("mRemoveBtn").performClick();await(()->model.getState().getValue().status==TimetableViewModel.Status.NO_PACK);assertArrayEquals(saved,Files.readAllBytes(sentinel.toPath()));assertTrue("removed pack retained trust state",new File(RuntimeEnvironment.getApplication().getFilesDir(),"transit_pack/trust-state.json").isFile());assertTrue("removed pack retained initialized marker",new File(RuntimeEnvironment.getApplication().getFilesDir(),"transit_pack/trust.initialized").isFile());
  }
}
