package com.zps.zest.langchain4j.index;

import com.intellij.openapi.diagnostic.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Structural index for code relationships and dependencies.
 * Tracks:
 * - Call graphs (who calls whom)
 * - Inheritance hierarchies
 * - Field/method access patterns
 * - Package dependencies
 * - Override relationships
 */
public class StructuralIndex {
    private static final Logger LOG = Logger.getInstance(StructuralIndex.class);
    
    // Element ID -> Structural information
    private final Map<String, ElementStructure> elements = new ConcurrentHashMap<>();
    
    // Reverse indices for efficient lookup
    private final Map<String, Set<String>> callers = new ConcurrentHashMap<>(); // Who calls this method
    private final Map<String, Set<String>> subclasses = new ConcurrentHashMap<>(); // Who extends this class
    private final Map<String, Set<String>> implementations = new ConcurrentHashMap<>(); // Who implements this interface
    private final Map<String, Set<String>> fieldAccessors = new ConcurrentHashMap<>(); // Who accesses this field
    private final Map<String, Set<String>> overriders = new ConcurrentHashMap<>(); // Who overrides this method
    
    // Package-level dependencies
    private final Map<String, Set<String>> packageDependencies = new ConcurrentHashMap<>();
    
    /**
     * Indexes a code element with its structural relationships.
     */
    public void indexElement(String elementId, ElementStructure structure) {
        // Store the element
        elements.put(elementId, structure);
        
        // Update reverse indices
        updateReverseIndices(elementId, structure);
        
        // Update package dependencies
        updatePackageDependencies(structure);
        
        LOG.debug("Indexed structural information for: " + elementId);
    }
    
    /**
     * Updates reverse indices for efficient relationship queries.
     */
    private void updateReverseIndices(String elementId, ElementStructure structure) {
        // Update callers index
        for (String called : structure.getCalls()) {
            callers.computeIfAbsent(called, k -> ConcurrentHashMap.newKeySet()).add(elementId);
        }
        
        // Update subclasses index
        if (structure.getSuperClass() != null) {
            subclasses.computeIfAbsent(structure.getSuperClass(), k -> ConcurrentHashMap.newKeySet()).add(elementId);
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
     * Updates package-level dependencies.
     */
    private void updatePackageDependencies(ElementStructure structure) {
        if (structure.getPackageName() != null) {
            Set<String> dependencies = new HashSet<>();
            
            // Extract packages from all referenced elements
            for (String element : structure.getAllReferencedElements()) {
                String pkg = extractPackage(element);
                if (pkg != null && !pkg.equals(structure.getPackageName())) {
                    dependencies.add(pkg);
                }
            }
            
            if (!dependencies.isEmpty()) {
                packageDependencies.computeIfAbsent(structure.getPackageName(), k -> ConcurrentHashMap.newKeySet())
                    .addAll(dependencies);
            }
        }
    }
    
    /**
     * Finds elements that call the given method.
     */
    public List<String> findCallers(String methodId) {
        Set<String> callerSet = callers.get(methodId);
        return callerSet != null ? new ArrayList<>(callerSet) : Collections.emptyList();
    }
    
    /**
     * Finds elements that are called by the given method.
     */
    public List<String> findCallees(String methodId) {
        ElementStructure structure = elements.get(methodId);
        return structure != null ? new ArrayList<>(structure.getCalls()) : Collections.emptyList();
    }
    
    /**
     * Finds subclasses of the given class.
     */
    public List<String> findSubclasses(String classId) {
        Set<String> subclassSet = subclasses.get(classId);
        return subclassSet != null ? new ArrayList<>(subclassSet) : Collections.emptyList();
    }
    
    /**
     * Finds implementations of the given interface.
     */
    public List<String> findImplementations(String interfaceId) {
        Set<String> implSet = implementations.get(interfaceId);
        return implSet != null ? new ArrayList<>(implSet) : Collections.emptyList();
    }
    
    /**
     * Finds methods that override the given method.
     */
    public List<String> findOverridingMethods(String methodId) {
        Set<String> overriderSet = overriders.get(methodId);
        return overriderSet != null ? new ArrayList<>(overriderSet) : Collections.emptyList();
    }
    
    /**
     * Finds elements that access the given field.
     */
    public List<String> findFieldAccessors(String fieldId) {
        Set<String> accessorSet = fieldAccessors.get(fieldId);
        return accessorSet != null ? new ArrayList<>(accessorSet) : Collections.emptyList();
    }
    
    /**
     * Finds all related elements with their relationship types.
     */
    public Map<RelationType, List<String>> findAllRelated(String elementId) {
        Map<RelationType, List<String>> related = new EnumMap<>(RelationType.class);
        
        // Direct relationships from the element
        ElementStructure structure = elements.get(elementId);
        if (structure != null) {
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
        
        return related;
    }
    
    /**
     * Calculates structural similarity between two elements.
     */
    public double calculateStructuralSimilarity(String elementId1, String elementId2) {
        ElementStructure struct1 = elements.get(elementId1);
        ElementStructure struct2 = elements.get(elementId2);
        
        if (struct1 == null || struct2 == null) {
            return 0.0;
        }
        
        double similarity = 0.0;
        int factors = 0;
        
        // Same package
        if (struct1.getPackageName() != null && struct1.getPackageName().equals(struct2.getPackageName())) {
            similarity += 0.2;
            factors++;
        }
        
        // Same superclass
        if (struct1.getSuperClass() != null && struct1.getSuperClass().equals(struct2.getSuperClass())) {
            similarity += 0.3;
            factors++;
        }
        
        // Common interfaces
        Set<String> commonInterfaces = new HashSet<>(struct1.getImplements());
        commonInterfaces.retainAll(struct2.getImplements());
        if (!commonInterfaces.isEmpty()) {
            similarity += 0.2 * ((double) commonInterfaces.size() / 
                Math.max(struct1.getImplements().size(), struct2.getImplements().size()));
            factors++;
        }
        
        // Common method calls
        Set<String> commonCalls = new HashSet<>(struct1.getCalls());
        commonCalls.retainAll(struct2.getCalls());
        if (!commonCalls.isEmpty()) {
            similarity += 0.15 * ((double) commonCalls.size() / 
                Math.max(struct1.getCalls().size(), struct2.getCalls().size()));
            factors++;
        }
        
        // Common field accesses
        Set<String> commonFields = new HashSet<>(struct1.getAccessesFields());
        commonFields.retainAll(struct2.getAccessesFields());
        if (!commonFields.isEmpty()) {
            similarity += 0.15 * ((double) commonFields.size() / 
                Math.max(struct1.getAccessesFields().size(), struct2.getAccessesFields().size()));
            factors++;
        }
        
        return factors > 0 ? similarity / factors : 0.0;
    }
    
    /**
     * Finds structurally similar elements.
     */
    public List<SimilarityResult> findStructurallySimilar(String elementId, int maxResults) {
        ElementStructure reference = elements.get(elementId);
        if (reference == null) {
            return Collections.emptyList();
        }
        
        List<SimilarityResult> results = new ArrayList<>();
        
        for (Map.Entry<String, ElementStructure> entry : elements.entrySet()) {
            if (!entry.getKey().equals(elementId)) {
                double similarity = calculateStructuralSimilarity(elementId, entry.getKey());
                if (similarity > 0.1) {
                    results.add(new SimilarityResult(entry.getKey(), similarity));
                }
            }
        }
        
        // Sort by similarity
        results.sort((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()));
        
        return results.stream()
            .limit(maxResults)
            .collect(Collectors.toList());
    }
    
    /**
     * Gets package dependencies.
     */
    public Map<String, Set<String>> getPackageDependencies() {
        return new HashMap<>(packageDependencies);
    }
    
    /**
     * Extracts package from element ID.
     */
    private String extractPackage(String elementId) {
        int lastDot = elementId.lastIndexOf('.');
        int hashIndex = elementId.indexOf('#');
        
        if (hashIndex > 0 && lastDot > 0) {
            // Method: extract package from class part
            String classPart = elementId.substring(0, hashIndex);
            lastDot = classPart.lastIndexOf('.');
            return lastDot > 0 ? classPart.substring(0, lastDot) : null;
        } else if (lastDot > 0) {
            // Class or field
            return elementId.substring(0, lastDot);
        }
        
        return null;
    }
    
    /**
     * Removes an element from the index.
     */
    public void removeElement(String elementId) {
        ElementStructure structure = elements.remove(elementId);
        if (structure != null) {
            // Remove from reverse indices
            for (String called : structure.getCalls()) {
                Set<String> callerSet = callers.get(called);
                if (callerSet != null) {
                    callerSet.remove(elementId);
                }
            }
            
            if (structure.getSuperClass() != null) {
                Set<String> subclassSet = subclasses.get(structure.getSuperClass());
                if (subclassSet != null) {
                    subclassSet.remove(elementId);
                }
            }
            
            // ... remove from other indices similarly
        }
    }
    
    /**
     * Gets statistics about the structural index.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_elements", elements.size());
        stats.put("total_call_relationships", callers.values().stream().mapToInt(Set::size).sum());
        stats.put("total_inheritance_relationships", subclasses.values().stream().mapToInt(Set::size).sum());
        stats.put("total_implementations", implementations.values().stream().mapToInt(Set::size).sum());
        stats.put("package_count", packageDependencies.size());
        return stats;
    }
    
    /**
     * Clears the entire index.
     */
    public void clear() {
        elements.clear();
        callers.clear();
        subclasses.clear();
        implementations.clear();
        fieldAccessors.clear();
        overriders.clear();
        packageDependencies.clear();
        LOG.info("Cleared structural index");
    }
    
    /**
     * Structural information about a code element.
     */
    public static class ElementStructure {
        private final String elementId;
        private final String elementType; // class, interface, method, field
        private final String packageName;
        private final String containingClass;
        private final String superClass;
        private final Set<String> implementsInterfaces = new HashSet<>();
        private final Set<String> calls = new HashSet<>();
        private final Set<String> overrides = new HashSet<>();
        private final Set<String> accessesFields = new HashSet<>();
        private final Map<String, Object> additionalMetadata = new HashMap<>();
        
        public ElementStructure(String elementId, String elementType) {
            this.elementId = elementId;
            this.elementType = elementType;
            this.packageName = extractPackageFromId(elementId);
            this.containingClass = extractContainingClass(elementId);
        }
        
        private String extractPackageFromId(String id) {
            int lastDot = id.lastIndexOf('.');
            int hashIndex = id.indexOf('#');
            
            if (hashIndex > 0) {
                String classPart = id.substring(0, hashIndex);
                lastDot = classPart.lastIndexOf('.');
                return lastDot > 0 ? classPart.substring(0, lastDot) : null;
            }
            return lastDot > 0 ? id.substring(0, lastDot) : null;
        }
        
        private String extractContainingClass(String id) {
            int hashIndex = id.indexOf('#');
            if (hashIndex > 0) {
                return id.substring(0, hashIndex);
            }
            return null;
        }
        
        public Set<String> getAllReferencedElements() {
            Set<String> all = new HashSet<>();
            all.addAll(calls);
            all.addAll(implementsInterfaces);
            all.addAll(overrides);
            all.addAll(accessesFields);
            if (superClass != null) all.add(superClass);
            return all;
        }
        
        // Getters and setters
        public String getElementId() { return elementId; }
        public String getElementType() { return elementType; }
        public String getPackageName() { return packageName; }
        public String getContainingClass() { return containingClass; }
        public String getSuperClass() { return superClass; }
        public void setSuperClass(String superClass) { this.superClass = superClass; }
        public Set<String> getImplements() { return implementsInterfaces; }
        public Set<String> getCalls() { return calls; }
        public Set<String> getOverrides() { return overrides; }
        public Set<String> getAccessesFields() { return accessesFields; }
        public Map<String, Object> getAdditionalMetadata() { return additionalMetadata; }
    }
    
    /**
     * Types of structural relationships.
     */
    public enum RelationType {
        CALLS("calls"),
        CALLED_BY("called by"),
        EXTENDS("extends"),
        EXTENDED_BY("extended by"),
        IMPLEMENTS("implements"),
        IMPLEMENTED_BY("implemented by"),
        OVERRIDES("overrides"),
        OVERRIDDEN_BY("overridden by"),
        ACCESSES_FIELD("accesses field"),
        FIELD_ACCESSED_BY("field accessed by");
        
        private final String displayName;
        
        RelationType(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    /**
     * Result of structural similarity search.
     */
    public static class SimilarityResult {
        private final String elementId;
        private final double similarity;
        
        public SimilarityResult(String elementId, double similarity) {
            this.elementId = elementId;
            this.similarity = similarity;
        }
        
        public String getElementId() { return elementId; }
        public double getSimilarity() { return similarity; }
    }
}
