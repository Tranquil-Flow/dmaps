package app.organicmaps.sdk.transit;

import static org.junit.Assert.*;

import org.junit.Test;

/** A service date is only queried when it can hold a departure in the window. Pure; no Android or device. */
public class BoardServiceDayTest {
  @Test public void onlyDatesThatCannotDepartAreSkipped() {
    assertTrue(TimetableRepository.serviceDayCanDepart(0, 100, 100));
    assertTrue("a window reaching exactly the last offset", TimetableRepository.serviceDayCanDepart(100, 200, 100));
    assertFalse("the window starts past the last departure offset", TimetableRepository.serviceDayCanDepart(101, 200, 100));
    assertFalse("the window ends before the service day starts", TimetableRepository.serviceDayCanDepart(0, -1, 100));
    assertFalse("an empty window", TimetableRepository.serviceDayCanDepart(50, 49, 100));
  }
}
