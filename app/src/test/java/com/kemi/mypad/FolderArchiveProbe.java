package com.kemi.mypad;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipFile;

/** Plain Java regression probe for directory sharing archives. */
public final class FolderArchiveProbe {
    public static void main(String[] args) throws Exception {
        File temporary = Files.createTempDirectory("kemi-folder-archive").toFile();
        File folder = new File(temporary, "演示:资料");
        File nested = new File(folder, "子目录");
        require(nested.mkdirs(), "create fixture folders");
        write(new File(folder, "说明.txt"), "KEMI Pads");
        write(new File(nested, "数据.bin"), "1234");

        File archive = new File(temporary, FolderArchive.safeArchiveName(folder.getName()));
        FolderArchive.zip(folder, archive);
        require("演示_资料.zip".equals(archive.getName()), "safe archive name");
        try (ZipFile zip = new ZipFile(archive)) {
            require(zip.getEntry("演示:资料/") != null, "root folder entry");
            require(zip.getEntry("演示:资料/说明.txt") != null, "root file entry");
            require(zip.getEntry("演示:资料/子目录/数据.bin") != null, "nested file entry");
        }
        System.out.println("FolderArchiveProbe PASS");
    }

    private static void write(File file, String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
