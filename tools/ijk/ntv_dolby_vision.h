/* nTv additions, LGPL-2.1-or-later. Do not equate a HEVC decoder with DV. */
#ifndef NTV_DOLBY_VISION_H
#define NTV_DOLBY_VISION_H
#include "libavformat/avformat.h"
#include "libavutil/common.h"
#include <stdlib.h>
static int ntv_dovi_stream(AVStream *st)
{
    if (!st || st->codecpar->codec_type != AVMEDIA_TYPE_VIDEO) return 0;
    unsigned tag = st->codecpar->codec_tag;
    return av_dict_get(st->metadata, "ntv_dovi_profile", NULL, 0)
        || tag == MKTAG('d','v','h','e') || tag == MKTAG('d','v','h','1')
        || tag == MKTAG('d','v','a','v') || tag == MKTAG('d','v','a','1');
}
static int ntv_dovi_value(AVStream *st, const char *key)
{
    AVDictionaryEntry *entry = av_dict_get(st->metadata, key, NULL, 0);
    return entry ? atoi(entry->value) : -1;
}
#endif
