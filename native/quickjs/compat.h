#ifndef NTV_QUICKJS_COMPAT_H
#define NTV_QUICKJS_COMPAT_H
#include <stdlib.h>
#include <malloc.h>
#include <math.h>
#include <stdint.h>

/* API 14 has no exported malloc_usable_size. Track allocation sizes so QuickJS's
 * memory accounting stays effective; returning zero would break its heap limit. */
typedef union { size_t size; long double alignment; void *pointer; } NtvAllocation;
static inline void *ntv_qjs_malloc(size_t size) {
    if (size > SIZE_MAX - sizeof(NtvAllocation)) return NULL;
    NtvAllocation *p = malloc(sizeof(*p) + size);
    if (!p) return NULL;
    p->size = size;
    return p + 1;
}
static inline void ntv_qjs_free(void *ptr) {
    if (ptr) free((NtvAllocation *)ptr - 1);
}
static inline size_t ntv_qjs_usable_size(const void *ptr) {
    return ptr ? ((const NtvAllocation *)ptr - 1)->size : 0;
}
static inline void *ntv_qjs_realloc(void *ptr, size_t size) {
    if (!ptr) return ntv_qjs_malloc(size);
    if (!size) { ntv_qjs_free(ptr); return NULL; }
    if (size > SIZE_MAX - sizeof(NtvAllocation)) return NULL;
    NtvAllocation *p = realloc((NtvAllocation *)ptr - 1, sizeof(*p) + size);
    if (!p) return NULL;
    p->size = size;
    return p + 1;
}
static inline double ntv_qjs_log2(double value) { return log(value) * 1.4426950408889634074; }
#define malloc ntv_qjs_malloc
#define free ntv_qjs_free
#define realloc ntv_qjs_realloc
#define malloc_usable_size ntv_qjs_usable_size
#define log2 ntv_qjs_log2
#endif
