package com.kemi.mypad;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates a portable ZIP for sharing a folder through Android's share sheet. */
final class FolderArchive {
    private FolderArchive() { }

    static String safeArchiveName(String folderName) {
        String safe = folderName == null ? "文件夹" : folderName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safe.isEmpty()) safe = "文件夹";
        return safe + ".zip";
    }

    static void zip(File directory, File output) throws IOException {
        if (directory == null || !directory.isDirectory()) throw new IOException("Folder is unavailable");
        File root = directory.getCanonicalFile();
        File target = output.getCanonicalFile();
        File parent = target.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) throw new IOException("Cannot create output folder");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(target))) {
            add(zip, root, root, root.getName() + "/");
        } catch (IOException error) {
            if (target.exists()) target.delete();
            throw error;
        }
    }

    private static void add(ZipOutputStream zip, File root, File item, String entryName) throws IOException {
        File canonical = item.getCanonicalFile();
        String rootPath = root.getPath();
        if (!(canonical.getPath().equals(rootPath) || canonical.getPath().startsWith(rootPath + File.separator))) {
            throw new IOException("Folder contains an unsafe link");
        }
        if (canonical.isDirectory()) {
            ZipEntry directoryEntry = new ZipEntry(entryName);
            directoryEntry.setTime(canonical.lastModified());
            zip.putNextEntry(directoryEntry);
            zip.closeEntry();
            File[] children = canonical.listFiles();
            if (children == null) throw new IOException("Cannot read " + canonical.getName());
            for (File child : children) add(zip, root, child, entryName + child.getName() + (child.isDirectory() ? "/" : ""));
            return;
        }
        ZipEntry fileEntry = new ZipEntry(entryName);
        fileEntry.setTime(canonical.lastModified());
        zip.putNextEntry(fileEntry);
        byte[] buffer = new byte[64 * 1024];
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(canonical))) {
            int count;
            while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
        }
        zip.closeEntry();
    }
}
