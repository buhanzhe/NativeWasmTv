package xiao.bu.tv;

import android.content.SharedPreferences;

/** Only explicit choices are written; retries and changing playlist order cannot overwrite them. */
final class ChannelPlaybackMemory {
    private final SharedPreferences preferences;
    ChannelPlaybackMemory(SharedPreferences preferences) { this.preferences=preferences; }
    private static String key(String channel,String kind) {
        return "channel_memory_"+kind+"_"+android.net.Uri.encode(channel);
    }
    void rememberSource(String channel,String source) {
        if(source!=null)preferences.edit().putString(key(channel,"source"),source).apply();
    }
    int source(String channel,Channel value) {
        String source=preferences.getString(key(channel,"source"),"");
        for(int i=0;i<value.sourceCount();i++)if(source.equals(value.sourceUrl(i)))return i;
        return 0;
    }
    void rememberDecode(String channel,String mode) {
        preferences.edit().putString(key(channel,"decode"),mode).apply();
    }
    String decode(String channel,String fallback) {
        String value=preferences.getString(key(channel,"decode"),fallback);
        return "auto".equals(value)||"hardware".equals(value)||"software".equals(value)?value:fallback;
    }
    void rememberUserAgent(String channel,String mode) {
        preferences.edit().putString(key(channel,"ua"),mode).apply();
    }
    String userAgent(String channel,String fallback) {
        String value=preferences.getString(key(channel,"ua"),fallback);
        return WebSiteProfile.validUserAgentMode(value)?value:fallback;
    }
    void rememberEngine(String channel,String engine) {
        preferences.edit().putString(key(channel,"engine"),engine).apply();
    }
    String engine(String channel,String fallback,boolean liteSupported) {
        if(!liteSupported)return "system";
        String value=preferences.getString(key(channel,"engine"),fallback);
        return "system".equals(value)||"lite".equals(value)?value:fallback;
    }
}
