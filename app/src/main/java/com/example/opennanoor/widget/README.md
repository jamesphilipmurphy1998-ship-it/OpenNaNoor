# Adding a new OpenNaNoor widget

Recipe for the ClockWidgetProvider/ClockWidgetConfigActivity pair - follow the
same shape for the next one. Five pieces, always the same five:

1. **`res/xml/<name>_widget_info.xml`** - the `AppWidgetProviderInfo`. Declares
   `minWidth`/`minHeight`, `initialLayout`, `resizeMode`, and (if the widget
   needs setup before it's usable) `android:configure="<package>.widget.<Name>ConfigActivity"`.

2. **`res/layout/<name>_widget.xml`** - the `RemoteViews` layout. **Not
   Compose** - RemoteViews only supports a small fixed set of Android View
   classes (LinearLayout, TextView, ImageView, Button, etc., no custom
   composables). If the content is variable-length (a list of cities, say),
   the standard trick is a fixed stack of N rows in the XML, each one's
   visibility set to `GONE` in code when there's no data for it - not a
   dynamic RecyclerView/ListView-style adapter unless you actually need one.

3. **`<Name>WidgetProvider.kt`** (`AppWidgetProvider`) - `onUpdate` builds
   `RemoteViews` and calls `AppWidgetManager.updateAppWidget`. If the widget
   needs to refresh more often than `updatePeriodMillis` allows (**30-minute
   floor**, hardcoded by Android - not tunable, and useless for anything
   showing live data like a clock), don't fight it: schedule your own
   `AlarmManager` tick from `onEnabled`/cancel it in `onDisabled`, and have
   it re-schedule itself every time it fires (`ClockWidgetProvider`'s
   `scheduleTick`). Use `setAndAllowWhileIdle`, not
   `setExactAndAllowWhileIdle` - exact alarms need `SCHEDULE_EXACT_ALARM`, a
   permission with its own user-facing grant step on Android 12+, which a
   widget that only needs roughly-per-minute accuracy doesn't justify.

4. **`<Name>WidgetConfigActivity.kt`** (only if step 1 declared `configure`) -
   a normal Compose `ComponentActivity`. Read `AppWidgetManager.EXTRA_APPWIDGET_ID`
   from the intent in `onCreate`, call `setResult(RESULT_CANCELED)`
   immediately (the contract: if this activity finishes without explicitly
   setting `RESULT_OK` + that same extra, the system discards the widget
   placement entirely), and set `RESULT_OK` only once the user actually
   saves.

5. **Storage** (only if per-instance config exists) - key whatever you're
   storing by `appWidgetId` (`ClockWidgetStore`), since more than one
   instance of the same widget can be placed and each needs its own
   settings. Clean up in `onDeleted`.

6. **Manifest**: a `<receiver>` for the provider (pointing at the info XML
   via `<meta-data android:name="android.appwidget.provider">`, with
   `android:permission` left off - that's for notification listeners, not
   widgets) and, if there's a config activity, a separate `<activity>` with
   an `APPWIDGET_CONFIGURE` intent-filter.

## The one trap that will bite you

**Never give any view in the RemoteViews layout a click target that spans
the widget's full bounds** (`setOnClickPendingIntent(android.R.id.background, ...)`
on the root, especially). `AppWidgetHostView` is a real native Android View
embedded via Compose's `AndroidView` interop - once ANY view inside it is
clickable, its native touch dispatch claims a touch landing anywhere in its
bounds before Compose's own gesture handling ever sees it. That breaks
whatever Compose-side interaction is supposed to happen over the widget:
this app's move-drag and resize-handle gestures both live in HomePage.kt as
Compose `pointerInput`s layered around/over the AndroidView, and a
full-bounds native click silently ate both (`ClockWidgetProvider`'s
now-removed click-to-reconfigure, and the `git log` around
"Fix clock widget blocking its own resize handle" for exactly this
happening).

The flip side of that same fact caused a second bug when the click was
removed: with NOTHING clickable left in the RemoteViews, the native View no
longer consumes the touch at all, so it now reaches Compose's OWN
independent long-press detectors - both the widget's move-drag detector
AND the page background's separate long-press-to-open-menu detector,
racing on the same touch stream, both firing within a couple of
milliseconds of each other (confirmed via `adb logcat` at the time - see
commit "Fix long-pressing a click-less widget opening the home menu instead
of dragging it"). LauncherScreen's page-background long-click now guards
against this generically (checks `drag.draggingWidgetId`/`resizingWidgetId`
before acting), so a new click-less widget shouldn't need its own fix for
this - but it's worth knowing why that guard exists before touching it.

**If you truly need a tap-to-do-something interaction on a widget**, put the
click target on a small, specific child view inside the layout, not the
root - small enough it can't plausibly overlap wherever HomePage's own
resize handle or drag detector sit.

## Landing position

New widgets are placed via `WidgetHostController.finishBinding`, at the top
of whichever page the user was on when they opened the widget picker
(`pendingPage`, captured in `startPick`). It finds the first free row via
`firstFreeRow` rather than hardcoding row 0 - two widgets both claiming row 0
previously drew on top of each other, with the covered one reading as "an
invisible widget I can't see". Keep using `firstFreeRow` for any future
widget-placement code rather than reintroducing a hardcoded row.
