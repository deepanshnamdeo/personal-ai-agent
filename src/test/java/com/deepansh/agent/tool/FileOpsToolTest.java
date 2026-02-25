package com.deepansh.agent.tool;

import com.deepansh.agent.config.ToolProperties;
import com.deepansh.agent.tool.impl.FileOpsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileOpsToolTest {

    @TempDir
    Path tempDir;

    private FileOpsTool tool;
    private ToolProperties props;

    @BeforeEach
    void setUp() {
        props = new ToolProperties();
        props.getFileOps().setBaseDirectory(tempDir.toString());
        tool = new FileOpsTool(props);
    }

    @Test
    void write_thenRead_roundTrips() {
        tool.execute(Map.of("operation", "write", "filename", "test.txt", "content", "hello world"));
        String result = tool.execute(Map.of("operation", "read", "filename", "test.txt"));
        assertThat(result).isEqualTo("hello world");
    }

    @Test
    void append_addsToExistingFile() {
        tool.execute(Map.of("operation", "write",  "filename", "notes.txt", "content", "line1\n"));
        tool.execute(Map.of("operation", "append", "filename", "notes.txt", "content", "line2\n"));
        String result = tool.execute(Map.of("operation", "read", "filename", "notes.txt"));
        assertThat(result).contains("line1").contains("line2");
    }

    @Test
    void list_showsWrittenFiles() {
        tool.execute(Map.of("operation", "write", "filename", "a.md", "content", "# A"));
        tool.execute(Map.of("operation", "write", "filename", "b.txt", "content", "B"));
        String result = tool.execute(Map.of("operation", "list"));
        assertThat(result).contains("a.md").contains("b.txt");
    }

    @Test
    void delete_removesFile() {
        tool.execute(Map.of("operation", "write", "filename", "temp.txt", "content", "x"));
        tool.execute(Map.of("operation", "delete", "filename", "temp.txt"));
        String result = tool.execute(Map.of("operation", "read", "filename", "temp.txt"));
        assertThat(result).contains("not found");
    }

    @Test
    void write_disallowedExtension_returnsError() {
        String result = tool.execute(Map.of("operation", "write",
                "filename", "evil.exe", "content", "bad content"));
        assertThat(result).startsWith("ERROR:").contains("not allowed");
    }

    @Test
    void read_pathTraversal_returnsError() {
        String result = tool.execute(Map.of("operation", "read", "filename", "../../etc/passwd"));
        assertThat(result).startsWith("ERROR:");
    }

    @Test
    void write_missingContent_returnsError() {
        String result = tool.execute(Map.of("operation", "write", "filename", "test.txt"));
        assertThat(result).startsWith("ERROR:").contains("content");
    }

    @Test
    void read_nonExistentFile_returnsNotFound() {
        String result = tool.execute(Map.of("operation", "read", "filename", "ghost.txt"));
        assertThat(result).contains("not found");
    }

    @Test
    void subdirectory_createsAndReads() {
        tool.execute(Map.of("operation", "write", "filename", "notes/project.md", "content", "# Project"));
        String result = tool.execute(Map.of("operation", "read", "filename", "notes/project.md"));
        assertThat(result).isEqualTo("# Project");
    }
}
