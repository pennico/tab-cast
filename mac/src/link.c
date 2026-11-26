// SPDX-License-Identifier: GPL-3.0-or-later
// Tab Cast's link to Tab Cast TV, in C (see link.h).

#include "link.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <mach/mach_time.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

struct TcLink {
    int fd;
    pthread_mutex_t wlock;
    pthread_t reader;
    int readerStarted;
    _Atomic int alive;
    _Atomic double rtt;
    char hello[4096];
    uint32_t helloLen;
    TcOnMessage on;
    void *ctx;
};

static uint64_t nanos(void) {
    static mach_timebase_info_data_t tb;
    if (!tb.denom) mach_timebase_info(&tb);
    return mach_absolute_time() * tb.numer / tb.denom;
}

static void be16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)(v >> 8); p[1] = (uint8_t)v; }
static void be32(uint8_t *p, uint32_t v) { for (int i = 0; i < 4; i++) p[i] = (uint8_t)(v >> (24 - 8 * i)); }
static void be64(uint8_t *p, uint64_t v) { for (int i = 0; i < 8; i++) p[i] = (uint8_t)(v >> (56 - 8 * i)); }
static uint32_t rd32(const uint8_t *p) { return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3]; }
static uint64_t rd64(const uint8_t *p) { uint64_t v = 0; for (int i = 0; i < 8; i++) v = v << 8 | p[i]; return v; }

static int writeAll(int fd, const void *b, size_t n) {
    const uint8_t *p = b;
    while (n > 0) {
        ssize_t w = send(fd, p, n, 0);
        if (w < 0) { if (errno == EINTR) continue; return -1; }
        p += w; n -= (size_t)w;
    }
    return 0;
}

/** Reads exactly n bytes, waiting at most timeout_ms in total (-1: no limit). 0 = ok. */
static int readAll(int fd, void *b, size_t n, int timeout_ms) {
    uint8_t *p = b;
    uint64_t end = timeout_ms < 0 ? 0 : nanos() + (uint64_t)timeout_ms * 1000000ull;
    while (n > 0) {
        if (timeout_ms >= 0) {
            uint64_t now = nanos();
            if (now >= end) return -1;
            struct pollfd pf = { fd, POLLIN, 0 };
            int r = poll(&pf, 1, (int)((end - now) / 1000000ull) + 1);
            if (r <= 0) { if (r < 0 && errno == EINTR) continue; return -1; }
        }
        ssize_t got = recv(fd, p, n, 0);
        if (got <= 0) { if (got < 0 && errno == EINTR) continue; return -1; }
        p += got; n -= (size_t)got;
    }
    return 0;
}

/** Reads one message into a malloc'd payload (caller frees). 0 = ok. */
static int readMsg(int fd, int timeout_ms, int *type, int *flags, uint8_t **payload, uint32_t *len) {
    uint8_t h[16];
    if (readAll(fd, h, 16, timeout_ms)) return -1;
    *type = h[0]; *flags = h[1];
    *len = rd32(h + 4);
    if (*len > TC_MAX_PAYLOAD) return -1;
    *payload = malloc(*len + 1);
    if (!*payload) return -1;
    if (*len && readAll(fd, *payload, *len, timeout_ms)) { free(*payload); return -1; }
    (*payload)[*len] = 0;
    return 0;
}

int tc_send(TcLink *l, int type, int flags, int64_t pts, const void *payload, uint32_t len) {
    if (!l || !atomic_load(&l->alive) || len > TC_MAX_PAYLOAD) return -1;
    uint8_t h[16];
    h[0] = (uint8_t)type; h[1] = (uint8_t)flags; be16(h + 2, 0); be32(h + 4, len); be64(h + 8, (uint64_t)pts);
    pthread_mutex_lock(&l->wlock);
    int r = writeAll(l->fd, h, 16);
    if (!r && len) r = writeAll(l->fd, payload, len);
    pthread_mutex_unlock(&l->wlock);
    if (r) atomic_store(&l->alive, 0);
    return r;
}

int tc_send_kv(TcLink *l, int type, const char *kv) { return tc_send(l, type, 0, 0, kv, (uint32_t)strlen(kv)); }

int tc_kv_get(const uint8_t *kv, uint32_t len, const char *key, char *out, size_t outlen) {
    size_t kl = strlen(key);
    uint32_t i = 0;
    while (i < len) {
        uint32_t e = i;
        while (e < len && kv[e] != '\n') e++;
        if (e - i > kl && !memcmp(kv + i, key, kl) && kv[i + kl] == '=') {
            size_t n = e - i - kl - 1;
            if (n >= outlen) n = outlen - 1;
            memcpy(out, kv + i + kl + 1, n);
            out[n] = 0;
            return 1;
        }
        i = e + 1;
    }
    return 0;
}

const char *tc_hello(const TcLink *l, const char *key) {
    // A few rotating buffers, so several values can be used in one printf.
    static __thread char v[8][1024];
    static __thread int next;
    char *b = v[next++ & 7];
    return l && tc_kv_get((const uint8_t *)l->hello, l->helloLen, key, b, sizeof v[0]) ? b : NULL;
}

TcLink *tc_connect(const char *host, int port, const char *name, const char *app, const char *token, int timeout_ms,
                   char *err, size_t errlen) {
    char ps[16];
    snprintf(ps, sizeof ps, "%d", port);
    struct addrinfo hints = { 0 }, *ai = NULL;
    hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM;
    if (getaddrinfo(host, ps, &hints, &ai) || !ai) { snprintf(err, errlen, "can't find %s", host); return NULL; }
    int fd = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
    if (fd < 0) { freeaddrinfo(ai); snprintf(err, errlen, "no socket"); return NULL; }
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof one);
    int big = 4 << 20;
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &big, sizeof big);
    // Connect with a time limit (a TV on another network just doesn't answer).
    int fl = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, fl | O_NONBLOCK);
    int r = connect(fd, ai->ai_addr, ai->ai_addrlen);
    freeaddrinfo(ai);
    if (r < 0 && errno == EINPROGRESS) {
        struct pollfd pf = { fd, POLLOUT, 0 };
        r = poll(&pf, 1, timeout_ms) == 1 ? 0 : -1;
        int so = 0; socklen_t sl = sizeof so;
        if (!r && (getsockopt(fd, SOL_SOCKET, SO_ERROR, &so, &sl) || so)) r = -1;
    }
    fcntl(fd, F_SETFL, fl);
    if (r < 0) {
        close(fd);
        snprintf(err, errlen, "can't reach Tab Cast TV at %s:%d (is the TV app open, and is this Mac on the TV's Wi-Fi?)", host, port);
        return NULL;
    }
    TcLink *l = calloc(1, sizeof *l);
    l->fd = fd;
    pthread_mutex_init(&l->wlock, NULL);
    atomic_store(&l->alive, 1);
    atomic_store(&l->rtt, -1.0);
    char hello[1024];
    snprintf(hello, sizeof hello, "name=%s\napp=%s%s%s", name, app, token && *token ? "\ntoken=" : "", token && *token ? token : "");
    if (writeAll(fd, "TCM1", 4) || tc_send_kv(l, TC_HELLO, hello)) { snprintf(err, errlen, "the TV closed the connection"); tc_close(l); return NULL; }
    char magic[4];
    int type, flags; uint8_t *p; uint32_t len;
    if (readAll(fd, magic, 4, timeout_ms) || memcmp(magic, "TCM1", 4) || readMsg(fd, timeout_ms, &type, &flags, &p, &len)) {
        snprintf(err, errlen, "%s:%d isn't Tab Cast TV", host, port); tc_close(l); return NULL;
    }
    if (type != TC_HELLO_ACK) { free(p); snprintf(err, errlen, "unexpected answer %d", type); tc_close(l); return NULL; }
    l->helloLen = len < sizeof l->hello ? len : (uint32_t)sizeof l->hello - 1;
    memcpy(l->hello, p, l->helloLen);
    free(p);
    return l;
}

int tc_pair(TcLink *l, const char *pin, const char *name, int timeout_ms, char *token_out, size_t tokenlen, char *err,
            size_t errlen) {
    char kv[300];
    snprintf(kv, sizeof kv, "pin=%s\nname=%s", pin, name);
    if (tc_send_kv(l, TC_PAIR, kv)) { snprintf(err, errlen, "the TV closed the connection"); return 0; }
    uint64_t end = nanos() + (uint64_t)timeout_ms * 1000000ull;
    while (nanos() < end) {
        int type, flags; uint8_t *p; uint32_t len;
        if (readMsg(l->fd, (int)((end - nanos()) / 1000000ull) + 1, &type, &flags, &p, &len)) break;
        if (type == TC_PAIR_RESULT) {
            char ok[16] = "", why[200] = "";
            tc_kv_get(p, len, "ok", ok, sizeof ok);
            int good = !strcmp(ok, "true") && tc_kv_get(p, len, "token", token_out, tokenlen);
            if (!good) { tc_kv_get(p, len, "error", why, sizeof why); snprintf(err, errlen, "the TV refused the code%s%s", *why ? ": " : "", why); }
            free(p);
            return good;
        }
        free(p);
    }
    snprintf(err, errlen, "no answer from the TV to the code");
    return 0;
}

static void *readerLoop(void *arg) {
    TcLink *l = arg;
    while (atomic_load(&l->alive)) {
        int type, flags; uint8_t *p; uint32_t len;
        // The TV pings or sends status at least every few seconds; 15 s of silence means the link is gone.
        if (readMsg(l->fd, 15000, &type, &flags, &p, &len)) { atomic_store(&l->alive, 0); break; }
        if (type == TC_PING) tc_send(l, TC_PONG, 0, 0, p, len);
        else if (type == TC_PONG && len >= 8) atomic_store(&l->rtt, (double)(nanos() - rd64(p)) / 1e6);
        else if (l->on) l->on(type, flags, p, len, l->ctx);
        free(p);
    }
    return NULL;
}

void tc_start_reader(TcLink *l, TcOnMessage on, void *ctx) {
    l->on = on; l->ctx = ctx;
    if (!pthread_create(&l->reader, NULL, readerLoop, l)) l->readerStarted = 1;
}

double tc_rtt_ms(const TcLink *l) { return l ? atomic_load(&l->rtt) : -1; }
int tc_alive(const TcLink *l) { return l && atomic_load(&l->alive); }

void tc_close(TcLink *l) {
    if (!l) return;
    if (atomic_load(&l->alive)) tc_send(l, TC_BYE, 0, 0, NULL, 0);
    atomic_store(&l->alive, 0);
    shutdown(l->fd, SHUT_RDWR);
    close(l->fd);
    if (l->readerStarted) pthread_join(l->reader, NULL);
    pthread_mutex_destroy(&l->wlock);
    free(l);
}
