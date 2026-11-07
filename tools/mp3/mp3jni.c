// TV Town's MP3 maker: a thin bridge to LAME 3.100 (LGPL). Rebuild with tools/mp3/build.sh.
#include <jni.h>
#include <stdint.h>
#include "lame.h"

JNIEXPORT jlong JNICALL Java_com_nareshjagriya_tvtown_Mp3Lame_open(JNIEnv *e, jclass c, jint rate, jint channels, jint kbps) {
    lame_t l = lame_init();
    if (!l) return 0;
    lame_set_in_samplerate(l, rate);
    lame_set_num_channels(l, channels);
    lame_set_brate(l, kbps);
    lame_set_quality(l, 5);
    lame_set_bWriteVbrTag(l, 0);
    lame_set_write_id3tag_automatic(l, 0);
    if (lame_init_params(l) < 0) {
        lame_close(l);
        return 0;
    }
    return (jlong) (intptr_t) l;
}

JNIEXPORT jint JNICALL Java_com_nareshjagriya_tvtown_Mp3Lame_encode(JNIEnv *e, jclass c, jlong h, jshortArray pcm, jint frames, jint channels, jbyteArray out) {
    lame_t l = (lame_t) (intptr_t) h;
    jshort *in = (*e)->GetShortArrayElements(e, pcm, 0);
    jbyte *o = (*e)->GetByteArrayElements(e, out, 0);
    jsize size = (*e)->GetArrayLength(e, out);
    int n = channels == 1 ? lame_encode_buffer(l, in, in, frames, (unsigned char *) o, size)
                          : lame_encode_buffer_interleaved(l, in, frames, (unsigned char *) o, size);
    (*e)->ReleaseByteArrayElements(e, out, o, 0);
    (*e)->ReleaseShortArrayElements(e, pcm, in, JNI_ABORT);
    return n;
}

JNIEXPORT jint JNICALL Java_com_nareshjagriya_tvtown_Mp3Lame_flush(JNIEnv *e, jclass c, jlong h, jbyteArray out) {
    jbyte *o = (*e)->GetByteArrayElements(e, out, 0);
    int n = lame_encode_flush((lame_t) (intptr_t) h, (unsigned char *) o, (*e)->GetArrayLength(e, out));
    (*e)->ReleaseByteArrayElements(e, out, o, 0);
    return n;
}

JNIEXPORT void JNICALL Java_com_nareshjagriya_tvtown_Mp3Lame_close(JNIEnv *e, jclass c, jlong h) {
    lame_close((lame_t) (intptr_t) h);
}
