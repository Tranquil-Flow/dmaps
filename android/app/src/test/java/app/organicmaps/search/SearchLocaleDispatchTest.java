package app.organicmaps.search;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import app.organicmaps.MwmActivity;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.location.LocationHelper;
import app.organicmaps.util.Utils;
import org.mockito.MockedStatic;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.downloader.MapManager;
import app.organicmaps.widget.PlaceholderView;
import app.organicmaps.sdk.search.SearchEngine;
import app.organicmaps.sdk.util.Language;
import app.organicmaps.widget.SearchToolbarController;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Real Fragment -> SDK Java dispatch, shadowing only the final native boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35,
        shadows = {SearchLocaleDispatchTest.NativeSearch.class, SearchLocaleDispatchTest.NativeFramework.class,
                   SearchLocaleDispatchTest.NativeMaps.class})
public class SearchLocaleDispatchTest
{
  private SearchFragment fragment;
  private SearchToolbarController toolbar;
  private Context context;
  private final AtomicReference<String> query = new AtomicReference<>("Alexanderplatz");

  @Implements(value = SearchEngine.class, callNativeMethodsByDefault = false)
  public static class NativeSearch
  {
    static final List<String> locales = new ArrayList<>();
    static boolean interactive;
    static boolean mapAndTable;
    static boolean category;
    static String queryText;
    @Implementation protected static boolean nativeRunSearch(byte[] query, boolean category, String locale,
        long timestamp, boolean hasPosition, double lat, double lon)
    { locales.add(locale); interactive = false; NativeSearch.category = category;
      queryText = new String(query, java.nio.charset.StandardCharsets.UTF_8); return true; }
    @Implementation protected static void nativeRunInteractiveSearch(byte[] query, boolean category, String locale,
        long timestamp, boolean mapAndTable, boolean hasPosition, double lat, double lon)
    { locales.add(locale); interactive = true; NativeSearch.mapAndTable = mapAndTable;
      NativeSearch.category = category; queryText = new String(query, java.nio.charset.StandardCharsets.UTF_8); }
    @Implementation protected static void nativeCancelAllSearches() {}
  }

  @Implements(value = Framework.class, callNativeMethodsByDefault = false)
  public static class NativeFramework
  {
    @Implementation protected static void nativeClearApiPoints() {}
  }

  @Implements(value = MapManager.class, callNativeMethodsByDefault = false)
  public static class NativeMaps
  {
    @Implementation protected static int nativeGetDownloadedCount() { return 1; }
    @Implementation protected static boolean nativeIsDownloading() { return false; }
  }

  @Before public void setup() throws Exception
  {
    NativeSearch.locales.clear();
    context = RuntimeEnvironment.getApplication();
    fragment = spy(new SearchFragment());
    doReturn(context).when(fragment).requireContext();
    doReturn(mock(SearchActivity.class)).when(fragment).requireActivity();
    doReturn(true).when(fragment).isAdded();
    doReturn(mock(FragmentManager.class)).when(fragment).getChildFragmentManager();

    // Invoke the actual inner text-watcher handler without constructing an Activity/native core.
    Class<?> type = Class.forName(SearchFragment.class.getName() + "$ToolbarController");
    toolbar = (SearchToolbarController) mock(type, CALLS_REAL_METHODS);
    Field outer = type.getDeclaredField("this$0"); outer.setAccessible(true); outer.set(toolbar, fragment);
    doAnswer(inv -> query.get()).when(toolbar).getQuery();
    doAnswer(inv -> !query.get().isEmpty()).when(toolbar).hasQuery();
    doReturn(false).when(toolbar).isCategory();
    doAnswer(inv -> {
      doReturn(inv.getArgument(1, Boolean.class)).when(toolbar).isCategory();
      change(inv.getArgument(0, String.class));
      return null;
    }).when(toolbar).setQuery(anyString(), anyBoolean());
    doNothing().when(toolbar).showProgress(anyBoolean());
    MaterialToolbar bar = mock(MaterialToolbar.class);
    when(bar.getLayoutParams()).thenReturn(new AppBarLayout.LayoutParams(-1, -2));
    doReturn(bar).when(toolbar).getToolbar();
    set("mToolbarController", toolbar);
    set("mResultsFrame", mock(View.class));
    set("mSearchAdapter", mock(SearchAdapter.class));
    set("mResultsPlaceholder", mock(PlaceholderView.class));
    set("mTabFrame", mock(View.class));
    set("mShowOnMapFab", mock(ExtendedFloatingActionButton.class));
    set("mOpenContactPicker", mock(FloatingActionButton.class));
  }

  private void set(String name, Object value) throws Exception
  { Field f = SearchFragment.class.getDeclaredField(name); f.setAccessible(true); f.set(fragment, value); }
  private Object get(String name) throws Exception
  { Field f = SearchFragment.class.getDeclaredField(name); f.setAccessible(true); return f.get(fragment); }
  private void invoke(String name) throws Exception
  { Method m = SearchFragment.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(fragment); }
  private void initial(String locale) throws Exception
  {
    Bundle args = new Bundle(); args.putString(SearchActivity.EXTRA_QUERY, query.get());
    if (locale != null) args.putString(SearchActivity.EXTRA_LOCALE, locale);
    fragment.setArguments(args); invoke("readArguments");
  }
  private void change(String text) throws Exception
  {
    query.set(text);
    Class<?> type = Class.forName(SearchFragment.class.getName() + "$ToolbarController");
    Method m = type.getDeclaredMethod("onTextChanged", String.class); m.setAccessible(true); m.invoke(toolbar, text);
  }

  private void resumeInitial() throws Exception
  {
    // Only location acquisition/history/navigation are sandboxed. The real onResume/text watcher/SDK run.
    try (MockedStatic<MwmApplication> apps = mockStatic(MwmApplication.class);
         MockedStatic<app.organicmaps.sdk.util.Config> config = mockStatic(app.organicmaps.sdk.util.Config.class);
         MockedStatic<Utils> utils = mockStatic(Utils.class))
    {
      MwmApplication app = mock(MwmApplication.class);
      when(app.getLocationHelper()).thenReturn(mock(LocationHelper.class));
      apps.when(() -> MwmApplication.from(context)).thenReturn(app);
      fragment.onResume();
    }
  }

  @Test public void firstPhoneListQueryUsesLinkLocale() throws Exception
  {
    initial("de_AT"); resumeInitial();
    assertEquals(List.of("de_AT"), NativeSearch.locales);
    assertFalse(NativeSearch.interactive);
  }

  @Test public void firstTabletListQueryUsesLinkLocale() throws Exception
  {
    doReturn(mock(MwmActivity.class)).when(fragment).requireActivity();
    initial("de_AT"); resumeInitial();
    assertEquals(List.of("de_AT"), NativeSearch.locales);
    assertTrue(NativeSearch.interactive);
  }

  @Test public void missingLocaleKeepsKeyboardFallback() throws Exception
  {
    initial(null); resumeInitial();
    assertEquals(List.of(Language.getKeyboardLocale(context)), NativeSearch.locales);
  }

  @Test public void manualEditDropsLinkOverride() throws Exception
  {
    initial("de_AT"); resumeInitial();
    NativeSearch.locales.clear(); change("Berlin");
    assertNull("manual edit must not retain link language", get("mInitialLocale"));
    assertEquals(List.of(Language.getKeyboardLocale(context)), NativeSearch.locales);
  }

  @Test public void clearingQueryDropsOverrideBeforeReturning() throws Exception
  {
    initial("de_AT"); resumeInitial();
    NativeSearch.locales.clear(); change("");
    assertNull("clear must revoke the initial language", get("mInitialLocale"));
    assertTrue(NativeSearch.locales.isEmpty());
  }

  @Test public void viewStateRestoreDefersBeforeApplyingInitialQuery() throws Exception
  {
    initial("de_AT");
    change("Alexanderplatz");
    assertEquals("de_AT", get("mInitialLocale"));
    assertTrue("restored EditText must not dispatch before initial state is applied", NativeSearch.locales.isEmpty());
    resumeInitial();
    assertEquals(List.of("de_AT"), NativeSearch.locales);
  }

  @Test public void mapOnlyInitialWaitsForQueryAndKeepsLocale() throws Exception
  {
    initial("de_AT"); set("mInitialSearchOnMap", true); resumeInitial();
    assertEquals(List.of("de_AT"), NativeSearch.locales);
    assertEquals("Alexanderplatz", NativeSearch.queryText);
    assertTrue(NativeSearch.interactive);
    assertFalse(NativeSearch.mapAndTable);
    assertFalse((Boolean) get("mInitialSearchOnMap"));
  }

  @Test public void blankLocaleKeepsKeyboardFallback() throws Exception
  {
    initial(""); resumeInitial();
    assertEquals(List.of(Language.getKeyboardLocale(context)), NativeSearch.locales);
  }

  @Test public void originalQueryTypedAgainDoesNotResurrectOverride() throws Exception
  {
    initial("de_AT"); resumeInitial();
    NativeSearch.locales.clear(); change("Berlin"); change("Alexanderplatz");
    String keyboard = Language.getKeyboardLocale(context);
    assertEquals(List.of(keyboard, keyboard), NativeSearch.locales);
  }

  @Test public void categorySelectionRevokesOverride() throws Exception
  {
    initial("de_AT"); resumeInitial(); NativeSearch.locales.clear();
    fragment.onSearchCategorySelected("cafe");
    assertEquals(List.of(Language.getKeyboardLocale(context)), NativeSearch.locales);
    assertTrue(NativeSearch.category);
    assertNull(get("mInitialLocale"));
  }

  @Test public void utf8QueryAndLocaleTagReachNativeBoundaryVerbatim() throws Exception
  {
    query.set("東京"); initial("ja-JP"); resumeInitial();
    assertEquals("東京", NativeSearch.queryText);
    assertEquals(List.of("ja-JP"), NativeSearch.locales);
  }

  private void restore(Bundle state) throws Exception
  {
    Method m = SearchFragment.class.getDeclaredMethod("restoreSearchState", Bundle.class);
    m.setAccessible(true); m.invoke(fragment, state);
  }

  @Test public void configurationRestoreKeepsUneditedLinkLocale() throws Exception
  {
    initial("de_AT"); resumeInitial(); Bundle state = new Bundle();
    fragment.onSaveInstanceState(state); invoke("readArguments"); restore(state);
    NativeSearch.locales.clear(); resumeInitial();
    assertEquals(List.of("de_AT"), NativeSearch.locales);
    assertFalse((Boolean) get("mInitialSearchOnMap"));
  }

  @Test public void viewRecreationWithoutBundleKeepsEditedQueryAndRevokedLocale() throws Exception
  {
    initial("de_AT"); resumeInitial(); change("Berlin");
    fragment.onDestroyView(); invoke("readArguments");
    assertEquals("Berlin", get("mInitialQuery"));
    assertNull("view recreation must not replay the link locale", get("mInitialLocale"));
    NativeSearch.locales.clear(); resumeInitial();
    assertEquals("Berlin", NativeSearch.queryText);
    assertEquals(List.of(Language.getKeyboardLocale(context)), NativeSearch.locales);
  }

  @Test public void configurationRestoreKeepsEditedQueryWithoutOldLocale() throws Exception
  {
    initial("de_AT"); resumeInitial(); change("Berlin"); Bundle state = new Bundle();
    fragment.onSaveInstanceState(state); invoke("readArguments"); restore(state);
    assertEquals("Berlin", get("mInitialQuery"));
    assertNull(get("mInitialLocale"));
    NativeSearch.locales.clear(); resumeInitial();
    assertEquals("Berlin", NativeSearch.queryText);
    assertEquals(List.of(Language.getKeyboardLocale(context)), NativeSearch.locales);
  }
}
