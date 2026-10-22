# Building and testing

MobileClaw is a Kotlin Android app using views and XML. The `app` module builds
MobileClaw, and `demotarget` builds the optional TaskBoard AppFunctions demo app.
For an existing signed installation package, see [APK downloads](install.md).

## Toolchain

| Component | Requirement |
|---|---|
| Java | JDK 21 for the committed Gradle daemon configuration; source/bytecode target is Java 17 |
| Android SDK | Platform 36, Build Tools 36, and Platform Tools for `adb` |
| Build system | Included Gradle 9.3.1 wrapper, AGP 8.13.2, Kotlin 2.1.21 and KSP 2.1.21-2.0.2 |
| Recommended runtime | Android 16 / API 36 or newer |
| Minimum runtime | Android 8.0 / API 26 or newer, `arm64-v8a` or `x86_64` |
| AppFunctions example | Android 16 / API 36 or newer and a cooperating target app |

The app targets API 34 and compiles against API 36. Android Studio can install SDK
components. Gradle and Maven dependencies require network access on the first
build. Keep the committed Kotlin and KSP versions together.

Create a gitignored `local.properties` with your SDK location if Android Studio
has not done so. Model credentials can be entered in the installed app; they are
not required for compilation or ordinary regression tests.

## Build and install a development APK

From the repository root on Linux/macOS:

```bash
# Fast main-app compilation and JVM regression tests.
bash gradlew -Pmobileclaw.publicBuild=true :app:compileDebugKotlin :app:testDebugUnitTest

# Build both apps. TaskBoard has an independent unit-test suite.
bash gradlew -Pmobileclaw.publicBuild=true :app:assembleDebug :demotarget:assembleDebug :demotarget:testDebugUnitTest

# Replace SERIAL with the selected device identifier shown by adb devices.
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r demotarget/build/outputs/apk/debug/demotarget-debug.apk
```

Use `gradlew.bat` instead of `bash gradlew` on Windows. TaskBoard is optional for
ordinary chat, notification assistance and document search. Continue with the
[quick start](quickstart.md) to configure the installed app.

A locally built debug APK uses a development signing certificate. It cannot update
an installation signed with a different certificate. See the
[installation guide](install.md) before replacing an existing installation that
contains data you want to keep.

## Optional image similarity

The standard distributed APK omits MobileCLIP weights. It retains file-name/text,
OCR and image-label search. To include MobileCLIP image/text similarity in your
own build, fetch the assets before building:

```bash
bash tools/fetch_clip_model.sh
bash gradlew -Pmobileclaw.publicBuild=true :app:assembleDebug
```

The script downloads approximately 91 MB of model/tokenizer files and their
license to `app/src/main/assets/clip/`, an ignored directory. It uses the
MobileCLIP-S0 ONNX export specified by the script. Model licenses apply separately
from the app's MIT license. This image-search model is not an agent LLM and does
not replace the configured reasoning service.

Without these assets, the app builds and the CLIP-dependent tests skip. A checkout
that already contains the assets will include them when built, so use a clean
source checkout when reproducing the standard package.

## Model configuration and internal defaults

Ordinary debug builds and all release builds contain no developer credentials or
private endpoint values. **Settings > Model service** provides runtime
configuration with encrypted credential storage. See
[model services](model-configuration.md) for details.

An internal debug build can opt into one-time initialization from ignored local
settings with `LLM_DEV_DEFAULTS=true`. Such an APK embeds its initial credentials
and must stay private. `-Pmobileclaw.publicBuild=true` overrides that opt-in without
editing the local configuration. Release builds ignore those defaults regardless
of the flag. The same guard excludes the optional compile-time Notion token.

## Enable Notion in your own build

The Notion integration supports searching, reading and editing pages with your
own Notion API token. The distributed APK contains no Notion token and cannot
use these tools. To try them, build a debug APK with your own token:

1. Create a Notion internal integration (called an **internal connection** in
   Notion's current documentation) and obtain its API token. Follow the
   [Notion setup guide](https://developers.notion.com/guides/get-started/internal-connections).
   Enable read content access, plus insert and update content access if you want
   to use the editing tool.
2. Grant the integration access to the pages you want to use through Notion's
   **Connections** menu or the integration's content access settings.
3. Keep your SDK settings in gitignored `local.properties` and add the following,
   replacing the placeholder with your own token:

   ```properties
   LLM_DEV_DEFAULTS=true
   NOTION_API_KEY=your-own-notion-integration-token
   ```

4. Build the debug APK with the token enabled:

   ```bash
   bash gradlew -Pmobileclaw.publicBuild=false :app:assembleDebug
   ```

5. Install `app/build/outputs/apk/debug/app-debug.apk` using the installation
   instructions above. Configure your reasoning model in **Settings > Model
   service**, then ask MobileClaw to find a page shared with your integration.
   See the [Notion tools](skills.md#notion) for supported operations and
   confirmation rules.

The resulting APK embeds your Notion token, so keep it and `local.properties`
private. `LLM_DEV_DEFAULTS=true` also enables any model-service defaults already
present in that file; a model-service key is not required at build time.
`-Pmobileclaw.publicBuild=true` removes the Notion token, and release builds always
omit it. **Settings > Model service** configures only the reasoning model; it does
not provide a Notion token field.

## Tests

JVM tests cover pure logic and the real streaming/tool loop driven by canned
MockWebServer responses. They do not need a model API key. Framework behavior,
database migrations and live Android APIs use instrumented tests:

```bash
bash gradlew -Pmobileclaw.publicBuild=true :app:connectedDebugAndroidTest
```

Select the intended device before running this task. The task removes its app and
test APKs when it finishes. For repeated testing that needs to retain installation
state or a Shizuku grant, install the APKs and invoke `am instrument` directly.
Filter an instrumented class with
`-Pandroid.testInstrumentationRunnerArguments.class=fully.qualified.ClassName`;
the connected-test task does not accept JVM `--tests` filtering.

The live-model evaluation runner is explicitly opt-in and is skipped by ordinary
device tests. Its [guide](../tools/eval/README.md) explains how to evaluate an
endpoint with real prompts and tools. Build/test success is separate from the
quality of a live model's decisions.

For new tests, keep pure logic in `src/test/` and behavior depending on `Context`,
SQLite or other live Android APIs in `src/androidTest/`. JVM Android stubs return
default values in this project, so a passing JVM test alone does not verify
framework behavior.

## Code map

| Area | Main entry points |
|---|---|
| Notification capture and triage | `NotifListener`, `ReasoningModule`, `FollowUp`, `AgentRunState` |
| Foreground conversation | `MainActivity`, `MainViewModel`, `OpenAiAiClient`, `ChatStore` |
| Model configuration | `ModelServiceActivity`, `LlmConfigRepository`, `EncryptedLlmConfigStore` |
| Tools and permissions | `SkillRegistry`, `ToolRegistry`, `Capability` |
| Confirmation and audit | `PendingAction`, `NotifConfirm`, `ActionLogStore` |
| Privileged execution | `ShellGate`, `ShizukuShell`, `PrivilegedService`, `ShellCommandPolicy` |
| Persistence and memory | `MobileClawDb`, store/DAO classes, `MemoryInjection`, `DailyPassRunner` |
| Local search | `skills/search/`, AppSearch, ML Kit, optional MobileCLIP/ONNX Runtime |
| Cross-app example | `skills/appfunctions/` and the separate `demotarget/` module |

The [architecture guide](architecture.md) explains the relationships and important
state boundaries. Committed Room schemas are part of the source distribution;
database changes require matching migrations and migration tests.
