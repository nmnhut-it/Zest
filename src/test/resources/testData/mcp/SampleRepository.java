package com.example.repository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Sample repository class for MCP tool testing.
 */
public class SampleRepository {

    private final Map<Long, String> storage = new HashMap<>();

    /**
     * Save an entity.
     * @param id the entity id
     * @param value the entity value
     * @return the saved value
     */
    public String save(Long id, String value) {
        storage.put(id, value);
        return value;
    }

    /**
     * Find entity by id.
     * @param id the entity id
     * @return optional containing the entity if found
     */
    public Optional<String> findById(Long id) {
        return Optional.ofNullable(storage.get(id));
    }

    /**
     * Delete entity by id.
     * @param id the entity id
     * @return true if deleted, false otherwise
     */
    public boolean deleteById(Long id) {
        return storage.remove(id) != null;
    }

    /**
     * Count all entities.
     * @return the count
     */
    public int count() {
        return storage.size();
    }

    /**
     * Inner class for testing nested class lookup.
     */
    public static class EntityWrapper {
        private final String value;

        public EntityWrapper(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }
}
