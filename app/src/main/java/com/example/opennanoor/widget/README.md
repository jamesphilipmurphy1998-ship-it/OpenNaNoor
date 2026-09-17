# Adding a new OpenNaNoor widget

Recipe for the ClockWidgetProvider/ClockWidgetConfigActivity pair - follow the
same shape for the next one. Five pieces, always the same five:

1. **`res/xml/<name>_widget_info.xml`** - the `AppWidgetProviderInfo`. Declares
   `minWidth`/`minHeight`, `initialLayout`, `resizeMode`, and (if the widget
   needs setup before it's usable) `android:configure="<package>.widget.<Name>ConfigActivity"`.
   Also set `android:label="@string/<name>_widget_label"` to something
   specific ("Custom Clock 1", not "OpenNaNoor") - with no label, the OS
   widget picker falls back to showing the whole APP's name, which is
   useless once there's more than one custom widget to tell apart in that
   list. Label the `<receiver>` in the manifest with the same string too,
   for pickers that read it from there instead.

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

## Long-press-to-move vs. long-press-to-open-menu

With no click target left on the widget (see the trap above), a long-press
on it and the page background's own long-press-to-open-the-edit-menu
detector both watch the same raw touch stream and race independently -
whichever fires first wins, and with no consumption happening on the down
event, that's a genuine 50/50 coin flip per gesture, not a fixable-by-just-
checking-state-order thing. The fix lives on the widget's own `Box` in
HomePage.kt: an extra `.pointerInput` ahead of the drag-detector one that
does nothing but `awaitFirstDown().consume()`. Compose's long-press
detectors bail out the instant they see their down event consumed, so the
background's menu detector never gets to start its own timer - the widget's
own `detectDragGesturesAfterLongPress` still works fine on an
already-consumed down (it awaits with `requireUnconsumed = false`).

## Widget-vs-widget drag collisions

`previewWidgetRow` refuses (returns null) any drop that collides with
another widget's row band - left alone, that just snaps the drag back,
which reads as "it won't let me move it there". The fix is to swap
instead: on a refused drop, `overlappingWidget` runs the same
row-from-position math to find which single widget is in the way, and
`WidgetHostController.swapWidgets` exchanges the two. **Don't** just swap
their `topRow` values directly - that only avoids the two overlapping each
other when they're the same height (a span-1 widget swapped naively with
an adjacent span-2 one still ends up covered by the span-2 one's new,
taller footprint). Instead stack them back-to-back from whichever had the
smaller `topRow`: the one that was on the bottom moves to that anchor row,
the one that was on top moves to `anchor + (new bottom widget's rowSpan)`.
That's overlap-free for any pair of heights. Follow this same pattern
(refuse → check `overlappingWidget` → swap by re-stacking, not by
exchanging raw rows) for any future widget-placement interaction rather
than just refusing the drop.

## Per-instance appearance (text color, background image) - only for OUR OWN widgets

Once a widget is placed, its own spanner/options menu (`WidgetOptionsMenu`
in HomeMenu.kt) can offer "Text color" and "Background image" - but ONLY
for a widget whose RemoteViews we ourselves wrote (checked in
LauncherScreen.kt via `view.appWidgetInfo.provider.packageName ==
context.packageName`, gating `showAppearanceOptions`). A third-party
widget's own layout isn't ours to recolor - don't show these for one.

- **Text color**: `WidgetTextColorDialog` is a small fixed swatch palette
  (no color-picker dependency, no slider UI) - `onPick` writes straight to
  the widget's own store (`ClockWidgetStore.setTextColorFor`) and calls
  `<Name>WidgetProvider.refresh(context, appWidgetId)` so it's visible
  immediately rather than waiting for the next tick. The provider's own
  `updateWidget` reads it back (`textColorFor`) and calls
  `views.setTextColor(viewId, color)` per text view - has to be done
  per-view, there's no single "recolor everything" RemoteViews call.

- **Background image**: `WidgetBackgroundImageDialog` offers "No
  background" (clears) before "Choose photo" (launches
  `ActivityResultContracts.GetContent("image/*")`, wired in
  LauncherActivity.kt since the picker is an Activity-level launcher, not
  something a Composable deep in LauncherScreen can own). On pick, decode
  the URI's bytes immediately and **copy them into this app's own
  filesDir** (`ClockWidgetStore.backgroundImageFile`/`setBackgroundImage`)
  rather than keeping the picked `content://` Uri long-term - this app IS
  the widget host (RemoteViews for our own custom widgets inflate in our
  own process), so there's no real cross-app permission boundary to route
  around, and a private copy survives the source Uri's own grant ever
  being revoked. **Downscale before saving**
  (`LauncherActivity.downscale`, capped around 480px on the long side) -
  the provider redecodes whatever's on disk on every tick (see the
  30-minute-floor workaround above), so a multi-thousand-pixel photo would
  waste real CPU/memory every minute for no visible gain at widget size.
  The layout itself needs a background `ImageView` sibling BEHIND the
  content (see clock_widget.xml's own root `FrameLayout`), `GONE` until an
  image exists, with a scrim view between it and the text
  (`views.setInt(scrimId, "setBackgroundColor", ...)`) that switches to a
  much lighter overlay when a photo's present so the photo stays visible
  while text stays readable.

## Rounded corners - trivial for OUR OWN widget, not for a hosted one

Trying to round an `AppWidgetHostView`'s corners from HomePage.kt's own
Compose side (wrapping `AndroidView(widget.view)` in a `.clip()`, a
`graphicsLayer`, a native `ViewOutlineProvider` set from outside) is a
dead end - documented failures, don't retry them (see HomePage's own
"Rounding this view's corners is unresolved" comment, which is about a
widget in general, hosted as an opaque native View from Compose's
outside).

But for a widget whose RemoteViews layout THIS APP wrote, that whole
problem doesn't apply - round it at the source, inside the layout XML
itself, which is real native View clipping happening INSIDE the widget's
own inflated hierarchy, nothing to do with the Compose/AndroidView
boundary at all:

```xml
<FrameLayout
    android:background="@drawable/<name>_widget_mask"
    android:outlineProvider="background"
    android:clipToOutline="true">
```

`<name>_widget_mask` is a plain rounded-rect `<shape>` drawable (radius to
taste, e.g. 16dp to roughly match the icon grid's own `RoundedCornerShape`
in Tiles.kt) - its own fill color is irrelevant since it's fully covered by
whatever's drawn on top; it only exists to give `outlineProvider="background"`
a shape to derive an outline from. This clips EVERY child (background
image, scrim, text) to the rounded rect in one place - no per-child
rounding needed, and no need to round the scrim/background-image drawables
separately.

## Per-widget width resize (column span) - DATA MODEL ONLY, no UI yet

`WidgetPlacement`/`PlacedWidget` both carry a `columnSpan` (persisted as a
5th `:`-separated field in Settings' `widgetPlacements` string, optional on
read - defaults to the page's own `columns`, i.e. full width, for a
placement saved before this existed), and `WidgetHostController.resizeWidget`
takes both `rowSpan` and `columnSpan` together. **But there is still only
ONE resize handle** (bottom-center, vertical-only, unchanged from before) -
nothing yet lets a user actually change `columnSpan` from its default. A
Spotify-style corner handle (drag diagonally, changing both spans at once)
was asked for and is still TODO - don't assume it exists just because the
plumbing for it does.

Scope, when that handle IS built, should stay narrower than a real 2D grid:
**a widget is always left-anchored at column 0** - there's no horizontal
drag-to-reposition, only growing/shrinking width and height together from a
corner - and **a widget's row band should still reserve the FULL row
width** for icon-reflow purposes regardless of its own `columnSpan` - a
narrower widget just leaves blank space in its row rather than letting
icons flow in beside it. That's a deliberate simplification: the icon-band
system (`toDisplayY`/`toGridY`/`widgetBands`, see the drag-animation-traps
memory) assumes whole-row reservation throughout; making it truly 2D
(icons packing into the freed columns beside a narrow widget) is a much
bigger rearchitecture than "add a resize handle."

## Resizing all the way down to 1 row

The resize handle's floor (`minRowSpan` in HomePage.kt) is
`ceil(widget.minHeightDp / cellHeight.value)`, and `minHeightDp` comes from
the widget's own declared `AppWidgetProviderInfo.minResizeHeight` (falling
back to `minHeight`) - for OUR OWN widget, that's just whatever
`android:minHeight` says in `res/xml/<name>_widget_info.xml`. The clock
widget's was originally `60dp`, which computed to a 2-row floor on this
device and left visible empty space once a user only wanted 1 city showing
- lowered to `40dp` to let it floor at 1 row instead. When adding a new
widget, don't just copy a `minHeight` value from habit - set it to
whatever the layout can ACTUALLY still look acceptable at, since that
number is the literal floor a user can shrink it to.

## Widgets can transiently render "behind" icons during a swap - fixed with zIndex

Icons are declared AFTER widgets in HomePage.kt's same parent, so they draw
on top by default - normally invisible, since bands keep icons out of a
widget's own rows entirely. But a widget-vs-widget SWAP animates both
widgets to their new rows independently (each has its own `animatedY`
Animatable, no coordination between the two), so a transient one-frame
misalignment mid-animation can have a widget's edge overlap an icon's
rendered position - and because icons render later, that transient overlap
read as "the widget is behind the icons" (a real user report, right after
a swap). Fixed with `Modifier.zIndex(1f)` on each widget's own Box - cheap,
safe, and doesn't touch icons' own declaration order (which needs to stay
after widgets, so icons still render in the gaps around them - see
`widgetBands`/`toDisplayY`/`toGridY`).

## Third-party widget content overflowing into icons below - unresolved, don't retry clipToBounds

Shrinking a THIRD-PARTY widget (not one of ours) near its own declared
minimum can leave its real rendered content taller than the row span
allocated to it - that minimum is only what the widget's own author
promises looks acceptable, not something this app can verify or enforce.
Left alone, the overflow visually bleeds into whatever's below (icons on
the next row - read by a user as "the widget is behind the icons").

**Tried and reverted**: adding `.clipToBounds()` to the widget's own outer
Box in HomePage.kt. It DID clip the overflow, but it also clips every
child of that Box - including the resize handle and `RemoveBadge`, both
DELIBERATELY rendered partly outside that Box's own bounds (the handle
sits `offset(y = 10.dp)` below the widget's bottom edge; the badge hangs
mostly outside the AndroidView's own rectangle on purpose, see the
click-target trap above). Clipping there cut off half of both, and since
Compose hit-testing follows the same bounds as drawing for a clipped
region, they also became partly untouchable ("icons cut off half the
resize button", "stuck behind icons, can't touch it"). If this needs
solving properly, the clip has to live somewhere that doesn't also cover
the handle/badge - e.g. a clip specifically on the `AndroidView` itself
rather than the shared parent Box - not attempted yet.
