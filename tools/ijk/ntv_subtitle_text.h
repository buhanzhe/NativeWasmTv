/* nTv, LGPL-2.1-or-later. Plain text for the Java subtitle overlay. */
#include <stddef.h>
#include <string.h>
static size_t ntv_subtitle_text(const char *ass, char *output, size_t capacity)
{
    size_t used = 0;
    if (!output || !capacity) return 0;
    output[0] = 0;
    if (!ass) return 0;
    const char *text = ass;
    int fields = 8; /* FFmpeg AVSubtitleRect: ReadOrder,Layer,Style,...,Effect,Text */
    if (!strncmp(text, "Dialogue:", 9)) { text += 9; fields = 9; }
    for (int i = 0; i < fields; i++) {
        text = strchr(text, ',');
        if (!text) return 0;
        text++;
    }
    while (*text && used + 1 < capacity) {
        if (*text == '{') {
            const char *end = strchr(text, '}');
            if (end) { text = end + 1; continue; }
        }
        if (*text == '\\' && text[1]) {
            if (text[1] == 'N' || text[1] == 'n') { output[used++] = '\n'; text += 2; continue; }
            if (text[1] == 'h') { output[used++] = ' '; text += 2; continue; }
        }
        output[used++] = *text++;
    }
    // Do not return half a UTF-8 codepoint when the bounded buffer fills.
    if (*text && used) {
        size_t start = used - 1;
        while (start && ((unsigned char)output[start] & 0xc0) == 0x80) start--;
        unsigned char first = output[start];
        size_t count = first >= 0xf0 ? 4 : first >= 0xe0 ? 3 : first >= 0xc0 ? 2 : 1;
        if (start + count > used) used = start;
    }
    output[used] = 0;
    return used;
}
