package com.zps.zest.langchain4j.index;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Disk-based implementation of StructuralIndex that persists relationships to disk.
 * Uses a hybrid approach: frequently accessed data in memory, rest on disk.
 */
public class DiskBasedStructuralIndex extends StructuralIndex {
    private static final Logger LOG = Logger.getInstance(DiskBasedStructuralIndex.class);
    private static final String INDEX_DIR = ".idea/zest/structural-index";
    private static final String ELEMENTS_DIR = "elements";
    private static final String REVERSE_INDEX_FILE = "reverse-index.json";
    private static final int CACHE_SIZE = 5000;
    private static final int BATCH_SIZE = 100;
    private static final Gson GSON = new Gson();
    
    private final Path indexPath;
    private final Path elementsPath;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    // LRU cache for frequently accessed elements
    private final LinkedHashMap<String, ElementStructure> elementCache = 
        new LinkedHashMap<String, ElementStructure>(CACHE_SIZE + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ElementStructure> eldest) {
            if (size() > CACHE_SIZE) {
                // Write to disk before eviction
                try {
                    saveElementToDisk(eldest.getKey(), eldest.getValue());
                } catch (IOException e) {
                    LOG.error("Failed to save element to disk before eviction: " + eldest.getKey(), e);
                }
                return true;
            }
            return false;
        }
    };
    
    // Keep reverse indices in memory (they're accessed frequently)
    private final Map<String, Set<String>> callers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> subclasses = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> implementations = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> fieldAccessors = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> overriders = new ConcurrentHashMap<>();
    
    // Track which elements are on disk
    private final Set<String> diskElements = ConcurrentHashMap.newKeySet();
    
    // Batch write buffer
    private final Map<String, ElementStructure> writeBuffer = new ConcurrentHashMap<>();
    
    public DiskBasedStructuralIndex(Project project) throws IOException {
        // Create index directory
        String projectPath = project.getBasePath();
        if (projectPath == null) {
            throw new IOException("Project base path is null");
        }
        
        this.indexPath = Paths.get(projectPath, INDEX_DIR);
        this.elementsPath = indexPath.resolve(ELEMENTS_DIR);
        Files.createDirectories(elementsPath);
        
        // Load existing index
        loadFromDisk();
    }
    
    @Override
    public void indexElement(String elementId, ElementStructure structure) {
        lock.writeLock().lock();
        try {
            // Add to cache
            elementCache.put(elementId, structure);
            
            // Add to write buffer for batch writing
            writeBuffer.put(elementId, structure);
            
            // Update reverse indices
            updateReverseIndices(elementId, structure);
            
            // Flush buffer if it's getting large
            if (writeBuffer.size() >= BATCH_SIZE) {
                flushWriteBuffer();
            }
            
            LOG.debug("Indexed structural information for: " + elementId);
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    @Override
    public List<String> findCallers(String methodId) {
        lock.readLock().lock();
        try {
            Set<String> callerSet = callers.get(methodId);
            return callerSet != null ? new ArrayList<>(callerSet) : Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    @Override
    public List<String> findCallees(String methodId) {
        lock.readLock().lock();
        try {
            ElementStructure structure = getElement(methodId);
            return structure != null ? new ArrayList<>(structure.getCalls()) : Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    @Override
    public Map<RelationType, List<String>> findAllRelated(String elementId) {
        lock.readLock().lock();
        try {
            Map<RelationType, List<String>> related = new EnumMap<>(RelationType.class);
            
            // Get element structure
            ElementStructure structure = getElement(elementId);
            if (structure != null) {
                // Direct relationships
                if (!structure.getCalls().isEmpty()) {
                    related.put(RelationType.CALLS, new ArrayList<>(structure.getCalls()));
                }
                if (structure.getSuperClass() != null) {
                    related.put(RelationType.EXTENDS, Collections.singletonList(structure.getSuperClass()));
                }
                if (!structure.getImplements().isEmpty()) {
                    related.put(RelationType.IMPLEMENTS, new ArrayList<>(structure.getImplements()));
                }
                if (!structure.getOverrides().isEmpty()) {
                    related.put(RelationType.OVERRIDES, new ArrayList<>(structure.getOverrides()));
                }
                if (!structure.getAccessesFields().isEmpty()) {
                    related.put(RelationType.ACCESSES_FIELD, new ArrayList<>(structure.getAccessesFields()));
                }
            }
            
            // Reverse relationships
            addReverseRelationships(elementId, related);
            
            return related;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    @Override
    public void removeElement(String elementId) {
        lock.writeLock().lock();
        try {
            // Remove from cache
            ElementStructure structure = elementCache.remove(elementId);
            
            // Remove from write buffer
            writeBuffer.remove(elementId);
            
            // If not in cache, load from disk
            if (structure == null && diskElements.contains(elementId)) {
                try {
                    structure = loadElementFromDisk(elementId);
                } catch (IOException e) {
                    LOG.error("Failed to load element for removal: " + elementId, e);
                }
            }
            
            // Update reverse indices
            if (structure != null) {
                removeFromReverseIndices(elementId, structure);
            }
            
            // Remove from disk
            try {
                deleteElementFromDisk(elementId);
                diskElements.remove(elementId);
            } catch (IOException e) {
                LOG.error("Failed to delete element from disk: " + elementId, e);
            }
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            elementCache.clear();
            writeBuffer.clear();
            diskElements.clear();
            callers.clear();
            subclasses.clear();
            implementations.clear();
            fieldAccessors.clear();
            overriders.clear();
            
            // Clear disk storage
            try {
                if (Files.exists(elementsPath)) {
                    Files.walk(elementsPath)
                        .filter(Files::isRegularFile)
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                LOG.error("Failed to delete file: " + path, e);
                            }
                        });
                }
            } catch (IOException e) {
                LOG.error("Failed to clear disk storage", e);
            }
            
            LOG.info("Cleared structural index");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Gets an element from cache or disk.
     */
    private ElementStructure getElement(String elementId) {
        // Check cache first
        ElementStructure cached = elementCache.get(elementId);
        if (cached != null) {
            return cached;
        }
        
        // Check write buffer
        ElementStructure buffered = writeBuffer.get(elementId);
        if (buffered != null) {
            return buffered;
        }
        
        // Load from disk if available
        if (diskElements.contains(elementId)) {
            try {
                ElementStructure loaded = loadElementFromDisk(elementId);
                if (loaded != null) {
                    // Add to cache
                    elementCache.put(elementId, loaded);
                    return loaded;
                }
            } catch (IOException e) {
                LOG.error("Failed to load element from disk: " + elementId, e);
            }
        }
        
        return null;
    }
    
    /**
     * Saves an element to disk.
     */
    private void saveElementToDisk(String elementId, ElementStructure structure) throws IOException {
        Path elementPath = getElementPath(elementId);
        Files.createDirectories(elementPath.getParent());
        
        try (Writer writer = Files.newBufferedWriter(elementPath)) {
            GSON.toJson(structure, writer);
        }
        
        diskElements.add(elementId);
    }
    
    /**
     * Loads an element from disk.
     */
    private ElementStructure loadElementFromDisk(String elementId) throws IOException {
        Path elementPath = getElementPath(elementId);
        if (!Files.exists(elementPath)) {
            return null;
        }
        
        try (Reader reader = Files.newBufferedReader(elementPath)) {
            return GSON.fromJson(reader, ElementStructure.class);
        }
    }
    
    /**
     * Deletes an element from disk.
     */
    private void deleteElementFromDisk(String elementId) throws IOException {
        Path elementPath = getElementPath(elementId);
        Files.deleteIfExists(elementPath);
    }
    
    /**
     * Gets the file path for an element.
     */
    private Path getElementPath(String elementId) {
        // Use hash-based directory structure to avoid too many files in one directory
        String hash = Integer.toHexString(elementId.hashCode());
        String dir1 = hash.length() > 2 ? hash.substring(0, 2) : "00";
        String dir2 = hash.length() > 4 ? hash.substring(2, 4) : "00";
        String filename = elementId.replaceAll("[^a-zA-Z0-9.-]", "_") + ".json";
        
        return elementsPath.resolve(dir1).resolve(dir2).resolve(filename);
    }
    
    /**
     * Flushes the write buffer to disk.
     */
    private void flushWriteBuffer() {
        if (writeBuffer.isEmpty()) return;
        
        Map<String, ElementStructure> toWrite = new HashMap<>(writeBuffer);
        writeBuffer.clear();
        
        // Write in background
        new Thread(() -> {
            for (Map.Entry<String, ElementStructure> entry : toWrite.entrySet()) {
                try {
                    saveElementToDisk(entry.getKey(), entry.getValue());
                } catch (IOException e) {
                    LOG.error("Failed to save element to disk: " + entry.getKey(), e);
                }
            }
        }).start();
    }
    
    /**
     * Updates reverse indices.
     */
    private void updateReverseIndices(String elementId, ElementStructure structure) {
        // Update callers index
        for (String called : structure.getCalls()) {
            callers.computeIfAbsent(called, k -> ConcurrentHashMap.newKeySet()).add(elementId);
        }
        
        // Update subclasses index
        if (structure.getSuperClass() != null) {
            subclasses.computeIfAbsent(structure.getSuperClass(), k -> ConcurrentHashMap.newKeySet())
                .add(elementId);
        }
        
        // Update implementations index
        for (String iface : structure.getImplements()) {
            implementations.computeIfAbsent(iface, k -> ConcurrentHashMap.newKeySet()).add(elementId);
        }
        
        // Update field accessors index
        for (String field : structure.getAccessesFields()) {
            fieldAccessors.computeIfAbsent(field, k -> ConcurrentHashMap.newKeySet()).add(elementId);
        }
        
        // Update overriders index
        for (String overridden : structure.getOverrides()) {
            overriders.computeIfAbsent(overridden, k -> ConcurrentHashMap.newKeySet()).add(elementId);
        }
    }
    
    /**
     * Removes from reverse indices.
     */
    private void removeFromReverseIndices(String elementId, ElementStructure structure) {
        // Remove from callers
        for (String called : structure.getCalls()) {
            Set<String> callerSet = callers.get(called);
            if (callerSet != null) {
                callerSet.remove(elementId);
            }
        }
        
        // Remove from subclasses
        if (structure.getSuperClass() != null) {
            Set<String> subclassSet = subclasses.get(structure.getSuperClass());
            if (subclassSet != null) {
                subclassSet.remove(elementId);
            }
        }
        
        // Similar for other indices...
    }
    
    /**
     * Adds reverse relationships to the result map.
     */
    private void addReverseRelationships(String elementId, Map<RelationType, List<String>> related) {
        List<String> callersList = findCallers(elementId);
        if (!callersList.isEmpty()) {
            related.put(RelationType.CALLED_BY, callersList);
        }
        
        List<String> subclassesList = findSubclasses(elementId);
        if (!subclassesList.isEmpty()) {
            related.put(RelationType.EXTENDED_BY, subclassesList);
        }
        
        List<String> implementationsList = findImplementations(elementId);
        if (!implementationsList.isEmpty()) {
            related.put(RelationType.IMPLEMENTED_BY, implementationsList);
        }
        
        List<String> overridersList = findOverridingMethods(elementId);
        if (!overridersList.isEmpty()) {
            related.put(RelationType.OVERRIDDEN_BY, overridersList);
        }
        
        List<String> accessorsList = findFieldAccessors(elementId);
        if (!accessorsList.isEmpty()) {
            related.put(RelationType.FIELD_ACCESSED_BY, accessorsList);
        }
    }
    
    /**
     * Loads the index from disk.
     */
    private void loadFromDisk() throws IOException {
        // Load reverse indices
        Path reverseIndexPath = indexPath.resolve(REVERSE_INDEX_FILE);
        if (Files.exists(reverseIndexPath)) {
            try (Reader reader = Files.newBufferedReader(reverseIndexPath)) {
                ReverseIndices loaded = GSON.fromJson(reader, ReverseIndices.class);
                if (loaded != null) {
                    callers.putAll(loaded.callers);
                    subclasses.putAll(loaded.subclasses);
                    implementations.putAll(loaded.implementations);
                    fieldAccessors.putAll(loaded.fieldAccessors);
                    overriders.putAll(loaded.overriders);
                }
            }
        }
        
        // Scan for element files
        if (Files.exists(elementsPath)) {
            Files.walk(elementsPath)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".json"))
                .forEach(path -> {
                    String filename = path.getFileName().toString();
                    String elementId = filename.substring(0, filename.length() - 5)
                        .replace("_", ".");
                    diskElements.add(elementId);
                });
        }
        
        LOG.info("Loaded structural index with " + diskElements.size() + " elements on disk");
    }
    
    /**
     * Saves the index to disk.
     */
    public void saveToDisk() throws IOException {
        lock.writeLock().lock();
        try {
            // Flush write buffer
            flushWriteBuffer();
            
            // Save all cached elements
            for (Map.Entry<String, ElementStructure> entry : elementCache.entrySet()) {
                saveElementToDisk(entry.getKey(), entry.getValue());
            }
            
            // Save reverse indices
            ReverseIndices indices = new ReverseIndices();
            indices.callers = new HashMap<>(callers);
            indices.subclasses = new HashMap<>(subclasses);
            indices.implementations = new HashMap<>(implementations);
            indices.fieldAccessors = new HashMap<>(fieldAccessors);
            indices.overriders = new HashMap<>(overriders);
            
            Path reverseIndexPath = indexPath.resolve(REVERSE_INDEX_FILE);
            try (Writer writer = Files.newBufferedWriter(reverseIndexPath)) {
                GSON.toJson(indices, writer);
            }
            
            LOG.info("Saved structural index to disk");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Closes the index and saves state.
     */
    public void close() throws IOException {
        saveToDisk();
        clear();
    }
    
    @Override
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = super.getStatistics();
        stats.put("cache_size", elementCache.size());
        stats.put("disk_elements", diskElements.size());
        stats.put("write_buffer_size", writeBuffer.size());
        stats.put("index_path", indexPath.toString());
        return stats;
    }
    
    /**
     * Container for reverse indices.
     */
    private static class ReverseIndices {
        Map<String, Set<String>> callers = new HashMap<>();
        Map<String, Set<String>> subclasses = new HashMap<>();
        Map<String, Set<String>> implementations = new HashMap<>();
        Map<String, Set<String>> fieldAccessors = new HashMap<>();
        Map<String, Set<String>> overriders = new HashMap<>();
    }
}
