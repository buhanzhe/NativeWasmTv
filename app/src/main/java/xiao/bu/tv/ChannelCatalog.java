package xiao.bu.tv;

final class ChannelCatalog {
    static final int SOURCE_CCTV_WEB = 0;
    static final int SOURCE_YSP_CCTV = 1;
    static final int SOURCE_YSP_SATELLITE = 2;

    private static final String STREAM_BASE =
            "https://ldocctvwbcdbyte.volcfcdn.com/ldocctvwbcd/";
    private static final String BITRATE_RANGE = "?b=200-4000";

    static final Channel[] CCTV_CHANNELS = new Channel[] {
            channel("1", "CCTV-1 综合", "cctv1", "600001859", "2024078201"),
            channel("2", "CCTV-2 财经", "cctv2", "600001800", "2024075401"),
            channel("3", "CCTV-3 综艺", "cctv3", "600001801", "2024068501"),
            channel("4", "CCTV-4 中文国际（亚）", "cctv4", "600001814", "2029797101"),
            channel("5", "CCTV-5 体育", "cctv5", "600001818", "2024078401"),
            channel("5+", "CCTV-5+ 体育赛事", "cctv5plus", "600001817", "2024078001"),
            channel("6", "CCTV-6 电影", "cctv6", "600108442", "2013693901"),
            channel("7", "CCTV-7 国防军事", "cctv7", "600004092", "2024072001"),
            channel("8", "CCTV-8 电视剧", "cctv8", "600001803", "2029793001"),
            channel("9", "CCTV-9 纪录", "cctvjilu", "600004078", "2024078601"),
            channel("10", "CCTV-10 科教", "cctv10", "600001805", "2024078701"),
            channel("11", "CCTV-11 戏曲", "cctv11", "600001806", "2027248701"),
            channel("12", "CCTV-12 社会与法", "cctv12", "600001807", "2027248801"),
            channel("13", "CCTV-13 新闻", "cctv13", "600001811", "2029797201"),
            channel("14", "CCTV-14 少儿", "cctvchild", "600001809", "2027248901"),
            channel("15", "CCTV-15 音乐", "cctv15", "600001815", "2027249001"),
            channel("16", "CCTV-16 奥林匹克", "cctv16", "600098637", "2027249101"),
            channel("17", "CCTV-17 农业农村", "cctv17", "600001810", "2027249401"),
            channel("4欧", "CCTV-4 中文国际（欧）", "cctveurope", null, null),
            channel("4美", "CCTV-4 中文国际（美）", "cctvamerica", null, null)
    };

    static final Channel[] CHANNELS = CCTV_CHANNELS;

    // Keep this order and naming aligned with https://www.yangshipin.cn/tv/home.
    // The first group above remains the smaller CCTV.com fallback catalog.
    static final Channel[] YANGSHIPIN_CCTV_CHANNELS = new Channel[] {
            yangshipinChannel("1", "CCTV1", "600001859", "2024078201"),
            yangshipinChannel("2", "CCTV2", "600001800", "2024075401"),
            yangshipinChannel("3", "CCTV3", "600001801", "2024068501"),
            yangshipinChannel("4", "CCTV4", "600001814", "2029797101"),
            yangshipinChannel("5", "CCTV5", "600001818", "2024078401"),
            yangshipinChannel("6", "CCTV5+", "600001817", "2024078001"),
            yangshipinChannel("7", "CCTV6", "600108442", "2013693901"),
            yangshipinChannel("8", "CCTV7", "600004092", "2024072001"),
            yangshipinChannel("9", "CCTV8", "600001803", "2029793001"),
            yangshipinChannel("10", "CCTV9", "600004078", "2024078601"),
            yangshipinChannel("11", "CCTV10", "600001805", "2024078701"),
            yangshipinChannel("12", "CCTV11", "600001806", "2027248701"),
            yangshipinChannel("13", "CCTV12", "600001807", "2027248801"),
            yangshipinChannel("14", "CCTV13", "600001811", "2029797201"),
            yangshipinChannel("15", "CCTV14", "600001809", "2027248901"),
            yangshipinChannel("16", "CCTV15", "600001815", "2027249001"),
            yangshipinChannel("17", "CCTV16-HD", "600098637", "2027249101"),
            yangshipinChannel("18", "CCTV16(4K）", "600099502", "2027249301"),
            yangshipinChannel("19", "CCTV17", "600001810", "2027249401"),
            yangshipinChannel("20", "CCTV4K", "600002264", "2029810301"),
            yangshipinChannel("21", "CCTV8K", "600156816", "2026774101"),
            yangshipinChannel("22", "CGTN", "600014550", "2024181701"),
            yangshipinChannel("23", "CGTN法语频道", "600084704", "2024181801"),
            yangshipinChannel("24", "CGTN俄语频道", "600084758", "2024181901"),
            yangshipinChannel("25", "CGTN阿拉伯语频道", "600084782", "2024182001"),
            yangshipinChannel("26", "CGTN西班牙语频道", "600084744", "2024182101"),
            yangshipinChannel("27", "CGTN外语纪录频道", "600084781", "2024182301")
    };

    static final Channel[] SATELLITE_CHANNELS = new Channel[] {
            yangshipinChannel("1", "北京卫视", "600002309", "2024052703"),
            yangshipinChannel("2", "江苏卫视", "600002521", "2024171103"),
            yangshipinChannel("3", "东方卫视", "600002483", "2024054503"),
            yangshipinChannel("4", "浙江卫视", "600002520", "2024054703"),
            yangshipinChannel("5", "湖南卫视", "600002475", "2024054803"),
            yangshipinChannel("6", "湖北卫视", "600002508", "2024171203"),
            yangshipinChannel("7", "广东卫视", "600002485", "2024060903"),
            yangshipinChannel("8", "广西卫视", "600002509", "2024060703"),
            yangshipinChannel("9", "黑龙江卫视", "600002498", "2029797003"),
            yangshipinChannel("10", "海南卫视", "600002506", "2024055603"),
            yangshipinChannel("11", "重庆卫视", "600002531", "2024061103"),
            yangshipinChannel("12", "深圳卫视", "600002481", "2024061303"),
            yangshipinChannel("13", "四川卫视", "600002516", "2024061403"),
            yangshipinChannel("14", "河南卫视", "600002525", "2029797303"),
            yangshipinChannel("15", "福建东南卫视", "600002484", "2024061503"),
            yangshipinChannel("16", "贵州卫视", "600002490", "2024061603"),
            yangshipinChannel("17", "江西卫视", "600002503", "2024061703"),
            yangshipinChannel("18", "辽宁卫视", "600002505", "2024171303"),
            yangshipinChannel("19", "安徽卫视", "600002532", "2024171403"),
            yangshipinChannel("20", "河北卫视", "600002493", "2024171503"),
            yangshipinChannel("21", "山东卫视", "600002513", "2029787903"),
            yangshipinChannel("22", "天津卫视", "600152137", "2019927003"),
            yangshipinChannel("23", "吉林卫视", "600190405", "2025561503"),
            yangshipinChannel("24", "陕西卫视", "600190400", "2029795103"),
            yangshipinChannel("25", "甘肃卫视", "600190408", "2025561703"),
            yangshipinChannel("26", "宁夏卫视", "600190737", "2025608503"),
            yangshipinChannel("27", "内蒙古卫视", "600190401", "2025561203"),
            yangshipinChannel("28", "云南卫视", "600190402", "2025561303"),
            yangshipinChannel("29", "山西卫视", "600190407", "2025560803"),
            yangshipinChannel("30", "青海卫视", "600190406", "2025559103"),
            yangshipinChannel("31", "西藏卫视", "600190403", "2025558003"),
            yangshipinChannel("32", "中国教育电视台1频道", "600171827", "2022823801"),
            yangshipinChannel("33", "新疆卫视", "600152138", "2019927403")
    };

    static final Group[] GROUPS = new Group[] {
            new Group("央视网 · 央视频道", SOURCE_CCTV_WEB, CCTV_CHANNELS),
            new Group("央视频 · 央视频道", SOURCE_YSP_CCTV, YANGSHIPIN_CCTV_CHANNELS),
            new Group("央视频 · 卫视频道", SOURCE_YSP_SATELLITE, SATELLITE_CHANNELS)
    };

    private ChannelCatalog() {
    }

    static int wrapGroupIndex(int index) {
        int size = GROUPS.length;
        return (index % size + size) % size;
    }

    static int wrapIndex(int index) {
        return wrapIndex(CHANNELS, index);
    }

    static int wrapIndex(Channel[] channels, int index) {
        int size = channels.length;
        return (index % size + size) % size;
    }

    static int indexOfNumber(String number) {
        return indexOfNumber(CHANNELS, number);
    }

    static int indexOfNumber(Channel[] channels, String number) {
        for (int index = 0; index < channels.length; index++) {
            if (channels[index].number.equals(number)) {
                return index;
            }
        }
        return 0;
    }

    static int defaultChannelIndex(Group group) {
        if (group.source == SOURCE_YSP_SATELLITE) {
            return 0;
        }
        if (group.source == SOURCE_YSP_CCTV) {
            return indexOfPid(group.channels, "600001811");
        }
        return indexOfNumber(group.channels, "13");
    }

    private static int indexOfPid(Channel[] channels, String pid) {
        for (int index = 0; index < channels.length; index++) {
            if (pid.equals(channels[index].yangshipinPid)) {
                return index;
            }
        }
        return 0;
    }

    static String preferHighBitrate(String url) {
        if (url == null) {
            return null;
        }
        if (url.contains("b=200-2100")) {
            return url.replace("b=200-2100", "b=200-4000");
        }
        if (url.indexOf('?') >= 0) {
            return url;
        }
        return url + BITRATE_RANGE;
    }

    private static Channel channel(String number, String name, String streamId,
            String yangshipinPid, String yangshipinStreamId) {
        return new Channel(number, name, streamId, streamUrl(streamId),
                yangshipinPid, yangshipinStreamId);
    }

    private static Channel yangshipinChannel(String number, String name,
            String yangshipinPid, String yangshipinStreamId) {
        return new Channel(number, name, "ysp_" + yangshipinPid, null,
                yangshipinPid, yangshipinStreamId);
    }

    private static String streamUrl(String streamId) {
        if ("cctv1".equals(streamId)) {
            return "https://ldncctvwbcdcnc.v.wscdns.com/ldncctvwbcd/"
                    + "cdrmldcctv1_1/index.m3u8" + BITRATE_RANGE;
        }
        if ("cctv3".equals(streamId)) {
            return "https://ldocctvwbcdks.v.kcdnvip.com/ldocctvwbcd/"
                    + "cdrmldcctv3_1/index.m3u8" + BITRATE_RANGE;
        }
        if ("cctv5".equals(streamId)) {
            return "https://ldcctvwbcdks.v.kcdnvip.com/ldcctvwbcd/"
                    + "cdrmldcctv5_1/index.m3u8" + BITRATE_RANGE;
        }
        if ("cctv5plus".equals(streamId)) {
            return "https://ldcctvwbcdtxy.liveplay.myqcloud.com/ldcctvwbcd/"
                    + "cdrmldcctv5plus_1/index.m3u8" + BITRATE_RANGE;
        }
        if ("cctv6".equals(streamId)) {
            return "https://ldocctvwbcdbd.a.bdydns.com/ldocctvwbcd/"
                    + "cdrmldcctv6_1/index.m3u8" + BITRATE_RANGE;
        }
        if ("cctv8".equals(streamId)) {
            return "https://ldocctvwbcdks.v.kcdnvip.com/ldocctvwbcd/"
                    + "cdrmldcctv8_1/index.m3u8" + BITRATE_RANGE;
        }
        if ("cctv13".equals(streamId)) {
            return "https://ldncctvwbcdbd.a.bdydns.com/ldncctvwbcd/"
                    + "cdrmldcctv13_1/index.m3u8" + BITRATE_RANGE;
        }
        if ("cctv16".equals(streamId)) {
            return "https://ldcctvwbcdks.v.kcdnvip.com/ldcctvwbcd/"
                    + "cdrmldcctv16_1/index.m3u8" + BITRATE_RANGE;
        }
        return STREAM_BASE + "cdrmld" + streamId + "_1/index.m3u8" + BITRATE_RANGE;
    }

    static final class Group {
        final String title;
        final int source;
        final Channel[] channels;

        Group(String title, int source, Channel[] channels) {
            this.title = title;
            this.source = source;
            this.channels = channels;
        }
    }
}
