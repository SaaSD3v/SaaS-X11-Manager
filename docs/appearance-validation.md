# Manager appearance validation

The appearance workflow renders the X11APP UI on Pixel 2 emulators at API 26,
31, 32 and 34 (Android 8, 12, 12L and 14). It operates the production MainActivity
and saves full Android screenshots, including system bars. The component gallery
uses production container cards and log dialogs with sample data.

## Issues found

- Android 12+ retained the splash theme because MainActivity never called
  `installSplashScreen()`. The native Android title bar appeared above the Compose
  header, covered content and retained a light background in dark mode. The
  Activity now installs the splash API before `super.onCreate`, and the Manager
  window explicitly disables the native title bar.
- Several static light accents were pastel colors underneath white button text.
  Catppuccin's original primary button contrast was about 2.24:1. Accents now retain
  their hue while meeting text contrast requirements. Tests include the actual
  translucent backgrounds behind selected tabs, container controls and log text.
- Translucent secondary text made navigation and container status hard to read.
  Enabled text now uses the full foreground color. Disabled controls retain their
  distinct disabled appearance.
- Static dark surfaces had strong accent tinting. They now use neutral dark
  surfaces with a small palette tint. AMOLED remains a separate option and uses
  pure black for all surface roles, with both static and dynamic colors.
- Android 8 displayed a checked Dynamic Color switch even though that feature
  was unavailable. The switch now displays disabled/off on that platform, while
  preserving the saved preference. Selection and switch semantics expose the
  same state that is drawn on screen.

- X11 configuration still used an elevated, tinted dialog with a different header,
  cards and switches. X11, general and TigerVNC settings now use the same full-size
  editor, safe system/keyboard insets and explicit window icon colors. Full-size
  editors do not dim the Activity behind the system bars. Their cards
  and toggle rows are shared with the Manager configuration and compatibility page.
  Static menus now use the chosen palette tint, and AMOLED elevation remains black.

## Regression checks

- 48 combinations per platform: six palettes, light/dark, dynamic on/off and
  AMOLED on/off; Android 8 checks the static fallback.
- At least 4.5:1 for the text/color pairs in `theme-matrix.jsonl`, measured with
  Android ColorUtils after transparency is composited over the actual background.
- Home, Display, Requirements and Config screenshots across appearance modes.
- Open the real X11 editor through Display in each appearance mode, inspect all
  sections, operate the resolution menu and verify saved switch changes. Assert
  its rendered background matches the Manager rather than a tonal overlay.
- General settings, TigerVNC sections and compatibility settings across five theme
  cases, including enlarged text. Verify Cancel preserves VNC values and Save
  still persists the edited port using isolated test preferences.
- System theme changes, 130% system font size, persistence across Activity
  recreation, reset defaults and selection/switch states.
- No native action bar; status/navigation icon brightness follows the selected
  app theme independently of the system theme.
- Real wallpaper color changes and return to the saved static palette on APIs
  32 and 34. API 31's AOSP emulator does not include a Monet overlay generator;
  that wallpaper-extraction test is explicitly skipped there, while its dynamic
  Android color resources and all Manager controls remain covered.
- Log warnings/errors and the minimize/close controls across five theme cases.

The [appearance workflow](../.github/workflows/appearance-tests.yml) publishes
PNG captures, the color matrix, Android test XML/HTML and logcat in each platform's
artifact. APKs are built by the existing release workflow, which also retains
the audio integration and unit-test gates.

Android's [splash migration guide](https://developer.android.com/develop/ui/views/launch/splash-screen/migrate)
documents the required launch-theme transition. The
[text visibility guidance](https://developer.android.com/guide/topics/ui/accessibility/apps)
specifies the 4.5:1 threshold used here. The Android 12 AOSP
[overlay controller](https://android.googlesource.com/platform/frameworks/base/+/android12-release/packages/SystemUI/src/com/android/systemui/theme/ThemeOverlayController.java)
contains the unimplemented `getOverlay` method in that emulator generation.
