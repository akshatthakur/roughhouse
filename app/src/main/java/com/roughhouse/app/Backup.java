package com.roughhouse.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** A whole jam (settings, audio, analysis) in one zip file, for sharing and backups. */
final class Backup {
    private Backup() {}

    static final String EXT = ".roughhouse.zip";

    static void write(Jam s, OutputStream out) throws IOException {
        s.save();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            File[] fs = s.dir.listFiles();
            if (fs == null) throw new IOException("jam folder missing");
            byte[] buf = new byte[1 << 16];
            for (File f : fs) {
                if (!f.isFile() || !safe(f.getName()) || f.getName().endsWith(".tmp")) continue;
                z.putNextEntry(new ZipEntry(f.getName()));
                try (InputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
                }
                z.closeEntry();
            }
        }
    }

    /** Unpacks a backup into a new jam folder under root and returns it. */
    static File read(InputStream in, File root) throws IOException {
        File dir = new File(root, "s" + System.currentTimeMillis());
        if (!dir.mkdirs()) throw new IOException("couldn't create a folder for the jam");
        boolean hasJson = false;
        long total = 0;
        try (ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() || !safe(name)) continue; // ignore anything with a path in it
                if (name.equals("session.json")) hasJson = true;
                try (OutputStream o = new FileOutputStream(new File(dir, name))) {
                    int n;
                    while ((n = z.read(buf)) > 0) {
                        total += n;
                        if (total > 2_000_000_000L) throw new IOException("that backup is too large");
                        o.write(buf, 0, n);
                    }
                }
            }
        } catch (IOException ex) {
            Jam.deleteDir(dir);
            throw ex;
        }
        if (!hasJson) {
            Jam.deleteDir(dir);
            throw new IOException("that file isn't a Roughhouse jam");
        }
        return dir;
    }

    /** Only plain file names the app itself writes: no folders, no "..". */
    static boolean safe(String name) {
        return name.matches("session\\.json|t\\d+\\.pcm|a\\d+\\.json");
    }
}
