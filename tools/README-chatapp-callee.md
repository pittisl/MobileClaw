# The AppFunctions callee for the demo: Google's ChatApp sample

`call_app_function` needs another app that exposes AppFunctions. One tested callee is
Google's sample from `github.com/android/appfunctions` (`ChatApp/`, applicationId
`com.example.chatapp`), built at commit `858db2b` with **one change**, kept here as
`chatapp-legacy-appfunctions.patch`:

- the sample is on `appfunctions 1.0.0-alpha10`, whose compiler writes function metadata
  only to the `android.app.appfunctions.v2` file; the Android 16 build on the Pixel 7
  indexes functions from the **legacy** `android.app.appfunctions` manifest property and
  its `app_functions.xml`, so an unpatched ChatApp answers `App function not found`;
- the patch adds that property to the service and a hand-written
  `assets/app_functions_legacy.xml` listing the five function ids.

Use an explicit adb serial in place of `SERIAL`. Build (needs `platforms;android-37`, Gradle 9.6 — the sample's own wrapper fetches it):

```bash
git clone https://github.com/android/appfunctions && cd appfunctions
git checkout 858db2b && git apply /path/to/chatapp-legacy-appfunctions.patch
cd ChatApp
# Configure JAVA_HOME for the sample's required JDK before building.
./gradlew :app:assembleDebug -x spotlessCheck
adb -s SERIAL install -r -t app/build/outputs/apk/debug/app-debug.apk
```

Sanity check from the shell (uid 2000 holds `EXECUTE_APP_FUNCTIONS` on this build):

```bash
adb -s SERIAL shell "cmd app_function execute-app-function --package com.example.chatapp \
  --function 'com.example.chatapp.appfunctions.BaseChatAppFunctionService#searchContacts' \
  --parameters '{\"query\":\"a\",\"contactType\":\"ANY\"}'"
```

returns Alice Smith (endpointValue 1), Bob Johnson (2), … — the same call MobileClaw
stages through `ShellGate.modify` once the user confirms.
