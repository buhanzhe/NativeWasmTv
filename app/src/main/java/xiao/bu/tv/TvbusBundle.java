package xiao.bu.tv;

import android.content.Context;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;

/** Explicitly imported, immutable TVBus JAR bundles; no Python or native engine in the host APK. */
final class TvbusBundle {
    static final int LIMIT = 32 * 1024 * 1024;
    static final class Installed {
        final File jar, nativeFile;
        final String digest, version, origin;
        final JSONObject defaults;
        Installed(File jar, File nativeFile, String digest, String version, JSONObject defaults, String origin) {
            this.jar=jar; this.nativeFile=nativeFile; this.digest=digest; this.version=version; this.defaults=defaults; this.origin=origin;
        }
    }
    private static Installed cached;
    static synchronized Installed installed(Context context) throws Exception {
        String digest = context.getSharedPreferences("tvbus-bundle", 0).getString("digest", "");
        if (digest.isEmpty()) return null;
        if (!digest.matches("[a-f0-9]{64}")) throw new IOException("TVBus 插件记录损坏");
        File dir = new File(context.getFilesDir(), "tvbus-bundles/" + digest);
        if (cached != null && cached.jar.getParentFile().equals(dir) && cached.digest.equals(digest)) return cached;
        File jar = new File(dir, "plugin.jar");
        byte[] bytes = read(new FileInputStream(jar), LIMIT);
        if (!digest.equals(sha(bytes))) throw new IOException("TVBus JAR 插件校验失败，请重新导入");
        return cached = unpack(context, bytes, digest);
    }
    static synchronized String install(Context context, byte[] bytes) throws Exception {
        if (bytes.length > LIMIT) throw new IOException("TVBus JAR 插件超过 32 MB");
        String digest = sha(bytes);
        Installed incoming = unpack(context, bytes, digest);
        if (!context.getSharedPreferences("tvbus-bundle", 0).edit().putString("digest", digest).commit())
            throw new IOException("TVBus 插件激活失败");
        cached = incoming;
        return incoming.version;
    }
    private static Installed unpack(Context context, byte[] bytes, String digest) throws Exception {
        String entryName = "native/" + BuildConfig.CJS_PLUGIN_ABI + "/libtvcore.so";
        byte[] nativeBytes = null, dex = null, metadata = null;
        Set<String> names = new HashSet<>(); int totalBytes=0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            int entries=0;
            for (ZipEntry entry; (entry=zip.getNextEntry())!=null;) {
                if (++entries>40 || !names.add(entry.getName())) throw new IOException("TVBus JAR 目录无效");
                if (entry.isDirectory()) continue;
                byte[] data=readEntry(zip,entry.getName().equals("tvbus-plugin.json")?16384:16*1024*1024);
                totalBytes+=data.length;if(totalBytes>LIMIT)throw new IOException("TVBus JAR 解压内容过大");
                if (entry.getName().equals(entryName)) nativeBytes=data;
                else if (entry.getName().equals("classes.dex")) { if(data.length>2*1024*1024)throw new IOException("TVBus DEX 过大");dex=data; }
                else if (entry.getName().equals("tvbus-plugin.json")) metadata=data;
            }
        }
        if (nativeBytes==null) throw new IOException("TVBus 插件不支持当前设备架构 " + BuildConfig.CJS_PLUGIN_ABI);
        if (dex==null || dex.length<112 || dex[0]!='d' || dex[1]!='e' || dex[2]!='x' || metadata==null)
            throw new IOException("不是有效 TVBus JAR 插件");
        TvbusPlugin.validate(nativeBytes);
        JSONObject info = new JSONObject(new String(metadata,"UTF-8"));
        if (info.optInt("api",0)!=1 || !"tvbus".equals(info.optString("type",""))) throw new IOException("TVBus JAR 接口不兼容");
        String nativeSha=sha(nativeBytes);
        if (!nativeSha.equals(info.getJSONObject("engines").optString(BuildConfig.CJS_PLUGIN_ABI,"")))
            throw new IOException("TVBus JAR 引擎校验失败");
        if (!sha(dex).equals(info.optString("dexSha256",""))) throw new IOException("TVBus JAR DEX 校验失败");
        File dir=new File(context.getFilesDir(),"tvbus-bundles/"+digest);
        if (!dir.isDirectory()&&!dir.mkdirs()) throw new IOException("TVBus 插件目录创建失败");
        File jar=new File(dir,"plugin.jar"), nativeFile=new File(dir,"libtvcore-"+nativeSha+".so");
        save(jar,bytes,false); save(nativeFile,nativeBytes,true);
        JSONObject defaults=info.optJSONObject("defaults");
        JSONObject urls=info.optJSONObject("engineUrls");
        String origin=urls==null?"":urls.optString(BuildConfig.CJS_PLUGIN_ABI,"");
        if(!origin.isEmpty())TvbusLiveConfig.online(origin);
        return new Installed(jar,nativeFile,digest,info.optString("version","1"),defaults==null?new JSONObject():defaults,origin);
    }
    private static void save(File target, byte[] bytes, boolean executable) throws Exception {
        if (target.isFile() && sha(bytes).equals(sha(read(new FileInputStream(target),LIMIT)))) return;
        File temp=File.createTempFile("install-",".tmp",target.getParentFile());
        try {
            try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);out.getFD().sync();}
            if (executable&&!temp.setExecutable(true,true)) throw new IOException("TVBus 插件权限设置失败");
            if (!temp.setReadOnly()||!temp.renameTo(target)) throw new IOException("TVBus 插件保存失败");
        } finally {temp.delete();}
    }
    private static byte[] readEntry(InputStream input,int limit)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=input.read(buffer))!=-1){if(out.size()+n>limit)throw new IOException("TVBus JAR 条目过大");out.write(buffer,0,n);}return out.toByteArray();
    }
    private static byte[] read(InputStream input,int limit)throws IOException {try(InputStream in=input){return readEntry(in,limit);}}
    static String sha(byte[] bytes)throws Exception {StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.US,"%02x",b&255));return out.toString();}
}
