package modules.battle.game.setting;

import modules.battle.game.setting.GameRequestHandler;
import modules.battle.game.setting.BaseClientRequestHandler;
import modules.battle.game.setting.TableServiceImpl;
import modules.battle.game.setting.BaseTable;
import modules.battle.game.setting.Game;
import modules.battle.game.setting.User;
import modules.battle.game.setting.DataCmd;
import modules.battle.game.setting.CmdDefine;
import modules.battle.game.setting.ErrorDefine;
import modules.battle.game.setting.MsgService;
import modules.battle.game.setting.RequestPlayerPlayCard;
import modules.battle.game.setting.ResponseBroadCastWithUId;
import modules.battle.game.setting.TableMsgWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.MockedStatic;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
public class CompleteGameRequestHandlerTest {
    @Mock
    private TableServiceImpl tableServiceImpl;

    @Mock
    private BaseTable table;

    @Mock
    private Game game;

    @Mock
    private User user;

    @Mock
    private DataCmd cmd;

    private GameRequestHandler handler;

    @BeforeEach
    public void setUp() {
        handler = new GameRequestHandler();
        // Mock TableServiceImpl to return our mock table
        try (MockedStatic<TableServiceImpl> mockedStatic = mockStatic(TableServiceImpl.class)) {
            mockedStatic.when(TableServiceImpl::getInstance).thenReturn(tableServiceImpl);
            when(tableServiceImpl.getTableFromUser(anyLong())).thenReturn(Optional.of(table));
        }
    }

    @Test
    public void testProcessPlayerPlayCardHappyPath() {
        // Setup
        when(cmd.getId()).thenReturn(CmdDefine.PLAYER_PLAY_CARD);
        RequestPlayerPlayCard req = mock(RequestPlayerPlayCard.class);
        when(req.listCard).thenReturn(java.util.Arrays.asList("card1", "card2"));
        try (MockedStatic<RequestPlayerPlayCard> mockedStatic = mockStatic(RequestPlayerPlayCard.class)) {
            mockedStatic.when(() -> RequestPlayerPlayCard.<RequestPlayerPlayCard>new(any())).thenReturn(req);
            doNothing().when(req).unpackData();
            when(table.tryPlayCard(user, req.listCard)).thenReturn(true);
        }

        // Execute
        handler.handleClientRequest(user, cmd);

        // Verify
        verify(table).tryPlayCard(user, req.listCard);
        verifyNoMoreInteractions(table, cmd, user);
    }

    @Test
    public void testProcessPlayerPlayCardEmptyCardList() {
        // Setup
        when(cmd.getId()).thenReturn(CmdDefine.PLAYER_PLAY_CARD);
        RequestPlayerPlayCard req = mock(RequestPlayerPlayCard.class);
        when(req.listCard).thenReturn(new java.util.ArrayList<>());
        try (MockedStatic<RequestPlayerPlayCard> mockedStatic = mockStatic(RequestPlayerPlayCard.class)) {
            mockedStatic.when(() -> RequestPlayerPlayCard.<RequestPlayerPlayCard>new(any())).thenReturn(req);
            doNothing().when(req).unpackData();
        }

        // Execute
        handler.handleClientRequest(user, cmd);

        // Verify
        verify(MsgService.class);
        verifyStatic(MsgService.class);
        MsgService.responseCommon(eq(user), eq(CmdDefine.PLAYER_PLAY_CARD), eq(ErrorDefine.PARAM_INVALID));
        verifyNoMoreInteractions(table, cmd, user);
    }

    @Test
    public void testProcessPlayerPlayCardFailure() {
        // Setup
        when(cmd.getId()).thenReturn(CmdDefine.PLAYER_PLAY_CARD);
        RequestPlayerPlayCard req = mock(RequestPlayerPlayCard.class);
        when(req.listCard).thenReturn(java.util.Arrays.asList("card1", "card2"));
        try (MockedStatic<RequestPlayerPlayCard> mockedStatic = mockStatic(RequestPlayerPlayCard.class)) {
            mockedStatic.when(() -> RequestPlayerPlayCard.<RequestPlayerPlayCard>new(any())).thenReturn(req);
            doNothing().when(req).unpackData();
            when(table.tryPlayCard(user, req.listCard)).thenReturn(false);
        }

        // Execute
        handler.handleClientRequest(user, cmd);

        // Verify
        verify(table).tryPlayCard(user, req.listCard);
        verify(MsgService.class);
        verifyStatic(MsgService.class);
        MsgService.responseCommon(eq(user), eq(CmdDefine.PLAYER_PLAY_CARD), eq(ErrorDefine.FAIL));
        verifyNoMoreInteractions(table, cmd, user);
    }

    @Test
    public void testProcessPressStartGameHappyPath() {
        // Setup
        when(cmd.getId()).thenReturn(CmdDefine.PRESS_START_GAME);
        when(table.tryStartGame(user)).thenReturn(true);

        // Execute
        handler.handleClientRequest(user, cmd);

        // Verify
        verify(table).tryStartGame(user);
        verifyNoMoreInteractions(table, cmd, user);
    }

    @Test
    public void testProcessPressStartGameFailure() {
        // Setup
        when(cmd.getId()).thenReturn(CmdDefine.PRESS_START_GAME);
        when(table.tryStartGame(user)).thenReturn(false);

        // Execute
        handler.handleClientRequest(user, cmd);

        // Verify
        verify(table).tryStartGame(user);
        verify(MsgService.class);
        verifyStatic(MsgService.class);
        MsgService.responseCommon(eq(user), eq(CmdDefine.PRESS_START_GAME), eq(ErrorDefine.FAIL));
        verifyNoMoreInteractions(table, cmd, user);
    }

    @Test
    public void testProcessEatBeginingEscobaHappyPath() {
        // Setup
        when(cmd.getId()).thenReturn(CmdDefine.EAT_BEGINING_ESCOBA);
        when(table.eatBeginingEscoba(user.getId())).thenReturn(ErrorDefine.SUCCESS);

        // Execute
        handler.handleClientRequest(user, cmd);

        // Verify
        verify(table).eatBeginingEscoba(user.getId());
        verify(table).broadcast(any(TableMsgWrapper.class));
        verifyNoMoreInteractions(table, cmd, user);
    }

    @Test
    public void testProcessEatBeginingEscobaFailure() {
        // Setup
        when(cmd.getId()).thenReturn(CmdDefine.EAT_BEGINING_ESCOBA);
        when(table.eatBeginingEscoba(user.getId())).thenReturn(ErrorDefine.FAIL);

        // Execute
        handler.handleClientRequest(user, cmd);

        // Verify
        verify(table).eatBeginingEscoba(user.getId());
        verify(MsgService.class);
        verifyStatic(MsgService.class);
        MsgService.responseCommon(eq(user), eq(CmdDefine.EAT_BEGINING_ESCOBA), eq(ErrorDefine.FAIL));
        verifyNoMoreInteractions(table, cmd, user);
    }

    @Test
    public void testProcessForceEndGameHappyPath() {
        // Setup
        when(cmd.getId()).thenReturn(CmdDefine.FORCE_END_GAME);
        when(table.getGame()).thenReturn(game);

        // Execute
        handler.handleClientRequest(user, cmd);

        // Verify
        verify(game).forceEnd(user.getId());
        verifyNoMoreInteractions(table, cmd, user, game);
    }
}