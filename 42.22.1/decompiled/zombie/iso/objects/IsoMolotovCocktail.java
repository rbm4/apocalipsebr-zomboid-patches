// Decompiled with Zomboid Decompiler v0.3.0 using Vineflower.
package zombie.iso.objects;

import java.util.Random;
import zombie.GameTime;
import zombie.UsedFromLua;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.opengl.Shader;
import zombie.core.skinnedmodel.model.ItemModelRenderer;
import zombie.core.skinnedmodel.model.WorldItemModelDrawer;
import zombie.core.textures.ColorInfo;
import zombie.core.textures.Texture;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoCell;
import zombie.iso.IsoPhysicsObject;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;
import zombie.util.lambda.PZOptional;

@UsedFromLua
public class IsoMolotovCocktail extends IsoPhysicsObject {
    private HandWeapon weapon;
    private IsoGameCharacter character;
    private float timer;
    private int explodeTimer;

    @Override
    public String getObjectName() {
        return "MolotovCocktail";
    }

    public IsoMolotovCocktail(IsoCell cell) {
        super(cell);
    }

    public IsoMolotovCocktail(IsoCell cell, float x, float y, float z, float xVelocity, float yVelocity, HandWeapon weapon, IsoGameCharacter character) {
        super(cell);
        this.weapon = weapon;
        this.character = character;
        this.explodeTimer = weapon.getTriggerExplosionTimer();
        this.velX = xVelocity;
        this.velY = yVelocity;
        Random spread = new Random(weapon.getID());
        this.velX = this.velX + (spread.nextFloat() * 0.4F - 0.2F);
        this.velY = this.velY + (spread.nextFloat() * 0.4F - 0.2F);
        this.setX(x);
        this.setY(y);
        this.setZ(z);
        this.setNextX(x);
        this.setNextY(y);
        this.offsetX = 0.0F;
        this.offsetY = 0.0F;
        this.terminalVelocity = -0.02F;
        Texture tex = PZOptional.ifPresent(weapon.getTex(), null, (_tex, _sprite) -> _sprite.LoadSingleTexture(_tex.getName()), this.sprite);
        if (tex != null) {
            this.sprite.animate = false;
            int scale = 2;
            this.sprite.def.scaleAspect(tex.getWidthOrig(), tex.getHeightOrig(), 32.0F, 32.0F);
        }

        this.speedMod = 0.6F;
    }

    public void collideCharacter() {
        if (this.explodeTimer == 0) {
            this.Explode();
        }
    }

    @Override
    public void collideGround() {
        if (this.explodeTimer == 0) {
            this.Explode();
        }
    }

    @Override
    public void collideWall() {
        if (this.explodeTimer == 0) {
            this.Explode();
        }
    }

    @Override
    public void update() {
        super.update();
        if (!this.isDestroyed()) {
            if (this.isCollidedThisFrame() && this.explodeTimer == 0) {
                this.Explode();
            }

            if (this.explodeTimer > 0) {
                this.timer = this.timer + GameTime.getInstance().getThirtyFPSMultiplier();
                if (this.timer >= this.explodeTimer) {
                    this.Explode();
                }
            }
        }
    }

    @Override
    public void render(float x, float y, float z, ColorInfo info, boolean bDoAttached, boolean bWallLightingPass, Shader shader) {
        if (Core.getInstance().isOption3DGroundItem() && ItemModelRenderer.itemHasModel(this.weapon)) {
            ItemModelRenderer.RenderStatus status = WorldItemModelDrawer.renderMain(this.weapon, this.getSquare(), this.getRenderSquare(), x, y, z, 0.0F);
            if (status == ItemModelRenderer.RenderStatus.Loading || status == ItemModelRenderer.RenderStatus.Ready) {
                return;
            }
        }

        super.render(x, y, z, info, bDoAttached, bWallLightingPass, shader);
        if (Core.debug) {
        }
    }

    private void Explode() {
        if (!this.isDestroyed() && this.getCurrentSquare() != null) {
            this.setDestroyed(true);
            this.getCurrentSquare().getMovingObjects().remove(this);
            this.getCell().Remove(this);
            if (!GameClient.client) {
                IsoTrap trap = new IsoTrap(this.character, this.weapon, this.getCurrentSquare().getCell(), this.getCurrentSquare());
                if (trap.shouldPlaceInWorldAfterThrowing()) {
                    trap.getSquare().AddTileObject(trap);
                }

                if (trap.isInstantExplosion()) {
                    if (GameServer.server && this.character instanceof IsoPlayer isoPlayer) {
                        INetworkPacket.sendToAll(PacketTypes.PacketType.AddExplosiveTrap, this.weapon, isoPlayer, trap.getSquare());
                    }

                    trap.triggerExplosion();
                } else if (GameServer.server) {
                    trap.transmitCompleteItemToClients();
                }
            }
        }
    }
}
