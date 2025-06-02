package com.zps.zest.langchain4j;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.zps.zest.rag.CodeAnalyzer;
import com.zps.zest.rag.CodeSignature;
import com.zps.zest.rag.DefaultCodeAnalyzer;
import dev.langchain4j.data.segment.TextSegment;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Test action for LangChain4j embedding service.
 * Demonstrates all features with performance metrics.
 */
public class TestLangChain4jAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(TestLangChain4jAction.class);
    
    public TestLangChain4jAction() {
        super("Test LangChain4j Embeddings", 
              "Test the LangChain4j embedding service with various features", 
              null);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        // Show options dialog
        String[] options = {
            "Quick Test (Basic embedding)",
            "Document Processing Test",
            "Search Test (Index current file)",
            "Hybrid Search Test",
            "Full System Test",
            "Performance Benchmark"
        };
        
        int choice = Messages.showChooseDialog(
            project,
            "Choose a test to run:",
            "Test LangChain4j",
            options,
            options[0],
            Messages.getQuestionIcon()
        );
        
        if (choice < 0) return;
        
        ProgressManager.getInstance().run(
            new Task.Backgroundable(project, "Testing LangChain4j", true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    StringBuilder results = new StringBuilder();
                    results.append("=== LangChain4j Test Results ===\n\n");
                    
                    try {
                        switch (choice) {
                            case 0:
                                runQuickTest(project, results, indicator);
                                break;
                            case 1:
                                runDocumentProcessingTest(project, results, indicator);
                                break;
                            case 2:
                                runSearchTest(project, results, indicator);
                                break;
                            case 3:
                                runHybridSearchTest(project, results, indicator);
                                break;
                            case 4:
                                runFullSystemTest(project, results, indicator);
                                break;
                            case 5:
                                runPerformanceBenchmark(project, results, indicator);
                                break;
                        }
                    } catch (Exception ex) {
                        results.append("ERROR: ").append(ex.getMessage()).append("\n");
                        LOG.error("Test failed", ex);
                    }
                    
                    // Show results
                    showResults(project, results.toString());
                }
            }
        );
    }
    
    private void runQuickTest(Project project, StringBuilder results, ProgressIndicator indicator) {
        indicator.setText("Running quick embedding test...");
        
        LocalEmbeddingService service = new LocalEmbeddingService();
        results.append("## Quick Embedding Test\n\n");
        
        // Test 1: Single embedding
        long start = System.currentTimeMillis();
        float[] embedding = service.embed("public class HelloWorld implements Serializable");
        long elapsed = System.currentTimeMillis() - start;
        
        results.append("✓ Single embedding generated\n");
        results.append("  - Model: ").append(service.getModelName()).append("\n");
        results.append("  - Dimension: ").append(embedding.length).append("\n");
        results.append("  - Time: ").append(elapsed).append("ms\n\n");
        
        // Test 2: Batch embedding
        List<String> texts = List.of(
            "public void processData(String input)",
            "private static final Logger LOG = Logger.getInstance()",
            "@Override public String toString()"
        );
        
        start = System.currentTimeMillis();
        List<float[]> embeddings = service.embedBatch(texts);
        elapsed = System.currentTimeMillis() - start;
        
        results.append("✓ Batch embeddings generated\n");
        results.append("  - Count: ").append(embeddings.size()).append("\n");
        results.append("  - Total time: ").append(elapsed).append("ms\n");
        results.append("  - Avg time per text: ").append(elapsed / texts.size()).append("ms\n\n");
        
        // Test 3: Similarity
        double similarity = service.cosineSimilarity(embeddings.get(0), embeddings.get(2));
        results.append("✓ Cosine similarity calculated\n");
        results.append("  - Similarity between method signatures: ")
              .append(String.format("%.3f", similarity)).append("\n\n");
        
        service.dispose();
    }
    
    private void runDocumentProcessingTest(Project project, StringBuilder results, ProgressIndicator indicator) {
        indicator.setText("Testing document processing...");
        
        DocumentProcessor processor = new DocumentProcessor();
        results.append("## Document Processing Test\n\n");
        
        // Create test content
        String javaCode = """
            package com.example;
            
            import java.util.List;
            
            /**
             * Example class for testing document processing.
             */
            public class ExampleService {
                private final Logger logger = LoggerFactory.getLogger(ExampleService.class);
                
                /**
                 * Processes a list of items.
                 * @param items the items to process
                 * @return processed count
                 */
                public int processItems(List<String> items) {
                    logger.info("Processing {} items", items.size());
                    int count = 0;
                    for (String item : items) {
                        if (processItem(item)) {
                            count++;
                        }
                    }
                    return count;
                }
                
                private boolean processItem(String item) {
                    // Complex processing logic here
                    return item != null && !item.isEmpty();
                }
            }
            """;
        
        // Test code processing
        long start = System.currentTimeMillis();
        List<TextSegment> segments = processor.processMarkdown(javaCode, "ExampleService.java");
        long elapsed = System.currentTimeMillis() - start;
        
        results.append("✓ Code document processed\n");
        results.append("  - Segments created: ").append(segments.size()).append("\n");
        results.append("  - Processing time: ").append(elapsed).append("ms\n\n");
        
        // Show segment details
        results.append("Segment details:\n");
        for (int i = 0; i < Math.min(3, segments.size()); i++) {
            TextSegment segment = segments.get(i);
            results.append("  [").append(i + 1).append("] Length: ")
                  .append(segment.text().length()).append(" chars\n");
            results.append("      Preview: ")
                  .append(segment.text().substring(0, Math.min(50, segment.text().length())))
                  .append("...\n");
        }
        
        // Test token estimation
        int tokens = processor.estimateTokens(javaCode);
        results.append("\n✓ Token estimation\n");
        results.append("  - Estimated tokens: ").append(tokens).append("\n");
        results.append("  - Chars per token: ")
              .append(String.format("%.1f", (double) javaCode.length() / tokens)).append("\n\n");
    }
    
    private void runSearchTest(Project project, StringBuilder results, ProgressIndicator indicator) {
        indicator.setText("Testing vector search...");
        
        results.append("## Vector Search Test\n\n");
        
        // Get current file
        VirtualFile currentFile = getCurrentFile(project);
        if (currentFile == null) {
            results.append("❌ No file currently open in editor\n");
            return;
        }
        
        RagService ragService = project.getService(RagService.class);
        
        // Clear existing index
        ragService.clearIndex();
        
        // Index current file
        indicator.setText("Indexing " + currentFile.getName() + "...");
        long start = System.currentTimeMillis();
        
        // Extract code signatures if it's a Java file
        List<CodeSignature> signatures = null;
        PsiFile psiFile = PsiManager.getInstance(project).findFile(currentFile);
        if (psiFile instanceof PsiJavaFile) {
            CodeAnalyzer analyzer = new DefaultCodeAnalyzer(project);
            signatures = analyzer.extractSignatures(psiFile);
            results.append("✓ Extracted ").append(signatures.size())
                  .append(" code signatures\n");
        }
        
        int segments = ragService.indexFile(currentFile, signatures);
        long indexTime = System.currentTimeMillis() - start;
        
        results.append("✓ File indexed: ").append(currentFile.getName()).append("\n");
        results.append("  - Segments: ").append(segments).append("\n");
        results.append("  - Index time: ").append(indexTime).append("ms\n\n");
        
        // Test searches
        String[] queries = {
            "class definition",
            "public method",
            "import statements",
            "error handling"
        };
        
        results.append("Search results:\n");
        for (String query : queries) {
            start = System.currentTimeMillis();
            List<RagService.SearchResult> searchResults = ragService.search(query, 3).join();
            long searchTime = System.currentTimeMillis() - start;
            
            results.append("\n✓ Query: \"").append(query).append("\"\n");
            results.append("  - Results: ").append(searchResults.size()).append("\n");
            results.append("  - Search time: ").append(searchTime).append("ms\n");
            
            if (!searchResults.isEmpty()) {
                RagService.SearchResult top = searchResults.get(0);
                results.append("  - Top score: ")
                      .append(String.format("%.3f", top.getScore())).append("\n");
                results.append("  - Preview: ")
                      .append(top.getContent().substring(0, Math.min(60, top.getContent().length())))
                      .append("...\n");
            }
        }
        
        // Show statistics
        Map<String, Object> stats = ragService.getStatistics();
        results.append("\n✓ Index statistics:\n");
        stats.forEach((key, value) -> 
            results.append("  - ").append(key).append(": ").append(value).append("\n")
        );
    }
    
    private void runHybridSearchTest(Project project, StringBuilder results, ProgressIndicator indicator) {
        indicator.setText("Testing hybrid search...");
        
        results.append("## Hybrid Search Test\n\n");
        
        HybridRagAgent hybridAgent = project.getService(HybridRagAgent.class);
        
        // Configure hybrid search
        hybridAgent.configure(true, 0.7); // Prefer local with 70% weight
        
        // Test queries
        String[] queries = {
            "configuration settings",
            "database connection",
            "API endpoint",
            "authentication"
        };
        
        for (String query : queries) {
            indicator.setText("Searching: " + query);
            
            long start = System.currentTimeMillis();
            List<HybridRagAgent.HybridSearchResult> results_ = 
                hybridAgent.search(query, 5).join();
            long elapsed = System.currentTimeMillis() - start;
            
            results.append("\n✓ Query: \"").append(query).append("\"\n");
            results.append("  - Total results: ").append(results_.size()).append("\n");
            results.append("  - Search time: ").append(elapsed).append("ms\n");
            
            // Count by source
            long localCount = results_.stream()
                .filter(r -> r.getSource() == HybridRagAgent.SearchSource.LOCAL)
                .count();
            long cloudCount = results_.stream()
                .filter(r -> r.getSource() == HybridRagAgent.SearchSource.CLOUD)
                .count();
            long bothCount = results_.stream()
                .filter(r -> r.getSource() == HybridRagAgent.SearchSource.BOTH)
                .count();
            
            results.append("  - Sources: LOCAL=").append(localCount)
                  .append(", CLOUD=").append(cloudCount)
                  .append(", BOTH=").append(bothCount).append("\n");
            
            if (!results_.isEmpty()) {
                HybridRagAgent.HybridSearchResult top = results_.get(0);
                results.append("  - Top result: ")
                      .append(top.getSource()).append(" (score: ")
                      .append(String.format("%.3f", top.getScore())).append(")\n");
            }
        }
        
        // Show statistics
        Map<String, Object> stats = hybridAgent.getStatistics();
        results.append("\n✓ Hybrid system statistics:\n");
        results.append(formatStats(stats, "  "));
    }
    
    private void runFullSystemTest(Project project, StringBuilder results, ProgressIndicator indicator) {
        results.append("## Full System Test\n\n");
        
        // Run all tests in sequence
        runQuickTest(project, results, indicator);
        results.append("\n---\n\n");
        
        runDocumentProcessingTest(project, results, indicator);
        results.append("\n---\n\n");
        
        runSearchTest(project, results, indicator);
        results.append("\n---\n\n");
        
        runHybridSearchTest(project, results, indicator);
    }
    
    private void runPerformanceBenchmark(Project project, StringBuilder results, ProgressIndicator indicator) {
        indicator.setText("Running performance benchmark...");
        
        results.append("## Performance Benchmark\n\n");
        
        LocalEmbeddingService service = new LocalEmbeddingService();
        
        // Benchmark 1: Embedding generation speed
        results.append("### Embedding Generation Speed\n");
        
        String[] testTexts = {
            "Short text",
            "A medium length text that contains more words and should take slightly longer to process",
            "This is a much longer text that simulates a typical code documentation or comment. " +
            "It contains multiple sentences and should give us a good idea of the embedding " +
            "generation performance for real-world use cases. The embedding model needs to " +
            "process all these tokens and generate a fixed-size vector representation."
        };
        
        for (String text : testTexts) {
            // Warm up
            service.embed(text);
            
            // Benchmark
            int iterations = 100;
            long start = System.currentTimeMillis();
            for (int i = 0; i < iterations; i++) {
                service.embed(text);
            }
            long elapsed = System.currentTimeMillis() - start;
            
            results.append("✓ Text length: ").append(text.length()).append(" chars\n");
            results.append("  - Total time: ").append(elapsed).append("ms\n");
            results.append("  - Avg per embedding: ")
                  .append(String.format("%.2f", (double) elapsed / iterations)).append("ms\n");
            results.append("  - Throughput: ")
                  .append(String.format("%.0f", iterations * 1000.0 / elapsed))
                  .append(" embeddings/sec\n\n");
        }
        
        // Benchmark 2: Similarity calculation
        results.append("### Similarity Calculation Speed\n");
        
        float[] embedding1 = service.embed("test embedding 1");
        float[] embedding2 = service.embed("test embedding 2");
        
        int simIterations = 10000;
        long start = System.currentTimeMillis();
        for (int i = 0; i < simIterations; i++) {
            service.cosineSimilarity(embedding1, embedding2);
        }
        long elapsed = System.currentTimeMillis() - start;
        
        results.append("✓ Similarity calculations: ").append(simIterations).append("\n");
        results.append("  - Total time: ").append(elapsed).append("ms\n");
        results.append("  - Throughput: ")
              .append(String.format("%.0f", simIterations * 1000.0 / elapsed))
              .append(" calculations/sec\n\n");
        
        // Benchmark 3: Vector search
        results.append("### Vector Search Performance\n");
        
        InMemoryVectorStore store = new InMemoryVectorStore(service);
        
        // Build index
        int[] sizes = {100, 1000, 5000};
        for (int size : sizes) {
            store.clear();
            
            // Index documents
            for (int i = 0; i < size; i++) {
                String text = "Document " + i + " with some content";
                float[] emb = service.embed(text);
                store.store("doc-" + i, emb, TextSegment.from(text), Map.of("index", i));
            }
            
            // Search benchmark
            float[] queryEmb = service.embed("search query");
            
            // Warm up
            store.search(queryEmb, 10);
            
            int searchIterations = 100;
            start = System.currentTimeMillis();
            for (int i = 0; i < searchIterations; i++) {
                store.search(queryEmb, 10);
            }
            elapsed = System.currentTimeMillis() - start;
            
            results.append("✓ Index size: ").append(size).append(" documents\n");
            results.append("  - Search iterations: ").append(searchIterations).append("\n");
            results.append("  - Total time: ").append(elapsed).append("ms\n");
            results.append("  - Avg search time: ")
                  .append(String.format("%.2f", (double) elapsed / searchIterations)).append("ms\n\n");
        }
        
        service.dispose();
    }
    
    private VirtualFile getCurrentFile(Project project) {
        // This is a simplified version - you might want to use FileEditorManager
        VirtualFile[] files = com.intellij.openapi.fileEditor.FileEditorManager
            .getInstance(project).getSelectedFiles();
        return files.length > 0 ? files[0] : null;
    }
    
    private String formatStats(Map<String, Object> stats, String indent) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : stats.entrySet()) {
            if (entry.getValue() instanceof Map) {
                sb.append(indent).append(entry.getKey()).append(":\n");
                sb.append(formatStats((Map<String, Object>) entry.getValue(), indent + "  "));
            } else {
                sb.append(indent).append(entry.getKey()).append(": ")
                  .append(entry.getValue()).append("\n");
            }
        }
        return sb.toString();
    }
    
    private void showResults(Project project, String results) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            // Create a custom dialog with scrollable text area
            com.intellij.openapi.ui.DialogBuilder builder = new com.intellij.openapi.ui.DialogBuilder(project);
            builder.setTitle("LangChain4j Test Results");
            
            javax.swing.JTextArea textArea = new javax.swing.JTextArea(results);
            textArea.setEditable(false);
            textArea.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
            
            javax.swing.JScrollPane scrollPane = new javax.swing.JScrollPane(textArea);
            scrollPane.setPreferredSize(new java.awt.Dimension(800, 600));
            
            builder.setCenterPanel(scrollPane);
            builder.addOkAction();
            builder.show();
        });
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabled(project != null);
    }
}
