package xiao.bu.tv;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Request-time metadata: unlike a navigator patch, this precedes the first GET. */
final class UserScriptRequestPolicy {
    static String userAgent(String scripts, String page) {
        try {
            URI uri = new URI(page);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return "";
            JSONArray entries = new JSONArray(scripts);
            String selected = "";
            for (int i=0;i<entries.length();i++) {
                JSONObject item=entries.optJSONObject(i);
                if(item==null || !item.optBoolean("enabled",true)) continue;
                List<String> matches=new ArrayList<>(), includes=new ArrayList<>(), excludes=new ArrayList<>(), excludeMatches=new ArrayList<>();
                String agent=""; boolean metadata=false;
                for(String raw:item.optString("source","").split("\n")) {
                    String line=raw.trim();
                    if(line.equals("// ==UserScript==")){metadata=true;continue;}
                    if(line.equals("// ==/UserScript=="))break;
                    if(!metadata || !line.startsWith("//"))continue;
                    String[] pair=line.substring(2).trim().split("\\s+",2);
                    if(pair.length!=2)continue;
                    switch(pair[0]) {
                        case "@match":matches.add(pair[1]);break;
                        case "@include":includes.add(pair[1]);break;
                        case "@exclude":excludes.add(pair[1]);break;
                        case "@exclude-match":excludeMatches.add(pair[1]);break;
                        case "@user-agent":agent=pair[1];break;
                    }
                }
                // A request override requires an explicit scope and printable HTTP value.
                if(agent.isEmpty() || agent.length()>1024 || !agent.matches("[\\x20-\\x7e]+"))continue;
                if(matches.isEmpty() && includes.isEmpty())continue;
                if(any(excludes,page,uri,false)||any(excludeMatches,page,uri,true))continue;
                if(any(matches,page,uri,true)||any(includes,page,uri,false))selected=agent;
            }
            return selected;
        } catch(Exception ignored) { return ""; }
    }
    private static boolean any(List<String> patterns,String page,URI uri,boolean match) {
        for(String p:patterns)if(match?matches(p,uri):glob(p,page))return true;
        return false;
    }
    private static boolean matches(String p,URI uri) {
        if(p.equals("<all_urls>"))return true;
        java.util.regex.Matcher m=Pattern.compile("^(\\*|https?|ftp|file)://([^/]*)(/.*)$").matcher(p);
        if(!m.matches())return false;
        String scheme=uri.getScheme().toLowerCase(java.util.Locale.US),host=uri.getHost();
        if(host==null || !(m.group(1).equals("*") || m.group(1).equals(scheme)))return false;
        String want=m.group(2).toLowerCase(java.util.Locale.US);host=host.toLowerCase(java.util.Locale.US);
        if(!want.equals("*")&&!host.equals(want)) {
            if(!want.startsWith("*."))return false;
            String base=want.substring(2);if(!host.equals(base)&&!host.endsWith("."+base))return false;
        }
        return glob(m.group(3),(uri.getRawPath().isEmpty()?"/":uri.getRawPath())+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery()));
    }
    private static boolean glob(String p,String value) {
        StringBuilder regex=new StringBuilder("^");
        for(String part:p.split("\\*",-1)){if(regex.length()>1)regex.append(".*");regex.append(Pattern.quote(part));}
        return value.matches(regex.append('$').toString());
    }
    private UserScriptRequestPolicy() {}
}
