package app.organicmaps.transit;

import android.app.Application;
import android.content.Context;
import android.view.*;
import android.graphics.*;
import app.organicmaps.R;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.lang.reflect.*;
import java.io.*;
import static org.junit.Assert.*;

/** Real native host Canvas/projection; not phone GPU, street basemap or navigation proof. */
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=35,qualifiers="en",manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WalkingPathPreviewTest {
  private Class<?> preview(){Class<?> type=null;try{type=Class.forName("app.organicmaps.transit.WalkingPathPreview");}catch(ClassNotFoundException missing){}assertNotNull("mapped walking geometry needs its actual bounded north-up path view",type);return type;}
  private float[] project(double[] points)throws Exception {Method m=preview().getDeclaredMethod("project",double[].class,int.class,int.class,int.class);m.setAccessible(true);return (float[])m.invoke(null,points,320,240,32);}
  @Test public void projectionRetainsEveryPointNorthUpAndEastRightWithoutChangingInput()throws Exception {
    double[] points={52.52,13.4,52.521,13.4,52.521,13.402},before=points.clone();float[] p=project(points);assertEquals(points.length,p.length);assertArrayEquals(before,points,0);
    assertEquals(p[0],p[2],0.01f);assertTrue("north must be up",p[3]<p[1]);assertTrue("east must be right",p[4]>p[2]);assertEquals(p[3],p[5],0.01f);
    for(int i=0;i<p.length;i+=2){assertTrue(p[i]>=31.99f&&p[i]<=288.01f);assertTrue(p[i+1]>=31.99f&&p[i+1]<=208.01f);}
  }
  @Test public void malformedOrUnrepresentableGeometryIsRefusedRatherThanDrawnAsAPath()throws Exception {
    for(double[] points:new double[][]{{0,0},{0,0,1},{0,0,Double.NaN,1},{0,0,86,1},{0,0,0,0},{0,179,0,-179},new double[65538]}){
      try{project(points);fail("unusable geometry accepted");}catch(InvocationTargetException failure){assertTrue(failure.getCause() instanceof IllegalArgumentException);}
    }
  }
  @Test public void nativeCanvasDrawsVisibleRouteAndEndpointMarksWithinTheOwnedView()throws Exception {
    Context context=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.MwmTheme);
    double[] source={52.52,13.4,52.522,13.4,52.522,13.404,52.521,13.404};
    View view=(View)preview().getConstructor(Context.class,double[].class).newInstance(context,source);java.util.Arrays.fill(source,Double.NaN);
    view.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(240,View.MeasureSpec.EXACTLY));view.layout(0,0,320,240);
    Bitmap image=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888);try{
      android.content.res.TypedArray colors=context.obtainStyledAttributes(new int[]{android.R.attr.colorBackground});int background;try{background=colors.getColor(0,Color.WHITE);}finally{colors.recycle();}
      Canvas canvas=new Canvas(image);canvas.drawColor(background);view.draw(canvas);int painted=0;for(int y=0;y<240;y++)for(int x=0;x<320;x++)if(image.getPixel(x,y)!=background)painted++;
      int compass=0;for(int y=8;y<52;y++)for(int x=294;x<316;x++)if(image.getPixel(x,y)!=background)compass++;assertTrue("north arrow and label must remain visible",compass>20);
      assertTrue("Canvas did not paint the route",painted>300);assertTrue("preview must not cover entire view",painted<320*240/2);assertNotNull(view.getContentDescription());assertTrue(view.isFocusable());
      String output=System.getProperty("dmaps.preview.output");if(output!=null){File target=new File(output);assertTrue(!target.exists());try(OutputStream out=new FileOutputStream(target)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}}
    }finally{image.recycle();}
  }
}
