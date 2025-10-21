package com.zps.zest.git;

import com.intellij.openapi.diagnostic.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure-Java Git operations using JGit library.
 * All operations are safe to run on background threads (no EDT constraints).
 * Eliminates process spawning overhead and provides better performance.
 */
public class JGitService {
    private static final Logger LOG = Logger.getInstance(JGitService.class);
    private static final Map<String, CachedStatus> statusCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5000;

    /**
     * Git status result containing all changed/staged/untracked files.
     */
    public static class GitStatusResult {
        public final Set<String> added;
        public final Set<String> modified;
        public final Set<String> removed;
        public final Set<String> untracked;
        public final Set<String> ignored;
        public final Map<String, String> status;

        GitStatusResult(Status jgitStatus) {
            this.added = new HashSet<>(jgitStatus.getAdded());
            this.modified = new HashSet<>(jgitStatus.getModified());
            this.removed = new HashSet<>(jgitStatus.getRemoved());
            this.untracked = new HashSet<>(jgitStatus.getUntracked());
            this.ignored = new HashSet<>(jgitStatus.getIgnoredNotInIndex());

            this.status = new HashMap<>();
            jgitStatus.getAdded().forEach(f -> status.put(f, "A"));
            jgitStatus.getChanged().forEach(f -> status.put(f, "M"));
            jgitStatus.getModified().forEach(f -> status.put(f, "M"));
            jgitStatus.getRemoved().forEach(f -> status.put(f, "D"));
            jgitStatus.getUntracked().forEach(f -> status.put(f, "A"));
        }

        public boolean hasChanges() {
            return !added.isEmpty() || !modified.isEmpty() || !removed.isEmpty() || !untracked.isEmpty();
        }

        public int getTotalFileCount() {
            return added.size() + modified.size() + removed.size() + untracked.size();
        }

        public String toNameStatusFormat() {
            StringBuilder result = new StringBuilder();
            added.forEach(f -> result.append("A\t").append(f).append("\n"));
            modified.forEach(f -> result.append("M\t").append(f).append("\n"));
            removed.forEach(f -> result.append("D\t").append(f).append("\n"));
            untracked.forEach(f -> result.append("A\t").append(f).append("\n"));
            return result.toString();
        }
    }

    /**
     * Gets Git status for the repository at the given path.
     * Results are cached for CACHE_TTL_MS to improve performance.
     */
    public static GitStatusResult getStatus(String projectPath) throws Exception {
        CachedStatus cached = statusCache.get(projectPath);
        if (cached != null && !cached.isExpired()) {
            LOG.info("Using cached Git status for: " + projectPath);
            return cached.result;
        }

        LOG.info("Fetching Git status using JGit for: " + projectPath);
        long startTime = System.currentTimeMillis();

        try (Git git = Git.open(new File(projectPath))) {
            Status status = git.status().call();
            GitStatusResult result = new GitStatusResult(status);

            statusCache.put(projectPath, new CachedStatus(result));

            long elapsed = System.currentTimeMillis() - startTime;
            LOG.info("JGit status completed in " + elapsed + "ms - found " +
                    result.getTotalFileCount() + " changed files, " +
                    result.ignored.size() + " ignored files");

            return result;
        } catch (Exception e) {
            LOG.error("Error getting Git status with JGit", e);
            throw new Exception("Failed to get Git status: " + e.getMessage(), e);
        }
    }

    /**
     * Checks if a file is ignored by .gitignore.
     * Uses cached status to avoid repeated checks.
     */
    public static boolean isFileIgnored(String projectPath, String filePath) {
        try {
            GitStatusResult status = getStatus(projectPath);
            return status.ignored.contains(filePath);
        } catch (Exception e) {
            LOG.debug("Error checking if file is ignored (assuming not ignored): " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets the diff content for a specific file.
     */
    public static String getFileDiff(String projectPath, String filePath, String statusCode) throws Exception {
        try (Git git = Git.open(new File(projectPath))) {
            Repository repository = git.getRepository();

            if ("A".equals(statusCode)) {
                return getUntrackedFileDiff(git, filePath);
            } else if ("D".equals(statusCode)) {
                return getDeletedFileDiff(git, repository, filePath);
            } else {
                return getModifiedFileDiff(git, repository, filePath);
            }
        } catch (Exception e) {
            LOG.warn("Error getting diff for file " + filePath + ": " + e.getMessage());
            throw new Exception("Failed to get diff: " + e.getMessage(), e);
        }
    }

    private static String getUntrackedFileDiff(Git git, String filePath) throws Exception {
        File file = new File(git.getRepository().getWorkTree(), filePath);
        if (!file.exists()) {
            return "(File not found)";
        }

        String content = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        StringBuilder diff = new StringBuilder();
        diff.append("@@ -0,0 +1,").append(content.split("\n").length).append(" @@\n");

        for (String line : content.split("\n")) {
            diff.append("+").append(line).append("\n");
        }

        return diff.toString();
    }

    private static String getDeletedFileDiff(Git git, Repository repository, String filePath) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter formatter = new DiffFormatter(out)) {

            formatter.setRepository(repository);

            AbstractTreeIterator oldTreeIterator = prepareTreeParser(repository, "HEAD");
            AbstractTreeIterator newTreeIterator = new CanonicalTreeParser();

            List<DiffEntry> diffs = formatter.scan(oldTreeIterator, newTreeIterator);

            for (DiffEntry diff : diffs) {
                if (diff.getOldPath().equals(filePath) || diff.getNewPath().equals(filePath)) {
                    formatter.format(diff);
                }
            }

            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static String getModifiedFileDiff(Git git, Repository repository, String filePath) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter formatter = new DiffFormatter(out)) {

            formatter.setRepository(repository);

            AbstractTreeIterator oldTreeIterator = prepareTreeParser(repository, "HEAD");
            AbstractTreeIterator newTreeIterator = new CanonicalTreeParser();

            List<DiffEntry> diffs = formatter.scan(oldTreeIterator, newTreeIterator);

            for (DiffEntry diff : diffs) {
                if (diff.getOldPath().equals(filePath) || diff.getNewPath().equals(filePath)) {
                    formatter.format(diff);
                }
            }

            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static AbstractTreeIterator prepareTreeParser(Repository repository, String ref) throws Exception {
        try (RevWalk walk = new RevWalk(repository)) {
            ObjectId commitId = repository.resolve(ref);
            if (commitId == null) {
                throw new IllegalStateException("Could not resolve ref: " + ref);
            }

            RevCommit commit = walk.parseCommit(commitId);
            RevTree tree = walk.parseTree(commit.getTree().getId());

            CanonicalTreeParser treeParser = new CanonicalTreeParser();
            try (ObjectReader reader = repository.newObjectReader()) {
                treeParser.reset(reader, tree.getId());
            }

            walk.dispose();
            return treeParser;
        }
    }

    /**
     * Clears the status cache. Call this when .gitignore or git state changes.
     */
    public static void clearCache() {
        int size = statusCache.size();
        statusCache.clear();
        LOG.info("Cleared JGit status cache (" + size + " entries)");
    }

    /**
     * Clears the cache for a specific project.
     */
    public static void clearCache(String projectPath) {
        if (statusCache.remove(projectPath) != null) {
            LOG.info("Cleared JGit status cache for: " + projectPath);
        }
    }

    private static class CachedStatus {
        final GitStatusResult result;
        final long timestamp;

        CachedStatus(GitStatusResult result) {
            this.result = result;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}
