package com.vstplayer.core;

import java.io.File;

/** Signature mirror for the PC compile check only. */
public final class Tags {

    public static final class Info {
        public String title, artist, album, albumArtist, lyrics;
    }

    public static Info read(File f) {
        return new Info();
    }
}
