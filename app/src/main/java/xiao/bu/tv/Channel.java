package xiao.bu.tv;

final class Channel {
    final String number;
    final String name;
    final String streamId;
    final String url;
    final String[] urls;
    final String yangshipinPid;
    final String yangshipinStreamId;
    final String yangshipinMaxDefinition;

    Channel(String number, String name, String streamId, String url,
            String yangshipinPid, String yangshipinStreamId) {
        this(number, name, streamId, url, yangshipinPid, yangshipinStreamId, null);
    }

    Channel(String number, String name, String streamId, String url,
            String yangshipinPid, String yangshipinStreamId,
            String yangshipinMaxDefinition) {
        this(number, name, streamId,
                url == null ? new String[0] : new String[] { url },
                yangshipinPid, yangshipinStreamId, yangshipinMaxDefinition);
    }

    private Channel(String number, String name, String streamId, String[] urls,
            String yangshipinPid, String yangshipinStreamId,
            String yangshipinMaxDefinition) {
        this.number = number;
        this.name = name;
        this.streamId = streamId;
        this.urls = urls;
        this.url = urls.length == 0 ? null : urls[0];
        this.yangshipinPid = yangshipinPid;
        this.yangshipinStreamId = yangshipinStreamId;
        this.yangshipinMaxDefinition = yangshipinMaxDefinition;
    }

    Channel withAdditionalUrl(String additionalUrl) {
        for (String existing : urls) {
            if (existing.equals(additionalUrl)) {
                return this;
            }
        }
        String[] combined = new String[urls.length + 1];
        System.arraycopy(urls, 0, combined, 0, urls.length);
        combined[urls.length] = additionalUrl;
        return new Channel(number, name, streamId, combined,
                yangshipinPid, yangshipinStreamId, yangshipinMaxDefinition);
    }

    int sourceCount() {
        return urls.length;
    }

    String sourceUrl(int index) {
        if (urls.length == 0) {
            return null;
        }
        int wrapped = (index % urls.length + urls.length) % urls.length;
        return urls[wrapped];
    }
}
