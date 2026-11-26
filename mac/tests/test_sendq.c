// SPDX-License-Identifier: GPL-3.0-or-later
// Tests of the send queue with a slow fake network.

#include "../src/sendq.h"

#include <assert.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

typedef struct {
    pthread_mutex_t m;
    int log[4096];        // what was sent, in order: +n = video frame n (key: n+10000), -1 = audio, -2 = control
    int n;
    int usPerSend;
    int failAfter;        // break the link after this many sends (0: never)
    int behind;
} Fake;

static int fakeSend(void *ctx, int type, int flags, int64_t pts, const void *p, uint32_t len) {
    (void)p; (void)len;
    Fake *f = ctx;
    usleep((useconds_t)f->usPerSend);
    pthread_mutex_lock(&f->m);
    if (f->failAfter && f->n >= f->failAfter) { pthread_mutex_unlock(&f->m); return -1; }
    f->log[f->n++] = type == 3 ? (int)pts + (flags & 1 ? 10000 : 0) : type == 5 ? -1 : -2;
    pthread_mutex_unlock(&f->m);
    return 0;
}

static void fakeBehind(void *ctx) { ((Fake *)ctx)->behind++; }

static void waitIdle(SendQ *q) {
    for (int i = 0; i < 400; i++) {
        SqStats s = sq_stats(q);
        if (s.videoQueuedBytes == 0) { usleep(20000); return; }
        usleep(5000);
    }
}

static void soundGoesFirst(void) {
    Fake f = { PTHREAD_MUTEX_INITIALIZER, {0}, 0, 20000, 0, 0 };
    SendQ *q = sq_new(fakeSend, fakeBehind, &f, 1 << 20, 50);
    uint8_t b[100] = {0};
    sq_video(q, 3, b, sizeof b, 1, 1);           // taken at once by the thread
    usleep(2000);
    for (int i = 2; i <= 4; i++) sq_video(q, 3, b, sizeof b, 0, i);
    sq_audio(q, 5, b, 10, 0);
    sq_control(q, 6, "mirror=off", 10);
    waitIdle(q);
    sq_free(q);
    // After the first frame: control, then sound, then the waiting frames in order.
    assert(f.log[0] == 10001);
    assert(f.log[1] == -2);
    assert(f.log[2] == -1);
    assert(f.log[3] == 2 && f.log[4] == 3 && f.log[5] == 4);
    printf("ok soundGoesFirst\n");
}

static void behindDropsUntilAKeyFrame(void) {
    Fake f = { PTHREAD_MUTEX_INITIALIZER, {0}, 0, 30000, 0, 0 };
    SendQ *q = sq_new(fakeSend, fakeBehind, &f, 1000, 50);   // 1000 B of picture may wait
    uint8_t b[300] = {0};
    sq_video(q, 3, b, sizeof b, 1, 1);
    for (int i = 2; i <= 20; i++) sq_video(q, 3, b, sizeof b, 0, i);  // far more than 1000 B: falls behind
    assert(f.behind == 1);                                           // told once, not for every frame
    for (int i = 21; i <= 25; i++) sq_video(q, 3, b, sizeof b, 0, i); // still waiting for a key frame: dropped
    sq_video(q, 3, b, sizeof b, 1, 26);
    sq_video(q, 3, b, sizeof b, 0, 27);
    waitIdle(q);
    SqStats s = sq_stats(q);
    sq_free(q);
    assert(s.videoDropped > 0 && s.behind == 1);
    // Never a non-key frame right after a gap: every sent frame follows its predecessor or is a key frame.
    int last = 0;
    for (int i = 0; i < f.n; i++) {
        int v = f.log[i];
        if (v < 0) continue;
        int key = v >= 10000, n = key ? v - 10000 : v;
        assert(key || n == last + 1);
        last = n;
    }
    assert(last == 27);
    printf("ok behindDropsUntilAKeyFrame (sent %d, dropped %ld)\n", f.n, s.videoDropped);
}

static void oldSoundIsDropped(void) {
    Fake f = { PTHREAD_MUTEX_INITIALIZER, {0}, 0, 50000, 0, 0 };
    SendQ *q = sq_new(fakeSend, fakeBehind, &f, 1 << 20, 3);
    uint8_t b[10] = {0};
    for (int i = 0; i < 10; i++) sq_audio(q, 5, b, sizeof b, i);
    usleep(300000);
    SqStats s = sq_stats(q);
    sq_free(q);
    assert(s.audioDropped >= 6);
    printf("ok oldSoundIsDropped (dropped %ld)\n", s.audioDropped);
}

static void aBrokenLinkStopsSending(void) {
    Fake f = { PTHREAD_MUTEX_INITIALIZER, {0}, 0, 1000, 2, 0 };
    SendQ *q = sq_new(fakeSend, fakeBehind, &f, 1 << 20, 50);
    uint8_t b[10] = {0};
    for (int i = 1; i <= 5; i++) sq_video(q, 3, b, sizeof b, i == 1, i);
    usleep(100000);
    SqStats s = sq_stats(q);
    assert(s.broken);
    sq_video(q, 3, b, sizeof b, 1, 6);
    sq_free(q);
    assert(f.n == 2);
    printf("ok aBrokenLinkStopsSending\n");
}

int main(void) {
    soundGoesFirst();
    behindDropsUntilAKeyFrame();
    oldSoundIsDropped();
    aBrokenLinkStopsSending();
    printf("all sendq tests passed\n");
    return 0;
}
