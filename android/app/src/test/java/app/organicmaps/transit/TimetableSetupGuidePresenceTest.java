package app.organicmaps.transit;
import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.lifecycle.*;
import app.organicmaps.R;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
/** Actual Activity-tree regression, compiled on sealed v60 without any new S1 API. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
public class TimetableSetupGuidePresenceTest {
 static TimetableViewModel model;
 public static class Surface extends TimetableActivity {
  @Override public ViewModelProvider.Factory getDefaultViewModelProviderFactory(){return new ViewModelProvider.Factory(){@NonNull @Override public <T extends ViewModel>T create(@NonNull Class<T> type){return type.cast(model);}};}
 }
 @Test public void noPackScreenExplainsExistingDownloadAndImportSteps(){
  model=mock(TimetableViewModel.class);TimetableViewModel.ScreenState state=new TimetableViewModel.ScreenState();state.status=TimetableViewModel.Status.READY;
  MutableLiveData<TimetableViewModel.ScreenState> live=new MutableLiveData<>(state);when(model.getState()).thenReturn(live);when(model.getQueryText()).thenReturn("");
  var controller=Robolectric.buildActivity(Surface.class);Surface a=controller.get();a.setTheme(R.style.MwmTheme);
  try{controller.create().start().resume().visible();state.revision++;live.setValue(state);Shadows.shadowOf(Looper.getMainLooper()).idle();
   View guide=a.findViewById(android.R.id.content).findViewWithTag("dmaps-setup-guide");assertNotNull("No-pack screen must show the manual setup guide",guide);assertEquals(View.VISIBLE,guide.getVisibility());
   String text=((TextView)guide).getText().toString();for(int id:new int[]{R.string.download_maps,R.string.transit_import_pack,R.string.transit_import_untrusted,R.string.transit_import_official})assertTrue("guide names the existing entry",text.contains(a.getString(id)));
  }finally{controller.pause().stop().destroy();}
 }
}
