package com.vstplayer.core;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Copies a rendered file into a folder the user picked through the Storage Access Framework. */
public final class Export {

    public static final int REQ_TREE = 42;
    private static final String KEY = "outTree";

    private Export() { }

    public static Uri tree(Context c) {
        SharedPreferences p = c.getSharedPreferences("vstp", Context.MODE_PRIVATE);
        String s = p.getString(KEY, null);
        return s == null ? null : Uri.parse(s);
    }

    public static void setTree(Context c, Uri uri) {
        SharedPreferences p = c.getSharedPreferences("vstp", Context.MODE_PRIVATE);
        p.edit().putString(KEY, uri == null ? null : uri.toString()).apply();
    }

    /** Human readable label for the picked folder. */
    public static String label(Context c) {
        Uri u = tree(c);
        if (u == null) return "应用内目录";
        String id = DocumentsContract.getTreeDocumentId(u);
        if (id == null) return "已选择目录";
        int i = id.indexOf(':');
        String path = i >= 0 ? id.substring(i + 1) : id;
        if (path == null || path.isEmpty()) return "存储根目录";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    public static Uri copy(Context c, File src, String displayName) throws IOException {
        return copy(c, src, displayName, null);
    }

    /**
     * Copies {@code src} into the picked folder, optionally inside (or nested under) one
     * sub-directory such as {@code "歌单名_rendered"}.
     *
     * <p>A re-render has to replace the previous copy: SAF providers (the AOSP one included)
     * silently rename a colliding create to {@code "name (1)"}, which used to leave one stale
     * duplicate per export. An existing document with the same name is truncated in place, and
     * only when the provider refuses to write it is it deleted and created again.
     *
     * @param folder sub-directory path relative to the picked folder, '/'-separated; null for the
     *               folder itself.
     * @return the written document uri, or null when no folder was picked.
     */
    public static Uri copy(Context c, File src, String displayName, String folder) throws IOException {
        Uri tree = tree(c);
        if (tree == null) return null;
        // The render is delivered as WAV (unknown source), FLAC (lossless source) or M4A
        // (lossy source); the media store needs the right type or the file cannot be played.
        String lower = displayName.toLowerCase(java.util.Locale.US);
        String mime = lower.endsWith(".flac") ? "audio/flac"
                : (lower.endsWith(".m4a") || lower.endsWith(".aac") ? "audio/mp4" : "audio/wav");
        return copyToTree(c, tree, src, displayName, mime, folder);
    }

    /** Copies {@code src} into an explicit tree (also used to push presets out of private storage). */
    public static Uri copyToTree(Context c, Uri tree, File src, String displayName, String mime,
                                 String folder) throws IOException {
        if (tree == null) return null;
        ContentResolver cr = c.getContentResolver();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        String parentId = rootId;
        if (folder != null && !folder.isEmpty()) parentId = ensureDir(cr, tree, rootId, folder);
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";

        String existing = findChild(cr, tree, parentId, displayName);
        if (existing != null) {
            Uri ex = DocumentsContract.buildDocumentUriUsingTree(tree, existing);
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = cr.openOutputStream(ex, "rwt")) {
                if (out != null) {
                    pump(in, out);
                    Diag.log("exported " + displayName + " -> " + ex + " (overwritten)");
                    return ex;
                }
            } catch (Exception e) {
                Diag.log("overwrite failed for " + displayName + " (" + e + "), recreating");
                try {
                    DocumentsContract.deleteDocument(cr, ex);
                } catch (Exception ignored) {
                }
            }
        }

        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId);
        Uri doc = DocumentsContract.createDocument(cr, parent, mime, displayName);
        if (doc == null) throw new IOException("无法创建输出文件");
        try (InputStream in = new FileInputStream(src);
             OutputStream out = cr.openOutputStream(doc, "wt")) {
            if (out == null) {
                try {
                    DocumentsContract.deleteDocument(cr, doc);
                } catch (Exception ignored) {
                }
                throw new IOException("无法写入输出文件");
            }
            pump(in, out);
        }
        Diag.log("exported " + displayName + " -> " + doc);
        return doc;
    }

    private static void pump(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        // read() may legitimately return 0 for a non-blocking stream; only -1 means EOF.
        while ((n = in.read(buf)) >= 0) {
            if (n > 0) out.write(buf, 0, n);
        }
    }

    /** Creates (or reuses) each '/'-separated segment below {@code rootId}; returns the leaf id. */
    private static String ensureDir(ContentResolver cr, Uri tree, String rootId, String folder)
            throws IOException {
        String parentId = rootId;
        for (String raw : folder.split("/")) {
            String name = raw.trim();
            if (name.isEmpty()) continue;
            String found = findChild(cr, tree, parentId, name);
            if (found == null) {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId);
                Uri made = DocumentsContract.createDocument(cr, parent,
                        DocumentsContract.Document.MIME_TYPE_DIR, name);
                if (made == null) throw new IOException("无法创建子目录 " + name);
                found = DocumentsContract.getDocumentId(made);
            }
            parentId = found;
        }
        return parentId;
    }

    /** @return the document id of the direct child called {@code name}, or null. */
    private static String findChild(ContentResolver cr, Uri tree, String parentId, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        Cursor cur = null;
        try {
            cur = cr.query(children, projection, null, null, null);
            if (cur == null) return null;
            while (cur.moveToNext()) {
                String dn = cur.getString(1);
                if (dn != null && dn.equalsIgnoreCase(name)) return cur.getString(0);
            }
        } catch (Exception e) {
            Diag.log("child query failed: " + e);
        } finally {
            if (cur != null) cur.close();
        }
        return null;
    }
}
