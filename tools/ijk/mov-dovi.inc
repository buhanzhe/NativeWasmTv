/* Parse the Dolby configuration record independently from the HEVC avcC/hvcC.
 * Keep it in stream metadata so the Android pipeline can choose a DV decoder.
 */
static int mov_read_ntv_dovi(MOVContext *c, AVIOContext *pb, MOVAtom atom)
{
    if (c->fc->nb_streams < 1 || atom.size < 4) return AVERROR_INVALIDDATA;
    AVStream *st = c->fc->streams[c->fc->nb_streams - 1];
    avio_r8(pb); /* major */
    avio_r8(pb); /* minor */
    int profile = avio_r8(pb);
    int flags = avio_r8(pb);
    av_dict_set_int(&st->metadata, "ntv_dovi_profile", profile >> 1, 0);
    av_dict_set_int(&st->metadata, "ntv_dovi_level", ((profile & 1) << 5) | (flags >> 3), 0);
    av_dict_set_int(&st->metadata, "ntv_dovi_el", (flags >> 1) & 1, 0);
    avio_skip(pb, atom.size - 4);
    return 0;
}
