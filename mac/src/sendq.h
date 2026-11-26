// SPDX-License-Identifier: GPL-3.0-or-later
//
// Tab Cast for Mac: the send queue between the encoders and the link. Capture and encoding never wait for the
// network: frames go into this queue, one thread sends them. Sound goes first (a gap in the sound is heard, a late
// frame is barely seen). When the picture falls behind (more than max_video_bytes waiting) the queued picture is
// dropped, nothing is sent until the next key frame, and on_behind is called so the encoder makes one and lowers
// its bitrate. A frame that can't be decoded without the ones before it is never sent after a gap.

#ifndef TC_SENDQ_H
#define TC_SENDQ_H

#include <stddef.h>
#include <stdint.h>

typedef struct SendQ SendQ;

/** Sends one message; 0 = sent, else the link is broken (the queue stops). Called on the queue's thread only. */
typedef int (*SqSend)(void *ctx, int type, int flags, int64_t pts, const void *payload, uint32_t len);
/** The picture fell behind: make a key frame (and send less). Called on the caller's thread of sq_video. */
typedef void (*SqBehind)(void *ctx);

typedef struct {
    long videoSent, videoDropped, audioSent, audioDropped, behind;
    size_t videoQueuedBytes;
    int broken;
} SqStats;

SendQ *sq_new(SqSend send, SqBehind behind, void *ctx, size_t maxVideoBytes, size_t maxAudioChunks);

/** Queues a picture (copied). key: a key frame. */
void sq_video(SendQ *q, int type, const void *data, size_t len, int key, int64_t pts);

/** Queues a sound chunk (copied); the oldest is dropped when more than maxAudioChunks wait. */
void sq_audio(SendQ *q, int type, const void *data, size_t len, int64_t pts);

/** Sends a control message (copied) before any queued media. */
void sq_control(SendQ *q, int type, const void *data, size_t len);

SqStats sq_stats(SendQ *q);

/** Sends what is queued (control first), stops the thread, frees. */
void sq_free(SendQ *q);

#endif
