package edu.pitt.isl.mobileclaw.notiftest.demo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

/** Explicit test-only shell entrypoint runs the incoming fixture under its own UID. */
public final class DemoIncomingCallReceiver extends BroadcastReceiver {
    public static final String ACTION = "edu.pitt.isl.mobileclaw.notiftest.test.INCOMING_CALL_FIXTURE";
    @Override public void onReceive(Context context, Intent intent) {
        setResultData("fixture receiver entered");
        if (!ACTION.equals(intent.getAction())) return;
        String runId = intent.getStringExtra("runId");
        if (runId == null || !runId.matches(DemoIncomingConnectionService.RUN_ID)) return;
        String stage = intent.getStringExtra("stage");
        if (stage == null) return;
        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        PhoneAccountHandle handle = DemoIncomingConnectionService.account(context);
        try {
            switch (stage) {
                case "register":
                    context.getSharedPreferences(DemoIncomingConnectionService.PREFS, Context.MODE_PRIVATE)
                            .edit().putString("runId", runId).commit();
                    telecom.registerPhoneAccount(PhoneAccount.builder(handle, "MobileClaw rehearsal")
                            .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                            .addSupportedUriScheme(PhoneAccount.SCHEME_TEL).build());
                    break;
                case "inject":
                    if (!runId.equals(context.getSharedPreferences(DemoIncomingConnectionService.PREFS, Context.MODE_PRIVATE)
                            .getString("runId", ""))) throw new IllegalStateException("Wrong run ID");
                    // Telecom enforces registration/enabled state itself. Avoid getPhoneAccount:
                    // Android 16 protects that metadata with READ_PHONE_NUMBERS even for this fixture.
                    Bundle extras = new Bundle();
                    extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, DemoIncomingConnectionService.ADDRESS);
                    telecom.addNewIncomingCall(handle, extras);
                    break;
                case "unregister":
                    telecom.unregisterPhoneAccount(handle);
                    context.getSharedPreferences(DemoIncomingConnectionService.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
                    break;
                default: return;
            }
            DemoIncomingConnectionService.writeEvent(context, runId, stage, null);
            setResultData("fixture stage " + stage);
        } catch (Throwable t) {
            DemoIncomingConnectionService.writeEvent(context, runId, "failed-" + stage, t.getClass().getSimpleName() + ": " + t.getMessage());
            setResultData("fixture failed " + stage + ": " + t);
        }
    }
}
