package xiao.bu.tv.jar;

import android.content.Context;
import java.io.File;
import org.json.JSONObject;

/** Optional CPython runtime loaded from an installed plugin. */
public interface PythonPlugin {
    void initialize(Context context, File archive) throws Exception;
    JSONObject invoke(JSONObject request) throws Exception;
}
