package com.nareshjagriya.tvtown;

/** LAME, the MP3 maker (LGPL), built by tools/mp3/build.sh. */
final class Mp3Lame {

    static {
        System.loadLibrary("tvtownmp3");
    }

    private Mp3Lame() {
    }

    static native long open(int rate, int channels, int kbps);

    static native int encode(long handle, short[] pcm, int frames, int channels, byte[] out);

    static native int flush(long handle, byte[] out);

    static native void close(long handle);
}
