#!/usr/bin/env bash
# Live, paid Gemini acceptance using production Kotlin code and synthetic data only.
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
curl --fail --silent --show-error --max-time 20 \
    'https://dns.google/resolve?name=generativelanguage.googleapis.com&type=A' > "$scratch/dns.json"
python3 - "$scratch" <<'PY'
import ipaddress, json, pathlib, sys
p = pathlib.Path(sys.argv[1])
data = json.loads((p / 'dns.json').read_text())
assert data['Status'] == 0, 'dns.google lookup failed'
ips = [str(ipaddress.IPv4Address(a['data'])) for a in data.get('Answer', []) if a['type'] == 1]
assert ips, 'dns.google returned no IPv4 addresses'
(p / 'hosts').write_text('127.0.0.1 localhost\n::1 localhost\n' +
    ''.join(ip + ' generativelanguage.googleapis.com\n' for ip in ips))
print('Resolved Google API through dns.google; TLS hostname verification remains enabled.')
PY
cat > "$scratch/live.init.gradle" <<'GRADLE'
allprojects {
    tasks.withType(Test).configureEach {
        systemProperty 'mobileclaw.liveGemini', 'true'
        systemProperty 'mobileclaw.localProperties', new File(rootProject.projectDir, 'local.properties').absolutePath
        systemProperty 'jdk.net.hosts.file', System.getenv('MOBILECLAW_ACCEPTANCE_HOSTS')
        outputs.upToDateWhen { false }
        testLogging.showStandardStreams = true
    }
}
GRADLE
cd "$repo"
MOBILECLAW_ACCEPTANCE_HOSTS="$scratch/hosts" bash gradlew -I "$scratch/live.init.gradle" \
    :app:testDebugUnitTest --tests 'edu.pitt.isl.mobileclaw.notiftest.GeminiLiveAcceptanceTest'
