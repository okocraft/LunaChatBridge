/*
 * @author     ucchy
 * @license    LGPLv3
 * @copyright  Copyright ucchy 2014
 */
package com.github.ucchyocean.bjm;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import com.github.ucchyocean.lc.japanize.Japanizer;

/**
 * サーバー間tellコマンドおよびJapanize化プラグイン
 * @author ucchy
 */
public class BungeeJapanizeMessenger extends Plugin implements Listener {

    private static final String DATE_FORMAT_PATTERN = "yyyy/MM/dd";
    private static final String TIME_FORMAT_PATTERN = "HH:mm:ss";

    private SimpleDateFormat dateFormat;
    private SimpleDateFormat timeFormat;

    private HashMap<String, String> history;
    private BJMConfig config;
    private JapanizeDictionary dictionary;

    /**
     * プラグインが有効化されたときに呼び出されるメソッド
     * @see net.md_5.bungee.api.plugin.Plugin#onEnable()
     */
    @Override
    public void onEnable() {

        // 初期化
        history = new HashMap<String, String>();
        dateFormat = new SimpleDateFormat(DATE_FORMAT_PATTERN);
        timeFormat = new SimpleDateFormat(TIME_FORMAT_PATTERN);

        // コマンド登録
        for ( String command : new String[]{
                "tell", "msg", "message", "m", "w", "t"}) {
            getProxy().getPluginManager().registerCommand(
                    this, new TellCommand(this, command));
        }
        for ( String command : new String[]{"reply", "r"}) {
            getProxy().getPluginManager().registerCommand(
                    this, new ReplyCommand(this, command));
        }
        for ( String command : new String[]{"dictionary", "dic"}) {
            getProxy().getPluginManager().registerCommand(
                    this, new DictionaryCommand(this, command));
        }

        // コンフィグ取得
        config = new BJMConfig(this);

        // 辞書取得
        dictionary = new JapanizeDictionary(this);

        // リスナー登録
        getProxy().getPluginManager().registerListener(this, this);
    }

    /**
     * コンフィグを返す
     * @return コンフィグ
     */
    public BJMConfig getConfig() {
        return config;
    }

    /**
     * 辞書を返す
     * @return 辞書
     */
    public JapanizeDictionary getDictionary() {
        return dictionary;
    }

    /**
     * プライベートメッセージの受信履歴を記録する
     * @param reciever 受信者
     * @param sender 送信者
     */
    protected void putHistory(String reciever, String sender) {
        history.put(reciever, sender);
    }

    /**
     * プライベートメッセージの受信履歴を取得する
     * @param reciever 受信者
     * @return 送信者
     */
    protected String getHistory(String reciever) {
        return history.get(reciever);
    }

    /**
     * プレイヤーがチャット発言した時に呼び出されるメソッド
     * @param event
     */
    @EventHandler
    public void onChat(ChatEvent event) {

        // 設定が無効なら、そのまま無視する
        if ( !config.isBroadcastChat() ) {
            return;
        }

        // コマンド実行の場合は、そのまま無視する
        if ( event.isCommand() ) {
            return;
        }

        // プレイヤーの発言ではない場合は、そのまま無視する
        if ( !(event.getSender() instanceof ProxiedPlayer) ) {
            return;
        }

        // 発言者と発言サーバーと発言内容の取得
        final ProxiedPlayer sender = (ProxiedPlayer)event.getSender();
        String senderServer = sender.getServer().getInfo().getName();
        String message = event.getMessage();

        // NGワードのマスク
        message = maskNGWord(message, config.getNgwordCompiled());

        // Japanizeの付加
        if ( message.startsWith(config.getNoneJapanizeMarker()) ) {

            message = message.substring(config.getNoneJapanizeMarker().length());

        } else {

            String japanize = Japanizer.japanize(message, config.getJapanizeType(),
                    dictionary.getDictionary());
            if ( japanize.length() > 0 ) {

                // NGワードのマスク
                japanize = maskNGWord(japanize, config.getNgwordCompiled());

                // フォーマット化してメッセージを上書きする
                String japanizeFormat = config.getJapanizeDisplayLine() == 1 ?
                        config.getJapanizeLine1Format() :
                        "%msg\n" + config.getJapanizeLine2Format();
                String preMessage = new String(message);
                message = japanizeFormat.replace("%msg", preMessage).replace("%japanize", japanize);
            }
        }

        // フォーマットの置き換え処理
        String result = config.getBroadcastChatFormat();
        result = result.replace("%senderserver", senderServer);
        result = result.replace("%sender", sender.getName());
        if ( result.contains("%date") ) {
            result = result.replace("%date", dateFormat.format(new Date()));
        }
        if ( result.contains("%time") ) {
            result = result.replace("%time", timeFormat.format(new Date()));
        }
        result = result.replace("%msg", message);
        result = Utility.replaceColorCode(result);

        // 発言したプレイヤーがいるサーバー"以外"のサーバーに、
        // 発言内容を送信する。
        for ( String server : getProxy().getServers().keySet() ) {

            if ( server.equals(senderServer) ) {
                continue;
            }

            ServerInfo info = getProxy().getServerInfo(server);
            for ( ProxiedPlayer player : info.getPlayers() ) {
                sendMessage(player, result);
            }
        }

        // ローカルも置き換える処理なら、置換えを行う
        if ( config.isBroadcastChatLocalJapanize() ) {

            // NOTE: 改行がサポートされないので、改行を含む場合は、
            // \nで分割して前半をセットし、後半は150ミリ秒後に送信する。
            if ( !message.contains("\n") ) {
                event.setMessage(Utility.removeColorCode(message));
            } else {
                int index = message.indexOf("\n");
                String pre = message.substring(0, index);
                final String post = Utility.replaceColorCode(
                        message.substring(index + "\n".length()));
                event.setMessage(Utility.removeColorCode(pre));
                getProxy().getScheduler().schedule(this, new Runnable() {
                    @Override
                    public void run() {
                        for ( ProxiedPlayer p : sender.getServer().getInfo().getPlayers() ) {
                            sendMessage(p, post);
                        }
                    }
                }, 150, TimeUnit.MILLISECONDS);
            }
        }

        // コンソールに表示設定なら、コンソールに表示する
        if ( config.isDisplayChatOnConsole() ) {
            getLogger().info(result);
        }
    }

    /**
     * NGワードをマスクする
     * @param message メッセージ
     * @param ngwords NGワード
     * @return マスクされたメッセージ
     */
    private String maskNGWord(String message, ArrayList<Pattern> ngwords) {
        for ( Pattern pattern : ngwords ) {
            Matcher matcher = pattern.matcher(message);
            if ( matcher.find() ) {
                message = matcher.replaceAll(
                        Utility.getAstariskString(matcher.group(0).length()));
            }
        }
        return message;
    }

    /**
     * 指定した対象にメッセージを送信する
     * @param reciever 送信先
     * @param message メッセージ
     */
    protected void sendMessage(CommandSender reciever, String message) {
        if ( message == null ) return;
        reciever.sendMessage(TextComponent.fromLegacyText(message));
    }
}
