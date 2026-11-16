package xiao.bu.tv;
import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import org.json.JSONObject;
/** Compile-only view surface; unavailable editions never create a Lite canvas. */
final class LiteCanvas extends View {
    LiteEngine engine;
    int fixedViewportWidth,fixedViewportHeight;
    LiteCanvas(Context context) { super(context); }
    void frame(JSONObject value) {}
    void textInput(JSONObject value) {}
    void endTextInput() {}
    boolean editingText() { return false; }
    boolean textInputKey(KeyEvent event) { return false; }
}
