# Read-only system AppFunctions on Pixel 9 Pro

These two optional scenarios use the same `call_app_function` → confirmation → Shizuku
route as TaskBoard. They query installed system apps on the same phone. Availability
depends on the system image and app versions; the Pixel 9 Pro has both interfaces.
Each read requires **Do it**, even when Full Automation is enabled. **Not now** declines
the query. Neither scenario changes a system setting.

Prerequisites: Android 16, Digital Wellbeing and Google Play services exposing the
functions below, and MobileClaw's existing Shizuku grant/service connection. The
background agent may remain **Paused**; foreground chat still works.

## Digital Wellbeing

Ask:

> Check my Focus Mode, Bedtime Mode, and app time limits. Only read their current settings.

Verify the confirmation names `com.google.android.apps.wellbeing` and
`getWellbeingDeviceState`, then press **Do it**. The result shows the reported modes,
schedules, distracting apps and app timers. Missing fields are marked **not reported**;
access restrictions and explanatory hints are preserved. A zero-minute app limit
means the app cannot be used.

Exact function:

```text
com.google.android.apps.wellbeing.appfunctions.BaseWellbeingAppFunctionService#getWellbeingDeviceState
```

Parameters:

```json
{"getWellbeingDeviceStateParams":{}}
```

## Quick Share

Ask:

> Check whether my phone is visible to other devices in Quick Share. Do not change any settings.

Verify the confirmation names `com.google.android.gms`, `getDeviceStateItem`, and
`quick_share_visibility`, then press **Do it**. The result reports the current receive
visibility. `HIDDEN` means hidden; other values are displayed as reported.

Exact function:

```text
com.google.android.gms.appfunctions.GmsDeviceState#getDeviceStateItem
```

Parameters:

```json
{"getDeviceStateItemParams":{"key":"quick_share_visibility"}}
```

## Explain and reset

The confirmed result appears directly in chat. As with TaskBoard, a model-written
explanation uses the **next user turn**:

> Explain the result you just received. Use that result without querying again.

The live catalog names these two reads; it does not discover or enable other system
functions. If a function is unavailable, report that limitation. Do not substitute
`setDeviceStateItem` or enable disabled functions.

Use **Reset demo** between visitors. These queries leave no TaskBoard task or changed
system setting to undo. The two interfaces were exercised on a Pixel 9 Pro running Android 16.
Check availability on the actual system image used for a new demonstration.
