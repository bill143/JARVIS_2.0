package com.jarvis.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.FileRecordStore;
import com.jarvis.memory.InMemoryRecordStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskBoardTest {

    @Test
    void createMoveAndListFoldTheEventLog() {
        TaskBoard board = new TaskBoard(new InMemoryRecordStore());
        Task a = board.create("Write spec", "", List.of(), 1);
        board.create("Ship it", "", List.of(a.id()), 2);
        assertEquals(2, board.list().size());

        board.move(a.id(), TaskStatus.DONE);
        Task reloaded = board.list().stream().filter(t -> t.id().equals(a.id())).findFirst().orElseThrow();
        assertEquals(TaskStatus.DONE, reloaded.status());
    }

    @Test
    void deleteRemovesFromTheFold() {
        TaskBoard board = new TaskBoard(new InMemoryRecordStore());
        Task t = board.create("temp", "", List.of(), 1);
        board.delete(t.id());
        assertTrue(board.list().isEmpty());
    }

    @Test
    void dependencyBlocksUntilItsDependencyIsDone() {
        TaskBoard board = new TaskBoard(new InMemoryRecordStore());
        Task a = board.create("A", "", List.of(), 1);
        Task b = board.create("B", "", List.of(a.id()), 2);

        assertTrue(TaskBoard.isBlocked(refresh(board, b.id()), board.list()));   // A not done
        board.move(a.id(), TaskStatus.DONE);
        assertFalse(TaskBoard.isBlocked(refresh(board, b.id()), board.list()));  // A done -> unblocked
    }

    @Test
    void tasksSurviveARestartWhenFileBacked(@TempDir Path dir) {
        new TaskBoard(new FileRecordStore(dir)).create("persist me", "notes", List.of(), 1);
        List<Task> reopened = new TaskBoard(new FileRecordStore(dir)).list();
        assertEquals(1, reopened.size());
        assertEquals("persist me", reopened.get(0).title());
    }

    private static Task refresh(TaskBoard board, String id) {
        return board.list().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }
}
