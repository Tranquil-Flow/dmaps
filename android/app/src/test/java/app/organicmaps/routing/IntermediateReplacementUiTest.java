package app.organicmaps.routing;

import android.app.Activity;
import android.app.Application;
import android.view.View;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real recycled adapter views; no renderer, search provider or native route engine. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35, manifest = Config.NONE)
public class IntermediateReplacementUiTest
{
  @Test public void middleTitleSelectsItsExactIntermediateIndexAndEndpointsAreNotReplacementControls()
  {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    activity.setTheme(R.style.MwmTheme);
    ArrayList<Integer> selected = new ArrayList<>();
    // Proxy avoids a compile-time reference to the not-yet-present listener method on baseline.
    ManageRouteAdapter.ManageRouteListener listener = (ManageRouteAdapter.ManageRouteListener)
        Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {ManageRouteAdapter.ManageRouteListener.class}, (proxy, method, args) -> {
              if (method.getName().equals("onRoutePointReplace")) selected.add((Integer) args[0]);
              return null;
            });
    RouteMarkData[] points = new RouteMarkData[5];
    for (int i = 0; i < points.length; ++i)
      points[i] = new RouteMarkData("place " + i, "", i == 0 ? RouteMarkType.Start
          : i == 4 ? RouteMarkType.Finish : RouteMarkType.Intermediate, i == 0 || i == 4 ? 0 : i - 1,
          true, false, false, 50 + i, 13);
    ManageRouteAdapter adapter = new ManageRouteAdapter(activity, points, listener);
    RecyclerView recycler = new RecyclerView(activity);
    recycler.setLayoutManager(new LinearLayoutManager(activity));
    recycler.setAdapter(adapter);
    activity.setContentView(recycler);
    recycler.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
    recycler.layout(0, 0, 1080, 1920);
    RecyclerView.ViewHolder middle = recycler.findViewHolderForAdapterPosition(2);
    assertNotNull(middle);
    middle.itemView.findViewById(R.id.title).performClick();
    assertEquals("Missing actual intermediate replacement UI dispatch", java.util.Arrays.asList(1), selected);
    assertFalse(recycler.findViewHolderForAdapterPosition(0).itemView.findViewById(R.id.title).isClickable());
    assertFalse(recycler.findViewHolderForAdapterPosition(4).itemView.findViewById(R.id.title).isClickable());
    recycler.setAdapter(null);
    middle.itemView.findViewById(R.id.title).performClick();
    assertEquals("Detached/recycled holder must not dispatch a stale index", java.util.Arrays.asList(1), selected);
    activity.finish();
  }
}
