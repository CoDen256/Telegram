package io.github.coden256.wpl.guard;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import org.telegram.tgnet.TLRPC.User;
import org.telegram.tgnet.TLRPC.Chat;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import kotlin.text.Regex;


public class GuardClientService extends Service {

    public static String USER_RULINGS = "user";
    public static String CHAT_RULINGS = "chat";

    public static class RulingSettings {
        public static String PREFIX = "rulings_";
        private final SharedPreferences preferences;
        private final String category;

        public RulingSettings(SharedPreferences preferences, String category){
            this.preferences = preferences;
            this.category = category;
        }

        public void writeRulings(List<Ruling> rulings){
            SharedPreferences.Editor editor = preferences.edit();
            editor.putStringSet(keyFrom(category),
                    rulings.stream().map(this::compileRuling).collect(Collectors.toSet())
            );
            editor.commit();
        }

        public List<Ruling> getRulings(){
            return preferences
                    .getStringSet(keyFrom(category), Set.of())
                    .stream()
                    .map(this::parseRuling)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }

        private Ruling parseRuling(String compiled){
            String[] split = compiled.split("//");
            if (split.length != 2) return null;
            Ruling newRuling = new Ruling();
            newRuling.path = split[0];
            newRuling.action = split[1];
            return newRuling;
        }

        private String compileRuling(Ruling ruling){
            return String.format("%s//%s", ruling.path, ruling.action);
        }

        private String keyFrom(String category){
            return PREFIX + category;
        }

        public static RulingSettings of(String category){
            return new RulingSettings(MessagesController.getGlobalMainSettings(), category);
        }
    }

    public static class RulingMatcher{

        private final List<Ruling> chatRulings;
        private final List<Ruling> userRulings;
        private Context context;

        public RulingMatcher(Context context, List<Ruling> chatRulings, List<Ruling> userRulings){
            this.chatRulings = chatRulings;
            this.userRulings = userRulings;
            this.context = context;
        }

        public RulingMatcher(Context context){
            this(context,RulingSettings.of(GuardClientService.CHAT_RULINGS).getRulings(),
                    RulingSettings.of(GuardClientService.USER_RULINGS).getRulings());
        }

        public void init(long chatId, long userId){
            Log.w("GuardMatcher", "Checking: chat " + chatId + " user " + userId);

        }

        public boolean shouldBlockUser(User user){ // user.id != 0
            Log.w("GuardMatcher", String.format("user_id=%d username=%s",user.id, UserObject.getPublicUsername(user)));
            Log.w("GuardMatcher", String.format("isBot?%b isContact?%b isMe?%b", UserObject.isBot(user), UserObject.isContact(user), UserObject.isUserSelf(user)) );
            String path = asPath(user);
            boolean blocked = isBlocked(path, userRulings);
            Log.w("GuardMatcher", "/users/"+path+(blocked? " blocked":" allowed"));
            if (blocked){
                Toast.makeText(context, "/users/"+path +" blocked", Toast.LENGTH_SHORT).show();
            }
            return blocked;
        }

        public boolean shouldBlockChat(Chat chat){ // chat.id != 0
            Log.w("GuardMatcher", String.format("chat_id=%d username=%s",chat.id, ChatObject.getPublicUsername(chat)));
            Log.w("GuardMatcher", String.format("isChannelOrGiga?%b isChannel?%b isMega?%b canPost?%b canSendMsg?%b isPublic?%b",
                    ChatObject.isChannelOrGiga(chat), ChatObject.isChannel(chat), ChatObject.isMegagroup(chat),
                    ChatObject.canPost(chat), ChatObject.canSendMessages(chat), ChatObject.isPublic(chat)) );
            String path = asPath(chat);
            boolean blocked = isBlocked(path, chatRulings);
            Log.w("GuardMatcher", "/chats/"+path+(blocked? " blocked":" allowed"));

            if (blocked){
                Toast.makeText(context, "/chats/"+path +" blocked", Toast.LENGTH_SHORT).show();
            }
            return blocked;
        }

        // <id>-<isPublic><isGiga><isMega><isChannel><canPost><canSendMsg>
        // 1040-pgmcos
        // 1040-p-mc-s
        private String asPath(Chat chat){
            return String.format("%d-%s%s%s%s%s%s",
                    chat.id,
                    encode(ChatObject.isPublic(chat), "p"),
                    encode(ChatObject.isChannelOrGiga(chat), "g"),
                    encode(ChatObject.isMegagroup(chat), "m"),
                    encode(ChatObject.isChannel(chat), "c"),
                    encode(ChatObject.canPost(chat), "o"),
                    encode(ChatObject.canSendMessages(chat), "s"));
        }

        // <id>-<isBot>
        // 1042-b
        // 1042--
        private String asPath(User user){
            return String.format("%d-%s", user.id, encode(UserObject.isBot(user), "b"));
        }

        private String encode(boolean b, String flag){
            return b ? flag : "-";
        }

        private boolean isBlocked(String subject, List<Ruling> rulings){
            List<Ruling> matching = rulings.stream().filter(r -> matches(subject, r.path)).collect(Collectors.toList());
            Log.i("GuardMatcher", "Matches: "+ matching.stream().map(GuardClientService::pretty).toList());
            if (matching.isEmpty()) return false;
            return matching.stream().noneMatch(r -> r.action.equals("FORCE")) &&
                    matching.stream().anyMatch(r -> r.action.equals("BLOCK"));
        }

        private boolean matches(String path, String rulingPath){
            return new Regex(rulingPath.replace("*", ".*")).matches(path);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "Guard Client received a new connection: " + intent);
        return new GuardClient.Stub() {
            @Override
            public void onRulings(List<Ruling> newRulings) {
                Log.i(TAG, "Receiving rulings: " + newRulings.stream().map(GuardClientService::pretty).toList());
                updateChatRules(filterSubPath(newRulings, "/chats/"));
                updateUserRules(filterSubPath(newRulings, "/users/"));
            }
        };
    }

    private void updateChatRules(List<Ruling> chats) {
        Log.i(TAG, "[chat] Updating chat rules: " + chats.stream().map(GuardClientService::pretty).collect(Collectors.toList()));
        RulingSettings.of(CHAT_RULINGS).writeRulings(chats);
    }

    private void updateUserRules(List<Ruling> users) {
        Log.i(TAG, "[user] Updating user rules: " + users.stream().map(GuardClientService::pretty).collect(Collectors.toList()));
        RulingSettings.of(USER_RULINGS).writeRulings(users);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "Guard Client unbounded: " + intent);
        return super.onUnbind(intent);
    }

    private static final String TAG = "GuardClient";

    private List<Ruling> filterSubPath(List<Ruling> rulings, String subPath) {
        List<Ruling> filtered = new ArrayList<>();
        for (Ruling it : rulings) {
            if (it.path.startsWith(subPath)) {
                Ruling newRuling = new Ruling();
                newRuling.path = it.path.substring(subPath.length());
                newRuling.action = it.action;
                if (!newRuling.path.contains("/")) {
                    filtered.add(newRuling);
                }
            }
        }
        return filtered;
    }

    private static String pretty(Ruling ruling) {
        return "{" + ruling.path + ": " + ruling.action + "}";
    }
}