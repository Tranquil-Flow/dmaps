package app.organicmaps.routing;

import android.app.Activity;
import android.app.Application;
import android.view.LayoutInflater;
import android.view.View;
import app.organicmaps.MwmActivity;
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import java.lang.reflect.Method;
import java.util.Collections;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual inflated sheet and click handler; host Activity bridge mocked, not Activity startup proof. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, manifest = Config.NONE,
        shadows = IntermediateAppendTest.NativeRoute.class)
public class IntermediateAppendUiTest
{
  private Activity activity;
  private MwmActivity host;
  private ManageRouteBottomSheet sheet;
  private View root;
  private RouteMarkData[] nativePoints;
  private boolean accepted;
  private RouteMarkData[] captured;
  private int requests;
  @Before public void setup() throws Exception
  {
    activity = Robolectric.buildActivity(Activity.class).setup().get(); activity.setTheme(R.style.MwmTheme);
    nativePoints = new RouteMarkData[] {mark("start", RouteMarkType.Start), mark("finish", RouteMarkType.Finish)};
    IntermediateAppendTest.NativeRoute.points = () -> nativePoints;
    IntermediateAppendTest.NativeRoute.calls.clear();
    host = mock(MwmActivity.class, invocation -> {
      if (invocation.getMethod().getName().equals("onAppendIntermediateRoutePoint"))
      {
        captured = invocation.getArgument(0); ++requests; return accepted;
      }
      return RETURNS_DEFAULTS.answer(invocation);
    });
    sheet = spy(new ManageRouteBottomSheet());
    doReturn(activity).when(sheet).getContext(); doReturn(activity).when(sheet).requireContext();
    doReturn(activity.getResources()).when(sheet).getResources(); doReturn(host).when(sheet).requireActivity();
    doNothing().when(sheet).showMyLocationIcon(anyBoolean()); doNothing().when(sheet).dismiss();
    root = sheet.onCreateView(LayoutInflater.from(activity), null, null);
  }
  @After public void cleanup() { if (activity != null) activity.finish(); }
  private static RouteMarkData mark(String name, RouteMarkType type)
  { return new RouteMarkData(name, "", type, 0, true, false, false, 50, 13); }
  private View appendControl()
  {
    int id = activity.getResources().getIdentifier("btn__append_stop", "id",
        activity.getResources().getResourcePackageName(R.id.btn__plan));
    assertTrue("Missing explicit append control resource", id != 0);
    View control = root.findViewById(id); assertNotNull("Append control must exist without intermediates", control);
    assertEquals(View.VISIBLE, control.getVisibility()); assertTrue(control.isEnabled()); return control;
  }
  @Test public void emptyIntermediateSheetDispatchesExactDisplayedEndpointsAndDismissesOnlyOnAcceptance()
  {
    accepted = true; assertTrue(appendControl().performClick()); assertEquals(1, requests);
    assertArrayEquals(nativePoints, captured); verify(sheet).dismiss();
    assertTrue(IntermediateAppendTest.NativeRoute.calls.isEmpty());
  }
  @Test public void refusalKeepsDraftOpenWithoutNativeMutation()
  {
    accepted = false; appendControl().performClick(); assertEquals(1, requests); verify(sheet, never()).dismiss();
    assertTrue(IntermediateAppendTest.NativeRoute.calls.isEmpty());
  }
  @Test public void unsavedDraftOrderIsPassedToControllerNotReplacedByFreshNativeOrder()
  {
    Collections.swap(sheet.mManageRouteAdapter.getRoutePoints(), 0, 1);
    appendControl().performClick(); assertEquals(1, requests);
    assertSame(nativePoints[1], captured[0]); assertSame(nativePoints[0], captured[1]);
    verify(sheet, never()).dismiss();
  }
}
