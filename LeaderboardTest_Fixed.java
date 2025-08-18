package com.zps.leaderboard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.ScriptOutputType;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@ExtendWith(MockitoExtension.class)
public class LeaderboardTest {

    @Mock 
    private StatefulRedisConnection<String, String> mockConnection;
    
    @Mock 
    private RedisCommands<String, String> mockCommands;
    
    private Leaderboard leaderboard;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(mockConnection.sync()).thenReturn(mockCommands);
        leaderboard = new Leaderboard("testLeaderboardKey", mockConnection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    public void testLoadScriptSuccess() {
        var luaScriptContent = "return 'abcdef123456'";
        
        try (var filesMocked = mockStatic(Files.class)) {
            filesMocked.when(() -> Files.readString(Path.of("config/leaderboard.lua")))
                      .thenReturn(luaScriptContent);
            
            when(mockCommands.scriptLoad(luaScriptContent)).thenReturn("deadbeef1234");
            
            var lb = new Leaderboard("key", mockConnection);
            // Note: Testing private method through constructor behavior
            // In real scenario, we'd test public behavior that uses the script
            assertNotNull(lb);
        }
    }

    @Test
    public void testLoadScriptFailure() {
        try (var filesMocked = mockStatic(Files.class)) {
            filesMocked.when(() -> Files.readString(Path.of("config/leaderboard.lua")))
                      .thenThrow(new RuntimeException("File not found"));
            
            RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
                new Leaderboard("key", mockConnection);
            });
            
            assertTrue(thrown.getMessage().contains("Failed to initialize leaderboard service"));
        }
    }

    @Test
    public void testAddScoreValid() {
        List<Object> mockResult = Arrays.asList("user123", 10.0, 15.5, 5, 0, 123456789L);
        when(mockCommands.evalsha(anyString(), eq(ScriptOutputType.MULTI), any(String[].class), 
                                 eq("user123"), eq("10.0"), anyString()))
            .thenReturn(mockResult);
        
        LeaderboardScore score = leaderboard.addScore("user123", 10.0);
        
        assertNotNull(score);
        assertEquals("user123", score.userId());
        assertEquals(10.0, score.rawScore());
        assertEquals(15.5, score.compositeScore());
        assertEquals(5, score.rank());
        assertEquals(123456789L, score.timestamp());
    }

    @Test
    public void testAddScoreNullResult() {
        when(mockCommands.evalsha(anyString(), eq(ScriptOutputType.MULTI), 
                                 any(String[].class), anyString(), anyString(), anyString()))
            .thenReturn(null);
        
        LeaderboardScore score = leaderboard.addScore("user123", 5.0);
        
        assertNotNull(score);
        assertEquals("user123", score.userId());
        assertEquals(0.0, score.rawScore());
        assertEquals(0.0, score.compositeScore());
        assertEquals(0, score.rank());
        assertEquals(0L, score.timestamp());
    }

    @Test
    public void testGetTopScoresValid() {
        List<Object> entries = new ArrayList<>();
        entries.addAll(Arrays.asList("user1", 1.0, 2.0, 3, "123456789"));
        entries.addAll(Arrays.asList("user2", 4.0, 5.0, 6, "987654321"));
        List<Object> redisResult = Arrays.asList("ignored", entries);
        
        when(mockCommands.evalsha(anyString(), eq(ScriptOutputType.MULTI), 
                                 any(String[].class), eq("5")))
            .thenReturn(redisResult);
        
        List<LeaderboardScore> scores = leaderboard.getTopScores(5);
        
        assertNotNull(scores);
        assertEquals(2, scores.size());
        
        LeaderboardScore first = scores.get(0);
        assertEquals("user1", first.userId());
        assertEquals(1.0, first.rawScore());
        assertEquals(2.0, first.compositeScore());
        assertEquals(3, first.rank());
        assertEquals(123456789L, first.timestamp());
    }

    @Test
    public void testGetTopScoresEmptyResult() {
        List<Object> redisResultEmptyEntries = Arrays.asList("ignored", Collections.emptyList());
        when(mockCommands.evalsha(anyString(), eq(ScriptOutputType.MULTI), 
                                 any(String[].class), anyString()))
            .thenReturn(redisResultEmptyEntries);
        
        List<LeaderboardScore> emptyScores = leaderboard.getTopScores(5);
        
        assertNotNull(emptyScores);
        assertTrue(emptyScores.isEmpty());
    }

    @Test
    public void testGetUserScoreValid() {
        List<Object> redisResult = Arrays.asList("user123", 10.0, 15.5, 5, "123456789");
        when(mockCommands.evalsha(anyString(), eq(ScriptOutputType.MULTI), 
                                 any(String[].class), eq("user123")))
            .thenReturn(redisResult);
        
        LeaderboardScore score = leaderboard.getUserScore("user123");
        
        assertEquals("user123", score.userId());
        assertEquals(10.0, score.rawScore());
        assertEquals(15.5, score.compositeScore());
        assertEquals(5, score.rank());
        assertEquals(123456789L, score.timestamp());
    }

    @Test
    public void testGetUserScoreNullOrEmptyUserId() {
        assertThrows(IllegalArgumentException.class, () -> leaderboard.getUserScore(null));
        assertThrows(IllegalArgumentException.class, () -> leaderboard.getUserScore(""));
        assertThrows(IllegalArgumentException.class, () -> leaderboard.getUserScore("   "));
    }

    @Test
    public void testStaticLoadIdempotence() throws Exception {
        // Reset static fields via reflection to simulate fresh state
        Field loadedField = Leaderboard.class.getDeclaredField("loaded");
        loadedField.setAccessible(true);
        loadedField.set(null, false);
        
        Field winCountField = Leaderboard.class.getDeclaredField("WIN_COUNT");
        winCountField.setAccessible(true);
        winCountField.set(null, null);
        
        Field matchCountField = Leaderboard.class.getDeclaredField("MATCH_COUNT");
        matchCountField.setAccessible(true);
        matchCountField.set(null, null);
        
        // Call load first time
        Leaderboard.load();
        Leaderboard firstWinCount = Leaderboard.winCount();
        Leaderboard firstMatchCount = Leaderboard.matchCount();
        
        assertNotNull(firstWinCount);
        assertNotNull(firstMatchCount);
        
        // Call load second time (idempotent)
        Leaderboard.load();
        Leaderboard secondWinCount = Leaderboard.winCount();
        Leaderboard secondMatchCount = Leaderboard.matchCount();
        
        // Ensure same instances returned
        assertSame(firstWinCount, secondWinCount);
        assertSame(firstMatchCount, secondMatchCount);
    }

    @Test
    public void testStaticWinAndMatchCountReturn() {
        assertNotNull(Leaderboard.winCount());
        assertNotNull(Leaderboard.matchCount());
        assertSame(Leaderboard.winCount(), Leaderboard.get(null, null));
    }

    @Test
    public void testGetMethodReturnsWinCount() {
        JsonObject dummyCmd = mock(JsonObject.class);
        ChannelHandlerContext dummyCtx = mock(ChannelHandlerContext.class);
        Leaderboard result = Leaderboard.get(dummyCmd, dummyCtx);
        
        assertNotNull(result);
        assertSame(Leaderboard.winCount(), result);
    }
}