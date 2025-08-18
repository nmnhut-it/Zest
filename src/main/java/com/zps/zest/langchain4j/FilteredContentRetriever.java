package com.zps.zest.langchain4j;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Content retriever that filters results based on a predicate.
 * Used to create specialized retrievers for different file types or content categories.
 */
public class FilteredContentRetriever implements ContentRetriever {
    
    private final ContentRetriever baseRetriever;
    private final Predicate<Content> filter;
    
    public FilteredContentRetriever(ContentRetriever baseRetriever, Predicate<Content> filter) {
        this.baseRetriever = baseRetriever;
        this.filter = filter;
    }
    
    @Override
    public List<Content> retrieve(Query query) {
        // Get results from base retriever and apply filter
        return baseRetriever.retrieve(query).stream()
            .filter(filter)
            .collect(Collectors.toList());
    }
}