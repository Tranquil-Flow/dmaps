package app.organicmaps.routing;

import android.view.View;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.routing.RouteAltitudeData;
import app.organicmaps.sdk.util.StringUtils;
import app.organicmaps.util.ElevationChartMath;
import app.organicmaps.util.ThemeUtils;
import app.organicmaps.widget.placepage.AxisValueFormatter;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import com.google.android.material.textview.MaterialTextView;
import java.util.ArrayList;

/** Route-only, synchronous presentation. Uses the retained DEFAULT renderer, never a track/map cursor. */
final class RouteElevationChartController implements OnChartValueSelectedListener
{
  private final View mFrame;
  private final LineChart mChart;
  private final MaterialTextView mSelection;
  private final MaterialTextView mMin;
  private final MaterialTextView mMax;

  RouteElevationChartController(View frame)
  {
    mFrame = frame;
    mChart = frame.findViewById(R.id.route_altitude_line_chart);
    mSelection = frame.findViewById(R.id.route_altitude_selection);
    mMin = frame.findViewById(R.id.route_altitude_min);
    mMax = frame.findViewById(R.id.route_altitude_max);
    mChart.getDescription().setEnabled(false);
    mChart.getLegend().setEnabled(false);
    mChart.setTouchEnabled(true);
    mChart.setScaleXEnabled(true);
    mChart.setScaleYEnabled(false);
    mChart.setDrawGridBackground(false);
    mChart.setOnChartValueSelectedListener(this);
    mChart.getAxisRight().setEnabled(false);
    mChart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
    mChart.getXAxis().setLabelCount(3, false);
    mChart.getXAxis().setValueFormatter(new AxisValueFormatter(mChart));
    mChart.getAxisLeft().setValueFormatter((value, axis) -> Framework.nativeFormatAltitude(value));
    int textColor = ThemeUtils.getColor(frame.getContext(), R.attr.elevationProfileAxisLabelColor);
    mChart.getXAxis().setTextColor(textColor);
    mChart.getAxisLeft().setTextColor(textColor);
    clear();
  }

  boolean show(@Nullable RouteAltitudeData profile, boolean metric)
  {
    clear();
    if (profile == null)
      return false;
    ArrayList<Entry> entries = new ArrayList<>(profile.size());
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    float previous = -1;
    for (int i = 0; i < profile.size(); ++i)
    {
      float x = (float) profile.distanceAt(i);
      float y = (float) profile.altitudeAt(i);
      // Strict float order avoids ambiguous default-renderer/highlighter duplicate vertices.
      // A repeated or float-collapsed segment retains the legacy bitmap; never invent samples.
      if (!Float.isFinite(x) || !Float.isFinite(y) || x <= previous)
        return false;
      previous = x;
      entries.add(new Entry(x, y));
      min = Math.min(min, profile.altitudeAt(i));
      max = Math.max(max, profile.altitudeAt(i));
    }
    final ElevationChartMath.AxisBounds bounds;
    try
    {
      bounds = ElevationChartMath.axisBounds(min, max, !metric);
    }
    catch (IllegalArgumentException invalid)
    {
      return false;
    }
    mChart.getAxisLeft().setAxisMinimum(bounds.lowerMetres);
    mChart.getAxisLeft().setAxisMaximum(bounds.upperMetres);
    mChart.getAxisLeft().setLabelCount(bounds.stepCount + 1, true);
    mChart.getXAxis().setAxisMinimum(0);
    mChart.getXAxis().setAxisMaximum(previous);
    LineDataSet set = new LineDataSet(entries, "");
    set.setMode(LineDataSet.Mode.LINEAR);
    set.setDrawCircles(false);
    set.setDrawHorizontalHighlightIndicator(false);
    set.setColor(ThemeUtils.getColor(mFrame.getContext(), R.attr.elevationProfileColor));
    LineData data = new LineData(set);
    data.setDrawValues(false);
    mChart.setData(data);
    mChart.notifyDataSetChanged();
    mChart.invalidate();
    // Actual raw extrema, not padded axis limits; preserve the native altitude unit formatter.
    mMin.setText(mFrame.getContext().getString(R.string.elevation_profile_min_elevation)
                 + ": " + Framework.nativeFormatAltitude(min));
    mMax.setText(mFrame.getContext().getString(R.string.elevation_profile_max_elevation)
                 + ": " + Framework.nativeFormatAltitude(max));
    mMin.setVisibility(View.VISIBLE);
    mMax.setVisibility(View.VISIBLE);
    mFrame.setVisibility(View.VISIBLE);
    return true;
  }

  void clear()
  {
    mChart.highlightValue(null, false);
    mChart.clear();
    mChart.fitScreen();
    mSelection.setText("");
    mSelection.setVisibility(View.GONE);
    mMin.setText("");
    mMax.setText("");
    mMin.setVisibility(View.GONE);
    mMax.setVisibility(View.GONE);
    mFrame.setVisibility(View.GONE);
  }

  @Override
  public void onValueSelected(Entry entry, Highlight highlight)
  {
    // Snapped chart-local value only. No BookmarkManager, GPS or native map-selection call.
    mSelection.setText(StringUtils.nativeFormatDistance(entry.getX()).toString(mFrame.getContext())
                       + " • " + Framework.nativeFormatAltitude(entry.getY()));
    mSelection.setVisibility(View.VISIBLE);
  }

  @Override
  public void onNothingSelected()
  {
    mSelection.setText("");
    mSelection.setVisibility(View.GONE);
  }
}
