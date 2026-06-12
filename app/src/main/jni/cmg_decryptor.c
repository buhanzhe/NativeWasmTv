#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>

#include "cmg_wasm.h"
#include "cmg_memory_init.h"
#include "wasm-rt.h"
#include "wasm-rt-impl.h"

#define LOG_TAG "cmg_decrypt"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define CMG_INITIAL_PAGES 256u
#define CMG_MAX_PAGES 1536u
#define CMG_TABLE_SIZE 560u
#define CMG_TABLE_BASE 0u
#define CMG_TEMP_DOUBLE_PTR 17920u
#define CMG_DYNAMIC_TOP_PTR 17904u
#define CMG_EMT_STACK_TOP 6309392u
#define CMG_EMBIND_STORAGE 6309392u
#define CMG_DYNAMIC_TOP_AFTER_SHELL_ALLOCATIONS 6687984u
#define CMG_MEMORY_EXTEND 2048u
#define CMG_PLAYER_MEMORY_EXTEND 1968u
#define CMG_NAL_MEMORY_EXTEND CMG_MEMORY_EXTEND
#define CMG_PAGE_HOST "https://www.yangshipin.cn/tv/home"
#define CMG_LOCATION_HREF "blob:https://www.yangshipin.cn/tv/home"

typedef enum cmg_emval_type {
  CMG_EMVAL_EMPTY,
  CMG_EMVAL_LOCATION,
  CMG_EMVAL_STRING,
  CMG_EMVAL_DESTRUCTORS
} cmg_emval_type;

typedef struct cmg_emval_handle {
  cmg_emval_type type;
  int refcount;
  char* text;
  u32 destructor_address;
} cmg_emval_handle;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_initialized;
static wasm_rt_memory_t g_memory;
static wasm_rt_table_t g_table;
static u32 g_table_base = CMG_TABLE_BASE;
static u32 g_temp_double_ptr = CMG_TEMP_DOUBLE_PTR;
static u32 g_dynamic_top_ptr = CMG_DYNAMIC_TOP_PTR;
static u32 g_emt_stack_top = CMG_EMT_STACK_TOP;
static u32 g_embind_storage = CMG_EMBIND_STORAGE;
static u32 g_temp_ret0;
static char g_player_tag[32];
static char g_forced_player_tag[32];
static int g_player_active;
static int g_player_init_trap;
static u32 g_update_tag;
static u32 g_logged_update_tag;
static cmg_emval_handle g_emval_handles[64];
static u32 g_emval_free_list[64];
static size_t g_emval_free_count;
static u32 g_emval_handle_count;
static int g_location_property_reads;

u32 (*Z_envZ___table_baseZ_i) = &g_table_base;
u32 (*Z_envZ_aZ_i) = &g_temp_double_ptr;
u32 (*Z_envZ_bZ_i) = &g_dynamic_top_ptr;
u32 (*Z_envZ_cZ_i) = &g_emt_stack_top;
u32 (*Z_envZ_dZ_i) = &g_embind_storage;
wasm_rt_memory_t (*Z_envZ_memory) = &g_memory;
wasm_rt_table_t (*Z_envZ_table) = &g_table;

static uint8_t* cmg_pointer(u32 address, size_t size) {
  if ((uint64_t) address + size > g_memory.size) {
    return NULL;
  }
  return g_memory.data + address;
}

static int cmg_apply_memory_init(void) {
  size_t relocation_index;
  if (CMG_EMBIND_STORAGE + sizeof(CMG_MEMORY_INIT) > g_memory.size) {
    LOGE("CMG memory init too large: storage=%u payload=%u memory=%u",
        CMG_EMBIND_STORAGE, (u32) sizeof(CMG_MEMORY_INIT), (u32) g_memory.size);
    return 0;
  }
  memcpy(g_memory.data + CMG_EMBIND_STORAGE, CMG_MEMORY_INIT, sizeof(CMG_MEMORY_INIT));
  for (relocation_index = 0;
      relocation_index < sizeof(CMG_MEMORY_RELOCATIONS) / sizeof(CMG_MEMORY_RELOCATIONS[0]);
      relocation_index++) {
    uint32_t* target = (uint32_t*) cmg_pointer(
        CMG_EMBIND_STORAGE + CMG_MEMORY_RELOCATIONS[relocation_index], sizeof(uint32_t));
    if (target == NULL) {
      return 0;
    }
    *target += CMG_EMBIND_STORAGE;
  }
  ((u32*) (g_memory.data + CMG_DYNAMIC_TOP_PTR))[0] = CMG_DYNAMIC_TOP_AFTER_SHELL_ALLOCATIONS;
  return 1;
}

static u32 cmg_strlen(u32 address) {
  u32 length = 0;
  while (address + length < g_memory.size && g_memory.data[address + length] != 0) {
    length++;
  }
  return length;
}

static void cmg_reset_emval_state(void) {
  u32 handle;
  for (handle = 5; handle < g_emval_handle_count; handle++) {
    free(g_emval_handles[handle].text);
  }
  memset(g_emval_handles, 0, sizeof(g_emval_handles));
  memset(g_emval_free_list, 0, sizeof(g_emval_free_list));
  g_emval_handles[1].type = CMG_EMVAL_LOCATION;
  g_emval_free_count = 0;
  g_emval_handle_count = 5;
  g_location_property_reads = 0;
}

static u32 cmg_allocate_emval_handle(void) {
  u32 handle;
  if (g_emval_free_count > 0) {
    handle = g_emval_free_list[--g_emval_free_count];
  } else {
    if (g_emval_handle_count >= sizeof(g_emval_handles) / sizeof(g_emval_handles[0])) {
      return 1;
    }
    handle = g_emval_handle_count++;
  }
  memset(&g_emval_handles[handle], 0, sizeof(g_emval_handles[handle]));
  g_emval_handles[handle].refcount = 1;
  return handle;
}

static u32 cmg_register_emval_string(const char* text, size_t length) {
  u32 handle = cmg_allocate_emval_handle();
  if (handle <= 4) {
    return handle;
  }
  g_emval_handles[handle].text = (char*) malloc(length + 1);
  if (g_emval_handles[handle].text == NULL) {
    return 1;
  }
  memcpy(g_emval_handles[handle].text, text, length);
  g_emval_handles[handle].text[length] = '\0';
  g_emval_handles[handle].type = CMG_EMVAL_STRING;
  return handle;
}

static u32 cmg_register_emval_destructors(void) {
  u32 handle = cmg_allocate_emval_handle();
  if (handle > 4) {
    g_emval_handles[handle].type = CMG_EMVAL_DESTRUCTORS;
  }
  return handle;
}

static void cmg_release_emval_handle(u32 handle) {
  if (handle <= 4 || handle >= g_emval_handle_count || g_emval_handles[handle].refcount == 0) {
    return;
  }
  if (--g_emval_handles[handle].refcount == 0) {
    free(g_emval_handles[handle].text);
    memset(&g_emval_handles[handle], 0, sizeof(g_emval_handles[handle]));
    if (g_emval_free_count < sizeof(g_emval_free_list) / sizeof(g_emval_free_list[0])) {
      g_emval_free_list[g_emval_free_count++] = handle;
    }
  }
}

static u32 cmg_write_string(const char* text) {
  size_t length = strlen(text) + 1;
  u32 address = (*Z_CaZ_ii)((u32) length);
  uint8_t* target = cmg_pointer(address, length);
  if (target != NULL) {
    memcpy(target, text, length);
  }
  return address;
}

static u32 cmg_write_padded_string(const char* text, u32 extra) {
  size_t length = strlen(text);
  size_t total = length + 1u + extra;
  u32 address = (*Z_CaZ_ii)((u32) total);
  uint8_t* target = cmg_pointer(address, total);
  if (target != NULL) {
    memset(target, 0, total);
    memcpy(target, text, length);
  }
  return address;
}

static u32 cmg_write_tag_buffer(const char* text, u32 extra, int clear_buffer) {
  size_t length = strlen(text);
  size_t total = length + (size_t) extra;
  u32 address = (*Z_CaZ_ii)((u32) total);
  uint8_t* target = cmg_pointer(address, total);
  if (target != NULL) {
    if (clear_buffer) {
      memset(target, 0, total);
    }
    memcpy(target, text, length);
  }
  return address;
}

static u32 cmg_update_player_with_fresh_tag(void) {
  u32 tag_address = cmg_write_tag_buffer(g_player_tag, CMG_MEMORY_EXTEND, 1);
  u32 update_tag = 0;
  if (cmg_pointer(tag_address, strlen(g_player_tag) + CMG_MEMORY_EXTEND) != NULL) {
    update_tag = (*Z_daZ_ii)(tag_address);
  }
  if (tag_address != 0) {
    (*Z_BaZ_vi)(tag_address);
  }
  return update_tag;
}

static u32 cmg_return_js_string(const char* text) {
  size_t length = strlen(text) + 1;
  u32 address = (*Z_EaZ_ii)((u32) length);
  uint8_t* target = cmg_pointer(address, length);
  if (target != NULL) {
    memcpy(target, text, length);
  }
  return address;
}

typedef u32 (*cmg_decode_function)(u32, u32, u32, u32);

static int cmg_should_run_decode_step(u32 update_tag, int index) {
  int shift = (7 - index) * 4;
  u32 digit = (update_tag >> shift) & 0x0fu;
  return digit <= 6u;
}

static const char* cmg_emval_text(u32 handle) {
  if (handle == 1) {
    return CMG_LOCATION_HREF;
  }
  if (handle < g_emval_handle_count && g_emval_handles[handle].text != NULL) {
    return g_emval_handles[handle].text;
  }
  return "";
}

static u32 env_now_ms(void) {
  struct timeval now;
  gettimeofday(&now, NULL);
  return (u32) ((uint64_t) now.tv_sec * 1000u + (uint64_t) now.tv_usec / 1000u);
}

static void cmg_make_player_tag(void) {
  struct timeval now;
  uint64_t millis;
  if (g_forced_player_tag[0] != '\0') {
    snprintf(g_player_tag, sizeof(g_player_tag), "%s", g_forced_player_tag);
    return;
  }
  gettimeofday(&now, NULL);
  millis = (uint64_t) now.tv_sec * 1000u + (uint64_t) now.tv_usec / 1000u;
  snprintf(g_player_tag, sizeof(g_player_tag), "%llu", (unsigned long long) millis);
}

static u32 import_e(void) { return g_temp_ret0; }
static void import_f(u32 value) { g_temp_ret0 = value; }
static u32 import_g(u32 timeval_address, u32 timezone_address) {
  struct timeval now;
  uint8_t* target;
  (void) timezone_address;
  gettimeofday(&now, NULL);
  target = cmg_pointer(timeval_address, 8);
  if (target == NULL) {
    return (u32) -1;
  }
  ((u32*) target)[0] = (u32) now.tv_sec;
  ((u32*) target)[1] = (u32) now.tv_usec;
  return 0;
}
static void import_h(u32 a, u32 b, u32 c, u32 d, u32 e) {
  (void) a; (void) b; (void) c; (void) d; (void) e;
}
static f64 import_i(void) {
  struct timeval now;
  gettimeofday(&now, NULL);
  return (f64) now.tv_sec * 1000.0 + (f64) now.tv_usec / 1000.0;
}
static void import_j(void) { LOGE("CMG pure virtual call"); wasm_rt_trap(WASM_RT_TRAP_UNREACHABLE); }
static void import_k(u32 a, u32 b) { (void) a; (void) b; }
static void import_l(u32 a) { (void) a; }
static u32 import_m(u32 a, u32 b, u32 c, u32 d, u32 e) {
  (void) a; (void) b; (void) c; (void) d; (void) e; return 0;
}
static u32 import_n(u32 a, u32 b, u32 c, u32 d) {
  (void) a; (void) b; (void) c; (void) d; return 0;
}
static u32 import_o(u32 requested_size) {
  LOGE("CMG cannot grow memory to %u", requested_size);
  wasm_rt_trap(WASM_RT_TRAP_EXHAUSTION);
  return 0;
}
static u32 cmg_memmove(u32 destination, u32 source, u32 size) {
  uint8_t* destination_pointer = cmg_pointer(destination, size);
  uint8_t* source_pointer = cmg_pointer(source, size);
  if (destination_pointer != NULL && source_pointer != NULL) {
    memmove(destination_pointer, source_pointer, size);
  }
  return destination;
}
static u32 import_p(u32 function_index, u32 a, u32 b) {
  (void) function_index; (void) a; (void) b; return 0;
}
static void import_q(void) { LOGE("CMG llvm trap"); wasm_rt_trap(WASM_RT_TRAP_UNREACHABLE); }
static void import_r(u32 a) { (void) a; }
static u32 import_s(u32 requested_size) {
  uint64_t current_pages = g_memory.pages;
  uint64_t requested_pages = ((uint64_t) requested_size + 65535u) / 65536u;
  if (requested_pages <= current_pages) {
    return 1;
  }
  return wasm_rt_grow_memory(&g_memory, requested_pages - current_pages) == (u32) -1 ? 0 : 1;
}
static u32 import_t(u32 destination, u32 source, u32 size) {
  return cmg_memmove(destination, source, size);
}
static void import_u(u32 flags, u32 message) {
  const char* text = (const char*) cmg_pointer(message, 1);
  (void) flags;
  if (text != NULL) {
    LOGI("%s", text);
  }
}
static u32 import_v(void) { return 1; }
static u32 import_w(void) { return g_memory.size; }
static u32 import_x(u32 flags, u32 buffer, u32 size) {
  (void) flags; (void) buffer; (void) size; return 0;
}
static u32 import_y(u32 a, u32 b, f64 c, u32 d, u32 e, u32 f, u32 g) {
  (void) a; (void) b; (void) c; (void) d; (void) e; (void) f; (void) g; return 0;
}
static u32 import_z(u32 operation, u32 argument) {
  const char* name = (const char*) cmg_pointer(argument, 1);
  (void) operation;
  if (name == NULL) {
    return 0;
  }
  if (strcmp(name, "self.location.href") == 0) {
    return cmg_return_js_string(CMG_LOCATION_HREF);
  }
  if (strcmp(name, "self.location.host") == 0) {
    return cmg_return_js_string("www.yangshipin.cn");
  }
  if (strcmp(name, "self.location.protocol") == 0) {
    return cmg_return_js_string("https:");
  }
  if (strcmp(name, "location.href") == 0) {
    return cmg_return_js_string(CMG_LOCATION_HREF);
  }
  if (strcmp(name, "location.host") == 0) {
    return cmg_return_js_string("www.yangshipin.cn");
  }
  if (strcmp(name, "location.protocol") == 0) {
    return cmg_return_js_string("https:");
  }
  return cmg_return_js_string("");
}
static void import_A(void) { LOGE("CMG abort"); wasm_rt_trap(WASM_RT_TRAP_UNREACHABLE); }
static u32 import_B(u32 type, u32 value_address) {
  uint32_t* length_pointer;
  uint8_t* text;
  u32 result;
  uint8_t* raw;
  u32 string_address = value_address;
  (void) type;
  raw = cmg_pointer(value_address, 32);
  if (raw != NULL) {
    uint32_t candidate = ((uint32_t*) raw)[0];
    uint8_t* candidate_bytes = cmg_pointer(candidate, 32);
    if (candidate_bytes != NULL) {
      string_address = candidate;
    }
  }
  length_pointer = (uint32_t*) cmg_pointer(string_address, sizeof(uint32_t));
  if (length_pointer == NULL) {
    return 1;
  }
  if (*length_pointer > 4096u) {
    return 1;
  }
  text = cmg_pointer(string_address + 4, *length_pointer);
  if (text == NULL) {
    return 1;
  }
  result = cmg_register_emval_string((const char*) text, *length_pointer);
  return result;
}
static void import_C(u32 destructors) {
  if (destructors > 4 && destructors < g_emval_handle_count
      && g_emval_handles[destructors].type == CMG_EMVAL_DESTRUCTORS) {
    if (g_emval_handles[destructors].destructor_address != 0) {
      (*Z_AaZ_vi)(g_emval_handles[destructors].destructor_address);
    }
    cmg_release_emval_handle(destructors);
  }
}
static u32 import_D(u32 object, u32 property_handle) {
  const char* property = property_handle < g_emval_handle_count
      ? g_emval_handles[property_handle].text : NULL;
  if (object == 1 && property != NULL) {
    if (strcmp(property, "href") == 0) {
      return cmg_register_emval_string(CMG_LOCATION_HREF, strlen(CMG_LOCATION_HREF));
    }
    if (strcmp(property, "host") == 0) {
      return cmg_register_emval_string("www.yangshipin.cn", strlen("www.yangshipin.cn"));
    }
    if (strcmp(property, "protocol") == 0) {
      return cmg_register_emval_string("blob:", strlen("blob:"));
    }
  }
  if (object == 1) {
    g_location_property_reads++;
    if (g_location_property_reads == 1) {
      return cmg_register_emval_string("www.yangshipin.cn", strlen("www.yangshipin.cn"));
    }
    if (g_location_property_reads == 2) {
      return cmg_register_emval_string(CMG_LOCATION_HREF, strlen(CMG_LOCATION_HREF));
    }
    return cmg_register_emval_string("blob:", strlen("blob:"));
  }
  return 1;
}
static u32 import_E(u32 name_address) {
  const char* name = (const char*) cmg_pointer(name_address, 1);
  if (name != NULL && (strcmp(name, "location") == 0 || strcmp(name, "self") == 0)) {
    return 1;
  }
  return 1;
}
static void import_F(u32 handle) { cmg_release_emval_handle(handle); }
static f64 import_G(u32 handle, u32 return_type, u32 destructors_address) {
  const char* text = cmg_emval_text(handle);
  size_t length = strlen(text);
  u32 destructors = cmg_register_emval_destructors();
  u32 result;
  uint32_t* pointer;
  uint8_t* output;
  (void) return_type;
  pointer = (uint32_t*) cmg_pointer(destructors_address, sizeof(uint32_t));
  if (pointer == NULL) {
    return 0.0;
  }
  *pointer = destructors;
  result = (*Z_EaZ_ii)((u32) length + 5);
  pointer = (uint32_t*) cmg_pointer(result, sizeof(uint32_t));
  if (pointer == NULL) {
    return 0.0;
  }
  *pointer = (uint32_t) length;
  output = cmg_pointer(result + 4, length + 1);
  if (output == NULL) {
    return 0.0;
  }
  memcpy(output, text, length + 1);
  if (destructors > 4) {
    g_emval_handles[destructors].destructor_address = result;
  }
  return (f64) result;
}
static void import_H(u32 a) { (void) a; }
static u32 import_I(u32 a, u32 b) { (void) a; (void) b; return 0; }
static void import_J(u32 a, u32 b) { (void) a; (void) b; }
static void import_K(u32 a, u32 b, u32 c) { (void) a; (void) b; (void) c; }
static void import_L(u32 a, u32 b) { (void) a; (void) b; }
static void import_M(u32 a, u32 b, u32 c) { (void) a; (void) b; (void) c; }
static void import_N(u32 a, u32 b, u32 c, u32 d, u32 e) {
  (void) a; (void) b; (void) c; (void) d; (void) e;
}
static void import_O(u32 a, u32 b, u32 c) { (void) a; (void) b; (void) c; }
static void import_P(u32 a, u32 b) { (void) a; (void) b; }
static void import_Q(u32 a, u32 b, u32 c, u32 d, u32 e) {
  (void) a; (void) b; (void) c; (void) d; (void) e;
}
static u32 import_R(u32 a, u32 b) { (void) a; (void) b; return (u32) -1; }
static u32 import_S(u32 a, u32 b) { (void) a; (void) b; return (u32) -1; }
static u32 import_T(u32 a, u32 b) { (void) a; (void) b; return (u32) -1; }
static void import_U(u32 a) { (void) a; }
static u32 import_V(void) { return 0; }
static u32 import_W(u32 a) { (void) a; return 0; }
static void import_X(u32 a, u32 b, u32 c, u32 d, u32 e, u32 f, u32 g) {
  (void) a; (void) b; (void) c; (void) d; (void) e; (void) f; (void) g;
}
static void import_Y(u32 a, u32 b, u32 c, u32 d, u32 e, u32 f) {
  (void) a; (void) b; (void) c; (void) d; (void) e; (void) f;
}
static void import_Z(u32 a, u32 b, u32 c, u32 d, u32 e) {
  (void) a; (void) b; (void) c; (void) d; (void) e;
}
static void import_(u32 a, u32 b, u32 c, u32 d) { (void) a; (void) b; (void) c; (void) d; }
static void import_dollar(u32 a, u32 b, u32 c) { (void) a; (void) b; (void) c; }
static void import_aa(u32 message) {
  uint8_t* text = cmg_pointer(message, 1);
  LOGE("CMG abort: %s", text != NULL ? (const char*) text : "");
  wasm_rt_trap(WASM_RT_TRAP_UNREACHABLE);
}

u32 (*Z_envZ_eZ_iv)(void) = import_e;
void (*Z_envZ_fZ_vi)(u32) = import_f;
u32 (*Z_envZ_gZ_iii)(u32, u32) = import_g;
void (*Z_envZ_hZ_viiiii)(u32, u32, u32, u32, u32) = import_h;
f64 (*Z_envZ_iZ_dv)(void) = import_i;
void (*Z_envZ_jZ_vv)(void) = import_j;
void (*Z_envZ_kZ_vii)(u32, u32) = import_k;
void (*Z_envZ_lZ_vi)(u32) = import_l;
u32 (*Z_envZ_mZ_iiiiii)(u32, u32, u32, u32, u32) = import_m;
u32 (*Z_envZ_nZ_iiiii)(u32, u32, u32, u32) = import_n;
u32 (*Z_envZ_oZ_ii)(u32) = import_o;
u32 (*Z_envZ_pZ_iiii)(u32, u32, u32) = import_p;
void (*Z_envZ_qZ_vv)(void) = import_q;
void (*Z_envZ_rZ_vi)(u32) = import_r;
u32 (*Z_envZ_sZ_ii)(u32) = import_s;
u32 (*Z_envZ_tZ_iiii)(u32, u32, u32) = import_t;
void (*Z_envZ_uZ_vii)(u32, u32) = import_u;
u32 (*Z_envZ_vZ_iv)(void) = import_v;
u32 (*Z_envZ_wZ_iv)(void) = import_w;
u32 (*Z_envZ_xZ_iiii)(u32, u32, u32) = import_x;
u32 (*Z_envZ_yZ_iiidiiii)(u32, u32, f64, u32, u32, u32, u32) = import_y;
u32 (*Z_envZ_zZ_iii)(u32, u32) = import_z;
void (*Z_envZ_AZ_vv)(void) = import_A;
u32 (*Z_envZ_BZ_iii)(u32, u32) = import_B;
void (*Z_envZ_CZ_vi)(u32) = import_C;
u32 (*Z_envZ_DZ_iii)(u32, u32) = import_D;
u32 (*Z_envZ_EZ_ii)(u32) = import_E;
void (*Z_envZ_FZ_vi)(u32) = import_F;
f64 (*Z_envZ_GZ_diii)(u32, u32, u32) = import_G;
void (*Z_envZ_HZ_vi)(u32) = import_H;
u32 (*Z_envZ_IZ_iii)(u32, u32) = import_I;
void (*Z_envZ_JZ_vii)(u32, u32) = import_J;
void (*Z_envZ_KZ_viii)(u32, u32, u32) = import_K;
void (*Z_envZ_LZ_vii)(u32, u32) = import_L;
void (*Z_envZ_MZ_viii)(u32, u32, u32) = import_M;
void (*Z_envZ_NZ_viiiii)(u32, u32, u32, u32, u32) = import_N;
void (*Z_envZ_OZ_viii)(u32, u32, u32) = import_O;
void (*Z_envZ_PZ_vii)(u32, u32) = import_P;
void (*Z_envZ_QZ_viiiii)(u32, u32, u32, u32, u32) = import_Q;
u32 (*Z_envZ_RZ_iii)(u32, u32) = import_R;
u32 (*Z_envZ_SZ_iii)(u32, u32) = import_S;
u32 (*Z_envZ_TZ_iii)(u32, u32) = import_T;
void (*Z_envZ_UZ_vi)(u32) = import_U;
u32 (*Z_envZ_VZ_iv)(void) = import_V;
u32 (*Z_envZ_WZ_ii)(u32) = import_W;
void (*Z_envZ_XZ_viiiiiii)(u32, u32, u32, u32, u32, u32, u32) = import_X;
void (*Z_envZ_YZ_viiiiii)(u32, u32, u32, u32, u32, u32) = import_Y;
void (*Z_envZ_Z5AZ_viiiii)(u32, u32, u32, u32, u32) = import_Z;
void (*Z_envZ__Z_viiii)(u32, u32, u32, u32) = import_;
void (*Z_envZ_Z24Z_viii)(u32, u32, u32) = import_dollar;
void (*Z_envZ_aaZ_vi)(u32) = import_aa;

static int cmg_init_locked(void) {
  int trap;
  if (g_initialized) {
    return 1;
  }
  LOGI("CMG init: allocate memory/table");
  wasm_rt_allocate_memory(&g_memory, CMG_INITIAL_PAGES, CMG_MAX_PAGES);
  wasm_rt_allocate_table(&g_table, CMG_TABLE_SIZE, CMG_TABLE_SIZE);
  cmg_reset_emval_state();
  memset(g_memory.data, 0, g_memory.size);
  if (!cmg_apply_memory_init()) {
    LOGE("CMG memory init failed");
    return 0;
  }
  trap = wasm_rt_impl_try();
  if (trap != 0) {
    LOGE("CMG wasm init trapped: %d", trap);
    return 0;
  }
  LOGI("CMG init: generated init begin");
  init();
  ((u32*) (g_memory.data + CMG_DYNAMIC_TOP_PTR))[0] = CMG_DYNAMIC_TOP_AFTER_SHELL_ALLOCATIONS;
  LOGI("CMG init: global constructors");
  (*Z_LaZ_vv)();
  ((u32*) (g_memory.data + CMG_DYNAMIC_TOP_PTR))[0] = CMG_DYNAMIC_TOP_AFTER_SHELL_ALLOCATIONS;
  cmg_make_player_tag();
  LOGI("CMG init: activate player tag %s", g_player_tag);
  u32 init_tag_address = cmg_write_tag_buffer(g_player_tag, CMG_MEMORY_EXTEND, 1);
  LOGI("CMG init: activate player");
  g_player_init_trap = wasm_rt_impl_try();
  if (g_player_init_trap == 0) {
    (*Z_baZ_ii)(init_tag_address);
    g_player_active = 1;
  } else {
    LOGE("CMG InitPlayer trapped: %d", g_player_init_trap);
    g_player_active = 1;
  }
  if (init_tag_address != 0) {
    (*Z_BaZ_vi)(init_tag_address);
  }
  g_initialized = 1;
  LOGI("CMG wasm runtime initialized");
  return 1;
}

JNIEXPORT jstring JNICALL
Java_com_bu_cc_tv_NativeCmgDecryptor_probeRuntime(JNIEnv* env, jclass clazz) {
  char result[224];
  u32 update_tag = 0;
  int init_trap = 0;
  int update_trap = 0;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  if (cmg_init_locked()) {
    init_trap = g_player_init_trap;
    update_trap = wasm_rt_impl_try();
    if (update_trap == 0) {
      update_tag = cmg_update_player_with_fresh_tag();
    } else {
      LOGE("CMG UpdatePlayer trapped: %d", update_trap);
    }
  }
  pthread_mutex_unlock(&g_lock);
  snprintf(result, sizeof(result),
      "ready=%d initTrap=%d updateTrap=%d updateTag=%08x tag=%s memory=%u",
      g_initialized, init_trap, update_trap, update_tag, g_player_tag, g_memory.size);
  return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jboolean JNICALL
Java_com_bu_cc_tv_NativeCmgDecryptor_configureRuntimeForProbe(
    JNIEnv* env, jclass clazz, jstring player_tag, jint update_tag) {
  const char* tag_chars;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  if (g_initialized) {
    LOGW("CMG probe configure ignored because runtime is already initialized");
    pthread_mutex_unlock(&g_lock);
    return JNI_FALSE;
  }
  memset(g_forced_player_tag, 0, sizeof(g_forced_player_tag));
  if (player_tag != NULL) {
    tag_chars = (*env)->GetStringUTFChars(env, player_tag, NULL);
    if (tag_chars != NULL) {
      snprintf(g_forced_player_tag, sizeof(g_forced_player_tag), "%s", tag_chars);
      (*env)->ReleaseStringUTFChars(env, player_tag, tag_chars);
    }
  }
  g_update_tag = (u32) update_tag;
  g_logged_update_tag = g_update_tag;
  LOGI("CMG probe configured tag=%s updateTag=%08x",
      g_forced_player_tag[0] == '\0' ? "(auto)" : g_forced_player_tag, g_update_tag);
  pthread_mutex_unlock(&g_lock);
  return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_bu_cc_tv_NativeCmgDecryptor_updateSessionForProbe(JNIEnv* env, jclass clazz) {
  u32 update_tag = 0;
  int trap;
  (void) env;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  if (cmg_init_locked() && g_player_active) {
    trap = wasm_rt_impl_try();
    if (trap == 0) {
      update_tag = cmg_update_player_with_fresh_tag();
      g_update_tag = update_tag;
      if (update_tag != g_logged_update_tag) {
        LOGI("CMG updateTag=%08x", update_tag);
        g_logged_update_tag = update_tag;
      }
    } else {
      LOGE("CMG UpdatePlayer trapped: %d", trap);
    }
  }
  pthread_mutex_unlock(&g_lock);
  return (jint) update_tag;
}

JNIEXPORT void JNICALL
Java_com_bu_cc_tv_NativeCmgDecryptor_touchActiveForProbe(JNIEnv* env, jclass clazz) {
  int trap;
  (void) env;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  if (cmg_init_locked() && g_player_active) {
    trap = wasm_rt_impl_try();
    if (trap == 0) {
      (void) cmg_update_player_with_fresh_tag();
    } else {
      LOGE("CMG touch active trapped: %d", trap);
    }
  }
  pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_com_bu_cc_tv_NativeCmgDecryptor_setUpdateTagForProbe(
    JNIEnv* env, jclass clazz, jint update_tag) {
  (void) env;
  (void) clazz;
  pthread_mutex_lock(&g_lock);
  if (g_update_tag == (u32) update_tag) {
    pthread_mutex_unlock(&g_lock);
    return;
  }
  g_update_tag = (u32) update_tag;
  g_logged_update_tag = g_update_tag;
  if (g_update_tag != 0u) {
    LOGI("CMG forced updateTag=%08x", g_update_tag);
  }
  pthread_mutex_unlock(&g_lock);
}

JNIEXPORT jbyteArray JNICALL
Java_com_bu_cc_tv_NativeCmgDecryptor_decodeNalForProbe(
    JNIEnv* env, jclass clazz, jbyteArray input, jboolean live, jboolean run_steps) {
  jsize length;
  jbyte* input_bytes;
  jbyteArray output;
  u32 data_address;
  u32 tag_address;
  u32 host_length;
  size_t data_allocation_size;
  u32 update_tag;
  u32 output_length;
  int trap;
  int index;
  int ran_steps = 0;
  uint8_t* data_pointer;
  cmg_decode_function live_functions[9];
  cmg_decode_function vod_functions[9];
  cmg_decode_function* functions;
  (void) clazz;
  (void) live;
  if (input == NULL) {
    return NULL;
  }
  length = (*env)->GetArrayLength(env, input);
  input_bytes = (*env)->GetByteArrayElements(env, input, NULL);
  if (input_bytes == NULL) {
    return NULL;
  }
  pthread_mutex_lock(&g_lock);
  if (!cmg_init_locked()) {
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  if (!g_player_active) {
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  live_functions[0] = Z_eaZ_iiiii;
  live_functions[1] = Z_faZ_iiiii;
  live_functions[2] = Z_gaZ_iiiii;
  live_functions[3] = Z_haZ_iiiii;
  live_functions[4] = Z_iaZ_iiiii;
  live_functions[5] = Z_jaZ_iiiii;
  live_functions[6] = Z_kaZ_iiiii;
  live_functions[7] = Z_laZ_iiiii;
  live_functions[8] = Z_maZ_iiiii;
  vod_functions[0] = Z_naZ_iiiii;
  vod_functions[1] = Z_oaZ_iiiii;
  vod_functions[2] = Z_paZ_iiiii;
  vod_functions[3] = Z_qaZ_iiiii;
  vod_functions[4] = Z_raZ_iiiii;
  vod_functions[5] = Z_saZ_iiiii;
  vod_functions[6] = Z_taZ_iiiii;
  vod_functions[7] = Z_uaZ_iiiii;
  vod_functions[8] = Z_vaZ_iiiii;
  functions = live ? live_functions : vod_functions;
  host_length = live ? (u32) strlen(CMG_PAGE_HOST) : 0;
  data_allocation_size = (size_t) length + host_length + CMG_NAL_MEMORY_EXTEND;
  data_address = (*Z_CaZ_ii)((u32) data_allocation_size);
  data_pointer = cmg_pointer(data_address, data_allocation_size);
  if (data_pointer == NULL) {
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  memcpy(data_pointer, input_bytes, (size_t) length);
  if (host_length > 0) {
    memcpy(data_pointer + length, CMG_PAGE_HOST, host_length);
  }
  tag_address = cmg_write_tag_buffer(g_player_tag, 0, 0);
  if (cmg_pointer(tag_address, strlen(g_player_tag)) == NULL) {
    (*Z_BaZ_vi)(data_address);
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  output_length = (u32) length;
  trap = wasm_rt_impl_try();
  if (trap != 0) {
    LOGE("CMG decode trapped: trap=%d len=%d live=%d updateTag=%08x",
        trap, (int) length, live ? 1 : 0, g_update_tag);
    (*Z_BaZ_vi)(data_address);
    (*Z_BaZ_vi)(tag_address);
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  update_tag = g_update_tag;
  if (update_tag == 0) {
    update_tag = cmg_update_player_with_fresh_tag();
    g_update_tag = update_tag;
  }
  if (length > 50000) {
    LOGI("CMG decode begin len=%d live=%d runSteps=%d updateTag=%08x tagAddr=%u dataAddr=%u hostLen=%u",
        (int) length, live ? 1 : 0, run_steps ? 1 : 0, update_tag,
        tag_address, data_address, host_length);
  }
  if (run_steps) {
    for (index = 0; index < 8; index++) {
      if (cmg_should_run_decode_step(update_tag, index)) {
        functions[index](tag_address, data_address, (u32) length, host_length);
        ran_steps++;
      }
    }
  }
  if (live) {
    output_length = functions[8](tag_address, data_address, (u32) length, host_length);
  } else {
    output_length = functions[8](data_address, tag_address, (u32) length, host_length);
  }
  if (length > 50000) {
    LOGI("CMG decode end len=%d out=%u ranSteps=%d head=%02x%02x%02x%02x %02x%02x%02x%02x",
        (int) length, output_length, ran_steps,
        data_pointer[0], data_pointer[1], data_pointer[2], data_pointer[3],
        data_pointer[32], data_pointer[33], data_pointer[34], data_pointer[35]);
  }
  output = (*env)->NewByteArray(env, (jsize) output_length);
  if (output != NULL) {
    (*env)->SetByteArrayRegion(env, output, 0, (jsize) output_length, (jbyte*) data_pointer);
  }
  (*Z_BaZ_vi)(data_address);
  (*Z_BaZ_vi)(tag_address);
  pthread_mutex_unlock(&g_lock);
  (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
  return output;
}

JNIEXPORT jbyteArray JNICALL
Java_com_bu_cc_tv_NativeCmgDecryptor_decodeNalSingleStepForProbe(
    JNIEnv* env, jclass clazz, jbyteArray input, jboolean live, jint step) {
  jsize length;
  jbyte* input_bytes;
  jbyteArray output;
  u32 data_address;
  u32 tag_address;
  u32 host_length;
  size_t data_allocation_size;
  u32 output_length;
  int trap;
  uint8_t* data_pointer;
  cmg_decode_function live_functions[9];
  cmg_decode_function vod_functions[9];
  cmg_decode_function* functions;
  (void) clazz;
  if (input == NULL || step < 0 || step > 8) {
    return NULL;
  }
  length = (*env)->GetArrayLength(env, input);
  input_bytes = (*env)->GetByteArrayElements(env, input, NULL);
  if (input_bytes == NULL) {
    return NULL;
  }
  pthread_mutex_lock(&g_lock);
  if (!cmg_init_locked() || !g_player_active) {
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  live_functions[0] = Z_eaZ_iiiii;
  live_functions[1] = Z_faZ_iiiii;
  live_functions[2] = Z_gaZ_iiiii;
  live_functions[3] = Z_haZ_iiiii;
  live_functions[4] = Z_iaZ_iiiii;
  live_functions[5] = Z_jaZ_iiiii;
  live_functions[6] = Z_kaZ_iiiii;
  live_functions[7] = Z_laZ_iiiii;
  live_functions[8] = Z_maZ_iiiii;
  vod_functions[0] = Z_naZ_iiiii;
  vod_functions[1] = Z_oaZ_iiiii;
  vod_functions[2] = Z_paZ_iiiii;
  vod_functions[3] = Z_qaZ_iiiii;
  vod_functions[4] = Z_raZ_iiiii;
  vod_functions[5] = Z_saZ_iiiii;
  vod_functions[6] = Z_taZ_iiiii;
  vod_functions[7] = Z_uaZ_iiiii;
  vod_functions[8] = Z_vaZ_iiiii;
  functions = live ? live_functions : vod_functions;
  host_length = live ? (u32) strlen(CMG_PAGE_HOST) : 0;
  data_allocation_size = (size_t) length + host_length + CMG_NAL_MEMORY_EXTEND;
  data_address = (*Z_CaZ_ii)((u32) data_allocation_size);
  data_pointer = cmg_pointer(data_address, data_allocation_size);
  if (data_pointer == NULL) {
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  memcpy(data_pointer, input_bytes, (size_t) length);
  if (host_length > 0) {
    memcpy(data_pointer + length, CMG_PAGE_HOST, host_length);
  }
  tag_address = cmg_write_tag_buffer(g_player_tag, 0, 0);
  if (cmg_pointer(tag_address, strlen(g_player_tag)) == NULL) {
    (*Z_BaZ_vi)(data_address);
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  output_length = (u32) length;
  trap = wasm_rt_impl_try();
  if (trap != 0) {
    LOGE("CMG single step trapped: trap=%d len=%d live=%d step=%d",
        trap, (int) length, live ? 1 : 0, (int) step);
    (*Z_BaZ_vi)(data_address);
    (*Z_BaZ_vi)(tag_address);
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    return NULL;
  }
  if (live || step != 8) {
    output_length = functions[step](tag_address, data_address, (u32) length, host_length);
  } else {
    output_length = functions[step](data_address, tag_address, (u32) length, host_length);
  }
  if (length > 50000) {
    LOGI("CMG single step len=%d live=%d step=%d out=%u tagAddr=%u dataAddr=%u head=%02x%02x%02x%02x",
        (int) length, live ? 1 : 0, (int) step, output_length,
        tag_address, data_address,
        data_pointer[32], data_pointer[33], data_pointer[34], data_pointer[35]);
  }
  output = (*env)->NewByteArray(env, (jsize) output_length);
  if (output != NULL) {
    (*env)->SetByteArrayRegion(env, output, 0, (jsize) output_length, (jbyte*) data_pointer);
  }
  (*Z_BaZ_vi)(data_address);
  (*Z_BaZ_vi)(tag_address);
  pthread_mutex_unlock(&g_lock);
  (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
  return output;
}
