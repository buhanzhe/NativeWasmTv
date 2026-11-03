"""Add optional PES-boundary H5E JNI calls without changing the existing whole-TS API."""
from pathlib import Path
import argparse

def patch(source):
    if 'NativeH5eDecryptor_supportsStreamingTransportStream' in source:
        return source
    old = 'static int decrypt_transport_stream(uint8_t* output, const uint8_t* input, size_t length) {'
    assert old in source, 'Unexpected H5E transport entry'
    source = source.replace(old, old.replace('size_t length)', 'size_t length, int first_part)'))
    start = source.index('static int decrypt_transport_stream(')
    end = source.index('JNIEXPORT jbyteArray', start)
    body = source[start:end]
    body = body.replace('gettimeofday(&g_worker_now, NULL);', 'if (first_part) gettimeofday(&g_worker_now, NULL);')
    body = body.replace('if (g_session_started) {', 'if (first_part && g_session_started) {')
    body = body.replace('    return 0;\n', '    return -1;\n', 1)
    source = source[:start] + body + source[end:]
    start = source.index('JNIEXPORT jbyteArray')
    end = source.index('JNIEXPORT void', start)
    whole = source[start:end]
    assert '(size_t) length);' in whole
    whole = whole.replace('(size_t) length);', '(size_t) length, 1);').replace('if (!decrypted)', 'if (decrypted <= 0)')
    part = whole.replace('NativeH5eDecryptor_decryptTransportStream(', 'NativeH5eDecryptor_decryptTransportStreamPart(')
    part = part.replace('jbyteArray transport_stream)', 'jbyteArray transport_stream, jboolean first_part)')
    part = part.replace('  g_decrypt_generation = __sync_add_and_fetch(&g_cancel_generation, 0);', '''  if (first_part) g_stream_part_active = 1;
  if (!g_stream_part_active) return NULL;
  if (first_part) g_decrypt_generation = __sync_add_and_fetch(&g_cancel_generation, 0);
  if (g_decrypt_generation != __sync_add_and_fetch(&g_cancel_generation, 0)) return NULL;''')
    part = part.replace('(size_t) length, 1);', '(size_t) length, first_part);')
    # PAT/PMT/audio prefixes are valid chunks with no encrypted video NAL.
    part = part.replace('if (decrypted <= 0)', 'if (decrypted < 0)')
    part = part.replace('    trapped = 1;', '    trapped = 1;\n    g_stream_part_active = 0;')
    # A legacy whole-segment call invalidates any preceding streaming session.
    whole = whole.replace('  (void) clazz;', '  (void) clazz;\n  g_stream_part_active = 0;', 1)
    part = part.replace('  length = (*env)->GetArrayLength(env, transport_stream);', '''  length = (*env)->GetArrayLength(env, transport_stream);
  if (length == 0 || length % TS_PACKET_SIZE != 0) {
    g_stream_part_active = 0;
    return NULL;
  }''')
    probe = '''JNIEXPORT jboolean JNICALL
Java_com_bu_cc_tv_NativeH5eDecryptor_supportsStreamingTransportStream(JNIEnv* env, jclass clazz) {
  (void) env; (void) clazz; return JNI_TRUE;
}

'''
    source = source[:start] + whole + probe + part + source[end:]
    marker = 'static __thread int g_session_started;'
    assert marker in source
    source = source.replace(marker, marker + '\nstatic __thread int g_stream_part_active;')
    release = source.index('Java_com_bu_cc_tv_NativeH5eDecryptor_releaseThreadContext')
    source = source[:release] + source[release:].replace('  (void) clazz;', '  (void) clazz;\n  g_stream_part_active = 0;', 1)
    return source

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    args = parser.parse_args()
    old = args.source.read_text(encoding='utf-8')
    updated = patch(old)
    if updated != old:
        args.source.write_text(updated, encoding='utf-8', newline='\n')
    print('H5E optional streaming API ready:', args.source)
