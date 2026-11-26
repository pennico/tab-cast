// SPDX-License-Identifier: GPL-3.0-or-later
// Tab Cast for Mac: the send queue (see sendq.h).

#include "sendq.h"

#include <pthread.h>
#include <stdlib.h>
#include <string.h>

enum { KIND_CONTROL, KIND_AUDIO, KIND_VIDEO };

typedef struct Item {
    struct Item *next;
    int kind, type, flags;
    int64_t pts;
    size_t len;
    uint8_t data[];
} Item;

typedef struct { Item *head, *tail; size_t n, bytes; } List;

struct SendQ {
    SqSend send;
    SqBehind behind;
    void *ctx;
    size_t maxVideoBytes, maxAudioChunks;
    pthread_mutex_t m;
    pthread_cond_t c;
    pthread_t thread;
    List control, audio, video;
    int stop, waitKey;
    SqStats st;
};

static void push(List *l, Item *it) {
    it->next = NULL;
    if (l->tail) l->tail->next = it; else l->head = it;
    l->tail = it; l->n++; l->bytes += it->len;
}

static Item *pop(List *l) {
    Item *it = l->head;
    if (!it) return NULL;
    l->head = it->next;
    if (!l->head) l->tail = NULL;
    l->n--; l->bytes -= it->len;
    return it;
}

static void clear(List *l, long *count) {
    Item *it;
    while ((it = pop(l))) { if (count) (*count)++; free(it); }
}

static Item *item(int kind, int type, int flags, const void *data, size_t len, int64_t pts) {
    Item *it = malloc(sizeof *it + len);
    if (!it) return NULL;
    it->kind = kind; it->type = type; it->flags = flags; it->pts = pts; it->len = len;
    if (len) memcpy(it->data, data, len);
    return it;
}

static void *loop(void *arg) {
    SendQ *q = arg;
    for (;;) {
        pthread_mutex_lock(&q->m);
        while (!q->stop && !q->control.head && !q->audio.head && !q->video.head) pthread_cond_wait(&q->c, &q->m);
        Item *it = pop(&q->control);
        if (!it) it = pop(&q->audio);
        if (!it) it = pop(&q->video);
        q->st.videoQueuedBytes = q->video.bytes;
        int stopping = q->stop;
        pthread_mutex_unlock(&q->m);
        if (!it) { if (stopping) break; continue; }
        int broken = q->st.broken;
        if (!broken && q->send(q->ctx, it->type, it->flags, it->pts, it->data, (uint32_t)it->len)) broken = 1;
        pthread_mutex_lock(&q->m);
        if (broken) {
            q->st.broken = 1;
            clear(&q->control, NULL); clear(&q->audio, &q->st.audioDropped); clear(&q->video, &q->st.videoDropped);
        } else if (it->kind == KIND_VIDEO) q->st.videoSent++;
        else if (it->kind == KIND_AUDIO) q->st.audioSent++;
        pthread_mutex_unlock(&q->m);
        free(it);
    }
    return NULL;
}

SendQ *sq_new(SqSend send, SqBehind behind, void *ctx, size_t maxVideoBytes, size_t maxAudioChunks) {
    SendQ *q = calloc(1, sizeof *q);
    if (!q) return NULL;
    q->send = send; q->behind = behind; q->ctx = ctx;
    q->maxVideoBytes = maxVideoBytes; q->maxAudioChunks = maxAudioChunks;
    pthread_mutex_init(&q->m, NULL);
    pthread_cond_init(&q->c, NULL);
    if (pthread_create(&q->thread, NULL, loop, q)) { free(q); return NULL; }
    return q;
}

void sq_video(SendQ *q, int type, const void *data, size_t len, int key, int64_t pts) {
    int callBehind = 0;
    pthread_mutex_lock(&q->m);
    if (q->st.broken) { q->st.videoDropped++; pthread_mutex_unlock(&q->m); return; }
    if (!key && (q->waitKey || q->video.bytes + len > q->maxVideoBytes)) {
        // Behind: what waits is stale, and without it the next frames can't be decoded: drop until a key frame.
        if (!q->waitKey) { q->st.behind++; callBehind = 1; clear(&q->video, &q->st.videoDropped); }
        q->waitKey = 1;
        q->st.videoDropped++;
    } else {
        if (key) {
            // A key frame starts a fresh picture: anything older that still waits is no longer needed.
            if (q->video.bytes + len > q->maxVideoBytes) clear(&q->video, &q->st.videoDropped);
            q->waitKey = 0;
        }
        Item *it = item(KIND_VIDEO, type, key ? 1 : 0, data, len, pts);
        if (it) { push(&q->video, it); pthread_cond_signal(&q->c); } else q->st.videoDropped++;
    }
    pthread_mutex_unlock(&q->m);
    if (callBehind && q->behind) q->behind(q->ctx);
}

void sq_audio(SendQ *q, int type, const void *data, size_t len, int64_t pts) {
    pthread_mutex_lock(&q->m);
    if (q->st.broken) { q->st.audioDropped++; pthread_mutex_unlock(&q->m); return; }
    while (q->audio.n >= q->maxAudioChunks) { free(pop(&q->audio)); q->st.audioDropped++; }
    Item *it = item(KIND_AUDIO, type, 0, data, len, pts);
    if (it) { push(&q->audio, it); pthread_cond_signal(&q->c); }
    pthread_mutex_unlock(&q->m);
}

void sq_control(SendQ *q, int type, const void *data, size_t len) {
    pthread_mutex_lock(&q->m);
    Item *it = item(KIND_CONTROL, type, 0, data, len, 0);
    if (it) { push(&q->control, it); pthread_cond_signal(&q->c); }
    pthread_mutex_unlock(&q->m);
}

SqStats sq_stats(SendQ *q) {
    pthread_mutex_lock(&q->m);
    SqStats s = q->st;
    s.videoQueuedBytes = q->video.bytes;
    pthread_mutex_unlock(&q->m);
    return s;
}

void sq_free(SendQ *q) {
    if (!q) return;
    pthread_mutex_lock(&q->m);
    q->stop = 1;
    pthread_cond_signal(&q->c);
    pthread_mutex_unlock(&q->m);
    pthread_join(q->thread, NULL);
    clear(&q->control, NULL); clear(&q->audio, NULL); clear(&q->video, NULL);
    pthread_cond_destroy(&q->c);
    pthread_mutex_destroy(&q->m);
    free(q);
}
