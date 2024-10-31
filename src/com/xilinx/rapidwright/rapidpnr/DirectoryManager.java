package com.xilinx.rapidwright.rapidpnr;

import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;

public class DirectoryManager {

    private Path rootDir;
    private Map<String, Path> name2SubDirMap;

    public DirectoryManager(Path rootDir) {
        this.rootDir = rootDir;

        // create root directory
        if (!rootDir.toFile().exists()) {
            rootDir.toFile().mkdirs();
        }

        name2SubDirMap = new HashMap<>();
    }

    public Path addSubDir(String subDirName) {
        if (name2SubDirMap.containsKey(subDirName)) {
            return name2SubDirMap.get(subDirName);
        }
        Path subDir = rootDir.resolve(subDirName);
        if (!subDir.toFile().exists()) {
            subDir.toFile().mkdirs();
        }
        name2SubDirMap.put(subDirName, subDir);
        return subDir;
    }

    public Path getRootDir() {
        return rootDir;
    }

    public Path getSubDir(String subDirName) {
        return name2SubDirMap.get(subDirName);
    }
}
