package com.zps.zest.langchain4j.index;

import com.intellij.openapi.diagnostic.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Name-based index optimized for identifier matching with camelCase awareness.
 * Uses Lucene for efficient text search with support for:
 * - CamelCase splitting (addScore -> add score)
 * - Snake_case handling
 * - Fuzzy matching
 * - Prefix/suffix search
 */
public class NameIndex {
    private static final Logger LOG = Logger.getInstance(NameIndex.class);
    
    private final Directory directory;
    private final Analyzer analyzer;
    private IndexWriter writer;
    private DirectoryReader reader;
    private IndexSearcher searcher;
    
    // Patterns for identifier parsing
    private static final Pattern CAMEL_CASE_PATTERN = Pattern.compile("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    private static final Pattern SNAKE_CASE_PATTERN = Pattern.compile("_");
    private static final Pattern DOT_PATTERN = Pattern.compile("\\.");
    
    public NameIndex() throws IOException {
        this.directory = new ByteBuffersDirectory();
        this.analyzer = createCodeAwareAnalyzer();
        
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        this.writer = new IndexWriter(directory, config);
        
        refreshSearcher();
    }
    
    /**
     * Creates a custom analyzer that handles code identifiers.
     */
    private Analyzer createCodeAwareAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                WhitespaceTokenizer tokenizer = new WhitespaceTokenizer();
                
                // Configure word delimiter to handle camelCase, snake_case, etc.
                int flags = WordDelimiterGraphFilter.GENERATE_WORD_PARTS |
                           WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS |
                           WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE |
                           WordDelimiterGraphFilter.SPLIT_ON_NUMERICS |
                           WordDelimiterGraphFilter.PRESERVE_ORIGINAL;
                
                TokenStream filter = new WordDelimiterGraphFilter(tokenizer, flags, null);
                filter = new LowerCaseFilter(filter);
                
                return new TokenStreamComponents(tokenizer, filter);
            }
        };
    }
    
    /**
     * Indexes a code element with its identifier information.
     */
    public void indexElement(String id, String signature, String type, String filePath, Map<String, String> additionalFields) throws IOException {
        Document doc = new Document();
        
        // Primary fields
        doc.add(new StringField("id", id, Field.Store.YES));
        doc.add(new TextField("signature", signature, Field.Store.YES));
        doc.add(new StringField("type", type, Field.Store.YES));
        doc.add(new StringField("file_path", filePath, Field.Store.YES));
        
        // Extract and index name components
        String simpleName = extractSimpleName(id);
        doc.add(new TextField("simple_name", simpleName, Field.Store.YES));
        
        // Add camelCase split version
        String spacedName = splitIdentifier(simpleName);
        doc.add(new TextField("spaced_name", spacedName, Field.Store.YES));
        
        // Add individual tokens
        List<String> tokens = tokenizeIdentifier(simpleName);
        doc.add(new TextField("tokens", String.join(" ", tokens), Field.Store.NO));
        
        // Extract package/class context
        String[] parts = id.split("[.#]");
        if (parts.length > 1) {
            // Package name
            String packageName = extractPackage(id);
            if (!packageName.isEmpty()) {
                doc.add(new TextField("package", packageName, Field.Store.YES));
            }
            
            // Class name
            String className = extractClassName(id);
            if (!className.isEmpty()) {
                doc.add(new TextField("class_name", className, Field.Store.YES));
            }
        }
        
        // Add method/field specific fields
        if (id.contains("#")) {
            String memberName = id.substring(id.lastIndexOf("#") + 1);
            doc.add(new TextField("member_name", memberName, Field.Store.YES));
            doc.add(new TextField("member_spaced", splitIdentifier(memberName), Field.Store.NO));
        } else if (id.contains(".") && Character.isLowerCase(simpleName.charAt(0))) {
            // Likely a field
            doc.add(new TextField("field_name", simpleName, Field.Store.YES));
        }
        
        // Add additional fields
        for (Map.Entry<String, String> entry : additionalFields.entrySet()) {
            doc.add(new TextField(entry.getKey(), entry.getValue(), Field.Store.YES));
        }
        
        // Update or add document
        writer.updateDocument(new Term("id", id), doc);
    }
    
    /**
     * Searches the name index with various strategies.
     */
    public List<SearchResult> search(String queryStr, int maxResults) throws IOException {
        refreshSearcher();
        
        List<SearchResult> allResults = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        
        // Strategy 1: Exact match on simple name
        Query exactQuery = new TermQuery(new Term("simple_name", queryStr.toLowerCase()));
        addResults(exactQuery, 2.0f, maxResults, allResults, seenIds);
        
        // Strategy 2: Search in spaced names (for "add score" -> "addScore")
        String spacedQuery = queryStr.toLowerCase().replace(" ", "");
        if (queryStr.contains(" ")) {
            // User typed spaces, look for camelCase version
            Query spacedNameQuery = new TermQuery(new Term("simple_name", spacedQuery));
            addResults(spacedNameQuery, 1.8f, maxResults, allResults, seenIds);
            
            // Also try member name
            Query memberQuery = new TermQuery(new Term("member_name", spacedQuery));
            addResults(memberQuery, 1.8f, maxResults, allResults, seenIds);
        }
        
        // Strategy 3: Token-based search
        BooleanQuery.Builder tokenQuery = new BooleanQuery.Builder();
        String[] queryTokens = queryStr.toLowerCase().split("\\s+");
        for (String token : queryTokens) {
            tokenQuery.add(new TermQuery(new Term("tokens", token)), BooleanClause.Occur.MUST);
        }
        addResults(tokenQuery.build(), 1.5f, maxResults, allResults, seenIds);
        
        // Strategy 4: Fuzzy search for typos
        Query fuzzyQuery = new FuzzyQuery(new Term("simple_name", queryStr.toLowerCase()), 2);
        addResults(fuzzyQuery, 1.2f, maxResults, allResults, seenIds);
        
        // Strategy 5: Prefix search
        Query prefixQuery = new PrefixQuery(new Term("simple_name", queryStr.toLowerCase()));
        addResults(prefixQuery, 1.0f, maxResults, allResults, seenIds);
        
        // Strategy 6: Full text search on signature
        try {
            QueryParser parser = new QueryParser("signature", analyzer);
            Query parsedQuery = parser.parse(queryStr);
            addResults(parsedQuery, 0.8f, maxResults, allResults, seenIds);
        } catch (Exception e) {
            // Ignore parse errors
        }
        
        // Sort by score and limit
        allResults.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        
        return allResults.stream()
            .limit(maxResults)
            .collect(ArrayList::new, (list, item) -> list.add(item), ArrayList::addAll);
    }
    
    /**
     * Adds search results with boost factor.
     */
    private void addResults(Query query, float boost, int maxResults, 
                           List<SearchResult> results, Set<String> seenIds) throws IOException {
        TopDocs topDocs = searcher.search(query, maxResults);
        
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            Document doc = searcher.doc(scoreDoc.doc);
            String id = doc.get("id");
            
            if (!seenIds.contains(id)) {
                seenIds.add(id);
                results.add(new SearchResult(
                    id,
                    doc.get("signature"),
                    doc.get("type"),
                    doc.get("file_path"),
                    scoreDoc.score * boost
                ));
            }
        }
    }
    
    /**
     * Commits changes to the index.
     */
    public void commit() throws IOException {
        writer.commit();
        refreshSearcher();
    }
    
    /**
     * Refreshes the searcher with latest index changes.
     */
    private void refreshSearcher() throws IOException {
        DirectoryReader newReader = DirectoryReader.open(directory);
        if (reader != null) {
            reader.close();
        }
        reader = newReader;
        searcher = new IndexSearcher(reader);
    }
    
    /**
     * Extracts simple name from fully qualified identifier.
     */
    private String extractSimpleName(String id) {
        // Handle method names (com.example.Class#method)
        if (id.contains("#")) {
            return id.substring(id.lastIndexOf("#") + 1);
        }
        
        // Handle class names (com.example.Class)
        if (id.contains(".")) {
            String lastPart = id.substring(id.lastIndexOf(".") + 1);
            // Check if it's likely a field (starts with lowercase)
            if (!lastPart.isEmpty() && Character.isLowerCase(lastPart.charAt(0))) {
                return lastPart;
            }
            // Otherwise, return the class name
            int secondLastDot = id.lastIndexOf(".", id.lastIndexOf(".") - 1);
            if (secondLastDot >= 0 && Character.isUpperCase(id.charAt(secondLastDot + 1))) {
                return id.substring(secondLastDot + 1);
            }
            return lastPart;
        }
        
        return id;
    }
    
    /**
     * Splits identifier into space-separated words.
     */
    private String splitIdentifier(String identifier) {
        // Handle camelCase
        String result = CAMEL_CASE_PATTERN.matcher(identifier).replaceAll(" ");
        
        // Handle snake_case
        result = SNAKE_CASE_PATTERN.matcher(result).replaceAll(" ");
        
        // Handle dots
        result = DOT_PATTERN.matcher(result).replaceAll(" ");
        
        return result.toLowerCase().trim();
    }
    
    /**
     * Tokenizes identifier into individual words.
     */
    private List<String> tokenizeIdentifier(String identifier) {
        String split = splitIdentifier(identifier);
        return Arrays.asList(split.split("\\s+"));
    }
    
    /**
     * Extracts package name from qualified identifier.
     */
    private String extractPackage(String id) {
        int lastDot = id.lastIndexOf(".");
        int hashIndex = id.indexOf("#");
        
        if (hashIndex > 0) {
            // Method: extract package from class part
            String classPart = id.substring(0, hashIndex);
            lastDot = classPart.lastIndexOf(".");
            if (lastDot > 0) {
                return classPart.substring(0, lastDot);
            }
        } else if (lastDot > 0) {
            // Could be class or field
            String possiblePackage = id.substring(0, lastDot);
            // Check if last part starts with uppercase (class) or lowercase (field)
            String lastPart = id.substring(lastDot + 1);
            if (!lastPart.isEmpty() && Character.isUpperCase(lastPart.charAt(0))) {
                // It's a class, return package
                return possiblePackage;
            } else {
                // It's a field, need to extract package from class
                int secondLastDot = possiblePackage.lastIndexOf(".");
                if (secondLastDot > 0) {
                    return possiblePackage.substring(0, secondLastDot);
                }
            }
        }
        
        return "";
    }
    
    /**
     * Extracts class name from qualified identifier.
     */
    private String extractClassName(String id) {
        if (id.contains("#")) {
            // Method: extract class name
            String classPart = id.substring(0, id.indexOf("#"));
            return classPart.substring(classPart.lastIndexOf(".") + 1);
        } else {
            // Could be class or field
            int lastDot = id.lastIndexOf(".");
            if (lastDot > 0) {
                String lastPart = id.substring(lastDot + 1);
                if (!lastPart.isEmpty() && Character.isUpperCase(lastPart.charAt(0))) {
                    // It's a class
                    return lastPart;
                } else {
                    // It's a field, extract class name
                    String beforeLast = id.substring(0, lastDot);
                    int secondLastDot = beforeLast.lastIndexOf(".");
                    if (secondLastDot >= 0) {
                        return beforeLast.substring(secondLastDot + 1);
                    }
                    return beforeLast;
                }
            }
        }
        return "";
    }
    
    /**
     * Closes the index and releases resources.
     */
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
        }
        if (reader != null) {
            reader.close();
        }
        directory.close();
    }
    
    /**
     * Search result from name index.
     */
    public static class SearchResult {
        private final String id;
        private final String signature;
        private final String type;
        private final String filePath;
        private final float score;
        
        public SearchResult(String id, String signature, String type, String filePath, float score) {
            this.id = id;
            this.signature = signature;
            this.type = type;
            this.filePath = filePath;
            this.score = score;
        }
        
        // Getters
        public String getId() { return id; }
        public String getSignature() { return signature; }
        public String getType() { return type; }
        public String getFilePath() { return filePath; }
        public float getScore() { return score; }
    }
}
