// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.iso.areas.isoregion.metagrid;

import gnu.trove.list.array.TShortArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.function.Consumer;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.math.PZMath;
import zombie.debug.DebugType;
import zombie.iso.BuildingDef;
import zombie.iso.BuildingID;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridOcclusionData;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMetaCell;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoObject;
import zombie.iso.IsoRoomLight;
import zombie.iso.IsoWorld;
import zombie.iso.LightingJNI;
import zombie.iso.RoomDef;
import zombie.iso.RoomID;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.areas.IsoBuilding;
import zombie.iso.areas.IsoRoom;
import zombie.iso.areas.isoregion.IsoRegions;
import zombie.iso.areas.isoregion.data.DataChunk;
import zombie.iso.areas.isoregion.data.DataRoot;
import zombie.iso.areas.isoregion.regions.IsoChunkRegion;
import zombie.iso.areas.isoregion.regions.IsoWorldRegion;
import zombie.iso.fboRenderChunk.FBORenderCutaways;
import zombie.iso.objects.IsoGenerator;
import zombie.iso.objects.IsoLightSwitch;
import zombie.network.GameServer;
import zombie.pathfind.PolygonalMap2;

public final class WorldRegionToMetaGrid {
    private static final IsoDirections[] DIRECTIONS = IsoDirections.values();
    private final ArrayList<BuildingDef> tempBuildingList = new ArrayList<>();
    private final ArrayList<RoomDef> tempRooms = new ArrayList<>();
    private final ArrayList<IsoWorldRegion> tempRegionList = new ArrayList<>();
    private final ArrayList<IsoWorldRegion> worldRegions = new ArrayList<>();
    // Every insertion is gated by the reconciliation's done set. Preserve insertion order with constant-time membership/removal.
    private final LinkedHashSet<IsoWorldRegion> allWorldRegions = new LinkedHashSet<>();
    private final HashSet<IsoWorldRegion> done = new HashSet<>();
    private final HashSet<BuildingDef> added = new HashSet<>();
    private final HashMap<Integer, ArrayList<BuildingDef>> buildingsByLevel = new HashMap<>();
    private final HashMap<BuildingDef, ArrayList<IsoWorldRegion>> buildingToRegions = new HashMap<>();
    private final TShortArrayList freeSquares = new TShortArrayList();
    private final HashSet<Long> dirtyChunks = new HashSet<>();
    private final List<IsoChunk> updateChunks = new ArrayList<>();
    private static final IsoGameCharacter.Location tempLocation = new IsoGameCharacter.Location();

    public void clientProcessBuildings(ArrayList<IsoGameCharacter.Location> changedCells) {
        DataRoot root=IsoRegions.apocBuildingRoot();
        commitPrepared(root,ApocBRBuildingPlan.build(root,changedCells,0L,()->false));
    }

    private final java.util.LinkedHashSet<IsoMetaCell> apocReindex = new java.util.LinkedHashSet<>();
    private final ArrayList<BuildingDef> apocRetired = new ArrayList<>();
    private final HashMap<Long,List<IsoWorldRegion>> apocCellRegions = new HashMap<>();
    private boolean apocBulk;

    /** One owner-thread publication. Worker geometry is complete before the first live removal. */
    public void commitPrepared(DataRoot root,ApocBRBuildingPlan plan) {
        if(plan.source!=root) throw new IllegalArgumentException("Wrong building-plan root");
        if(plan.cells.isEmpty()) return;
        long commitStarted=zombie.ApocBRClientTelemetryLite.beginPhase();
        try {
            WorldRegionToMetaGrid prepared=new WorldRegionToMetaGrid();
            long materialize=zombie.ApocBRClientTelemetryLite.beginPhase();
            try { prepared.materialize(plan); }
            finally { zombie.ApocBRClientTelemetryLite.endPhase(zombie.ApocBRClientTelemetryLite.Phase.REGION_BUILD_MATERIALIZE,materialize); }
            prepared.filterAndCombine();
            this.dirtyChunks.clear();this.apocReindex.clear();this.apocRetired.clear();this.apocCellRegions.clear();
            for(ApocBRBuildingPlan.Cell cell:plan.cells) this.apocCellRegions.put(chunkKey(cell.x(),cell.y()),cell.regions());
            int removed=0,addedCount=0;
            this.apocBulk=true;
            long remove=zombie.ApocBRClientTelemetryLite.beginPhase();
            try { for(ApocBRBuildingPlan.Cell cell:plan.cells) removed+=this.removeUserDefinedBuildingsFromCell(cell.x(),cell.y()); }
            finally { zombie.ApocBRClientTelemetryLite.endPhase(zombie.ApocBRClientTelemetryLite.Phase.REGION_BUILD_REMOVE,remove); }
            long global=zombie.ApocBRClientTelemetryLite.beginPhase();
            try {
                // Retired definitions cannot stay in the global list after eventual pool reuse.
                HashSet<BuildingDef> retired=new HashSet<>(this.apocRetired);
                IsoWorld.instance.getMetaGrid().buildings.removeIf(retired::contains);
            } finally { zombie.ApocBRClientTelemetryLite.endPhase(zombie.ApocBRClientTelemetryLite.Phase.REGION_BUILD_GLOBAL_INDEX,global); }
            this.buildingsByLevel.clear();this.buildingsByLevel.putAll(prepared.buildingsByLevel);
            this.buildingToRegions.clear();this.buildingToRegions.putAll(prepared.buildingToRegions);
            this.allWorldRegions.clear();this.allWorldRegions.addAll(prepared.allWorldRegions);
            // Removal clears new-root bindings too; publish only the surviving prepared associations.
            for(var entry:this.buildingToRegions.entrySet()) for(IsoWorldRegion region:entry.getValue()) region.setBuildingDef(entry.getKey());
            long insert=zombie.ApocBRClientTelemetryLite.beginPhase();
            try {
                for(int level=0;level<31;level++) {
                    ArrayList<BuildingDef> buildings=this.buildingsByLevel.get(level);
                    if(buildings!=null) for(BuildingDef building:buildings) { this.addToMetaGrid(building);addedCount++; }
                }
            } finally { zombie.ApocBRClientTelemetryLite.endPhase(zombie.ApocBRClientTelemetryLite.Phase.REGION_BUILD_INSERT,insert); }
            this.apocBulk=false;
            long reindex=zombie.ApocBRClientTelemetryLite.beginPhase();
            try { for(IsoMetaCell cell:this.apocReindex) this.recalculateBuildingAndRoomIDs(cell); }
            finally { zombie.ApocBRClientTelemetryLite.endPhase(zombie.ApocBRClientTelemetryLite.Phase.REGION_BUILD_REINDEX,reindex); }
            if(removed!=0 || addedCount!=0) {
                for(IsoWorldRegion region:this.allWorldRegions) this.markRegionChunksDirty(region);
                LightingJNI.buildingsChanged();
                long squares=zombie.ApocBRClientTelemetryLite.beginPhase();
                try { this.updateSquares(); }
                finally { zombie.ApocBRClientTelemetryLite.endPhase(zombie.ApocBRClientTelemetryLite.Phase.REGION_BUILD_SQUARES,squares); }
                IsoGridOcclusionData.SquareChanged();FBORenderCutaways.getInstance().squareChanged(null);
            }
            long retire=zombie.ApocBRClientTelemetryLite.beginPhase();
            try {
                for(BuildingDef building:this.apocRetired) {
                    this.releaseRooms(building.getRooms());this.releaseRooms(building.getEmptyOutside());
                    building.resetMinMaxLevel();Pools.buildingDef.release(building);
                }
            } finally { zombie.ApocBRClientTelemetryLite.endPhase(zombie.ApocBRClientTelemetryLite.Phase.REGION_BUILD_RETIRE,retire); }
            zombie.ApocBRClientTelemetryLite.buildingCommit(plan.cells.size(),plan.regionCount,plan.rectangleCount,removed,addedCount,this.apocReindex.size(),this.dirtyChunks.size());
            // The graph owns its bindings now. Do not retain its region objects across the next swap.
            for(ArrayList<IsoWorldRegion> regions:this.buildingToRegions.values()) regions.clear();
            this.allWorldRegions.clear();
        } finally {
            this.apocBulk=false;this.apocCellRegions.clear();this.apocReindex.clear();this.apocRetired.clear();
            zombie.ApocBRClientTelemetryLite.endPhase(zombie.ApocBRClientTelemetryLite.Phase.REGION_BUILD_COMMIT,commitStarted);
        }
    }

    private void materialize(ApocBRBuildingPlan plan) {
        for(ApocBRBuildingPlan.Shape shape:plan.shapes) {
            BuildingDef building=Pools.buildingDef.alloc();building.setUserDefined(true);
            ArrayList<IsoWorldRegion> regions=new ArrayList<>();this.buildingToRegions.put(building,regions);
            for(ApocBRBuildingPlan.Room source:shape.rooms()) {
                RoomDef room=Pools.roomDef.alloc();room.id=0L;room.setName("");room.explored=true;room.userDefined=true;
                room.level=source.level();room.building=building;
                for(ApocBRBuildingPlan.Rect rect:source.rects()) room.rects.add(Pools.roomRect.alloc().set(rect.x(),rect.y(),rect.w(),rect.h()));
                room.x=source.x();room.y=source.y();room.x2=source.x2();room.y2=source.y2();room.area=source.area();
                building.rooms.add(room);regions.add(source.region());this.allWorldRegions.add(source.region());
                source.region().setBuildingDef(building);
            }
            building.x=shape.x();building.y=shape.y();building.x2=shape.x2();building.y2=shape.y2();
            if(building.overlappedChunks==null) building.overlappedChunks=new TShortArrayList();
            building.overlappedChunks.resetQuick();
            for(long chunk:shape.chunks()) { building.overlappedChunks.add((short)(chunk>>32));building.overlappedChunks.add((short)chunk); }
            this.buildingsByLevel.computeIfAbsent(building.getMinLevel(),level->new ArrayList<>()).add(building);
        }
    }

    private void filterAndCombine() {
            long apocFilter = zombie.ApocBRClientTelemetryLite.beginPhase();
            for (int z = 0; z < 31; z++) {
                ArrayList<BuildingDef> buildings = this.buildingsByLevel.get(z);
                if (buildings != null) {
                    for (int i = buildings.size() - 1; i >= 0; i--) {
                        BuildingDef buildingDef = buildings.get(i);
                        if (this.isAdjacentToOrOverlappingAPredefinedBuilding(buildingDef)) {
                            this.discardBuilding(buildingDef, true);
                            buildings.remove(i);
                        }
                    }
                }
            }

            zombie.ApocBRClientTelemetryLite.endPhase(zombie.ApocBRClientTelemetryLite.Phase.REGION_BUILD_FILTER, apocFilter);
            long apocCombine = zombie.ApocBRClientTelemetryLite.beginPhase();
            for (int zx = 0; zx < 31; zx++) {
                ArrayList<BuildingDef> buildings = this.buildingsByLevel.get(zx);
                if (buildings != null) {
                    for (int ix = 0; ix < buildings.size(); ix++) {
                        BuildingDef buildingDef = buildings.get(ix);
                        this.combineBuildingLevels(buildingDef, this.buildingsByLevel);
                    }
                }
            }

            zombie.ApocBRClientTelemetryLite.endPhase(zombie.ApocBRClientTelemetryLite.Phase.REGION_BUILD_COMBINE, apocCombine);

    }

    private void collectRegions(int x,int y) {
        List<IsoWorldRegion> cached=this.apocCellRegions.get(chunkKey(x,y));
        if(cached==null) IsoRegions.getIsoWorldRegionsInCell(x,y,this.worldRegions);
        else { this.worldRegions.clear();this.worldRegions.addAll(cached); }
    }


    private void createBuildingsFromRegions(
        int cellX, int cellY, HashSet<IsoWorldRegion> done, HashSet<BuildingDef> added, HashMap<Integer, ArrayList<BuildingDef>> buildingsByLevel
    ) {
        this.collectRegions(cellX,cellY);

        for (IsoWorldRegion worldRegion : this.worldRegions) {
            if (worldRegion.isEnclosed() && !(worldRegion.getRoofedPercentage() < 0.5F) && !done.contains(worldRegion)) {
                BuildingDef buildingDef = worldRegion.getBuildingDef();
                if (buildingDef != null) {
                    if (added.contains(buildingDef)) {
                        continue;
                    }

                    boolean level = true;
                }

                buildingDef = this.regionToBuildingDef(worldRegion, done, added);
                int level = buildingDef.getMinLevel();
                if (!buildingsByLevel.containsKey(level)) {
                    buildingsByLevel.put(level, new ArrayList<>());
                }

                buildingsByLevel.get(level).add(buildingDef);
                added.add(buildingDef);
            }
        }
    }

    private int removeUserDefinedBuildingsFromCell(int cellX, int cellY) {
        this.collectRegions(cellX,cellY);

        for (IsoWorldRegion worldRegion : this.worldRegions) {
            if (worldRegion.getBuildingDef() != null) {
                worldRegion.setBuildingDef(null);
                this.markRegionChunksDirty(worldRegion);
            }
        }

        IsoMetaGrid metaGrid = IsoWorld.instance.getMetaGrid();
        IsoMetaCell metaCell = metaGrid.getCellData(cellX, cellY);
        if (metaCell == null) {
            return 0;
        } else {
            int removed = 0;

            for (int i = metaCell.buildings.size() - 1; i >= 0; i--) {
                BuildingDef buildingDef = metaCell.buildings.get(i);
                if (buildingDef.isUserDefined()) {
                    this.markBuildingChunksDirty(buildingDef);
                    this.removeFromMetaGrid(buildingDef);
                    if(this.apocBulk) { this.apocRetired.add(buildingDef);this.buildingToRegions.remove(buildingDef); }
                    else this.discardBuilding(buildingDef, true);
                    removed++;
                }
            }

            return removed;
        }
    }

    private BuildingDef regionToBuildingDef(IsoWorldRegion worldRegion, HashSet<IsoWorldRegion> done, HashSet<BuildingDef> added) {
        BuildingDef buildingDef = Pools.buildingDef.alloc();
        buildingDef.setUserDefined(true);
        this.buildingToRegions.put(buildingDef, new ArrayList<>());
        this.tempRegionList.clear();
        this.getConnectedRegions(worldRegion, this.tempRegionList);

        for (IsoWorldRegion neighbour : this.tempRegionList) {
            if (!done.contains(neighbour)) {
                done.add(neighbour);
                if (neighbour.isEnclosed() && !(neighbour.getRoofedPercentage() < 0.5F)) {
                    if (neighbour.getBuildingDef() != null) {
                        if (added.contains(neighbour.getBuildingDef())) {
                            continue;
                        }

                        DebugType.General.error("neighbour already has a BuildingDef");
                    }

                    RoomDef roomDef = this.regionToRoomDef(neighbour);
                    roomDef.building = buildingDef;
                    roomDef.CalculateBounds();
                    buildingDef.getRooms().add(roomDef);
                    neighbour.setBuildingDef(buildingDef);
                    this.buildingToRegions.get(buildingDef).add(neighbour);
                    this.allWorldRegions.add(neighbour);
                }
            }
        }

        buildingDef.CalculateBounds(this.tempRooms);
        return buildingDef;
    }

    private RoomDef regionToRoomDef(IsoWorldRegion worldRegion) {
        RoomDef roomDef = Pools.roomDef.alloc();
        roomDef.id = 0L;
        roomDef.setName("");
        roomDef.explored = true;
        roomDef.userDefined = true;
        int z = Integer.MAX_VALUE;

        for (IsoChunkRegion chunkRegion : worldRegion.getDebugIsoChunkRegionCopy()) {
            if (z == Integer.MAX_VALUE) {
                z = chunkRegion.getzLayer();
            } else if (z != chunkRegion.getzLayer()) {
                boolean dataChunk = true;
            }

            DataChunk dataChunk = chunkRegion.getDataChunk();
            this.freeSquares.resetQuick();

            for (int i = 63; i >= 0; i--) {
                int lx = i % 8;
                int ly = i / 8;
                byte flags = dataChunk.getSquare(lx, ly, z);
                if (flags > 0 && dataChunk.getIsoChunkRegion(lx, ly, z) == chunkRegion) {
                    this.freeSquares.add((short)i);
                }
            }

            int chunkX = dataChunk.getChunkX() * 8;
            int chunkY = dataChunk.getChunkY() * 8;

            while (!this.freeSquares.isEmpty()) {
                int index = this.freeSquares.get(this.freeSquares.size() - 1);
                int lx = index % 8;
                int ly = index / 8;
                RoomDef.RoomRect rect = this.addRoomRect(roomDef, chunkRegion, lx, ly, z);

                for (int y = rect.getY(); y < rect.getY2(); y++) {
                    for (int x = rect.getX(); x < rect.getX2(); x++) {
                        index = x - chunkX + (y - chunkY) * 8;
                        this.freeSquares.remove((short)index);
                    }
                }
            }
        }

        roomDef.level = z;
        return roomDef;
    }

    private int getSpan(DataChunk dataChunk, IsoChunkRegion chunkRegion, int lx, int ly, int z) {
        int span;
        for (span = 0; lx < 8; span++) {
            byte flags = dataChunk.getSquare(lx, ly, z);
            int index = lx + ly * 8;
            if (flags <= 0 || !this.freeSquares.contains((short)index)) {
                break;
            }

            lx++;
        }

        return span;
    }

    private RoomDef.RoomRect addRoomRect(RoomDef roomDef, IsoChunkRegion chunkRegion, int lx, int ly, int z) {
        DataChunk dataChunk = chunkRegion.getDataChunk();
        int y = ly;
        int h = 1;
        int w = this.getSpan(dataChunk, chunkRegion, lx, ly, z);
        ly++;

        while (ly < 8 && this.getSpan(dataChunk, chunkRegion, lx, ly, z) >= w) {
            ly++;
            h++;
        }

        RoomDef.RoomRect rect = Pools.roomRect.alloc();
        rect.set(dataChunk.getChunkX() * 8 + lx, dataChunk.getChunkY() * 8 + y, w, h);
        roomDef.getRects().add(rect);
        return rect;
    }

    private void getConnectedRegions(IsoWorldRegion worldRegion, ArrayList<IsoWorldRegion> result) {
        HashSet<IsoWorldRegion> seen = new HashSet<>(result);
        if (!seen.add(worldRegion)) return;
        result.add(worldRegion);
        // Iterator stack preserves vanilla depth-first order without linear visited scans or Java-stack growth.
        ArrayDeque<Iterator<IsoWorldRegion>> stack = new ArrayDeque<>();
        stack.push(worldRegion.getNeighbors().iterator());
        while (!stack.isEmpty()) {
            Iterator<IsoWorldRegion> neighbours = stack.peek();
            if (!neighbours.hasNext()) { stack.pop(); continue; }
            IsoWorldRegion neighbour = neighbours.next();
            if (neighbour.isEnclosed() && neighbour.getRoofedPercentage() >= 0.5F && seen.add(neighbour)) {
                result.add(neighbour);
                stack.push(neighbour.getNeighbors().iterator());
            }
        }
    }

    private void addToMetaGrid(BuildingDef buildingDef) {
        IsoMetaGrid metaGrid = IsoWorld.instance.getMetaGrid();
        int cellX = buildingDef.getCellX();
        int cellY = buildingDef.getCellY();
        IsoMetaCell metaCell = metaGrid.getCellData(cellX, cellY);
        if (metaCell != null) {
            this.mergeRoomsOntoMetaCell(buildingDef, buildingDef.rooms, metaCell);
            this.mergeRoomsOntoMetaCell(buildingDef, buildingDef.emptyoutside, metaCell);
            buildingDef.metaId = buildingDef.calculateMetaID(cellX, cellY);
            if (metaCell.buildingByMetaId.containsKey(buildingDef.metaId)) {
                DebugType.General.error("duplicate building metaID");
            }

            metaCell.buildings.add(buildingDef);
            metaCell.buildingByMetaId.put(buildingDef.metaId, buildingDef);
            if (!metaGrid.buildings.contains(buildingDef)) {
                metaGrid.buildings.add(buildingDef);
            }

            this.recalculateBuildingAndRoomIDs(metaCell);
        }
    }

    private void removeFromMetaGrid(BuildingDef buildingDef) {
        IsoMetaGrid metaGrid = IsoWorld.instance.getMetaGrid();
        int cellX = buildingDef.getCellX();
        int cellY = buildingDef.getCellY();
        IsoMetaCell metaCell = metaGrid.getCellData(cellX, cellY);
        if (metaCell != null) {
            BuildingDef buildingDef1 = metaCell.buildingByMetaId.get(buildingDef.metaId);
            if (buildingDef1 != null && buildingDef1 != buildingDef) {
                buildingDef = buildingDef1;
            }

            metaCell.removeRooms(buildingDef.getRooms());
            metaCell.removeRooms(buildingDef.getEmptyOutside());
            metaGrid.removeRoomsFromAdjacentCells(buildingDef);
            this.removeBuildingFromMetaCell(buildingDef, metaCell);
            this.recalculateBuildingAndRoomIDs(metaCell);
        }
    }

    private void removeBuildingFromMetaCell(BuildingDef buildingDef, IsoMetaCell metaCell) {
        for (int i = 0; i < buildingDef.rooms.size(); i++) {
            RoomDef roomDef = buildingDef.rooms.get(i);
            this.removeRoomDef(metaCell, roomDef, false);
        }

        for (int i = 0; i < buildingDef.emptyoutside.size(); i++) {
            RoomDef roomDef = buildingDef.emptyoutside.get(i);
            this.removeRoomDef(metaCell, roomDef, false);
        }

        metaCell.buildings.remove(buildingDef);
        metaCell.buildingByMetaId.remove(buildingDef.metaId);
        metaCell.isoBuildings.remove(buildingDef.id);
    }

    private void removeRoomDef(IsoMetaCell metaCell, RoomDef roomDef, boolean bLoading) {
        metaCell.rooms.remove(roomDef.id);
        metaCell.roomList.remove(roomDef);
        metaCell.roomByMetaId.remove(roomDef.metaId);
        IsoRoom isoRoom = metaCell.isoRooms.remove(roomDef.id);
        if (isoRoom != null) {
            this.removeIsoRoom(isoRoom, bLoading);
        }
    }

    private void removeIsoRoom(IsoRoom isoRoom, boolean bLoading) {
        isoRoom.clear(bLoading);
    }

    private void recalculateBuildingAndRoomIDs(IsoMetaCell metaCell) {
        if(this.apocBulk) { this.apocReindex.add(metaCell);return; }
        int cellX = metaCell.getX();
        int cellY = metaCell.getY();
        metaCell.rooms.clear();

        for (int i = 0; i < metaCell.roomList.size(); i++) {
            RoomDef roomDef = metaCell.roomList.get(i);
            long nextID=RoomID.makeID(cellX,cellY,i);
            if(roomDef.id!=nextID) this.markRoomChunksDirty(roomDef);
            roomDef.id = nextID;
            metaCell.rooms.put(roomDef.id, roomDef);
        }

        for (int i = 0; i < metaCell.buildings.size(); i++) {
            BuildingDef buildingDef = metaCell.buildings.get(i);
            buildingDef.id = BuildingID.makeID(cellX, cellY, i);
            buildingDef.apocInvalidateRoofRoomIDs();
        }

        ArrayList<IsoBuilding> isoBuildings = new ArrayList<>(metaCell.isoBuildings.values());
        metaCell.isoBuildings.clear();

        for (IsoBuilding isoBuilding : isoBuildings) {
            metaCell.isoBuildings.put(isoBuilding.def.id, isoBuilding);
        }

        ArrayList<IsoRoom> isoRooms = new ArrayList<>(metaCell.isoRooms.values());
        metaCell.isoRooms.clear();

        for (IsoRoom isoRoom : isoRooms) {
            metaCell.isoRooms.put(isoRoom.def.id, isoRoom);
        }
    }

    private void mergeRoomsOntoMetaCell(BuildingDef buildingDef, ArrayList<RoomDef> rooms, IsoMetaCell metaCell) {
        if (!rooms.isEmpty()) {
            for (int roomIndex = 0; roomIndex < rooms.size(); roomIndex++) {
                RoomDef roomDef = rooms.get(roomIndex);
                if(!this.apocBulk) roomDef.id = RoomID.makeID(metaCell.getX(), metaCell.getY(), metaCell.roomList.size());
                roomDef.metaId = roomDef.calculateMetaID(metaCell.getX(), metaCell.getY());
                if (!this.apocBulk && metaCell.rooms.containsKey(roomDef.id)) {
                    DebugType.General.error("duplicate RoomDef.ID for room at %d,%d,%d", roomDef.x, roomDef.y, roomDef.level);
                }

                if(!this.apocBulk) metaCell.rooms.put(roomDef.id, roomDef);
                metaCell.roomList.add(roomDef);
                if (metaCell.roomByMetaId.contains(roomDef.metaId)) {
                    DebugType.General.error("duplicate RoomDef.metaID for room at %d,%d,%d", roomDef.x, roomDef.y, roomDef.level);
                }

                metaCell.roomByMetaId.put(roomDef.metaId, roomDef);
            }

            IsoMetaGrid metaGrid = IsoWorld.instance.getMetaGrid();
            metaCell.addRooms(rooms, buildingDef.getCellX() * 256, buildingDef.getCellY() * 256);
            metaGrid.addRoomsToAdjacentCells(buildingDef, rooms);
        }
    }

    private boolean isAdjacentToOrOverlappingAPredefinedBuilding(BuildingDef buildingDef) {
        IsoMetaGrid metaGrid = IsoWorld.instance.getMetaGrid();
        this.tempBuildingList.clear();
        metaGrid.getBuildingsIntersecting(buildingDef.getX() - 1, buildingDef.getY() - 1, buildingDef.getW() + 2, buildingDef.getH() + 2, this.tempBuildingList);

        for (BuildingDef buildingDef1 : this.tempBuildingList) {
            if (buildingDef != buildingDef1 && buildingDef.isBasement() == buildingDef1.isBasement() && !buildingDef1.isUserDefined()) {
                if (buildingDef.isAdjacent(buildingDef1, true)) {
                    return true;
                }

                if (buildingDef.overlaps(buildingDef1, true)) {
                    return true;
                }
            }
        }

        return false;
    }

    private void combineBuildingLevels(BuildingDef buildingDef, HashMap<Integer, ArrayList<BuildingDef>> buildingsByLevel) {
        for (int z = buildingDef.getMaxLevel() + 1; z < 32; z++) {
            ArrayList<BuildingDef> buildings = buildingsByLevel.get(z);
            if (buildings != null) {
                for (int i = buildings.size() - 1; i >= 0; i--) {
                    BuildingDef buildingDef1 = buildings.get(i);
                    if (buildingDef1 == buildingDef) {
                        throw new IllegalStateException("building is in two levels");
                    }

                    if (buildingDef.isAdjacent(buildingDef1, true) || buildingDef.overlaps(buildingDef1, true)) {
                        buildingDef.addRoomsOf(buildingDef1, this.tempRooms);
                        ArrayList<IsoWorldRegion> regions = this.buildingToRegions.get(buildingDef1);
                        this.assignBuildingToRegions(regions, buildingDef);
                        regions.clear();
                        this.discardBuilding(buildingDef1, false);
                        buildingDef.resetMinMaxLevel();
                        buildings.remove(i);
                    }
                }
            }
        }
    }

    private void discardBuilding(BuildingDef buildingDef, boolean bReleaseRooms) {
        ArrayList<IsoWorldRegion> regions = this.buildingToRegions.get(buildingDef);
        this.assignBuildingToRegions(regions, null);
        regions.clear();
        this.buildingToRegions.remove(buildingDef);
        if (bReleaseRooms) {
            this.releaseRooms(buildingDef.getRooms());
            this.releaseRooms(buildingDef.getEmptyOutside());
        } else {
            buildingDef.getRooms().clear();
            buildingDef.getEmptyOutside().clear();
        }

        buildingDef.resetMinMaxLevel();
        Pools.buildingDef.release(buildingDef);
    }

    private void releaseRooms(ArrayList<RoomDef> rooms) {
        for (int i = 0; i < rooms.size(); i++) {
            RoomDef roomDef = rooms.get(i);
            Pools.roomRect.releaseAll(roomDef.getRects());
            roomDef.getRects().clear();
            roomDef.building = null;
            Pools.roomDef.release(roomDef);
        }

        rooms.clear();
    }

    private void assignBuildingToRegions(ArrayList<IsoWorldRegion> regions, BuildingDef buildingDef) {
        for (int i = 0; i < regions.size(); i++) {
            IsoWorldRegion region = regions.get(i);
            region.setBuildingDef(buildingDef);
            if (buildingDef == null) {
                this.allWorldRegions.remove(region);
            } else {
                this.allWorldRegions.add(region);

                this.buildingToRegions.get(buildingDef).add(region);
            }
        }
    }

    private void updateSquares() {
        this.updateChunks.clear();

        for (int i = 0; i < IsoPlayer.numPlayers; i++) {
            IsoChunkMap chunkMap = IsoWorld.instance.currentCell.getChunkMap(i);
            if (chunkMap != null && !chunkMap.ignore) {
                for (int cy = 0; cy < IsoChunkMap.chunkGridWidth; cy++) {
                    for (int cx = 0; cx < IsoChunkMap.chunkGridWidth; cx++) {
                        IsoChunk chunk = chunkMap.getChunk(cx, cy);
                        if (chunk != null && this.chunkIsDirty(chunk)) {
                            this.updateChunks.add(chunk);
                        }
                    }
                }
            }
        }

        this.forEachSquare(square -> {
            IsoMetaGrid metaGrid = IsoWorld.instance.getMetaGrid();
            RoomDef roomDef = metaGrid.getRoomAt(square.x, square.y, square.z);
            long roomID = roomDef == null ? -1L : roomDef.id;
            square.setRoomID(roomID);
            roomDef = metaGrid.getEmptyOutsideAt(square.x, square.y, square.z);
            if (roomDef != null) {
                IsoRoom room = square.getChunk().getRoom(roomDef.id);
                square.roofHideBuilding = room == null ? null : room.building;
            } else square.roofHideBuilding=null;

            IsoRoom room = square.getRoom();
            if (roomID != -1L && room != null) {
                if (room.roomLights.isEmpty()) {
                    room.createLights(room.getRoomDef().lightsActive);
                    if (!GameServer.server) {
                        ArrayList<IsoRoomLight> roomLightsWorld = IsoWorld.instance.currentCell.roomLights;

                        for (int ixx = 0; ixx < room.roomLights.size(); ixx++) {
                            IsoRoomLight roomLight = room.roomLights.get(ixx);
                            if (!roomLightsWorld.contains(roomLight)) {
                                roomLightsWorld.add(roomLight);
                            }
                        }
                    }
                }

                for (int ix = 0; ix < square.getObjects().size(); ix++) {
                    IsoObject object = square.getObjects().get(ix);
                    if (object instanceof IsoGenerator generator && generator.isActivated() && !square.getProperties().has(IsoFlagType.exterior)) {
                        room.getBuilding().setToxic(true);
                    }

                    if (object instanceof IsoLightSwitch lightSwitch && !room.lightSwitches.contains(lightSwitch)) {
                        room.lightSwitches.add(lightSwitch);
                    }
                }
            }
        });
        this.forEachSquare(square -> {
            square.associatedBuilding = IsoWorld.instance.getMetaGrid().getAssociatedBuildingAt(square.x, square.y);
            square.RecalcProperties();
            PolygonalMap2.instance.squareChanged(square);
        });

        for (IsoChunk chunk : this.updateChunks) {
            chunk.invalidateRenderChunkLevels(2112L);
            chunk.getCutawayData().invalidateAll();
            chunk.checkLightingLater_AllPlayers_AllLevels();
        }
    }

    private void forEachSquare(Consumer<IsoGridSquare> consumer) {
        for (IsoChunk chunk : this.updateChunks) {
            for (int z = chunk.getMinLevel(); z <= chunk.getMaxLevel(); z++) {
                IsoGridSquare[] squares = chunk.getSquaresForLevel(z);

                for (int j = 0; j < squares.length; j++) {
                    IsoGridSquare square = squares[j];
                    if (square != null) {
                        consumer.accept(square);
                    }
                }
            }
        }
    }

    private void markRoomChunksDirty(RoomDef room) {
        for(RoomDef.RoomRect rect:room.rects) {
            for(int y=Math.floorDiv(rect.y,8);y<=Math.floorDiv(rect.y+rect.h-1,8);y++)
                for(int x=Math.floorDiv(rect.x,8);x<=Math.floorDiv(rect.x+rect.w-1,8);x++) this.markChunkDirty(x,y);
        }
    }

    private void markChunkDirty(int cx, int cy) {
        this.dirtyChunks.add(chunkKey(cx, cy));
    }

    private static long chunkKey(int x, int y) { return ((long)x << 32) | (y & 0xffffffffL); }

    private void markRegionChunksDirty(IsoWorldRegion region) {
        for (IsoChunkRegion chunk : region.getChunkRegions()) {
            DataChunk dataChunk = chunk.getDataChunk();
            this.markChunkDirty(dataChunk.getChunkX(), dataChunk.getChunkY());
        }
    }

    private void markBuildingChunksDirty(BuildingDef building) {
        if (building.overlappedChunks != null) {
            for (int i = 0; i < building.overlappedChunks.size(); i += 2) {
                this.markChunkDirty(building.overlappedChunks.get(i), building.overlappedChunks.get(i + 1));
            }
        }
    }

    private boolean chunkIsDirty(IsoChunk chunk) {
        int chunkX = PZMath.fastfloor(chunk.wx * 8 / 8.0F);
        int chunkY = PZMath.fastfloor(chunk.wy * 8 / 8.0F);
        return this.dirtyChunks.contains(chunkKey(chunkX, chunkY));
    }
}
