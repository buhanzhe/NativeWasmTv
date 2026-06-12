package com.bu.cc.tv;

final class Channel {
    final String number;
    final String name;
    final String streamId;
    final String url;
    final String yangshipinPid;
    final String yangshipinStreamId;

    Channel(String number, String name, String streamId, String url,
            String yangshipinPid, String yangshipinStreamId) {
        this.number = number;
        this.name = name;
        this.streamId = streamId;
        this.url = url;
        this.yangshipinPid = yangshipinPid;
        this.yangshipinStreamId = yangshipinStreamId;
    }
}
