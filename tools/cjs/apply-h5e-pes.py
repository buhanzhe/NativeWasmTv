"""Repair H5E TS repacking when the decrypted PES fits in its first packet."""
from pathlib import Path


def patch(source):
    start = source.index('static int write_pes_payload(')
    end = source.index('static int decrypt_pes(', start)
    writer = Path(__file__).with_name('h5e-pes-writer.h').read_text(encoding='utf-8')
    writer = writer[writer.index('static int write_pes_payload('):].strip()
    return source[:start] + writer + '\n\n' + source[end:]
