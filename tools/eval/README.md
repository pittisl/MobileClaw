# Model evaluation

The instrumented runner sends the production prompts and tool catalog to an
OpenAI-compatible model endpoint. It compares notification triage and tool-use tasks
against explicit expectations. `-e eval true` is required; a normal
`connectedDebugAndroidTest` run skips live evaluation.

## Components

| File | Role |
|---|---|
| `ModelEvalRunner.kt` (`androidTest`, `eval/`) | Triage and chat suites; one JSON line per case |
| `EvalNotifs.kt` | Synthetic notification fixtures and expected dispositions |
| `CannedTools.kt` | Real tool names/descriptions/schemas with recorded calls and canned results for actions and permission-dependent data |
| `ChatTasks.kt` | Expected/forbidden tools, argument checks and answer checks |
| `run_eval.sh` | Build, install on one explicit device, run instrumentation, pull results |
| `report.py` | Compare locally generated runs; `-v` includes failed expectations |

The suites use the real request builders and catalog, but canned tool outputs keep
model evaluation separate from real-world action execution. Results generated under
`tools/eval/results/` are local outputs and are ignored by Git.

## Run a baseline

Put `adb` on PATH, or set `ADB=/path/to/adb`. Build and install matching debug and
instrumentation APKs on the selected device:

```sh
bash gradlew -Pmobileclaw.publicBuild=true :app:assembleDebug :app:assembleDebugAndroidTest
adb -s SERIAL install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Open MobileClaw and save your provider, model and credentials in
**Settings > Model service**, following the [model configuration guide](../../docs/model-configuration.md).
Without endpoint/model/key overrides, the runner reads that saved runtime profile.
Then run the baseline using the installed APKs:

```sh
bash tools/eval/run_eval.sh -s SERIAL -t baseline --no-install
```

Use a fresh tag and inspect the pulled JSONL files. The shell wrapper filters console
output and tolerates missing result pulls; its exit status alone is not proof that the
suite passed or produced a complete result set.

## Run against your own inference server

Start an OpenAI-compatible inference server using its own documented setup. Expose it
on the development host's loopback interface. If it runs remotely, establish an SSH
forward in a separate terminal, for example:

```sh
ssh -N -L 18080:127.0.0.1:18080 user@inference-host.example.org
```

Then forward the device loopback port to the host through adb:

```sh
bash tools/eval/run_eval.sh -s SERIAL -t local-model -R 18080 \
  -e http://127.0.0.1:18080/v1/chat/completions -m local -k local -r 900 --no-install
python3 tools/eval/report.py baseline local-model -v
```

`--no-install` requires matching debug and instrumentation APKs already installed.
The `local` API key above is a placeholder for a locally controlled endpoint, not a
credential. Supply the credentials required by your own server. Cleartext HTTP is
allowed to loopback and the Android emulator's host alias `10.0.2.2`
(`res/xml/network_security_config.xml`); a direct LAN HTTP address is rejected.
This evaluation uses the server for inference; GPU latency does not measure
on-phone inference performance.

## Options and interpretation

- `-p "TEXT"` appends a chat system-prompt suffix for that run only.
- `-o TASK_ID` selects one chat task; `-S triage|chat|both` selects suites.
- `--images` includes the image input for a model that accepts images.
- `-r SECONDS` changes the read timeout.
- Chat passes require every expectation; `report.py -v` explains failures.
- Triage uses the expected-disposition table. Ongoing notifications are skipped because
  production triage also excludes them.
- Read model answers alongside tool calls: describing an action does not execute its
  tool. Results on these fixed tasks do not establish general agent reliability.
