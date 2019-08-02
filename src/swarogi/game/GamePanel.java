package swarogi.game;

import swarogi.common.Configuration;
import swarogi.common.ContentManager;
import swarogi.common.WindowSize;
import swarogi.engine.MapLoader;
import swarogi.enums.Direction;
import swarogi.interfaces.WindowSizeProvider;
import swarogi.data.Database;
import swarogi.interfaces.*;
import swarogi.enums.ActionButton;
import swarogi.models.*;
import swarogi.playermodes.*;

import javax.swing.JPanel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

public class GamePanel extends JPanel implements PlayerModeChangeListener, WindowSizeProvider {

    private GameMap map;
    private Renderer renderer;
    private ArrayList<Player> players;
    private int currentPlayerId;
    private PlayerMode currentPlayerMode;
    private ArrayDeque<Action> actions;
    private ControlsProvider controlsProvider;
    private Font font;

    private void initializePlayer(Player player, String name, Color color, int team, Tile gordPosition) {
        player.setName(name);
        player.setColor(color.getRed(), color.getGreen(), color.getBlue());
        player.setTeam(team);

        player.setTribeLevel(Configuration.INITIAL_TRIBE_LEVEL);
        player.setFood(Configuration.INITIAL_FOOD);
        player.setWood(Configuration.INITIAL_WOOD);
        player.setArmyCapacity(Configuration.INITIAL_ARMY_LIMIT);

        Building building = new Building(Database.Gord, player);

        GameCamera camera = new GameCamera();
        Point center = map.getTile(0, 0).getCenter();
        Dimension windowSize = this.getSize();
        camera.x = - (windowSize.width - center.x) / 2;
        camera.y = - (windowSize.height - center.y) / 2;

        player.setCamera(camera);

        PlaceableData model = building.getPlaceableData();
        Point tileCenter = gordPosition.getCenter();
        String textureName = model.getTextureName();
        BufferedImage texture = ContentManager.getModel(textureName);

        if (texture != null) {
            int textureWidth = (int) (texture.getWidth() * model.getXScale());
            int textureHeight = (int) (texture.getHeight() * model.getYScale());
            int x = tileCenter.x + model.getXTexturePosition();
            int y = tileCenter.y + model.getYTexturePosition();
            camera.x = x - Configuration.WINDOW_WIDTH / 2;
            camera.y = y - Configuration.WINDOW_HEIGHT / 2;
        }

        // TODO: Analogiczne umieszczenie jest w CreateBuildingAction. Wyciągnąć to gdzieś.
        if (map.tryPlace(building, gordPosition)) {
            map.addDestructible(building);
            player.setGord(building);
            player.addBuilding(building);
            for (Tile tile : building.getAllTiles()) {
                for (Placeable decoration : tile.removeDecorations()) {
                    this.map.removePlaceable(decoration);
                }
            }
            building.setRemainingConstructionTime(0);
            building.restoreHealth(building.getMaxHealth());
        }

        Unit leader = new Unit(Database.Hero, player);
        Tile leaderPosition = map.getTileNeighbor(gordPosition, Direction.BOTTOM);
        if (leaderPosition != null) { leaderPosition = map.getTileNeighbor(leaderPosition, Direction.BOTTOM); }
        if (map.tryPlace(leader, leaderPosition)) {
            map.addDestructible(leader);
            player.setLeader(leader);
            player.addUnit(leader);
            player.increaseArmySize(leader.getUnitData().getRequiredArmySize());
        }
    }

    private void nextPlayer() {

        int n = players.size();
        if (currentPlayerId < n) {
            Player previousPlayer = players.get(currentPlayerId);
            previousPlayer.setControls(null);
            ++currentPlayerId;
        }
        if (currentPlayerId == n) {
            // TODO: Rozegrać turę dla neutralnych, jeśli będą
            currentPlayerId = 0;
        }

        Player currentPlayer = players.get(currentPlayerId);
        currentPlayer.updateResearch();
        currentPlayer.restoreCommandPoints();
        currentPlayer.updateUnits();     // Jednostka może umrzeć zanim wybuduje
        currentPlayer.getResources();    // Nowo wybudowany budynek nie dostarcza jeszcze surowców
        currentPlayer.updateBuildings();
        currentPlayer.setControls(controlsProvider);
        currentPlayerMode = new SelectionPlayerMode(currentPlayer, this, map);
    }

    public GamePanel(GameMap map, ControlsProvider controls) {
        this.map = map;
        this.controlsProvider = controls;

        this.actions = new ArrayDeque<>();
        this.renderer = new Renderer();

        this.font = new Font("TimesRoman", Font.PLAIN, 14);

        this.players = new ArrayList<>();

        List<Tile> playersPosition = map.getPlayerPositions();
        int n = Math.min(playersPosition.size(), Configuration.MAX_PLAYERS);

        for (int i = 0; i < n; ++i) {
            Player player = new Player();
            initializePlayer(player, "Gracz " + Integer.toString(i + 1), Configuration.PLAYER_COLORS.get(i), i, playersPosition.get(i));
            this.players.add(player);
        }

        currentPlayerId = players.size();

        WindowSize.setWindowSizeProvider(this);

        nextPlayer();
    }

    public void update(long time) {

        //Point mousePosition = controlsProvider.getPointerPosition();
        // TODO: Zbadać zdarzenia z interfejsem?

        Player currentPlayer = players.get(currentPlayerId);

        if (!currentPlayerMode.isLockingCamera()) {
            currentPlayer.updateCamera();
        }

        updateModeSelection();

        currentPlayerMode.update();

        if (currentPlayer.getControls().isButtonDown(ActionButton.MENU_9)) {
            Configuration.mapBuildingXSymmetry = !Configuration.mapBuildingXSymmetry;
        }
        if (currentPlayer.getControls().isButtonDown(ActionButton.MENU_10)) {
            Configuration.mapBuildingYSymmetry = !Configuration.mapBuildingYSymmetry;
        }
        if (currentPlayer.getControls().isButtonDown(ActionButton.MENU_11)) {
            Configuration.mapBuildingDiagSymmetry = !Configuration.mapBuildingDiagSymmetry;
        }
        if (currentPlayer.getControls().isButtonDown(ActionButton.MENU_12)) {
            MapLoader.saveMap(map, "exported.txt");
        }

        if (currentPlayer.getControls().isButtonDown(ActionButton.HIDE_HP_BARS)) {
            Configuration.areHpBarsVisible = !Configuration.areHpBarsVisible;
        }

        if (!currentPlayerMode.isPausingGameplay()) {
            // Zaktualizuj akcje
            while (!actions.isEmpty()) {
                Action currentAction = actions.peek();
                if (!currentAction.hasStarted()) {
                    if (currentAction.canBeExecuted()) {
                        currentAction.start();
                    }
                    else {
                        currentAction.abort();
                        actions.poll();
                        break;
                    }
                }
                if (currentAction.isCompleted()) {
                    currentAction.finish();
                    actions.poll();
                }
                else {
                    currentAction.update();
                    break;
                }
            }
        }

        if (controlsProvider.isButtonDown(ActionButton.END_TURN)) {
            nextPlayer();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        setBackground(new Color(32,32,32));

        GameCamera camera = players.get(currentPlayerId).getCamera();
        renderer.startRendering(g, camera);

        int cameraX = camera.x;
        int cameraY = camera.y;

        int tileWidth = Configuration.TILE_WIDTH;
        int tileHeight = Configuration.TILE_HEIGHT;

        int mapTilesX = map.getTilesX();
        int mapTilesY = map.getTilesY();

        if (map != null) {
            for (int i = 0; i < mapTilesX; ++i) {
                for (int j = 0; j < mapTilesY; ++j) {
                    Tile tile = map.getTile(i, j);
                    if (tile != null) {
                        Point pos = tile.getTopLeft();
                        g.drawImage(ContentManager.getTerrain(tile.getTerrainType()),
                                pos.x - cameraX, pos.y - cameraY,
                                tileWidth, tileHeight, null);
                    }
                }
            }
        }

        currentPlayerMode.renderSelection(g, camera);

        // TODO: Mało estetyczne rozwiązanie, ale pozwala rozdzielić logikę od interfejsu.
        for (Placeable placeable : map.getPlaceables()) {
            if (placeable instanceof Obstacle) {
                renderer.render((Obstacle)placeable);
            }
            else if (placeable instanceof Unit) {
                renderer.render((Unit)placeable);
            }
            else if (placeable instanceof Building) {
                renderer.render((Building) placeable);
            }
            else if (placeable instanceof Decoration) {
                renderer.render((Decoration)placeable);
            }
        }

        currentPlayerMode.renderGui(g, getSize(), font);

        drawBorder(g);

        renderer.endRendering();
    }


    // "Brutalne" przełączenie tryby gry pomiędzy opcjami debugowymi.
    private void updateModeSelection() {
        Player player = players.get(currentPlayerId);
        ControlsProvider controlsProvider = player.getControls();

        if (controlsProvider.isButtonDown(ActionButton.MENU_1)) {
            System.out.println("Wybrano tryb rozgrywki");
            currentPlayerMode = new SelectionPlayerMode(player, this, map);
        }
        else if (controlsProvider.isButtonDown(ActionButton.MENU_2)) {
            System.out.println("Wybrano tryb umieszczania jednostek");
            currentPlayerMode = new DebugUnitsPlacingPlayerMode(player, this, map);
        }
        else if (controlsProvider.isButtonDown(ActionButton.MENU_3)) {
            System.out.println("Wybrano tryb umieszczania budynków");
            currentPlayerMode = new DebugBuildingsPlacingPlayerMode(player, this, map);
        }
        else if (controlsProvider.isButtonDown(ActionButton.MENU_4)) {
            System.out.println("Wybrano tryb umieszczania innych obiektów");
            currentPlayerMode = new DebugOthersPlacingPlayerMode(player, this, map);
        }
        else if (controlsProvider.isButtonDown(ActionButton.MENU_5)) {
            System.out.println("Wybrano tryb edycji terenu");
            currentPlayerMode = new DebugChangeTerrainPlayerMode(player, this, map);
        }
    }

    private void drawBorder(Graphics g) {
        Dimension size = this.getSize();

        g.setColor(Color.black);
        g.fillRect(0, 0, size.width, 51);

        int topFromX = ContentManager.borderTopLeft.getWidth();
        int topToX = size.width - ContentManager.borderTopRight.getWidth();

        int bottomFromX = ContentManager.borderBottomLeft.getWidth();
        int bottomToX = size.width - ContentManager.borderBottomRight.getWidth();

        int leftFromY = ContentManager.borderTopLeft.getHeight();
        int leftToY = size.height - ContentManager.borderBottomLeft.getHeight();

        int rightFromY = ContentManager.borderTopLeft.getHeight();
        int rightToY = size.height - ContentManager.borderBottomRight.getHeight();

        int incr = ContentManager.borderTop.getWidth();
        for (int x = topFromX; x < topToX; x += incr) {
            g.drawImage(ContentManager.borderTop, x, 0, null);
        }

        incr = ContentManager.borderBottom.getWidth();
        int temp = size.height - ContentManager.borderBottom.getHeight();
        for (int x = bottomFromX; x < bottomToX; x += incr) {
            g.drawImage(ContentManager.borderBottom, x, temp, null);
        }

        incr = ContentManager.borderLeft.getHeight();
        for (int y = leftFromY; y < leftToY; y += incr) {
            g.drawImage(ContentManager.borderLeft, 0, y, null);
        }

        incr = ContentManager.borderRight.getHeight();
        temp = size.width - ContentManager.borderRight.getWidth();
        for (int y = rightFromY; y < rightToY; y += incr) {
            g.drawImage(ContentManager.borderRight, temp, y, null);
        }

        g.drawImage(ContentManager.borderTopLeft, 0, 0, null);
        g.drawImage(ContentManager.borderBottomLeft, 0, leftToY, null);
        g.drawImage(ContentManager.borderTopRight, topToX, 0, null);
        g.drawImage(ContentManager.borderBottomRight, bottomToX, rightToY, null);

        g.setFont(font);

        int x6 = size.width / 6;
        int leftPadding = 20;
        int topPadding = 30;

        Player currentPlayer = players.get(currentPlayerId);
        g.setColor(currentPlayer.getColor());
        g.drawString(currentPlayer.getName(), leftPadding, topPadding);
        g.setColor(Color.white);
        g.drawString("Poziom: " + Integer.toString(currentPlayer.getTribeLevel()), leftPadding + x6, topPadding);
        g.drawString("Żywność: " + Integer.toString(currentPlayer.getFood()), leftPadding + 2 * x6, topPadding);
        g.drawString("Drewno: " + Integer.toString(currentPlayer.getWood()), leftPadding + 3 * x6, topPadding);
        g.drawString("Rozmiar armii: " + Integer.toString(currentPlayer.getArmySize()) + "/"
                + Integer.toString(currentPlayer.getArmyCapacity()), leftPadding + 4 * x6, topPadding);
        g.drawString("Punkty akcji: " + Integer.toString(currentPlayer.getCommandPoints()) + "/" +
                "" + Integer.toString(currentPlayer.getMaxCommandPoints()), leftPadding + 5 * x6, topPadding);

    }

    @Override
    public void onPlayerModeChanged(PlayerMode playerMode) {
        this.currentPlayerMode = playerMode;
    }

    @Override
    public void addAction(Action action) {
        this.actions.add(action);
    }
}