package com.kemi.mypad;

import android.content.Context;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

final class FileOperations {
    private FileOperations() { }

    static boolean canDelete(Context context, File file) {
        if (file == null || !file.exists()) return false;
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (isChildOf(file, downloads)) return true;
        StorageManager manager = context.getSystemService(StorageManager.class);
        if (manager == null) return false;
        for (StorageVolume volume : manager.getStorageVolumes()) {
            File root = volume.getDirectory();
            String state = volume.getState();
            boolean mounted = Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
            if (!volume.isPrimary() && mounted && root != null && isChildOf(file, root)) return true;
        }
        return false;
    }

    static List<File> destinationRoots(Context context) {
        List<File> result = new ArrayList<>();
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloads.isDirectory() && downloads.canWrite()) result.add(downloads);
        StorageManager manager = context.getSystemService(StorageManager.class);
        if (manager != null) for (StorageVolume volume : manager.getStorageVolumes()) {
            File root = volume.getDirectory();
            if (!volume.isPrimary() && Environment.MEDIA_MOUNTED.equals(volume.getState())
                    && root != null && root.isDirectory() && root.canWrite()) result.add(root);
        }
        return result;
    }

    static int transfer(List<File> sources, File destination, boolean move) {
        if (sources == null || destination == null || !destination.isDirectory() || !destination.canWrite()) return 0;
        int completed = 0;
        for (File source : sources) try {
            String sourcePath = source.getCanonicalPath();
            String destinationPath = destination.getCanonicalPath();
            if (source.isDirectory() && destinationPath.startsWith(sourcePath + File.separator)) continue;
            File target = uniqueDestination(destination, source.getName());
            boolean ok;
            if (move && source.renameTo(target)) ok = true;
            else {
                ok = copyRecursively(source, target);
                if (ok && move) ok = deleteRecursively(source);
            }
            if (ok) completed++;
        } catch (Exception ignored) { }
        return completed;
    }

    static boolean deleteRecursively(File file) {
        if (file == null) return false;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) if (!deleteRecursively(child)) return false;
        }
        return file.delete();
    }

    private static boolean isChildOf(File file, File root) {
        try {
            String path = file.getCanonicalPath();
            String rootPath = root.getCanonicalPath();
            return !path.equals(rootPath) && path.startsWith(rootPath + File.separator);
        } catch (Exception ignored) { return false; }
    }

    private static File uniqueDestination(File directory, String name) {
        File target = new File(directory, name);
        if (!target.exists()) return target;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        int index = 2;
        while (target.exists()) target = new File(directory, stem + " (" + index++ + ")" + extension);
        return target;
    }

    private static boolean copyRecursively(File source, File target) {
        try {
            if (source.isDirectory()) {
                if (!target.mkdirs() && !target.isDirectory()) return false;
                File[] children = source.listFiles();
                if (children != null) for (File child : children) {
                    if (!copyRecursively(child, new File(target, child.getName()))) return false;
                }
                return true;
            }
            try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            }
            target.setLastModified(source.lastModified());
            return true;
        } catch (Exception ignored) { return false; }
    }
}
