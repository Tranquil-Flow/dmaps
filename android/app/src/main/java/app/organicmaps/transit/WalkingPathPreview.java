package app.organicmaps.transit;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.*;
import android.view.View;
import app.organicmaps.R;

/** Bounded north-up outline of the supplied mapped path, NOT a basemap or active navigation.
 * Owns only a defensive geometry copy and Canvas objects; no native core, location, I/O or history. */
public final class WalkingPathPreview extends View {
  private final double[] geometry;
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path=new Path();
  private final int ink,inverse,route;
  private float[] pixels;
  public WalkingPathPreview(Context context,double[] latLon){
    super(context);project(latLon,320,240,32);geometry=latLon.clone();
    TypedArray colors=context.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary,android.R.attr.textColorPrimaryInverse,android.R.attr.colorAccent});
    try{ink=colors.getColor(0,Color.DKGRAY);inverse=colors.getColor(1,Color.WHITE);route=colors.getColor(2,ink);}finally{colors.recycle();}
    setSaveEnabled(false);setContentDescription(context.getString(R.string.journey_walk_preview_description));setFocusable(true);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }
  private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
  /** Preserve every vertex/aspect/north orientation. Refuse antimeridian-spanning outlines rather
   * than silently draw a world-crossing shortcut or substitute a straight endpoint connection. */
  static float[] project(double[] latLon,int width,int height,int padding){
    if(latLon==null||latLon.length<4||latLon.length>65536||(latLon.length&1)!=0||padding<1||width<=2*padding||height<=2*padding)throw new IllegalArgumentException("bounded geometry and viewport required");
    double[] projected=new double[latLon.length];double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX;
    for(int i=0;i<latLon.length;i+=2){
      double lat=latLon[i],lon=latLon[i+1];if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85.05112878||Math.abs(lon)>180)throw new IllegalArgumentException("unrepresentable mapped coordinate");
      double x=Math.toRadians(lon),y=Math.log(Math.tan(Math.PI/4+Math.toRadians(lat)/2));projected[i]=x;projected[i+1]=y;
      minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
    }
    double dx=maxX-minX,dy=maxY-minY;if(dx>Math.PI||(dx==0&&dy==0))throw new IllegalArgumentException("unrepresentable mapped outline");
    double scale=Math.min((width-2.0*padding)/Math.max(dx,1e-12),(height-2.0*padding)/Math.max(dy,1e-12));
    double left=(width-dx*scale)/2,top=(height-dy*scale)/2;float[] result=new float[projected.length];
    for(int i=0;i<result.length;i+=2){result[i]=(float)(left+(projected[i]-minX)*scale);result[i+1]=(float)(top+(maxY-projected[i+1])*scale);}
    return result;
  }
  @Override protected void onMeasure(int widthSpec,int heightSpec){setMeasuredDimension(resolveSize(dp(320),widthSpec),resolveSize(dp(240),heightSpec));}
  @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){
    super.onSizeChanged(width,height,oldWidth,oldHeight);int padding=dp(32);pixels=width>2*padding&&height>2*padding?project(geometry,width,height,padding):null;
    path.reset();if(pixels!=null){path.moveTo(pixels[0],pixels[1]);for(int i=2;i<pixels.length;i+=2)path.lineTo(pixels[i],pixels[i+1]);}
  }
  @Override protected void onDraw(Canvas canvas){
    super.onDraw(canvas);if(pixels==null)return;
    paint.setStyle(Paint.Style.STROKE);paint.setColor(ink);paint.setAlpha(60);paint.setStrokeWidth(dp(1));canvas.drawRoundRect(dp(2),dp(2),getWidth()-dp(2),getHeight()-dp(2),dp(8),dp(8),paint);
    paint.setAlpha(255);paint.setColor(route);paint.setStrokeWidth(dp(3));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);canvas.drawPath(path,paint);
    marker(canvas,pixels[0],pixels[1],"A",true);marker(canvas,pixels[pixels.length-2],pixels[pixels.length-1],"B",false);
    // Compass is outside the path's reserved inset; it is not a GPS/heading indicator.
    float x=getWidth()-dp(16),y=dp(27);paint.setColor(ink);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));canvas.drawLine(x,y,x,y+dp(16),paint);canvas.drawLine(x,y,x-dp(4),y+dp(6),paint);canvas.drawLine(x,y,x+dp(4),y+dp(6),paint);
    paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(dp(12));canvas.drawText("N",x,dp(19),paint);
  }
  private void marker(Canvas canvas,float x,float y,String label,boolean start){
    paint.setStyle(Paint.Style.FILL);paint.setColor(ink);float radius=dp(10);
    if(start)canvas.drawCircle(x,y,radius,paint);else canvas.drawRoundRect(x-radius,y-radius,x+radius,y+radius,dp(2),dp(2),paint);
    paint.setColor(inverse);paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(dp(12));canvas.drawText(label,x,y-(paint.ascent()+paint.descent())/2,paint);
  }
}
