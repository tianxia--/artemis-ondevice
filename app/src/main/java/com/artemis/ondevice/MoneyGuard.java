package com.artemis.ondevice;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Keeps the agent away from money: banking, payment, wallet, securities and crypto apps (and
 * WeChat, QQ, Alipay), tasks about transfers, payments or purchases, and buttons that pay, transfer
 * or buy. Automating them risks account bans (their risk controls flag accessibility automation)
 * and could be abused for fraud. Not a setting: it cannot be turned off.
 */
final class MoneyGuard {

    /** Known money apps; the name patterns below catch the many banks not listed. */
    private static final Set<String> PACKAGES = new HashSet<>(Arrays.asList(
            // Chat apps with payments
            "com.tencent.mm", "com.tencent.mobileqq",
            // Payment and wallets
            "com.eg.android.AlipayGphone", "com.unionpay", "com.jd.jrapp", "com.webank.wemoney",
            "com.paypal.android.p2pmobile", "com.venmo", "com.squareup.cash",
            "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user",
            "com.samsung.android.spay", "com.samsung.android.samsungpay.gear", "com.huawei.wallet",
            // Chinese banks
            "cmb.pb", "com.chinamworld.main", "com.icbc", "com.android.bankabc", "com.chinamworld.bocmbci",
            "com.bankcomm.Bankcomm", "com.yitong.mbank.psbc", "com.ecitic.bank.mobile", "com.cib.cibmb",
            "com.spdb.mobilebank.per", "com.cebbank.mobile.cemb", "com.pingan.paces.ccms",
            // Securities and crypto
            "com.hexin.plat.android", "com.eastmoney.android.berlin", "cn.futu.trader", "com.tigerbrokers.stock",
            "com.binance.dev", "com.coinbase.android", "com.okinc.okex.gp", "com.robinhood.android"));

    private static final Pattern APP_NAME = Pattern.compile(
            "(?i)bank|银行|\\bpay\\b|^pay|pay$|支付|钱包|wallet|证券|基金|理财|金融|贷|信用卡|股票|炒股|期货"
                    + "|securities|broker|invest|trading|crypto|bitcoin|finance|forex");
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "(?i)bank|wallet|paypal|alipay|\\.pay\\.|\\.pay$|finance|securities|crypto|invest|trading|stock|broker");

    /** Tasks about money, or naming WeChat, QQ or Alipay ("微信读书", "QQ音乐" etc. are other apps). */
    private static final Pattern TASK = Pattern.compile(
            "(?i)微信(?!读书)|支付宝|wechat|weixin|alipay|(?<![a-z])qq(?!音乐|邮箱|浏览器|阅读)"
                    + "|转账|汇款|付款|支付|收款|红包|充值|提现|贷款|借款|还款|理财|炒股|股票|基金|证券|银行|信用卡"
                    + "|购买|下单|结账|买单|transfer money|send money|wire transfer|\\bpay(ment)?\\b|\\bbank"
                    + "|\\bloans?\\b|\\bstocks?\\b|crypto|bitcoin|checkout|purchase|\\bbuy\\b|top.?up|withdraw|wallet");

    /** Buttons that move money. Short texts only: a long text is content, not a button. */
    private static final Pattern BUTTON = Pattern.compile(
            "(?i)\\b(pay|pay now|transfer|send money|buy|buy now|purchase|place order|checkout|top up|withdraw"
                    + "|confirm payment)\\b|支付|付款|转账|汇款|购买|下单|提交订单|结算|买单|充值|提现|红包|确认付款");
    private static final int MAX_BUTTON_TEXT = 30;

    private static final Map<String, Boolean> CACHE = new ConcurrentHashMap<>();

    private MoneyGuard() {}

    /** Whether the agent must not operate this app. */
    static boolean isBlockedApp(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (PACKAGES.contains(pkg)) return true;
        Boolean known = CACHE.get(pkg);
        if (known != null) return known;
        boolean blocked = PACKAGE_NAME.matcher(pkg).find();
        if (!blocked) {
            try {
                PackageManager pm = ctx.getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                blocked = APP_NAME.matcher(String.valueOf(pm.getApplicationLabel(ai))).find();
            } catch (PackageManager.NameNotFoundException e) {
                blocked = false;
            }
        }
        CACHE.put(pkg, blocked);
        return blocked;
    }

    /** Whether a task asks for money matters or names a money app (by word or installed app name). */
    static boolean isBlockedTask(Context ctx, String task) {
        if (task == null) return false;
        if (TASK.matcher(task).find()) return true;
        for (String named : Actions.appsNamedIn(ctx, task)) {
            if (isBlockedApp(ctx, named.substring(named.lastIndexOf(" = ") + 3))) return true;
        }
        return false;
    }

    /** Whether the action would pay, transfer or buy. */
    static boolean isMoneyAction(Observation obs, ChatClient.ToolCall call) {
        if (!"tap".equals(call.name) && !"long_press".equals(call.name)) return false;
        String text = Sensitive.buttonText(Sensitive.targetLabel(obs, call.args));
        if (!text.isEmpty() && text.length() <= MAX_BUTTON_TEXT && BUTTON.matcher(text).find()) return true;
        return call.args.optBoolean("commits") && BUTTON.matcher(call.args.optString("summary")).find();
    }
}
