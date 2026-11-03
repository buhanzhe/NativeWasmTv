/* Preserve the PES header and PCR when a decrypted access unit shrinks to
 * less than the first TS packet's capacity. Used by the H5E build patch. */
static int write_pes_payload(uint8_t* output, pes_stream* pes, const uint8_t* bytes, size_t length) {
  size_t consumed = 0;
  size_t slot_index;
  size_t total_capacity = 0;
  for (slot_index = 0; slot_index < pes->slot_count; slot_index++) {
    total_capacity += pes->slots[slot_index].payload_length;
  }
  if (length > total_capacity) {
    LOGW("Decrypted PES grew beyond TS capacity: %u > %u", (unsigned int) length, (unsigned int) total_capacity);
    return 0;
  }
  for (slot_index = 0; slot_index < pes->slot_count; slot_index++) {
    packet_slot* slot = &pes->slots[slot_index];
    uint8_t* packet = output + slot->packet_offset;
    size_t available = length - consumed;
    if (available >= slot->payload_length) {
      memcpy(packet + slot->payload_offset, bytes + consumed, slot->payload_length);
      consumed += slot->payload_length;
      continue;
    }
    /* The first slot excludes the PES header from its writable ES bytes.
     * Moving only the ES to the end used to overwrite that header with TS
     * stuffing, leaving PUSI=1 followed by a raw NAL (Stagefright -1007). */
    size_t header_length = slot->packet_offset == pes->header_packet_offset
        ? pes->header_length : 0;
    size_t payload_length = header_length + available;
    size_t adaptation_length = 183 - payload_length;
    size_t old_adaptation = (packet[3] & 0x20) ? packet[4] : 0;
    if (header_length) {
      size_t offset = TS_PACKET_SIZE - payload_length;
      memmove(packet + offset, packet + pes->header_payload_offset, header_length);
      pes->header_payload_offset = offset;
    }
    packet[3] = (packet[3] & 0xcf) | 0x30;
    packet[4] = (uint8_t) adaptation_length;
    if (adaptation_length > 0) {
      /* Existing adaptation flags/PCR/OPCR stay byte-exact. Only the new
       * stuffing area is filled; the adaptation field can only grow here. */
      size_t preserved = old_adaptation ? old_adaptation : 1;
      if (!old_adaptation) packet[5] = 0;
      if (adaptation_length > preserved)
        memset(packet + 5 + preserved, 0xff, adaptation_length - preserved);
    }
    if (available) memcpy(packet + TS_PACKET_SIZE - available, bytes + consumed, available);
    consumed += available;
    /* Retain payload-present/continuity even for an exhausted continuation
     * packet: 183 adaptation bytes and zero payload, as in the existing API. */
  }
  if (consumed != length) return 0;
  if (pes->packet_length > 0 && pes->header_length >= 6
      && pes->packet_length >= pes->header_length - 6) {
    size_t updated_length = length + pes->header_length - 6;
    if (updated_length <= 0xffff) {
      uint8_t* header = output + pes->header_packet_offset + pes->header_payload_offset;
      header[4] = (uint8_t) ((updated_length >> 8) & 0xff);
      header[5] = (uint8_t) (updated_length & 0xff);
    }
  }
  return 1;
}
