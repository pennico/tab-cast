// SPDX-License-Identifier: GPL-3.0-or-later
//
// Tab Cast's link to Tab Cast TV, in C (the Mac side). Same wire format as link/Wire.kt: the 4 bytes "TCM1", then
// messages with a 16-byte big-endian header (type u8, flags u8, reserved u16, payload length u32, pts i64 µs) and a
// payload; text payloads are key=value lines.

#ifndef TC_LINK_H
#define TC_LINK_H

#include <stddef.h>
#include <stdint.h>

enum {
    TC_PORT = 46898,
    TC_HELLO = 1, TC_VIDEO_CONFIG = 2, TC_VIDEO = 3, TC_AUDIO_CONFIG = 4, TC_AUDIO = 5, TC_STATE = 6, TC_PING = 7,
    TC_BYE = 8, TC_PAIR = 17,
    TC_HELLO_ACK = 101, TC_KEYFRAME = 102, TC_PONG = 103, TC_STATS = 104, TC_STATUS = 105, TC_PAIR_RESULT = 108,
    TC_FLAG_KEY = 1,
    TC_MAX_PAYLOAD = 8 << 20,
};

typedef struct TcLink TcLink;

/** Called on the reader thread for every message except PING (answered by itself) and PONG (sets the round trip). */
typedef void (*TcOnMessage)(int type, int flags, const uint8_t *payload, uint32_t len, void *ctx);

/** Connects and says HELLO (token may be NULL or ""). NULL on failure, with a plain reason in err. */
TcLink *tc_connect(const char *host, int port, const char *name, const char *app, const char *token, int timeout_ms,
                   char *err, size_t errlen);

/** A value of the TV's HELLO_ACK (e.g. "paired", "codecs", "name", "app"), or NULL (valid until 8 more calls). */
const char *tc_hello(const TcLink *l, const char *key);

/**
 * Sends the PIN the TV shows and waits for PAIR_RESULT (up to timeout_ms). 1 = paired, token copied to token_out;
 * 0 = refused, reason in err.
 */
int tc_pair(TcLink *l, const char *pin, const char *name, int timeout_ms, char *token_out, size_t tokenlen, char *err,
            size_t errlen);

/** Sends one message; safe from several threads. 0 = sent, -1 = the link is broken. */
int tc_send(TcLink *l, int type, int flags, int64_t pts, const void *payload, uint32_t len);
int tc_send_kv(TcLink *l, int type, const char *kv);

/** Starts the reader thread (after pairing). */
void tc_start_reader(TcLink *l, TcOnMessage on, void *ctx);

/** Last measured round trip in ms (PING/PONG), or -1. */
double tc_rtt_ms(const TcLink *l);

/** 1 while the link is up. */
int tc_alive(const TcLink *l);

/** Sends BYE, closes, frees. */
void tc_close(TcLink *l);

/** The value of key in a key=value payload, copied into out (NUL-terminated); 1 when found. */
int tc_kv_get(const uint8_t *kv, uint32_t len, const char *key, char *out, size_t outlen);

#endif
