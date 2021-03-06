/*------------------------------------------------------------------------------
 Copyright (c) CovertJaguar, 2011-2016
 http://railcraft.info

 This code is the property of CovertJaguar
 and may only be used with explicit written
 permission unless otherwise specified on the
 license page at http://railcraft.info/wiki/info:license.
 -----------------------------------------------------------------------------*/
package mods.railcraft.common.blocks.machine.manipulator;

import mods.railcraft.api.carts.CartToolsAPI;
import mods.railcraft.api.carts.IFluidCart;
import mods.railcraft.api.tracks.ITrackKitInstance;
import mods.railcraft.api.tracks.ITrackKitLockdown;
import mods.railcraft.common.blocks.tracks.outfitted.TileTrackOutfitted;
import mods.railcraft.common.carts.EntityLocomotiveSteam;
import mods.railcraft.common.core.RailcraftConfig;
import mods.railcraft.common.fluids.AdvancedFluidHandler;
import mods.railcraft.common.fluids.FluidItemHelper;
import mods.railcraft.common.fluids.Fluids;
import mods.railcraft.common.gui.EnumGui;
import mods.railcraft.common.gui.GuiHandler;
import mods.railcraft.common.util.inventory.InvTools;
import mods.railcraft.common.util.misc.Predicates;
import mods.railcraft.common.util.misc.SafeNBTWrapper;
import mods.railcraft.common.util.network.RailcraftInputStream;
import mods.railcraft.common.util.network.RailcraftOutputStream;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;

import javax.annotation.Nullable;
import java.io.IOException;

public class TileFluidLoader extends TileFluidManipulator {

    private static final int RESET_WAIT = 200;
    private static final int TRANSFER_RATE = 20;
    private static final float MAX_PIPE_LENGTH = 0.96F;
    private static final float PIPE_INCREMENT = 0.01f;
    private float pipeLength;
    private boolean needsPipe;

    @Override
    public ManipulatorVariant getMachineType() {
        return ManipulatorVariant.FLUID_LOADER;
    }

    @Override
    public EnumFacing getFacing() {
        return EnumFacing.DOWN;
    }

    private void resetPipe() {
        pipeLength = 0;
    }

    public float getPipeLength() {
        return pipeLength;
    }

    private void setPipeLength(float y) {
        pipeLength = y;
        sendUpdateToClient();
    }

    private void extendPipe() {
        float y = pipeLength + PIPE_INCREMENT;
        if (pipeIsExtended())
            y = MAX_PIPE_LENGTH;
        setPipeLength(y);
    }

    private void retractPipe() {
        float y = pipeLength - PIPE_INCREMENT;
        if (pipeIsRetracted())
            y = 0;
        setPipeLength(y);
    }

    private boolean pipeIsExtended() {
        return pipeLength >= MAX_PIPE_LENGTH;
    }

    private boolean pipeIsRetracted() {
        return pipeLength <= 0;
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return new AxisAlignedBB(getX(), getY() - 1, getZ(), getX() + 1, getY() + 1, getZ() + 1);
    }

    @Override
    protected void reset() {
        super.reset();
        if (currentCart instanceof IFluidCart)
            ((IFluidCart) currentCart).setFilling(false);
    }

    @Override
    protected void upkeep() {
        super.upkeep();

        InvTools.validateInventory(this, SLOT_INPUT, worldObj, getPos(), s -> isItemValidForSlot(SLOT_INPUT, s));
        InvTools.validateInventory(this, SLOT_PROCESSING, worldObj, getPos(), FluidItemHelper::isContainer);
        InvTools.validateInventory(this, SLOT_OUTPUT, worldObj, getPos(), FluidItemHelper::isContainer);

        tankManager.pull(tileCache, Predicates.notInstanceOf(getClass()), EnumFacing.VALUES, 0, TRANSFER_RATE);
    }

    @Nullable
    @Override
    public EntityMinecart getCart() {
        needsPipe = false;
        EntityMinecart cart = super.getCart();
        if (cart == null) {
            cart = CartToolsAPI.getMinecartOnSide(worldObj, getPos().down(), 0.2f, EnumFacing.DOWN);
            needsPipe = true;
        }
        return cart;
    }

    @Override
    protected void waitForReset(@Nullable EntityMinecart cart) {
        if (pipeIsRetracted())
            sendCart(cart);
        else
            retractPipe();
    }

    @Override
    protected void onNoCart() {
        if (!pipeIsRetracted())
            retractPipe();
    }

    @Override
    protected void processCart(EntityMinecart cart) {
        if (cart instanceof EntityLocomotiveSteam) {
            EntityLocomotiveSteam loco = (EntityLocomotiveSteam) cart;
            if (!loco.isSafeToFill()) {
                retractPipe();
                return;
            }
        }

        AdvancedFluidHandler tankCart = getFluidHandler(cart, EnumFacing.UP);
        if (tankCart == null)
            return;
        boolean cartNeedsFilling = cartNeedsFilling(tankCart);

        if (cartNeedsFilling && needsPipe)
            extendPipe();
        else
            retractPipe();

        setProcessing(false);
        if (cartNeedsFilling && (!needsPipe || pipeIsExtended())) {
            FluidStack moved = FluidUtil.tryFluidTransfer(tankCart, tank, RailcraftConfig.getTankCartFillRate(), true);
            setProcessing(Fluids.isNotEmpty(moved));
        }

        if (isProcessing())
            setPowered(false);

        if (cart instanceof IFluidCart)
            ((IFluidCart) cart).setFilling(isProcessing());

        if (tankCart.isTankFull(tank.getFluidType()))
            setResetTimer(RESET_WAIT);
    }

    private boolean cartNeedsFilling(AdvancedFluidHandler tankCart) {
        FluidStack fluidStack = tank.getFluid();
        return fluidStack != null && fluidStack.amount > 0 && tankCart.canPutFluid(fluidStack);
    }

    @Override
    protected boolean hasWorkForCart(EntityMinecart cart) {
        if (!pipeIsRetracted())
            return true;
        AdvancedFluidHandler tankCart = getFluidHandler(cart, EnumFacing.UP);
        if (tankCart == null)
            return false;
        Fluid fluid = getFluidHandled();
        switch (redstoneController().getButtonState()) {
            case COMPLETE:
                return !tankCart.isTankFull(fluid);
            case PARTIAL:
                return tankCart.isTankEmpty(fluid);
        }
        return false;
    }

    @Override
    protected void setPowered(boolean p) {
        if (isManualMode())
            p = false;
        if (p) {
            resetPipe();
            if (worldObj != null) {
                TileEntity tile = worldObj.getTileEntity(getPos().down(2));
                if (tile instanceof TileTrackOutfitted) {
                    TileTrackOutfitted trackTile = (TileTrackOutfitted) tile;
                    ITrackKitInstance track = trackTile.getTrackKitInstance();
                    if (track instanceof ITrackKitLockdown)
                        ((ITrackKitLockdown) track).releaseCart();
                }
            }
        }
        super.setPowered(p);
    }

    @Override
    public void invalidate() {
        super.invalidate();
        resetPipe();
    }

    @Override
    public void validate() {
        super.validate();
        resetPipe();
    }

    @Override
    public void onBlockRemoval() {
        super.onBlockRemoval();
        resetPipe();
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);

        data.setFloat("pipeLength", pipeLength);
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);

        SafeNBTWrapper safe = new SafeNBTWrapper(data);
        pipeLength = safe.getFloat("pipeLength");
    }

    @Override
    public void writePacketData(RailcraftOutputStream data) throws IOException {
        super.writePacketData(data);
        data.writeFloat(pipeLength);
    }

    @Override
    public void readPacketData(RailcraftInputStream data) throws IOException {
        super.readPacketData(data);
        setPipeLength(data.readFloat());
    }

    @Override
    public boolean openGui(EntityPlayer player) {
        GuiHandler.openGui(EnumGui.MANIPULATOR_FLUID, player, worldObj, getPos());
        return true;
    }
}
