package com.deepansh.agent.tool.impl;

import com.deepansh.agent.config.ToolProperties;
import com.deepansh.agent.tool.AgentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MongoDB query tool — lets the agent query and write to collections.
 *
 * Operations:
 * - find:         query documents with a filter (JSON object)
 * - insertOne:    insert a single document
 * - updateMany:   update documents matching a filter
 * - countDocuments: count matching documents
 *
 * Security model:
 * - Collection allowlists: readable-collections / writable-collections
 * - Result cap: max-result-docs (default 100)
 * - No drop/delete-collection/drop-database commands
 * - Filter and document are parsed from JSON strings
 *
 * The agent provides queries as JSON strings:
 *   filter: '{"userId": "deepansh", "tag": "preference"}'
 *   document: '{"content": "prefers bullet points", "tag": "preference"}'
 */
@Component
@Slf4j
public class DatabaseQueryTool implements AgentTool {

    private static final List<String> BLOCKED_OPERATIONS =
            List.of("drop", "dropDatabase", "dropCollection", "deleteMany", "deleteOne",
                    "bulkWrite", "createIndex", "runCommand");

    private final MongoTemplate mongoTemplate;
    private final ToolProperties toolProperties;
    private final ObjectMapper objectMapper;

    public DatabaseQueryTool(MongoTemplate mongoTemplate,
                              ToolProperties toolProperties,
                              ObjectMapper objectMapper) {
        this.mongoTemplate = mongoTemplate;
        this.toolProperties = toolProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "query_database";
    }

    @Override
    public String getDescription() {
        return """
                Query or write to the MongoDB database.
                Operations: find (query documents), insertOne (add document),
                updateMany (modify documents), countDocuments (count matching docs).
                Filters and documents are provided as JSON strings.
                Only permitted collections can be accessed.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "operation", Map.of(
                                "type", "string",
                                "enum", List.of("find", "insertOne", "updateMany", "countDocuments"),
                                "description", "The MongoDB operation to execute"
                        ),
                        "collection", Map.of(
                                "type", "string",
                                "description", "The collection name. E.g: 'agent_memories', 'agent_sessions'"
                        ),
                        "filter", Map.of(
                                "type", "string",
                                "description", "JSON filter document. E.g: '{\"userId\": \"deepansh\", \"tag\": \"preference\"}'. Use '{}' for no filter."
                        ),
                        "document", Map.of(
                                "type", "string",
                                "description", "JSON document for insertOne, or update spec for updateMany. E.g: '{\"$set\": {\"tag\": \"fact\"}}'"
                        ),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "Max documents to return for find (default: 20, max: 100)"
                        )
                ),
                "required", List.of("operation", "collection")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String operation  = (String) arguments.get("operation");
        String collection = (String) arguments.get("collection");

        if (operation == null || operation.isBlank())
            return "ERROR: 'operation' is required";
        if (collection == null || collection.isBlank())
            return "ERROR: 'collection' is required";

        log.info("DB tool: operation={} collection={}", operation, collection);

        // Block dangerous operations — checked before switch to guarantee the error message
        if (BLOCKED_OPERATIONS.stream().anyMatch(b -> b.equalsIgnoreCase(operation))) {
            return "ERROR: Operation '" + operation + "' is not permitted.";
        }

        try {
            return switch (operation.toLowerCase()) {
                case "find"            -> executeFind(collection, arguments);
                case "insertone"       -> executeInsert(collection, arguments);
                case "updatemany"      -> executeUpdate(collection, arguments);
                case "countdocuments"  -> executeCount(collection, arguments);
                default -> "ERROR: Unknown operation. Use: find, insertOne, updateMany, countDocuments";
            };
        } catch (Exception e) {
            log.error("DB tool failed: operation={} collection={}", operation, collection, e);
            return "ERROR: " + e.getMessage();
        }
    }

    private String executeFind(String collection, Map<String, Object> args) throws Exception {
        checkReadable(collection);

        int maxDocs = toolProperties.getDatabase().getMaxResultDocs();
        int limit = resolveLimit(args.get("limit"), maxDocs);

        Document filter = parseFilter(args);

        List<Document> results = mongoTemplate.getDb()
                .getCollection(collection)
                .find(filter)
                .limit(limit)
                .into(new java.util.ArrayList<>());

        if (results.isEmpty()) return "No documents found in '" + collection + "' matching filter.";

        String json = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(results.stream()
                        .map(d -> objectMapper.convertValue(d, Map.class))
                        .collect(Collectors.toList()));

        return String.format("Found %d document(s) in '%s':\n\n%s", results.size(), collection, json);
    }

    private String executeInsert(String collection, Map<String, Object> args) throws Exception {
        checkWritable(collection);

        String docJson = (String) args.get("document");
        if (docJson == null || docJson.isBlank())
            return "ERROR: 'document' is required for insertOne";

        Document doc = Document.parse(docJson);
        mongoTemplate.getDb().getCollection(collection).insertOne(doc);

        return "Inserted document into '" + collection + "' with id: " + doc.getObjectId("_id");
    }

    private String executeUpdate(String collection, Map<String, Object> args) throws Exception {
        checkWritable(collection);

        Document filter = parseFilter(args);
        String updateJson = (String) args.get("document");
        if (updateJson == null || updateJson.isBlank())
            return "ERROR: 'document' (update spec) is required for updateMany";

        Document update = Document.parse(updateJson);
        var result = mongoTemplate.getDb().getCollection(collection).updateMany(filter, update);

        return String.format("Updated %d document(s) in '%s' (matched: %d)",
                result.getModifiedCount(), collection, result.getMatchedCount());
    }

    private String executeCount(String collection, Map<String, Object> args) throws Exception {
        checkReadable(collection);
        Document filter = parseFilter(args);
        long count = mongoTemplate.getDb().getCollection(collection).countDocuments(filter);
        return String.format("Count in '%s' matching filter: %d", collection, count);
    }

    private void checkReadable(String collection) {
        List<String> readable = toolProperties.getDatabase().getReadableCollectionList();
        if (!readable.isEmpty() && !readable.contains(collection)) {
            throw new SecurityException("Collection '" + collection + "' is not in the readable list. Allowed: " + readable);
        }
    }

    private void checkWritable(String collection) {
        List<String> writable = toolProperties.getDatabase().getWritableCollectionList();
        if (!writable.contains(collection)) {
            throw new SecurityException("Collection '" + collection + "' is not in the writable list. Allowed: " +
                    (writable.isEmpty() ? "[none configured]" : writable.toString()));
        }
    }

    private Document parseFilter(Map<String, Object> args) {
        String filterJson = (String) args.getOrDefault("filter", "{}");
        if (filterJson == null || filterJson.isBlank()) filterJson = "{}";
        return Document.parse(filterJson);
    }

    private int resolveLimit(Object raw, int max) {
        if (raw == null) return Math.min(20, max);
        try {
            return Math.min(Integer.parseInt(raw.toString()), max);
        } catch (NumberFormatException e) {
            return Math.min(20, max);
        }
    }
}
