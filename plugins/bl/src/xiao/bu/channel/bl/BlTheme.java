package xiao.bu.channel.bl;

import android.content.res.Configuration;

enum BlTheme {
    AUTO("自动",0,0,0,0,0,false),
    BLACK("黑色",0xff303030,0xff232630,0xffeeeeee,0xffcccccc,0xffbb86fc,true),
    WHITE("白色",0xfff5f6fa,0xffe7eaf1,0xff202535,0xff4e5869,0xff59429a,false),
    CLASSIC("经典主题",0xff32162c,0xff53273e,0xfffff4f8,0xffe9c8d7,0xffff91b2,true),
    PINK("粉色",0xfffce7ef,0xfff3d3e2,0xff432536,0xff684657,0xff953957,false),
    BLUE("蓝色",0xffe1edff,0xffcfdef4,0xff203653,0xff465b78,0xff315a98,false),
    PURPLE("紫色",0xffeee5fb,0xffe0d0f2,0xff362445,0xff5f496e,0xff70469a,false),
    RED("红色",0xfffce7e3,0xfff4d7d1,0xff4b282a,0xff6e4647,0xff9b3a3e,false);

    final String label;
    final int background, surface, text, muted, accent;
    final boolean dark;

    BlTheme(String label,int background,int surface,int text,int muted,int accent,boolean dark){
        this.label=label;
        this.background=background;
        this.surface=surface;
        this.text=text;
        this.muted=muted;
        this.accent=accent;
        this.dark=dark;
    }

    static BlTheme saved(String value){
        try{return valueOf(value);}catch(IllegalArgumentException|NullPointerException ignored){return BLACK;}
    }

    BlTheme resolve(Configuration configuration){
        if(this!=AUTO)return this;
        return (configuration.uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES?BLACK:WHITE;
    }

    int tint(int opacity){return (accent&0x00ffffff)|(opacity<<24);}
}
