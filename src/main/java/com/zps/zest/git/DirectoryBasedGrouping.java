package com.zps.zest.git;

import com.intellij.openapi.diagnostic.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Groups files by directory structure for hierarchical commit message generation.
 *
 * Strategy:
 * 1. Group files by their parent directory
 * 2. If a directory group is too large (>MAX_GROUP_SIZE or >MAX_FILES_PER_GROUP), split it
 * 3. Merge small groups from the same parent directory
 * 4. Prioritize groups by number of changes
 */
public class DirectoryBasedGrouping implements FileGroupStrategy {
    private static final Logger LOG = Logger.getInstance(DirectoryBasedGrouping.class);

    @Override
    public List<FileGroup> groupFiles(List<FileGroup.FileInfo> files) {
        LOG.info("Grouping " + files.size() + " files by directory structure");

        // Step 1: Group by directory
        Map<String, List<FileGroup.FileInfo>> dirMap = files.stream()
                .collect(Collectors.groupingBy(FileGroup.FileInfo::getDirectory));

        List<FileGroup> groups = new ArrayList<>();

        // Step 2: Create groups from directories
        for (Map.Entry<String, List<FileGroup.FileInfo>> entry : dirMap.entrySet()) {
            String directory = entry.getKey();
            List<FileGroup.FileInfo> dirFiles = entry.getValue();

            // Calculate total size for this directory
            int totalSize = dirFiles.stream()
                    .mapToInt(FileGroup.FileInfo::getDiffSize)
                    .sum();

            // If directory is too large, split it
            if (totalSize > MAX_GROUP_SIZE || dirFiles.size() > MAX_FILES_PER_GROUP) {
                groups.addAll(splitLargeGroup(directory, dirFiles));
            } else {
                groups.add(createGroup(directory, dirFiles));
            }
        }

        // Step 3: Sort groups by size (largest first for better context)
        groups.sort((g1, g2) -> Integer.compare(g2.getEstimatedSize(), g1.getEstimatedSize()));

        LOG.info("Created " + groups.size() + " groups from " + files.size() + " files");
        groups.forEach(g -> LOG.info("  Group: " + g.toString()));

        return groups;
    }

    /**
     * Splits a large directory group into smaller sub-groups.
     */
    private List<FileGroup> splitLargeGroup(String directory, List<FileGroup.FileInfo> files) {
        List<FileGroup> subGroups = new ArrayList<>();

        // Sort files by size (largest first)
        List<FileGroup.FileInfo> sortedFiles = new ArrayList<>(files);
        sortedFiles.sort((f1, f2) -> Integer.compare(f2.getDiffSize(), f1.getDiffSize()));

        FileGroup currentGroup = null;
        int currentGroupSize = 0;
        int groupIndex = 1;

        for (FileGroup.FileInfo file : sortedFiles) {
            // Start new group if needed
            if (currentGroup == null ||
                currentGroupSize + file.getDiffSize() > MAX_GROUP_SIZE ||
                currentGroup.getFileCount() >= MAX_FILES_PER_GROUP) {

                if (currentGroup != null) {
                    subGroups.add(currentGroup);
                }

                String groupName = formatGroupName(directory, groupIndex, sortedFiles.size());
                currentGroup = new FileGroup(groupName, "Part " + groupIndex + " of " + directory);
                currentGroupSize = 0;
                groupIndex++;
            }

            currentGroup.addFile(file);
            currentGroupSize += file.getDiffSize();
        }

        // Add the last group
        if (currentGroup != null && currentGroup.getFileCount() > 0) {
            subGroups.add(currentGroup);
        }

        return subGroups;
    }

    /**
     * Creates a file group from a directory and its files.
     */
    private FileGroup createGroup(String directory, List<FileGroup.FileInfo> files) {
        String groupName = formatGroupName(directory, 0, files.size());
        String description = describeGroup(files);

        FileGroup group = new FileGroup(groupName, description);
        files.forEach(group::addFile);

        return group;
    }

    /**
     * Formats a group name from directory path.
     */
    private String formatGroupName(String directory, int partNumber, int totalFiles) {
        if (directory.isEmpty()) {
            return partNumber > 0 ? "Root files (part " + partNumber + ")" : "Root files";
        }

        // Shorten long paths for readability
        String shortPath = shortenPath(directory);

        if (partNumber > 0) {
            return shortPath + " (part " + partNumber + ")";
        }

        return shortPath;
    }

    /**
     * Shortens a path for display (e.g., "src/main/java/com/example" → "s/m/j/com/example")
     */
    private String shortenPath(String path) {
        String[] parts = path.split("/");
        if (parts.length <= 3) {
            return path;
        }

        StringBuilder shortened = new StringBuilder();
        for (int i = 0; i < parts.length - 2; i++) {
            if (!parts[i].isEmpty()) {
                shortened.append(parts[i].charAt(0)).append("/");
            }
        }
        shortened.append(parts[parts.length - 2]).append("/");
        shortened.append(parts[parts.length - 1]);

        return shortened.toString();
    }

    /**
     * Creates a human-readable description of a group based on file types.
     */
    private String describeGroup(List<FileGroup.FileInfo> files) {
        Map<String, Long> extensionCounts = files.stream()
                .collect(Collectors.groupingBy(FileGroup.FileInfo::getFileExtension, Collectors.counting()));

        if (extensionCounts.size() == 1) {
            String ext = extensionCounts.keySet().iterator().next();
            return files.size() + " " + (ext.isEmpty() ? "file" : ext) + " file" + (files.size() > 1 ? "s" : "");
        }

        // Mixed types
        List<Map.Entry<String, Long>> sortedTypes = extensionCounts.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
                .limit(3)
                .collect(Collectors.toList());

        StringBuilder desc = new StringBuilder();
        for (int i = 0; i < sortedTypes.size(); i++) {
            Map.Entry<String, Long> entry = sortedTypes.get(i);
            if (i > 0) desc.append(", ");
            desc.append(entry.getValue()).append(" ").append(entry.getKey());
        }

        if (extensionCounts.size() > 3) {
            desc.append(", +").append(extensionCounts.size() - 3).append(" more");
        }

        return desc.toString();
    }

    @Override
    public String getStrategyName() {
        return "Directory-based grouping";
    }
}
