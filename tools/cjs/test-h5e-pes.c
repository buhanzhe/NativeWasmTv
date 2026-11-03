#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#define TS_PACKET_SIZE 188
#define LOGW(...) ((void)0)
typedef struct { size_t packet_offset, payload_offset, payload_length; } packet_slot;
typedef struct {
    packet_slot* slots; size_t slot_count, header_packet_offset, header_payload_offset, header_length;
    unsigned int packet_length;
} pes_stream;
#include "h5e-pes-writer.h"

static void test(size_t header_length, size_t adaptation, size_t slots, int bounded) {
    uint8_t original[188*4], output[188*4], bytes[188*4], restored[188*4], header[64];
    packet_slot slot[4]; size_t capacity=0, i, j, length;
    memset(original, 0xff, sizeof original);
    for (i=0;i<sizeof bytes;i++) bytes[i]=(uint8_t)(i*37+19);
    for (i=0;i<slots;i++) {
        uint8_t* p=original+i*188;
        p[0]=0x47;p[1]=(i?1:0x41);p[2]=0;p[3]=(uint8_t)((adaptation?0x30:0x10)|i);
        if(adaptation) {p[4]=(uint8_t)adaptation;p[5]=adaptation>=7?0x10:0;
            for(j=1;j<adaptation;j++) p[5+j]=(uint8_t)(j+0x30);}
        size_t offset=4+(adaptation?1+adaptation:0);
        if(!i) {
            memset(header,0,header_length);header[2]=1;header[3]=0xe0;
            header[6]=0x80;header[7]=0x80;header[8]=(uint8_t)(header_length-9);
            if(bounded) {header[4]=2;header[5]=33;}
            for(j=9;j<header_length;j++)header[j]=(uint8_t)(j+0x50);
            memcpy(p+offset,header,header_length);offset+=header_length;
        }
        slot[i]=(packet_slot){i*188,offset,188-offset};capacity+=188-offset;
    }
    for(length=0;length<=capacity;length++) {
        memcpy(output,original,sizeof output);
        pes_stream pes={slot,slots,0,4+(adaptation?1+adaptation:0),header_length,bounded?545:0};
        assert(write_pes_payload(output,&pes,bytes,length));
        size_t count=0;
        for(i=0;i<slots;i++) {
            uint8_t* p=output+i*188;
            assert(p[0]==0x47 && p[1]==original[i*188+1] && p[2]==0);
            assert((p[3]&15)==i && (p[3]&0x10));
            size_t offset=4+((p[3]&0x20)?1+p[4]:0);
            if(adaptation) assert(!memcmp(p+5,original+i*188+5,adaptation));
            if(!i) {
                assert(!memcmp(p+offset,header,4));
                assert(!memcmp(p+offset+6,header+6,header_length-6));
                unsigned n=(p[offset+4]<<8)|p[offset+5];
                assert(n==(bounded?length+header_length-6:0));
                offset+=header_length;
            }
            assert(offset<=188);memcpy(restored+count,p+offset,188-offset);count+=188-offset;
        }
        assert(count==length && !memcmp(restored,bytes,length));
    }
    memcpy(output,original,sizeof output);
    pes_stream pes={slot,slots,0,4+(adaptation?1+adaptation:0),header_length,0};
    assert(!write_pes_payload(output,&pes,bytes,capacity+1));
    assert(!memcmp(output,original,sizeof output));
}
int main(void) {
    const size_t headers[]={9,14,19,32},adaptations[]={0,1,7,19};
    size_t h,a,s;int bounded;
    for(h=0;h<4;h++)for(a=0;a<4;a++)for(s=1;s<=4;s++)for(bounded=0;bounded<=1;bounded++)
        test(headers[h],adaptations[a],s,bounded);
    puts("PASS all payload lengths: PES/PTS/DTS, PCR, continuity, empty tail, bounded/unbounded, overflow");
    return 0;
}
