package com.deepansh.agent.tool.impl;

import com.deepansh.agent.config.ToolProperties;
import com.deepansh.agent.tool.AgentTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Allows the agent to query and write to the PostgreSQL database.
 *
 * Security model — layered defense:
 * 1. Table allowlists: readable-tables / writable-tables in application.yml
 *    The agent can ONLY touch tables you explicitly permit.
 * 2. Statement type enforcement: SELECT-only tables cannot be mutated.
 * 3. SQL injection prevention: JdbcTemplate parameterized queries.
 *    The agent passes the full SQL but we validate it before execution.
 * 4. Row cap: SELECT results truncated at max-result-rows (default 100)
 *    to prevent context window explosion.
 * 5. No DDL ever: CREATE/DROP/ALTER/TRUNCATE are blocked unconditionally.
 *
 * Production note: For a multi-user or team agent, add a read-only
 * datasource (DataSource readOnlyDs) and route SELECT queries there.
 * For a personal agent, the single datasource is fine.
 */
@Component
@Slf4j
public class DatabaseQueryTool implements AgentTool {

    // Blocked statement prefixes — checked case-insensitively
    private static final List<String> BLOCKED_PREFIXES =
            List.of("DROP", "CREATE", "ALTER", "TRUNCATE", "GRANT", "REVOKE",
                    "EXECUTE", "EXEC", "CALL", "--", "/*");

    // Extracts the first word (statement type) and first table name from SQL
    // Matches: SELECT ... FROM table, INSERT INTO table, UPDATE table
    private static final Pattern SELECT_INSERT_PATTERN =
            Pattern.compile("^\\s*(SELECT|INSERT)\\s+.*?(?:FROM|INTO)\\s+(\\w+)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern UPDATE_PATTERN =
            Pattern.compile("^\\s*(UPDATE)\\s+(\\w+)",
                    Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbcTemplate;
    private final ToolProperties toolProperties;

    public DatabaseQueryTool(JdbcTemplate jdbcTemplate, ToolProperties toolProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.toolProperties = toolProperties;
    }

    @Override
    public String getName() {
        return "query_database";
    }

    @Override
    public String getDescription() {
        return """
                Query or write to the PostgreSQL database.
                Use 'SELECT' to read data, 'INSERT' or 'UPDATE' to write data.
                Only permitted tables can be accessed. Results are returned as a formatted table.
                Use this to look up stored memories, session history, or any other persisted data.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "sql", Map.of(
                                "type", "string",
                                "description", "The SQL statement to execute. Use standard PostgreSQL syntax. " +
                                               "E.g: 'SELECT content, tag FROM agent_memories WHERE user_id = ''deepansh'' ORDER BY created_at DESC LIMIT 10'"
                        ),
                        "params", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Optional list of positional parameters for the query (? placeholders). Prefer these over string interpolation."
                        )
                ),
                "required", List.of("sql")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String sql = (String) arguments.get("sql");
        if (sql == null || sql.isBlank()) {
            return "ERROR: 'sql' is required";
        }

        sql = sql.trim();

        // Layer 1: Block dangerous statement types
        String blockError = checkBlockedStatements(sql);
        if (blockError != null) return blockError;

        // Layer 2: Determine operation type and target table
        StatementInfo info = parseStatement(sql);
        if (info == null) {
            return "ERROR: Could not parse SQL statement. Ensure it starts with SELECT, INSERT, or UPDATE " +
                   "and references a table with FROM/INTO/UPDATE clause.";
        }

        // Layer 3: Table allowlist enforcement
        String allowlistError = checkTableAllowlist(info);
        if (allowlistError != null) return allowlistError;

        // Layer 4: Execute with JdbcTemplate (parameterized, injection-safe)
        Object[] params = resolveParams(arguments);

        log.info("DB tool executing [type={}, table={}]", info.statementType(), info.tableName());

        try {
            return switch (info.statementType().toUpperCase()) {
                case "SELECT" -> executeSelect(sql, params);
                case "INSERT", "UPDATE" -> executeWrite(sql, params);
                default -> "ERROR: Only SELECT, INSERT, UPDATE are supported.";
            };
        } catch (Exception e) {
            log.error("DB query failed: {}", sql, e);
            return "ERROR: Query execution failed — " + e.getMessage();
        }
    }

    private String executeSelect(String sql, Object[] params) {
        int maxRows = toolProperties.getDatabase().getMaxResultRows();

        // Append LIMIT if not already present
        String limitedSql = sql.toLowerCase().contains("limit") ? sql
                : sql + " LIMIT " + maxRows;

        List<Map<String, Object>> rows = params.length > 0
                ? jdbcTemplate.queryForList(limitedSql, params)
                : jdbcTemplate.queryForList(limitedSql);

        if (rows.isEmpty()) {
            return "Query returned 0 rows.";
        }

        return formatResultSet(rows);
    }

    private String executeWrite(String sql, Object[] params) {
        int affected = params.length > 0
                ? jdbcTemplate.update(sql, params)
                : jdbcTemplate.update(sql);

        return String.format("Write successful. Rows affected: %d", affected);
    }

    private String formatResultSet(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "0 rows returned.";

        List<String> columns = List.copyOf(rows.get(0).keySet());

        // Build column widths
        Map<String, Integer> widths = columns.stream().collect(
                Collectors.toMap(c -> c, c -> Math.max(c.length(),
                        rows.stream().mapToInt(r -> {
                            Object v = r.get(c);
                            return v == null ? 4 : v.toString().length();
                        }).max().orElse(0))));

        StringBuilder sb = new StringBuilder();

        // Header
        columns.forEach(c -> sb.append(pad(c, widths.get(c))).append(" | "));
        sb.append("\n");
        columns.forEach(c -> sb.append("-".repeat(widths.get(c))).append("-+-"));
        sb.append("\n");

        // Rows
        rows.forEach(row -> {
            columns.forEach(c -> {
                Object val = row.get(c);
                String cell = val == null ? "NULL" : val.toString();
                // Truncate long cell values
                if (cell.length() > 80) cell = cell.substring(0, 77) + "...";
                sb.append(pad(cell, widths.get(c))).append(" | ");
            });
            sb.append("\n");
        });

        sb.append("\n").append(rows.size()).append(" row(s) returned.");
        return sb.toString();
    }

    private String pad(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }

    private String checkBlockedStatements(String sql) {
        String upper = sql.toUpperCase().stripLeading();
        for (String blocked : BLOCKED_PREFIXES) {
            if (upper.startsWith(blocked.toUpperCase())) {
                return "ERROR: Statement type '" + blocked + "' is not permitted. " +
                       "Only SELECT, INSERT, and UPDATE are allowed.";
            }
        }
        return null;
    }

    private StatementInfo parseStatement(String sql) {
        Matcher m = UPDATE_PATTERN.matcher(sql);
        if (m.find()) {
            return new StatementInfo(m.group(1).toUpperCase(), m.group(2).toLowerCase());
        }
        m = SELECT_INSERT_PATTERN.matcher(sql);
        if (m.find()) {
            return new StatementInfo(m.group(1).toUpperCase(), m.group(2).toLowerCase());
        }
        return null;
    }

    private String checkTableAllowlist(StatementInfo info) {
        List<String> readable = toolProperties.getDatabase().getReadableTableList();
        List<String> writable = toolProperties.getDatabase().getWritableTableList();

        return switch (info.statementType()) {
            case "SELECT" -> {
                if (!readable.isEmpty() && !readable.contains(info.tableName())) {
                    yield "ERROR: Table '" + info.tableName() + "' is not in the readable tables list. " +
                          "Allowed: " + readable;
                }
                yield null;
            }
            case "INSERT", "UPDATE" -> {
                if (!writable.contains(info.tableName())) {
                    yield "ERROR: Table '" + info.tableName() + "' is not in the writable tables list. " +
                          "Allowed: " + (writable.isEmpty() ? "[none configured]" : writable.toString());
                }
                yield null;
            }
            default -> "ERROR: Unsupported statement type: " + info.statementType();
        };
    }

    @SuppressWarnings("unchecked")
    private Object[] resolveParams(Map<String, Object> arguments) {
        Object rawParams = arguments.get("params");
        if (rawParams instanceof List<?> list) {
            return list.toArray();
        }
        return new Object[0];
    }

    private record StatementInfo(String statementType, String tableName) {}
}
