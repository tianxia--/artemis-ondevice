package com.artemis.ondevice;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether an action needs the user's go-ahead: taps (and long presses) that send, call,
 * pay, buy, delete, post, invite or change a setting. The model flags them itself ({@code commits}
 * plus a {@code summary}); as a backstop the target's own text is checked, so a forgotten flag
 * does not let a Send button through.
 */
final class Sensitive {

    /** Button texts that commit something. Short labels only: a long text is content, not a button. */
    private static final Pattern COMMIT_WORDS = Pattern.compile(
            "(?i)\\b(send|call|video call|voice call|pay|transfer|buy|purchase|place order|checkout|delete|remove"
                    + "|post|publish|share|invite|submit|block|unfriend|uninstall|reset|erase)\\b"
                    + "|发送|拨打|呼叫|视频通话|语音通话|支付|付款|转账|购买|下单|结算|删除|移除|发布|分享|邀请|提交"
                    + "|拉黑|卸载|恢复出厂|清空|按住.{0,2}说话|hold to (talk|record)");
    private static final int MAX_BUTTON_TEXT = 30;
    private static final Pattern QUOTED = Pattern.compile("(?:Text|Desc): '([^']*)'");

    private Sensitive() {}

    /** What the action would do, for the confirmation prompt; null when it needs no confirmation. */
    static String check(Observation obs, ChatClient.ToolCall call) {
        if (!"tap".equals(call.name) && !"long_press".equals(call.name)) return null;
        JSONObject a = call.args;
        String label = targetLabel(obs, a);
        String text = buttonText(label);
        if (a.optBoolean("commits")) {
            String s = a.optString("summary").trim();
            return s.isEmpty() ? describe(text, obs) : s;
        }
        if (!text.isEmpty() && text.length() <= MAX_BUTTON_TEXT && COMMIT_WORDS.matcher(text).find()) {
            return describe(text, obs);
        }
        // Switches and checkboxes in a settings app change the phone's settings.
        boolean toggle = label.contains("checkable") || label.contains("unchecked") || label.contains("checked");
        if (toggle && obs.packageName.toLowerCase(java.util.Locale.ROOT).contains("settings")) {
            return describe(text.isEmpty() ? "a switch" : text, obs);
        }
        return null;
    }

    static String targetLabel(Observation obs, JSONObject a) {
        if (a.has("index") && !a.isNull("index")) {
            int i = a.optInt("index", -1);
            if (i >= 0 && i < obs.elements.size()) return obs.elements.get(i).label;
        }
        return "Text: '" + a.optString("target_description") + "'";
    }

    /** The element's visible text and description, without the list's field names and flags. */
    static String buttonText(String label) {
        StringBuilder sb = new StringBuilder();
        Matcher m = QUOTED.matcher(label);
        while (m.find()) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(m.group(1).trim());
        }
        return sb.toString();
    }

    private static String describe(String text, Observation obs) {
        return "“" + FlashLoop.clip(text, 40) + "” (" + obs.packageName + ")";
    }
}
