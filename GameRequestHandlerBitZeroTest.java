import bitzero.server.entities.User;
import bitzero.server.extensions.data.DataCmd;
import bitzero.test.IntegrationTestBase;
import bitzero.test.TestUser;
import modules.battle.game.setting.GameRequestHandler;
import modules.battle.table.model.BaseTable;
import modules.battle.table.setting.TableServiceImpl;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;

public class GameRequestHandlerBitZeroTest extends IntegrationTestBase {
    
    private GameRequestHandler handler;
    
    @BeforeAll
    public static void setUpBeforeClass() throws Exception {
        startServer();
    }
    
    @AfterAll
    public static void tearDownAfterClass() {
        stopServer();
    }
    
    @BeforeEach
    public void setUp() {
        handler = new GameRequestHandler();
        clearUsers();
        resetTestUsers();
    }
    
    @Test
    public void testHandleClientRequest_WithValidUser() {
        // Create test users
        TestUser user = TestUser.createLoggedIn("player1").withId(1);
        
        // Create a command with a valid ID to test that the handler doesn't crash
        DataCmd cmd = createSimpleCmd();
        
        // Execute handler - should not throw any exception
        assertDoesNotThrow(() -> {
            handler.handleClientRequest(user, cmd);
        });
    }
    
    @Test
    public void testHandleClientRequest_WithInvalidCommand() {
        // Create test users
        TestUser user = TestUser.createLoggedIn("player1").withId(1);
        
        // Create an unknown command to test graceful handling
        DataCmd cmd = createUnknownCmd();
        
        // Execute handler - should not throw any exception
        assertDoesNotThrow(() -> {
            handler.handleClientRequest(user, cmd);
        });
    }
    
    /**
     * Test that the handler properly handles the case when no table is found for a user
     */
    @Test
    public void testHandleClientRequest_NoTableFound() {
        // Create test users
        TestUser user = TestUser.createLoggedIn("player1").withId(1);
        
        // Create a simple command
        DataCmd cmd = createSimpleCmd();
        
        // Execute handler - should not throw any exception even when no table found
        assertDoesNotThrow(() -> {
            handler.handleClientRequest(user, cmd);
        });
    }
    
    /**
     * Helper method to create a simple command for testing
     */
    private DataCmd createSimpleCmd() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeShort(0); // Empty payload
            dos.flush();
            return new DataCmd(baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create simple command", e);
        }
    }
    
    /**
     * Helper method to create an unknown command for testing
     */
    private DataCmd createUnknownCmd() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeShort(9999); // Unknown command ID
            dos.flush();
            return new DataCmd(baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create unknown command", e);
        }
    }
}