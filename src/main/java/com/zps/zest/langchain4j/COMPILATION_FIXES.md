# Compilation Fixes Applied

## Issues Fixed

1. **OpenAiTokenizer not found**
   - Removed dependency on OpenAiTokenizer (not available in base LangChain4j)
   - Replaced with simple character-based token estimation (4 chars per token)
   - DocumentSplitters now use the overloaded methods without tokenizer

2. **Metadata type mismatches**
   - Changed from `Map<String, String>` to LangChain4j's `Metadata` class
   - Updated all createMetadata methods to return `Metadata` objects
   - Fixed TextSegment.from() calls to use Metadata instead of Map
   - Added proper conversion in createSegment method

3. **Messages.showChooseDialog parameter order**
   - Fixed parameter order: icon parameter comes before options array
   - Changed to pass `null` for icon parameter

4. **@Override annotation issue**
   - The BaseAgentTool class does have getExampleParams() method
   - Keep the @Override annotation as it correctly overrides the base method

5. **RagService metadata handling**
   - Fixed metadata.putAll() to properly handle LangChain4j's Metadata type
   - Used metadata.toMap() to get the underlying map for iteration

## Dependencies

The following LangChain4j dependencies are already in build.gradle.kts:
- langchain4j:0.35.0
- langchain4j-embeddings:0.35.0
- langchain4j-embeddings-all-minilm-l6-v2:0.35.0
- langchain4j-embeddings-bge-small-en-v15-q:0.35.0
- langchain4j-document-parser-apache-tika:0.35.0
- Apache Tika 2.9.2

## Notes

- The tokenizer implementation is simplified (4 chars/token estimate)
- For more accurate tokenization, consider adding a specific tokenizer library
- The code now properly uses LangChain4j's type system throughout

## Remaining Warnings (Non-Critical)

- `StringEscapeUtils` from org.apache.commons.lang is deprecated
  - Used in GenerateCodeCommentsAction, ChatboxUtilities, and WebBrowserPanel
  - Should be replaced with org.apache.commons.text.StringEscapeUtils
  - Or use alternative escaping methods
