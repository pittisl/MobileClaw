package edu.pitt.isl.mobileclaw.notiftest.demo;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Test APK only: managed local call with no modem or outbound call path.
 * Plain Java is intentional: the standalone test APK does not package target Kotlin runtime.
 */
public final class DemoIncomingConnectionService extends ConnectionService {
    public static final String ACCOUNT_ID = "mobileclaw-missed-call-fixture";
    public static final String PREFS = "incoming-call-fixture";
    public static final String RUN_ID = "MCCALL-[A-Z0-9-]{1,40}";
    public static final Uri ADDRESS = Uri.parse("tel:+12025550145");

    public static PhoneAccountHandle account(Context context) {
        return new PhoneAccountHandle(new ComponentName(context.getPackageName(),
                DemoIncomingConnectionService.class.getName()), ACCOUNT_ID);
    }

    public static void writeEvent(Context context, String runId, String stage, String error) {
        try {
            File file = new File(context.getFilesDir(), "demo-incoming-call/" + runId + ".json");
            file.getParentFile().mkdirs();
            JSONObject result = file.exists() ? new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)) : new JSONObject();
            result.put("runId", runId).put("source", "managed Telecom ConnectionService fixture")
                    .put("address", ADDRESS.toString()).put("stage", stage).put("timeMs", System.currentTimeMillis())
                    .put("networkCall", false).put("outboundCall", false);
            if (error != null) result.put("error", error);
            Files.write(file.toPath(), (result.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException("Cannot save fixture status", e); }
    }

    @Override public Connection onCreateIncomingConnection(PhoneAccountHandle manager, ConnectionRequest request) {
        final String runId = getSharedPreferences(PREFS, MODE_PRIVATE).getString("runId", "");
        if (!runId.matches(RUN_ID) || !request.getAccountHandle().equals(account(this)) || !ADDRESS.equals(request.getAddress())) {
            return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.ERROR));
        }
        writeEvent(this, runId, "ringing", null);
        Connection connection = new Connection() {
            @Override public void onAnswer() {
                writeEvent(DemoIncomingConnectionService.this, runId, "unexpected-answer", null);
                setDisconnected(new DisconnectCause(DisconnectCause.LOCAL)); destroy();
            }
            @Override public void onReject() {
                writeEvent(DemoIncomingConnectionService.this, runId, "rejected", null);
                setDisconnected(new DisconnectCause(DisconnectCause.REJECTED)); destroy();
            }
            @Override public void onDisconnect() { setDisconnected(new DisconnectCause(DisconnectCause.LOCAL)); destroy(); }
        };
        connection.setAddress(ADDRESS, TelecomManager.PRESENTATION_ALLOWED);
        connection.setCallerDisplayName("Morgan Demo", TelecomManager.PRESENTATION_ALLOWED);
        connection.setAudioModeIsVoip(true);
        connection.setRinging();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (connection.getState() == Connection.STATE_RINGING) {
                writeEvent(this, runId, "missed", null);
                connection.setDisconnected(new DisconnectCause(DisconnectCause.MISSED));
                connection.destroy();
            }
        }, 6000);
        return connection;
    }

    @Override public Connection onCreateOutgoingConnection(PhoneAccountHandle manager, ConnectionRequest request) {
        return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.ERROR, "Fixture is incoming-only"));
    }

    @Override public void onCreateIncomingConnectionFailed(PhoneAccountHandle manager, ConnectionRequest request) {
        String runId = getSharedPreferences(PREFS, MODE_PRIVATE).getString("runId", "");
        if (runId.matches(RUN_ID)) writeEvent(this, runId, "incoming-connection-failed", null);
    }
}
