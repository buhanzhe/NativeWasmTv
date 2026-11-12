package xiao.bu.tv;

import android.content.SharedPreferences;

/** Preserve numeric preferences imported from older builds with different storage types. */
final class PreferenceNumbers {
    static float readFloat(SharedPreferences preferences,String key,float fallback){
        float value;
        try{value=preferences.getFloat(key,fallback);}
        catch(ClassCastException legacy){
            Object raw=preferences.getAll().get(key);
            if(raw instanceof Number)value=((Number)raw).floatValue();
            else if(raw instanceof String){
                try{value=Float.parseFloat(((String)raw).trim());}
                catch(NumberFormatException invalid){return fallback;}
            }else return fallback;
        }
        return Float.isNaN(value)||Float.isInfinite(value)?fallback:value;
    }
    private PreferenceNumbers(){}
}
