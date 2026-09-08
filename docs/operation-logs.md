# Minimized operation logs

Every `TerminalDialog` requires a minimize callback. The minus button remains enabled
while the operation runs; the close and clear controls remain disabled until it ends.
On Android 13+, the first minimize requests notification permission. Denying that
permission still leaves the operation and its logs accessible in the owning screen.

Home (including Stop all), graphical-session installation/verification and individual
monitors use `OperationLogStore`. Each container/area or monitor has its own latest
result and a **View logs** action. Notifications open that same owner. Completed
notifications offer **Close**; closing one never clears its logs or stops another task.

Operation ViewModels belong to the application, hold no Activity or renderer callback,
and keep running when the activity or monitor screen is destroyed. The explicitly
minimized tasks are protected by `LogOperationService`. Its `specialUse` foreground
type describes user-started Linux setup and runtime commands; it has no audio or X11
server lifecycle responsibility. Package-plan overrides remain inside the installation
transaction and a shared mutex protects them while multiple installations are queued.

The progress bar is indeterminate: package download, configuration, service validation
and runtime cleanup do not expose a common, accurate percentage. The notification
shows semantic action checkpoints. See Android's [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)
and [notification permission behavior](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

The final logger burst is drained before completion. Atomic private archives retain up
to 1,500 recent lines per owner and its result. Active VNC credentials remain in memory
as before. A fresh process restores an unfinished archive as **Interrupted**, without
replaying root commands or claiming that the external operation succeeded.

`OperationArchiveTest` checks round trips, damaged files and interrupted work.
`OperationNotificationsTest` exercises Android API 26 and 34: progress/completion,
notification destinations, concurrent minimized tasks, immediate completion,
replacement of a previous run, activity destruction and durable final log delivery.
The normal `testReleaseUnitTest assembleRelease` CI gate also runs the existing
PulseAudio transport and X11 regression checks and produces all five APK variants.

Device acceptance: start an installation, minimize with **−**, leave and reopen the
app, tap its notification, minimize again, wait for completion and close the finished
notification. Reopen the saved result from the editor. Repeat for Home and monitor
operations, including a failed operation and notification permission denied.
