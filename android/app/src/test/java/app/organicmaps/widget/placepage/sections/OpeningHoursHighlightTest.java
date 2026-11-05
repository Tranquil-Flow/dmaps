package app.organicmaps.widget.placepage.sections;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.organicmaps.R;
import app.organicmaps.sdk.editor.data.HoursMinutes;
import app.organicmaps.sdk.editor.data.Timespan;
import app.organicmaps.sdk.editor.data.Timetable;
import java.lang.reflect.Field;
import java.util.Calendar;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Actual Android row binding, not a mock of its styling. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class OpeningHoursHighlightTest
{
  private Context context;
  private PlaceOpeningHoursAdapter adapter;
  private int today;

  @Before public void setup()
  {
    context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.MwmTheme);
    adapter = new PlaceOpeningHoursAdapter();
    today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
  }

  private Timetable timetable(int day)
  {
    return new Timetable(new Timespan(new HoursMinutes(9, 0, true), new HoursMinutes(18, 0, true)),
                         new Timespan[0], false, new int[] {day});
  }

  @SuppressWarnings("unchecked")
  private List<PlaceOpeningHoursAdapter.WeekScheduleData> rows() throws Exception
  {
    Field f = PlaceOpeningHoursAdapter.class.getDeclaredField("mWeekSchedule");
    f.setAccessible(true);
    return (List<PlaceOpeningHoursAdapter.WeekScheduleData>) f.get(adapter);
  }

  private int rowFor(int day) throws Exception
  {
    List<PlaceOpeningHoursAdapter.WeekScheduleData> rows = rows();
    for (int i = 0; i < rows.size(); i++)
    {
      var row = rows.get(i);
      boolean contains = row.startWeekDay <= row.endWeekDay
          ? row.startWeekDay <= day && day <= row.endWeekDay
          : day >= row.startWeekDay || day <= row.endWeekDay;
      if (contains) return i;
    }
    throw new AssertionError("day missing from schedule");
  }

  private PlaceOpeningHoursAdapter.ViewHolder holder()
  {
    return adapter.onCreateViewHolder(new FrameLayout(context), 0);
  }

  private TextView day(PlaceOpeningHoursAdapter.ViewHolder h)
  {
    return h.itemView.findViewById(R.id.tv__opening_hours_weekdays);
  }

  private void assertBothColumnsBold(PlaceOpeningHoursAdapter.ViewHolder h, boolean bold)
  {
    // Legacy Robolectric shadows getStyle(), but isBold() reads an uninitialized native mStyle.
    assertEquals("weekday emphasis", bold, (day(h).getTypeface().getStyle() & android.graphics.Typeface.BOLD) != 0);
    LinearLayout shifts = h.itemView.findViewById(R.id.ll__opening_hours_shifts);
    assertTrue("shift content retained", shifts.getChildCount() > 0);
    for (int i = 0; i < shifts.getChildCount(); i++)
      assertEquals("hours emphasis", bold,
          (((TextView) shifts.getChildAt(i)).getTypeface().getStyle() & android.graphics.Typeface.BOLD) != 0);
  }

  @Test public void currentWorkingRowEmphasizesDayAndHours() throws Exception
  {
    adapter.setTimetables(new Timetable[] {timetable(today)}, Calendar.MONDAY);
    var h = holder();
    adapter.onBindViewHolder(h, rowFor(today));
    assertBothColumnsBold(h, true);
  }

  @Test public void currentClosedRowEmphasizesAndRecyclingResets() throws Exception
  {
    int anotherDay = today == Calendar.SATURDAY ? Calendar.SUNDAY : today + 1;
    adapter.setTimetables(new Timetable[] {timetable(anotherDay)}, Calendar.MONDAY);
    var h = holder();
    adapter.onBindViewHolder(h, rowFor(today));
    assertBothColumnsBold(h, true);
    adapter.onBindViewHolder(h, rowFor(anotherDay));
    assertBothColumnsBold(h, false);
    assertNull("no stale today description", day(h).getContentDescription());
  }

  @Test public void currentRowAccessibleNameKeepsVisibleDay() throws Exception
  {
    adapter.setTimetables(new Timetable[] {timetable(today)}, Calendar.SUNDAY);
    var h = holder();
    adapter.onBindViewHolder(h, rowFor(today));
    assertNotNull("today must not be conveyed only by font weight", day(h).getContentDescription());
    String description = day(h).getContentDescription().toString();
    assertTrue(description.contains(context.getString(app.organicmaps.sdk.R.string.today)));
    assertTrue(description.contains(day(h).getText().toString()));
  }

  @Test public void allLocaleWeekStartsSelectExactlyOneCurrentRow() throws Exception
  {
    Timetable weekdays = new Timetable(
        new Timespan(new HoursMinutes(9, 0, true), new HoursMinutes(18, 0, true)),
        new Timespan[0], false, new int[] {2, 3, 4, 5, 6});
    for (int first = Calendar.SUNDAY; first <= Calendar.SATURDAY; first++)
      for (int current = Calendar.SUNDAY; current <= Calendar.SATURDAY; current++)
      {
        adapter.setTimetables(new Timetable[] {weekdays}, first, current);
        int count = 0;
        for (var row : rows()) if (row.isToday) count++;
        assertEquals(1, count);
        assertTrue(rows().get(rowFor(current)).isToday);
        assertEquals(first, rows().get(0).startWeekDay);
      }
  }

  @Test public void currentDayCanRefreshWithoutChangingTheDeviceClock() throws Exception
  {
    Timetable[] tt = {timetable(Calendar.MONDAY)};
    var h = holder();
    adapter.setTimetables(tt, Calendar.MONDAY, Calendar.SUNDAY);
    adapter.onBindViewHolder(h, rowFor(Calendar.SUNDAY));
    assertBothColumnsBold(h, true);
    adapter.setTimetables(tt, Calendar.MONDAY, Calendar.MONDAY);
    adapter.onBindViewHolder(h, rowFor(Calendar.SUNDAY));
    assertBothColumnsBold(h, false);
    adapter.onBindViewHolder(h, rowFor(Calendar.MONDAY));
    assertBothColumnsBold(h, true);
  }

  @Test public void preservesLocaleWeekOrder() throws Exception
  {
    for (int first = Calendar.SUNDAY; first <= Calendar.SATURDAY; first++)
    {
      adapter.setTimetables(new Timetable[] {timetable(today)}, first);
      assertEquals(first, rows().get(0).startWeekDay);
    }
  }
}
