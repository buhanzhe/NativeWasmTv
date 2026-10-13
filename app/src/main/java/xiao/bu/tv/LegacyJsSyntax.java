package xiao.bu.tv;

import java.util.regex.Pattern;

/** Conservative lexical preflight. Unchanged scripts still use WebView's parser. */
final class LegacyJsSyntax {
    private static final Pattern MODERN=Pattern.compile("=>|\\.\\.\\.|\\?\\.|\\?\\?|\\*\\*|&&=|\\|\\|=|\\b(?:let|const|class|import|export|async|await|yield)\\b|\\bnew\\s*\\.\\s*target\\b|\\b0[bBoO][0-9]|\\b[0-9][0-9_]*n\\b|[0-9]_[0-9]|[,{]\\s*[$a-zA-Z_][\\w$]*\\s*[,}]|[,{]\\s*[$a-zA-Z_][\\w$]*\\s*\\(|\\[[^]\\r\\n]*]\\s*[:=]|\\bvar\\s*\\[|\\bfunction\\s*\\*|\\bfunction\\b[^({]*\\([^)]*(?:=[^=]|\\{|\\[)|/[^/\\r\\n]+/[a-z]*[uyvsd]");
    static boolean needsCompilation(String source){
        if(source.contains("\\u{")||source.contains("\\p{")||source.contains("\\P{"))return true;
        StringBuilder code=new StringBuilder(source.length());
        for(int i=0;i<source.length();i++){
            char c=source.charAt(i);
            if(c=='`')return true;
            if(c=='\''||c=='"'){char quote=c;code.append("__string__");while(++i<source.length()){c=source.charAt(i);if(c=='\\')i++;else if(c==quote)break;}continue;}
            if(c=='/'&&i+1<source.length()){
                char next=source.charAt(i+1);
                if(next=='/'){i+=2;while(i<source.length()&&source.charAt(i)!='\n')i++;code.append('\n');continue;}
                if(next=='*'){int end=source.indexOf("*/",i+2);if(end<0)return true;i=end+1;code.append(' ');continue;}
            }
            code.append(c);
        }
        // Parse strict function-bearing scripts even when there is no other ES2015 token.
        // Use raw text: regex literals can contain quotes that confuse this cheap lexer.
        // The shared AST preflight decides whether a declaration really needs lowering.
        boolean strict=source.contains("\"use strict\"")||source.contains("'use strict'");
        return (strict&&source.contains("function"))||MODERN.matcher(code).find();
    }
    private LegacyJsSyntax(){}
}
