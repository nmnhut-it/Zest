package com.zps.zest.git;

import java.util.List;

/**
 * Strategy interface for grouping files for hierarchical commit message generation.
 * Different implementations can use different grouping strategies (by directory, size, type, etc.)
 */
public interface FileGroupStrategy {

    /**
     * Maximum recommended size for a single group in bytes.
     * Groups larger than this may be split further.
     */
    int MAX_GROUP_SIZE = 30000; // 30KB

    /**
     * Maximum recommended number of files per group.
     * Groups with more files may be split.
     */
    int MAX_FILES_PER_GROUP = 20;

    /**
     * Groups the given files into logical groups for processing.
     *
     * @param files List of files to group
     * @return List of file groups, ordered by priority/importance
     */
    List<FileGroup> groupFiles(List<FileGroup.FileInfo> files);

    /**
     * Returns a human-readable name for this grouping strategy.
     */
    String getStrategyName();
}
