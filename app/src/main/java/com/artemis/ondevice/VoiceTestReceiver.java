package com.artemis.ondevice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Drives voice calls from adb, to test them without a microphone:
 *
 * <pre>
 * adb shell am broadcast -a com.artemis.ondevice.VOICE_TEST -n com.artemis.ondevice/.VoiceTestReceiver --es cmd call
 * adb shell am broadcast -a com.artemis.ondevice.VOICE_TEST -n com.artemis.ondevice/.VoiceTestReceiver --es cmd say --es text "打开设置看看电量"
 * adb shell am broadcast -a com.artemis.ondevice.VOICE_TEST -n com.artemis.ondevice/.VoiceTestReceiver --es cmd hangup
 * adb shell am broadcast -a com.artemis.ondevice.VOICE_TEST -n com.artemis.ondevice/.VoiceTestReceiver --es cmd task --es text "现在几点了"
 * </pre>
 * "task" starts a typed task, as the Start button would (scripts/regression.py uses it).
 *
 * "call" acts like the wake word (voice wake-up must be on), "say" is a sentence as if the user
 * had said it, "hangup" ends the call. Guarded by WRITE_SECURE_SETTINGS in the manifest, so only
 * adb shell / system can send it. What is said is not logged.
 */
public class VoiceTestReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String cmd = String.valueOf(intent.getStringExtra("cmd"));
        VoiceCall call = VoiceCall.current;
        switch (cmd) {
            case "call":
                if (!VoiceService.canRun(ctx)) {
                    fail("voice wake-up is off");
                    return;
                }
                VoiceService.callRequested = true;
                break;
            case "say":
                String text = intent.getStringExtra("text");
                if (call == null || text == null || text.trim().isEmpty()) {
                    fail(call == null ? "no call in progress" : "no text");
                    return;
                }
                call.inject(text.trim());
                break;
            case "task":
                String task = intent.getStringExtra("text");
                if (task == null || task.trim().isEmpty() || RunState.isRunning()) {
                    fail(RunState.isRunning() ? "a task is running" : "no text");
                    return;
                }
                setResultData(AgentService.start(ctx, task.trim()));
                break;
            case "hangup":
                if (call == null) {
                    fail("no call in progress");
                    return;
                }
                call.hangUp();
                break;
            default:
                fail("cmd must be call, say, hangup or task");
                return;
        }
        setResultCode(1);
    }

    private void fail(String why) {
        AppLog.i(AppLog.VOICE, "test command refused: " + why);
        setResultCode(0);
        setResultData(why);
    }
}
