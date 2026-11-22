// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.network.packets;

import zombie.Lua.LuaEventManager;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.Role;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.JSONField;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;
import zombie.network.fields.character.OnlinePlayer;
import zombie.network.server.EventManager;

@PacketSetting(ordering = 0, priority = 1, reliability = 2, requiredCapability = Capability.LoginOnServer, handlingType = 3)
public class ExtraInfoPacket implements INetworkPacket {
    @JSONField
    boolean isForced;
    @JSONField
    private final OnlinePlayer player = new OnlinePlayer();
    @JSONField
    Role role;
    @JSONField
    boolean showAdminTag;
    @JSONField
    boolean zombiesDontAttack;
    @JSONField
    boolean godMod;
    @JSONField
    boolean invisible;
    @JSONField
    boolean unlimitedEndurance;
    @JSONField
    boolean unlimitedAmmo;
    @JSONField
    boolean knowAllRecipes;
    @JSONField
    boolean unlimitedCarry;
    @JSONField
    boolean buildCheat;
    @JSONField
    boolean farmingCheat;
    @JSONField
    boolean fishingCheat;
    @JSONField
    boolean healthCheat;
    @JSONField
    boolean mechanicsCheat;
    @JSONField
    boolean movablesCheat;
    @JSONField
    boolean fastMoveCheat;
    @JSONField
    boolean timedActionInstantCheat;
    @JSONField
    boolean noClip;
    @JSONField
    boolean canSeeAll;
    @JSONField
    boolean canHearAll;
    @JSONField
    boolean canUseBrushTool;
    @JSONField
    boolean canUseLootZed;
    @JSONField
    boolean canUseLootLog;
    @JSONField
    boolean animalCheat;
    @JSONField
    boolean animalExtraValues;
    @JSONField
    boolean canUseDebugContextMenu;
    @JSONField
    boolean isAlwaysDayCheat;

    @Override
    public void setData(Object... values) {
        IsoPlayer isoPlayer = (IsoPlayer)values[0];
        if (values.length > 1) {
            this.isForced = (Boolean)values[1];
        }

        this.player.set(isoPlayer);
        this.role = isoPlayer.getRole();
        this.showAdminTag = isoPlayer.isShowAdminTag();
        this.zombiesDontAttack = isoPlayer.isZombiesDontAttack();
        this.godMod = isoPlayer.isGodMod();
        this.invisible = isoPlayer.isInvisible();
        this.unlimitedEndurance = isoPlayer.isUnlimitedEndurance();
        this.unlimitedAmmo = isoPlayer.isUnlimitedAmmo();
        this.knowAllRecipes = isoPlayer.isKnowAllRecipes();
        this.unlimitedCarry = isoPlayer.isUnlimitedCarry();
        this.buildCheat = isoPlayer.isBuildCheat();
        this.farmingCheat = isoPlayer.isFarmingCheat();
        this.fishingCheat = isoPlayer.isFishingCheat();
        this.healthCheat = isoPlayer.isHealthCheat();
        this.mechanicsCheat = isoPlayer.isMechanicsCheat();
        this.movablesCheat = isoPlayer.isMovablesCheat();
        this.fastMoveCheat = isoPlayer.isFastMoveCheat();
        this.timedActionInstantCheat = isoPlayer.isTimedActionInstantCheat();
        this.noClip = isoPlayer.isNoClip();
        this.canSeeAll = isoPlayer.canSeeAll();
        this.canHearAll = isoPlayer.canHearAll();
        this.canUseBrushTool = isoPlayer.isCanUseBrushTool();
        this.canUseLootZed = isoPlayer.canUseLootZed();
        this.canUseLootLog = isoPlayer.canUseLootLog();
        this.animalCheat = isoPlayer.isAnimalCheat();
        this.animalExtraValues = isoPlayer.isAnimalExtraValuesCheat();
        this.isAlwaysDayCheat = isoPlayer.isAlwaysDayCheat();
        this.canUseDebugContextMenu = isoPlayer.canUseDebugContextMenu();
    }

    @Override
    public void write(ByteBufferWriter b) {
        this.player.write(b);
        this.role.send(b);
        b.putBoolean(this.isForced);
        b.putBoolean(this.showAdminTag);
        b.putBoolean(this.zombiesDontAttack);
        b.putBoolean(this.godMod);
        b.putBoolean(this.invisible);
        b.putBoolean(this.unlimitedEndurance);
        b.putBoolean(this.unlimitedAmmo);
        b.putBoolean(this.knowAllRecipes);
        b.putBoolean(this.unlimitedCarry);
        b.putBoolean(this.buildCheat);
        b.putBoolean(this.farmingCheat);
        b.putBoolean(this.fishingCheat);
        b.putBoolean(this.healthCheat);
        b.putBoolean(this.mechanicsCheat);
        b.putBoolean(this.movablesCheat);
        b.putBoolean(this.fastMoveCheat);
        b.putBoolean(this.timedActionInstantCheat);
        b.putBoolean(this.noClip);
        b.putBoolean(this.canSeeAll);
        b.putBoolean(this.canHearAll);
        b.putBoolean(this.canUseBrushTool);
        b.putBoolean(this.canUseLootZed);
        b.putBoolean(this.canUseLootLog);
        b.putBoolean(this.animalCheat);
        b.putBoolean(this.animalExtraValues);
        b.putBoolean(this.isAlwaysDayCheat);
        b.putBoolean(this.canUseDebugContextMenu);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        this.player.parse(b, connection);
        this.role = new Role("");
        this.role.parse(b);
        this.isForced = b.getBoolean();
        this.showAdminTag = b.getBoolean();
        this.zombiesDontAttack = b.getBoolean();
        this.godMod = b.getBoolean();
        this.invisible = b.getBoolean();
        this.unlimitedEndurance = b.getBoolean();
        this.unlimitedAmmo = b.getBoolean();
        this.knowAllRecipes = b.getBoolean();
        this.unlimitedCarry = b.getBoolean();
        this.buildCheat = b.getBoolean();
        this.farmingCheat = b.getBoolean();
        this.fishingCheat = b.getBoolean();
        this.healthCheat = b.getBoolean();
        this.mechanicsCheat = b.getBoolean();
        this.movablesCheat = b.getBoolean();
        this.fastMoveCheat = b.getBoolean();
        this.timedActionInstantCheat = b.getBoolean();
        this.noClip = b.getBoolean();
        this.canSeeAll = b.getBoolean();
        this.canHearAll = b.getBoolean();
        this.canUseBrushTool = b.getBoolean();
        this.canUseLootZed = b.getBoolean();
        this.canUseLootLog = b.getBoolean();
        this.animalCheat = b.getBoolean();
        this.animalExtraValues = b.getBoolean();
        this.isAlwaysDayCheat = b.getBoolean();
        this.canUseDebugContextMenu = b.getBoolean();
    }

    @Override
    public void processClient(UdpConnection connection) {
        IsoPlayer isoPlayer = this.player.getPlayer();
        if (!isoPlayer.remote) {
            GameClient.connection.setRole(this.role);
        }

        isoPlayer.setRole(this.role);
        isoPlayer.setShowAdminTag(this.showAdminTag);
        isoPlayer.setZombiesDontAttack(this.zombiesDontAttack);
        isoPlayer.setGodMod(this.godMod, this.isForced);
        isoPlayer.setInvisible(this.invisible, this.isForced);
        isoPlayer.setUnlimitedEndurance(this.unlimitedEndurance);
        isoPlayer.setUnlimitedAmmo(this.unlimitedAmmo);
        isoPlayer.setKnowAllRecipes(this.knowAllRecipes);
        isoPlayer.setUnlimitedCarry(this.unlimitedCarry);
        isoPlayer.setBuildCheat(this.buildCheat);
        isoPlayer.setFarmingCheat(this.farmingCheat);
        isoPlayer.setFishingCheat(this.fishingCheat);
        isoPlayer.setHealthCheat(this.healthCheat);
        isoPlayer.setMechanicsCheat(this.mechanicsCheat);
        isoPlayer.setMovablesCheat(this.movablesCheat);
        isoPlayer.setFastMoveCheat(this.fastMoveCheat);
        isoPlayer.setTimedActionInstantCheat(this.timedActionInstantCheat);
        isoPlayer.setNoClip(this.noClip, this.isForced);
        isoPlayer.setCanSeeAll(this.canSeeAll);
        isoPlayer.setCanHearAll(this.canHearAll);
        isoPlayer.setCanUseBrushTool(this.canUseBrushTool);
        isoPlayer.setCanUseLootZed(this.canUseLootZed);
        isoPlayer.setCanUseLootLog(this.canUseLootLog);
        isoPlayer.setAnimalCheat(this.animalCheat);
        isoPlayer.setAnimalExtraValuesCheat(this.animalExtraValues);
        isoPlayer.setAlwaysDayCheat(this.isAlwaysDayCheat);
        isoPlayer.setCanUseDebugContextMenu(this.canUseDebugContextMenu);
        LuaEventManager.triggerEvent("RefreshCheats");
    }

    @Override
    public void processServer(PacketTypes.PacketType packetType, UdpConnection connection) {
        IsoPlayer isoPlayer = this.player.getPlayer();
        boolean isOwnPlayer = connection.hasPlayer(isoPlayer);
        if (!connection.getRole().hasCapability(Capability.ToggleWriteRoleNameAbove) && this.showAdminTag) {
            PacketTypes.PacketAuthorization.onUnauthorized(connection, PacketTypes.PacketType.ExtraInfo);
        } else {
            isoPlayer.setShowAdminTag(this.showAdminTag);
            if (!connection.getRole().hasCapability(Capability.UseZombieDontAttackCheat) && this.zombiesDontAttack) {
                PacketTypes.PacketAuthorization.onUnauthorized(connection, PacketTypes.PacketType.ExtraInfo);
            } else {
                isoPlayer.setZombiesDontAttack(this.zombiesDontAttack);
                if (!connection.getRole().hasCapability(isOwnPlayer ? Capability.ToggleGodModHimself : Capability.ToggleGodModEveryone) && this.godMod) {
                    PacketTypes.PacketAuthorization.onUnauthorized(connection, PacketTypes.PacketType.ExtraInfo);
                } else {
                    if (this.godMod) {
                        EventManager.instance().report(String.format("[%s] player \"%s\" set god-mod", GameServer.serverName, isoPlayer.getUsername()));
                    }

                    isoPlayer.setGodMod(this.godMod);
                    if (!connection.getRole().hasCapability(isOwnPlayer ? Capability.ToggleInvisibleHimself : Capability.ToggleInvisibleEveryone)
                        && this.invisible) {
                        PacketTypes.PacketAuthorization.onUnauthorized(connection, PacketTypes.PacketType.ExtraInfo);
                    } else {
                        isoPlayer.setInvisible(this.invisible);
                        if (!connection.getRole().hasCapability(Capability.ToggleUnlimitedEndurance) && this.unlimitedEndurance) {
                            PacketTypes.PacketAuthorization.onUnauthorized(connection, PacketTypes.PacketType.ExtraInfo);
                        } else {
                            isoPlayer.setUnlimitedEndurance(this.unlimitedEndurance);
                            if (!connection.getRole().hasCapability(Capability.ToggleUnlimitedAmmo) && this.unlimitedAmmo) {
                                PacketTypes.PacketAuthorization.onUnauthorized(connection, PacketTypes.PacketType.ExtraInfo);
                            } else {
                                isoPlayer.setUnlimitedAmmo(this.unlimitedAmmo);
                                if (!connection.getRole().hasCapability(Capability.ToggleKnowAllRecipes) && this.knowAllRecipes) {
                                    PacketTypes.PacketAuthorization.onUnauthorized(connection, PacketTypes.PacketType.ExtraInfo);
                                } else {
                                    isoPlayer.setKnowAllRecipes(this.knowAllRecipes);
                                    if (!connection.getRole().hasCapability(Capability.ToggleUnlimitedCarry) && this.unlimitedCarry) {
                                        PacketTypes.PacketAuthorization.onUnauthorized(connection, PacketTypes.PacketType.ExtraInfo);
                                    } else {
                                        isoPlayer.setUnlimitedCarry(this.unlimitedCarry);
                                        if (!connection.getRole().hasCapability(Capability.UseMovablesCheat) && this.movablesCheat) {
                                            PacketTypes.PacketAuthorization.onUnauthorized(connection, PacketTypes.PacketType.ExtraInfo);
                                        } else {
                                            isoPlayer.setMovablesCheat(this.movablesCheat);
                                            if (!connection.getRole().hasCapability(Capability.UseFastMoveCheat) && this.fastMoveCheat) {
                                                PacketTypes.PacketAuthorization.onUnauthorized(connection, PacketTypes.PacketType.ExtraInfo);
                                            } else {
                                                isoPlayer.setFastMoveCheat(this.fastMoveCheat);
                                                if (!connection.getRole().hasCapability(Capability.UseBuildCheat) && this.buildCheat) {
                                                    PacketTypes.PacketAuthorization.onUnauthorized(connection, PacketTypes.PacketType.ExtraInfo);
                                                } else {
                                                    isoPlayer.setBuildCheat(this.buildCheat);
                                                    if (!connection.getRole().hasCapability(Capability.UseFarmingCheat) && this.farmingCheat) {
                                                        PacketTypes.PacketAuthorization.onUnauthorized(connection, PacketTypes.PacketType.ExtraInfo);
                                                    } else {
                                                        isoPlayer.setFarmingCheat(this.farmingCheat);
                                                        if (!connection.getRole().hasCapability(Capability.UseFishingCheat) && this.fishingCheat) {
                                                            PacketTypes.PacketAuthorization.onUnauthorized(connection, PacketTypes.PacketType.ExtraInfo);
                                                        } else {
                                                            isoPlayer.setFishingCheat(this.fishingCheat);
                                                            if (!connection.getRole().hasCapability(Capability.UseHealthCheat) && this.healthCheat) {
                                                                PacketTypes.PacketAuthorization.onUnauthorized(connection, PacketTypes.PacketType.ExtraInfo);
                                                            } else {
                                                                isoPlayer.setHealthCheat(this.healthCheat);
                                                                if (!connection.getRole().hasCapability(Capability.UseMechanicsCheat) && this.mechanicsCheat) {
                                                                    PacketTypes.PacketAuthorization.onUnauthorized(connection, PacketTypes.PacketType.ExtraInfo);
                                                                } else {
                                                                    isoPlayer.setMechanicsCheat(this.mechanicsCheat);
                                                                    if (!connection.getRole().hasCapability(Capability.UseTimedActionInstantCheat)
                                                                        && this.timedActionInstantCheat) {
                                                                        PacketTypes.PacketAuthorization.onUnauthorized(
                                                                            connection, PacketTypes.PacketType.ExtraInfo
                                                                        );
                                                                    } else {
                                                                        isoPlayer.setTimedActionInstantCheat(this.timedActionInstantCheat);
                                                                        if (!connection.getRole()
                                                                                .hasCapability(
                                                                                    isOwnPlayer
                                                                                        ? Capability.ToggleNoclipHimself
                                                                                        : Capability.ToggleNoclipEveryone
                                                                                )
                                                                            && this.noClip) {
                                                                            PacketTypes.PacketAuthorization.onUnauthorized(
                                                                                connection, PacketTypes.PacketType.ExtraInfo
                                                                            );
                                                                        } else {
                                                                            isoPlayer.setNoClip(this.noClip);
                                                                            if (!connection.getRole().hasCapability(Capability.CanSeeAll) && this.canSeeAll) {
                                                                                PacketTypes.PacketAuthorization.onUnauthorized(
                                                                                    connection, PacketTypes.PacketType.ExtraInfo
                                                                                );
                                                                            } else {
                                                                                isoPlayer.setCanSeeAll(this.canSeeAll);
                                                                                if (!connection.getRole().hasCapability(Capability.CanHearAll)
                                                                                    && this.canHearAll) {
                                                                                    PacketTypes.PacketAuthorization.onUnauthorized(
                                                                                        connection, PacketTypes.PacketType.ExtraInfo
                                                                                    );
                                                                                } else {
                                                                                    isoPlayer.setCanHearAll(this.canHearAll);
                                                                                    if (!connection.getRole().hasCapability(Capability.UseBrushToolManager)
                                                                                        && this.canUseBrushTool) {
                                                                                        PacketTypes.PacketAuthorization.onUnauthorized(
                                                                                            connection, PacketTypes.PacketType.ExtraInfo
                                                                                        );
                                                                                    } else {
                                                                                        isoPlayer.setCanUseBrushTool(this.canUseBrushTool);
                                                                                        if (!connection.getRole().hasCapability(Capability.UseLootZed)
                                                                                            && this.canUseLootZed) {
                                                                                            PacketTypes.PacketAuthorization.onUnauthorized(
                                                                                                connection, PacketTypes.PacketType.ExtraInfo
                                                                                            );
                                                                                        } else {
                                                                                            isoPlayer.setCanUseLootZed(this.canUseLootZed);
                                                                                            if (!connection.getRole().hasCapability(Capability.UseLootLog)
                                                                                                && this.canUseLootLog) {
                                                                                                PacketTypes.PacketAuthorization.onUnauthorized(
                                                                                                    connection, PacketTypes.PacketType.ExtraInfo
                                                                                                );
                                                                                            } else {
                                                                                                isoPlayer.setCanUseLootLog(this.canUseLootLog);
                                                                                                if (!connection.getRole()
                                                                                                        .hasCapability(Capability.AnimalCheats)
                                                                                                    && this.animalCheat) {
                                                                                                    PacketTypes.PacketAuthorization.onUnauthorized(
                                                                                                        connection, PacketTypes.PacketType.ExtraInfo
                                                                                                    );
                                                                                                } else {
                                                                                                    isoPlayer.setAnimalCheat(this.animalCheat);
                                                                                                    if (!connection.getRole()
                                                                                                            .hasCapability(Capability.AnimalCheats)
                                                                                                        && this.animalExtraValues) {
                                                                                                        PacketTypes.PacketAuthorization.onUnauthorized(
                                                                                                            connection, PacketTypes.PacketType.ExtraInfo
                                                                                                        );
                                                                                                    } else {
                                                                                                        isoPlayer.setAnimalExtraValuesCheat(
                                                                                                            this.animalExtraValues
                                                                                                        );
                                                                                                        if (!connection.getRole()
                                                                                                                .hasCapability(Capability.ClimateManager)
                                                                                                            && this.isAlwaysDayCheat) {
                                                                                                            PacketTypes.PacketAuthorization.onUnauthorized(
                                                                                                                connection, PacketTypes.PacketType.ExtraInfo
                                                                                                            );
                                                                                                        } else {
                                                                                                            isoPlayer.setAlwaysDayCheat(this.isAlwaysDayCheat);
                                                                                                            if (!connection.getRole()
                                                                                                                    .hasCapability(
                                                                                                                        Capability.UseDebugContextMenu
                                                                                                                    )
                                                                                                                && this.canUseDebugContextMenu) {
                                                                                                                PacketTypes.PacketAuthorization.onUnauthorized(
                                                                                                                    connection,
                                                                                                                    PacketTypes.PacketType.ExtraInfo
                                                                                                                );
                                                                                                            } else {
                                                                                                                isoPlayer.setCanUseDebugContextMenu(
                                                                                                                    this.canUseDebugContextMenu
                                                                                                                );
                                                                                                                this.sendToClients(
                                                                                                                    PacketTypes.PacketType.ExtraInfo, null
                                                                                                                );
                                                                                                            }
                                                                                                        }
                                                                                                    }
                                                                                                }
                                                                                            }
                                                                                        }
                                                                                    }
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean isConsistent(IConnection connection) {
        return this.player.isConsistent(connection) && (connection.getRole().hasAdminPower() || GameClient.client);
    }
}
