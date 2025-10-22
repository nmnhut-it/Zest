package com.zps.zest.git;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a group of related files for hierarchical commit message generation.
 * Files are grouped by directory, type, or size to enable divide-and-conquer processing
 * of large changesets that exceed LLM context limits.
 */
public class FileGroup {
    private final String name;
    private final String description;
    private final List<FileInfo> files;
    private int estimatedSize;

    public FileGroup(String name, String description) {
        this.name = name;
        this.description = description;
        this.files = new ArrayList<>();
        this.estimatedSize = 0;
    }

    public void addFile(FileInfo file) {
        files.add(file);
        estimatedSize += file.getDiffSize();
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<FileInfo> getFiles() {
        return files;
    }

    public int getFileCount() {
        return files.size();
    }

    public int getEstimatedSize() {
        return estimatedSize;
    }

    public int getEstimatedSizeKB() {
        return estimatedSize / 1000;
    }

    @Override
    public String toString() {
        return name + " (" + files.size() + " files, " + getEstimatedSizeKB() + "KB)";
    }

    /**
     * Information about a single file in the group.
     */
    public static class FileInfo {
        private final String path;
        private final String status;
        private final String diff;
        private final int diffSize;

        public FileInfo(String path, String status, String diff) {
            this.path = path;
            this.status = status;
            this.diff = diff;
            this.diffSize = diff != null ? diff.length() : 0;
        }

        /**
         * Constructor for preview mode - uses estimated size without full diff.
         * This is much faster for large changesets since we don't fetch actual diffs.
         */
        public FileInfo(String path, String status, int estimatedSize) {
            this.path = path;
            this.status = status;
            this.diff = null;
            this.diffSize = estimatedSize;
        }

        public String getPath() {
            return path;
        }

        public String getStatus() {
            return status;
        }

        public String getDiff() {
            return diff;
        }

        public int getDiffSize() {
            return diffSize;
        }

        public String getDirectory() {
            int lastSlash = path.lastIndexOf('/');
            return lastSlash > 0 ? path.substring(0, lastSlash) : "";
        }

        public String getFileName() {
            int lastSlash = path.lastIndexOf('/');
            return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        }

        public String getFileExtension() {
            String fileName = getFileName();
            int lastDot = fileName.lastIndexOf('.');
            return lastDot > 0 ? fileName.substring(lastDot + 1).toLowerCase() : "";
        }
    }
}
