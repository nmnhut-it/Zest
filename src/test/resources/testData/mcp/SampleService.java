package com.example.service;

import java.util.List;
import java.util.Optional;

/**
 * Sample service class for MCP tool testing.
 */
public class SampleService {

    private final String name;
    private int counter;

    public SampleService(String name) {
        this.name = name;
        this.counter = 0;
    }

    /**
     * Get the service name.
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Process items and return count.
     * @param items list of items to process
     * @return number of items processed
     */
    public int processItems(List<String> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        counter += items.size();
        return items.size();
    }

    /**
     * Find item by id.
     * @param id the item id
     * @return optional containing the item if found
     */
    public Optional<String> findById(long id) {
        if (id <= 0) {
            return Optional.empty();
        }
        return Optional.of("item-" + id);
    }

    /**
     * Overloaded method for testing.
     */
    public void doSomething() {
        counter++;
    }

    /**
     * Overloaded method with parameter.
     * @param value the value
     */
    public void doSomething(String value) {
        if (value != null) {
            counter += value.length();
        }
    }

    /**
     * Get current counter value.
     * @return the counter
     */
    public int getCounter() {
        return counter;
    }
}
