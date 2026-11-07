package com.youpi.observer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "YouPISmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            return;
        }

        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) return;

        final Context appContext = context.getApplicationContext();
        final ApiClient client = new ApiClient(appContext);
        if (!client.isPaired()) {
            Log.w(TAG, "SMS received but device is not paired with YouPI Gateway. Skipping.");
            return;
        }

        // A long SMS is delivered as several parts in one broadcast. Bank alerts often put the
        // amount in one part and the UPI reference (UTR) in the next, so the parts are joined per
        // sender before filtering; evaluating each part alone dropped or truncated such alerts.
        Map<String, StringBuilder> bodies = new LinkedHashMap<>();
        Map<String, Long> times = new LinkedHashMap<>();
        for (SmsMessage sms : messages) {
            if (sms == null) continue;
            String sender = sms.getOriginatingAddress();
            if (sender == null) sender = "";
            StringBuilder sb = bodies.get(sender);
            if (sb == null) {
                sb = new StringBuilder();
                bodies.put(sender, sb);
                times.put(sender, sms.getTimestampMillis());
            }
            String part = sms.getMessageBody();
            if (part != null) sb.append(part);
        }

        for (Map.Entry<String, StringBuilder> entry : bodies.entrySet()) {
            final String sender = entry.getKey();
            final String body = entry.getValue().toString();
            final long timestamp = times.get(sender);

            // Strict On-Device Data Minimization & Privacy Protection:
            // Evaluate on-device first. Discard personal, non-financial, or debit messages immediately.
            BankSmsFilter.FilterDecision decision = BankSmsFilter.evaluate(sender, body);
            if (!decision.isAllowed) {
                Log.d(TAG, "Locally discarded non-banking / personal SMS from: [" + sender + "] Reason: " + decision.reason);
                continue;
            }

            final String clientMessageId = UUID.randomUUID().toString();
            Log.i(TAG, "Valid bank credit alert accepted from [" + sender + "]. Dispatching ID: " + clientMessageId);

            // Dispatch on a worker thread (the always-running foreground service keeps the process
            // alive), retrying while the gateway is unreachable so a brief network drop does not
            // lose the payment alert.
            new Thread(new Runnable() {
                @Override
                public void run() {
                    // One client per dispatch: retry state must not be shared between concurrent alerts.
                    boolean success = new ApiClient(appContext).sendSmsWithRetry(sender, body, timestamp, clientMessageId);
                    Log.i(TAG, "Dispatched bank SMS to YouPI Gateway (" + clientMessageId + "): " + (success ? "SUCCESS" : "FAILED"));
                }
            }).start();
        }
    }
}
