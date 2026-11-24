// Services whose client a game links into its own main (nn::sf::hipc): Animal Crossing: New Horizons 3.0.3 carries the clients of olsc:u (cloud save data backup settings,
// main+0x404bc6c) and npns (push notifications). The SDK's client code connects through sm (an abort in sm::Initialize under InitializeHipcServiceResolution, from main+0x5b8198) and talks
// HIPC through svcSendSyncRequest; the host has neither. It hands out sessions of its own instead and answers each request in the message buffer: success, the output data zeroed, no
// objects or handles, unless the session's service answers it (sf_req, hle.h) — the services listed in sf_served and the ones registered with SF_SERVICE (audren:u, hle_audren_sv.c).
// For olsc that reads as "backup off for every user", so the game leaves the settings alone (main+0x2809c8c asks per user and switches backup off where it is on). Any other service
// name is refused with a red line, as sm refuses a service that is not registered. A session keeps its own state and ends it on close (an audio renderer stops rendering).
// The SDK's own client functions are taken over where they reach the kernel (their calls inside sdk go through its PLT): the session manager's CreateNewSession runs natively on
// DuplicateSession, and ConvertToDomainImpl on QueryPointerBufferSize and ConvertSessionToDomain. DM_SF_TRACE=1 dumps every request.
// Message layout (Atmosphère / libnx): HIPC header {u16 type, counts of X / A / B / W descriptors; u32 data words, receive list, special header flag}, the special header and descriptors,
// then the data words, whose CMIF part starts 16-byte aligned to the buffer: in a domain session the domain header {u8 type, u8 objects, u16 size, u32 object, u32, u32 token} first,
// then {"SFCI", version, command, token} and the arguments. The response is the same with {u32 objects, 3 × u32} and {"SFCO", version, result, token}
#include "hle.h"
#include <mach-o/getsect.h>
#include <mach-o/ldsyms.h>
#include <pthread.h>

#define SF_H0 0xd0000u   // session handles: outside the kernel handle table (0x1000 + slot, 4096 slots, hle_thread.c)
#define SF_MAX 64
int svc_thread(int num, uint64_t *regs);   // hle_thread.c
// A session: its service's answer (sf_req, hle.h; NULL: success with zeroed output, as for olsc:u), its own state, and what to do with that state when the session closes
static struct { char name[16]; int used, domain; unsigned seen; sf_answer answer; void *ctx; void (*close)(void *); } g_sf[SF_MAX];
static pthread_mutex_t g_sf_mu = PTHREAD_MUTEX_INITIALIZER;
static Result bt_answer(sf_req *q), btm_core_answer(sf_req *q), btm_answer(sf_req *q);   // the Bluetooth answers, below
static const struct { const char *name; sf_answer answer; } sf_served[] = {
    {"olsc:u", NULL}, {"bt", bt_answer}, {"btm:u", btm_answer},
};
// The services other files serve (SF_SERVICE, hle.h): audren:u (hle_audren_sv.c)
static const sf_service_ent *sf_registered(unsigned long *n) {
    unsigned long sz = 0; const sf_service_ent *e = (const sf_service_ent *)getsectiondata(&_mh_execute_header, "__DATA", "swsf", &sz);
    *n = e ? sz / sizeof *e : 0; return e;
}

static int sf_slot(uint32_t h) {
    unsigned i = h - SF_H0;
    return i < SF_MAX && g_sf[i].used ? (int)i : -1;
}
static uint32_t sf_new(const char *name, int domain, sf_answer answer, void *ctx, void (*close)(void *)) {
    pthread_mutex_lock(&g_sf_mu);
    unsigned i = 0; while (i < SF_MAX && g_sf[i].used) i++;
    if (i == SF_MAX) die("sf: more than %d host service sessions", SF_MAX);
    strlcpy(g_sf[i].name, name, sizeof g_sf[i].name); g_sf[i].used = 1; g_sf[i].domain = domain; g_sf[i].seen = 0; g_sf[i].answer = answer;
    g_sf[i].ctx = ctx; g_sf[i].close = close;
    pthread_mutex_unlock(&g_sf_mu);
    return SF_H0 + i;
}
uint32_t sf_session(const char *name, sf_answer answer, void *ctx, void (*close)(void *)) { return sf_new(name, 0, answer, ctx, close); }

// Bluetooth clients also connect at boot (Scarlet 4.0.0 aborts in InitializeBle without bt). bt and btm:u expose no remote devices: acquisition returns real, unsignalled
// kernel events, scans return no devices, and connection/GATT operations fail. The command layouts are libnx's bt.c / btmu.c; no Android Bluetooth access is implied.
static uint32_t sf_event(void) {   // a kernel event's read end, its write end closed at once: no host-side producer (nothing signals it), and the event goes when the client closes the read end
    uint32_t w = 0, r = hle_new_event(&w);
    uint64_t x[32] = {0}; x[0] = w; svc_thread(0x16, x);
    return r;
}
static Result bt_answer(sf_req *q) {
    if (q->cmd == 9) { q->out_copy = sf_event(); return RES_OK; }   // RegisterBleEvent: one copied event handle
    return RES_FAIL;   // no connected GATT peers or pending LE events
}
static Result btm_core_answer(sf_req *q) {
    switch (q->cmd) {
    case 0: case 17: case 21: case 26: case 33: {   // scan, connection, pairing, discovery and MTU events
        q->out_copy = sf_event(); uint32_t one = 1; memcpy(q->out, &one, 4); q->out_len = 4; break;
    }
    case 1: case 2: case 3: case 4: case 5: case 6: case 7: case 8: case 9: case 10:
    case 20: case 25: case 27: case 28: case 29: case 30: case 31: case 32: case 36: case 37:
        break;   // scans/registrations succeed; enumeration returns zero entries and lookups return false
    default: return RES_FAIL;   // cannot connect, pair or configure a device that is absent
    }
    return RES_OK;
}
static Result btm_answer(sf_req *q) {
    if (q->object != 2) {   // IBtmUser::GetCore: a domain object, or one moved session handle
        if (q->cmd != 0) return RES_FAIL;
        if (q->object) q->out_object = 2;   // a domain: the core object of the one controller's user core
        else q->out_move = sf_session("btm:core", btm_core_answer, NULL, NULL);
        return RES_OK;
    }
    return btm_core_answer(q);
}

HLE_FORCE("_ZN2nn2sf4hipc31InitializeHipcServiceResolutionEv", "service name resolution (connects to sm); the host resolves the names it serves itself", Result, h_sf_InitRes, void) { return RES_OK; }
HLE_FORCE("_ZN2nn2sf4hipc29FinalizeHipcServiceResolutionEv", "service name resolution (sm)", void, h_sf_FinRes, void) {}
HLE_FORCE("_ZN2nn2sf4hipc20ConnectToHipcServiceEPNS_3svc6HandleEPKc", "connect to a service through sm → a host session for the services the host serves", Result, h_sf_Connect,
          uint32_t *out, const char *name) {
    for (size_t k = 0; k < sizeof sf_served / sizeof *sf_served; k++)
        if (!strncmp(name, sf_served[k].name, 8)) {
            *out = sf_new(sf_served[k].name, 0, sf_served[k].answer, NULL, NULL); LOG("sf: %s → host session %#x", sf_served[k].name, *out); return RES_OK;
        }
    unsigned long nreg; const sf_service_ent *reg = sf_registered(&nreg);
    for (unsigned long k = 0; k < nreg; k++)
        if (!strncmp(name, reg[k].name, 8)) { *out = sf_new(reg[k].name, 0, reg[k].answer, NULL, NULL); LOG("sf: %s → host session %#x", reg[k].name, *out); return RES_OK; }
    LOG("[RED] sf: the game connects to service %.8s itself, which the host does not serve", name);
    return 0xe15u;   // sm ResultNotRegistered
}
HLE_FORCE("_ZN2nn2sf4hipc24CloseClientSessionHandleENS_3svc6HandleE", "close a session (kernel handle) → the host session", void, h_sf_Close, uint32_t h) {
    int i = sf_slot(h);
    if (i < 0) { LOG("[RED] sf: CloseClientSessionHandle on %#x, not a host session", h); return; }
    pthread_mutex_lock(&g_sf_mu); void (*close)(void *) = g_sf[i].close; void *ctx = g_sf[i].ctx; g_sf[i].used = 0; g_sf[i].close = NULL; g_sf[i].ctx = NULL; pthread_mutex_unlock(&g_sf_mu);
    if (close) close(ctx);   // the session's own state ends with it (an audio renderer stops rendering)
}
// Control requests: the pointer buffer is the server's receive buffer for X descriptors (none: the host reads them in place), the domain conversion makes the session's object 1, a duplicate
// is a new session to the same object
HLE_FORCE("_ZN2nn2sf4hipc6client22QueryPointerBufferSizeEPtNS_3svc6HandleE", "control request: pointer buffer size of a session → 0 for a host session", Result, h_sf_PtrBuf, uint16_t *out, uint32_t h) {
    if (sf_slot(h) < 0) { LOG("[RED] sf: QueryPointerBufferSize on %#x, not a host session", h); return RES_FAIL; }
    *out = 0; return RES_OK;
}
HLE_FORCE("_ZN2nn2sf4hipc6client22ConvertSessionToDomainEPNS0_4cmif18CmifDomainObjectIdENS_3svc6HandleE", "control request: convert a session to a domain → the host session's object 1", Result, h_sf_ToDomain,
          uint32_t *out, uint32_t h) {
    int i = sf_slot(h);
    if (i < 0) { LOG("[RED] sf: ConvertSessionToDomain on %#x, not a host session", h); return RES_FAIL; }
    g_sf[i].domain = 1; *out = 1; return RES_OK;
}
HLE_FORCE("_ZN2nn2sf4hipc6client16DuplicateSessionEPNS_3svc6HandleES4_", "control request: clone a session → a new host session to the same service", Result, h_sf_Dup, uint32_t *out, uint32_t h) {
    int i = sf_slot(h);
    if (i < 0) { LOG("[RED] sf: DuplicateSession on %#x, not a host session", h); return RES_FAIL; }
    *out = sf_new(g_sf[i].name, g_sf[i].domain, g_sf[i].answer, g_sf[i].ctx, NULL); return RES_OK;   // the state stays the original session's: it ends when that one closes
}
HLE_FORCE("_ZN2nn2sf4hipc6client18DuplicateSessionExEPNS_3svc6HandleES4_j", "control request: clone a session (with a tag) → a new host session to the same service", Result, h_sf_DupEx,
          uint32_t *out, uint32_t h, unsigned tag) { (void)tag; return h_sf_Dup(out, h); }

// A request on a host session. The message (Atmosphère / libnx): HIPC header {u16 type, X / A / B / W descriptor counts; data word count, special header flag}, the special header (process ID,
// copied and moved handles) and the descriptors, then the data words, whose CMIF part starts 16-byte aligned to the buffer. X descriptors are 2 words {index:6, address 36..41:6, address
// 32..35:4, size:16; address low}, A / B / W 3 words {size low; address low; mode:2, address 36..57:22, size 32..35:4, address 32..35:4}
static uint64_t sf_desc_addr(uint32_t lo, uint32_t mid4, uint32_t hi) { return lo | (uint64_t)mid4 << 32 | (uint64_t)hi << 36; }
HLE_FORCE("_ZN2nn2sf4hipc15SendSyncRequestENS_3svc6HandleEPvm", "send a request on a session (svcSendSyncRequest) → answered by the host for its sessions", Result, h_sf_Send,
          uint32_t h, uint32_t *m, uint64_t size) {
    int i = sf_slot(h);
    if (i < 0 || size < 0x40) { LOG("[RED] sf: SendSyncRequest on %#x (%llu bytes), not a host session", h, (unsigned long long)size); return 0xe401u; }   // kernel ResultInvalidHandle
    uint8_t *base = (uint8_t *)m;
    unsigned type = m[0] & 0xffff, nx = m[0] >> 16 & 15, na = m[0] >> 20 & 15, nb = m[0] >> 24 & 15, nw = m[0] >> 28 & 15, ndw = m[1] & 0x3ff;
    sf_req q; memset(&q, 0, sizeof q);
    uint32_t *p = m + 2;
    if (m[1] >> 31) {   // special header: process ID, copied and moved handles
        uint32_t sh = *p++; if (sh & 1) p += 2;
        for (unsigned k = 0; k < (sh >> 1 & 15); k++, p++) if (q.ncopy < 4) q.copy[q.ncopy++] = *p;
        for (unsigned k = 0; k < (sh >> 5 & 15); k++, p++) if (q.nmove < 4) q.move[q.nmove++] = *p;
    }
    for (unsigned k = 0; k < nx; k++, p += 2)
        if (q.nx < 4) { q.x[q.nx].p = (uint8_t *)(uintptr_t)sf_desc_addr(p[1], p[0] >> 12 & 15, p[0] >> 6 & 63); q.x[q.nx++].size = p[0] >> 16; }
    for (unsigned k = 0; k < na + nb + nw; k++, p += 3) {
        uint8_t *a = (uint8_t *)(uintptr_t)sf_desc_addr(p[1], p[2] >> 28, p[2] >> 2 & 0x3fffff); uint64_t n = p[0] | (uint64_t)(p[2] >> 24 & 15) << 32;
        if (k < na) { if (q.na < 4) { q.a[q.na].p = a; q.a[q.na++].size = n; } }
        else if (k < na + nb) { if (q.nb < 4) { q.b[q.nb].p = a; q.b[q.nb++].size = n; } }
    }
    size_t data = (size_t)((uint8_t *)p - base), cm = (data + 15) & ~(size_t)15;
    int dom = g_sf[i].domain && (type == 4 || type == 6);
    unsigned dtype = dom ? base[cm] : 1, obj = dom ? *(uint32_t *)(base + cm + 4) : 0;
    size_t hin = cm + (dom ? 16 : 0);
    if (hin + 16 > size || data + ndw * 4 > size) { LOG("[RED] sf: %s: request of %u data words does not fit %llu bytes", g_sf[i].name, ndw, (unsigned long long)size); return 0xe401u; }
    uint32_t magic = *(uint32_t *)(base + hin), ver = *(uint32_t *)(base + hin + 4), cmd = *(uint32_t *)(base + hin + 8);
    static int trace = -1; if (trace < 0) trace = getenv("DM_SF_TRACE") && atoi(getenv("DM_SF_TRACE"));
    if (trace) { char hx[3 * 0x80 + 1] = ""; for (unsigned k = 0; k < 0x80 && k < size; k++) snprintf(hx + 3 * k, 4, "%02x ", base[k]); LOG("sf: %s request: %s", g_sf[i].name, hx); }
    if (type == 2) return 0xf601u;   // HIPC Close: the session ends, and the kernel answers the closed-session result (SDK 21's client sends one for a session it is done with:
                                     // Star Fox's camera session; SDK 1.3.1's audren client closes a session it is done with too, Rayman Legends at 9.5 s)
    if (type != 4 && type != 6) { LOG("[RED] sf: %s: HIPC message type %u not served", g_sf[i].name, type); return 0xe401u; }
    if (dtype == 2) { memset(base, 0, 16); return RES_OK; }   // a domain object closed: nothing to answer
    if (magic != 0x49434653) { LOG("[RED] sf: %s: request without SFCI (%#x)", g_sf[i].name, magic); return 0xe401u; }
    q.cmd = cmd & 0xffff; q.object = obj; q.in = base + hin + 16; q.in_len = data + ndw * 4 > hin + 16 ? (size_t)(data + ndw * 4 - hin - 16) : 0; q.ctx = g_sf[i].ctx;
    unsigned key = (obj & 0xff) << 16 | (cmd & 0xffff);   // log each object / command once
    pthread_mutex_lock(&g_sf_mu); int first = g_sf[i].seen != key; g_sf[i].seen = key; pthread_mutex_unlock(&g_sf_mu);
    Result result = g_sf[i].answer ? g_sf[i].answer(&q) : RES_OK;
    if (q.out_move && dom) { LOG("[RED] sf: %s: command %u returns an object in a domain, which the host does not serve", g_sf[i].name, q.cmd); q.out_move = 0; result = 0xe401u; }
    if (first || trace) LOG("sf: %s object %u command %u (%u data words in): %s%s%s%s", g_sf[i].name, obj, cmd, ndw, result ? "fails" : g_sf[i].answer ? "success" : "success, zeroed output",
                            q.out_copy ? ", a copied handle" : "", q.out_move ? ", a moved session" : "", q.out_object ? ", an out object" : "");
    // The response: no descriptors. The special header (its handle counts) and the handles come right after the HIPC header, when there are any; the CMIF part starts at the next
    // 16-byte boundary (+0x10 with none or one handle), the domain's out header first in a domain. Everything after it up to the buffer's end reads as zero
    unsigned nc = q.out_copy ? 1 : 0, nm = q.out_move ? 1 : 0;
    size_t end = size < 0x1000 ? size : 0x1000, dw = 8 + (nc + nm ? 4 + 4 * (nc + nm) : 0);
    memset(base, 0, end);
    m[1] = (uint32_t)((end - dw) / 4 < 0x3ff ? (end - dw) / 4 : 0x3ff);
    if (nc + nm) { m[1] |= 1u << 31; m[2] = nc << 1 | nm << 5; if (nc) m[3] = q.out_copy; if (nm) m[3 + nc] = q.out_move; }
    size_t out = ((dw + 15) & ~(size_t)15) + (dom ? 16 : 0);
    uint32_t *o = (uint32_t *)(base + out); o[0] = 0x4f434653; o[1] = ver; o[2] = result; o[3] = 0;   // "SFCO"; a domain's out-object count before it stays 0
    if (q.out_object && dom) { *(uint32_t *)(base + out - 16) = 1; o[4] = q.out_object; }
    if (!result && q.out_len) memcpy(base + out + 16, q.out, q.out_len <= sizeof q.out && out + 16 + q.out_len <= end ? q.out_len : 0);
    return RES_OK;
}
