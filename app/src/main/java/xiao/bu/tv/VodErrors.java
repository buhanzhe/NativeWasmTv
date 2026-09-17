package xiao.bu.tv;

import org.json.JSONObject;

/** Never expose upstream exception messages: they may contain subscription credentials. */
final class VodErrors {
    /** Fixed application validation text only; never wrap upstream exception messages. */
    static final class Input extends java.io.IOException {
        Input(String message) { super(message); }
    }
    static String json(String action, Exception error) throws Exception {
        if (error instanceof Input) return new JSONObject().put("ok", false).put("code", "INPUT")
                .put("message", error.getMessage()).toString();
        String code = "INTERNAL", hint = "程序处理失败";
        Throwable cause = error;
        for (int i = 0; i < 8 && cause != null; i++, cause = cause.getCause()) {
            if (cause instanceof javax.net.ssl.SSLException) {
                code = "TLS"; hint = "HTTPS 握手失败，可能是旧系统 TLS 兼容问题"; break;
            }
            if (cause instanceof java.net.UnknownHostException) {
                code = "DNS"; hint = "域名解析失败"; break;
            }
            if (cause instanceof java.net.SocketTimeoutException) {
                code = "TIMEOUT"; hint = "网络请求超时"; break;
            }
            if (cause instanceof java.net.ConnectException) {
                code = "CONNECT"; hint = "无法连接源站"; break;
            }
            if (cause instanceof org.json.JSONException) {
                code = "JSON"; hint = "返回内容不是预期 JSON，或缺少必要字段"; break;
            }
            if (cause instanceof java.util.regex.PatternSyntaxException) {
                code = "REGEX"; hint = "系统正则表达式兼容错误"; break;
            }
            if (cause instanceof java.io.IOException) { code = "IO"; hint = "网络读写或本地保存失败"; }
        }
        String operation = "subscribe".equals(action) ? "订阅导入" : "search".equals(action) ? "搜索"
                : "detail".equals(action) ? "详情获取" : "import".equals(action) ? "剧集投递" : "点播操作";
        return new JSONObject().put("ok", false).put("code", code)
                .put("exception", error.getClass().getSimpleName())
                .put("message", operation + "失败 [" + code + "]：" + hint
                        + "（" + error.getClass().getSimpleName() + "）").toString();
    }
}
