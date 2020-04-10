package com.raoulvdberge.refinedpipes.network.pipe.attachment.extractor;

import com.raoulvdberge.refinedpipes.container.provider.ExtractorAttachmentContainerProvider;
import com.raoulvdberge.refinedpipes.network.Network;
import com.raoulvdberge.refinedpipes.network.NetworkManager;
import com.raoulvdberge.refinedpipes.network.fluid.FluidNetwork;
import com.raoulvdberge.refinedpipes.network.item.ItemNetwork;
import com.raoulvdberge.refinedpipes.network.pipe.Destination;
import com.raoulvdberge.refinedpipes.network.pipe.DestinationType;
import com.raoulvdberge.refinedpipes.network.pipe.Pipe;
import com.raoulvdberge.refinedpipes.network.pipe.attachment.Attachment;
import com.raoulvdberge.refinedpipes.network.pipe.item.ItemPipe;
import com.raoulvdberge.refinedpipes.network.pipe.transport.ItemTransport;
import com.raoulvdberge.refinedpipes.network.pipe.transport.callback.ItemBounceBackTransportCallback;
import com.raoulvdberge.refinedpipes.network.pipe.transport.callback.ItemInsertTransportCallback;
import com.raoulvdberge.refinedpipes.network.pipe.transport.callback.ItemPipeGoneTransportCallback;
import com.raoulvdberge.refinedpipes.routing.Path;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class ExtractorAttachment extends Attachment {
    private static final Logger LOGGER = LogManager.getLogger(ExtractorAttachment.class);

    public static final int MAX_FILTER_SLOTS = 15;

    private final Pipe pipe;
    private final ExtractorAttachmentType type;
    private final ItemStackHandler itemFilter;

    private int ticks;
    private RedstoneMode redstoneMode = RedstoneMode.IGNORED;
    private BlacklistWhitelist blacklistWhitelist = BlacklistWhitelist.BLACKLIST;
    private RoutingMode routingMode = RoutingMode.NEAREST;
    private int roundRobinIndex;
    private int stackSize;
    private boolean exactMode = true;

    public ExtractorAttachment(Pipe pipe, Direction direction, ExtractorAttachmentType type) {
        super(direction);

        this.pipe = pipe;
        this.type = type;
        this.stackSize = type.getItemsToExtract();
        this.itemFilter = createItemFilterInventory(this);
    }

    @Override
    public void update() {
        Network network = pipe.getNetwork();

        int tickInterval = 0;
        if (network instanceof ItemNetwork) {
            tickInterval = type.getItemTickInterval();
        } else if (network instanceof FluidNetwork) {
            tickInterval = type.getFluidTickInterval();
        }

        if (tickInterval != 0 && (ticks++) % tickInterval != 0) {
            return;
        }

        if (!redstoneMode.isEnabled(pipe.getWorld(), pipe.getPos())) {
            return;
        }

        BlockPos destinationPos = pipe.getPos().offset(getDirection());

        TileEntity tile = pipe.getWorld().getTileEntity(destinationPos);
        if (tile == null) {
            return;
        }

        if (network instanceof ItemNetwork) {
            tile.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, getDirection().getOpposite())
                .ifPresent(itemHandler -> update((ItemNetwork) network, destinationPos, itemHandler));
        } else if (network instanceof FluidNetwork) {
            tile.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, getDirection().getOpposite())
                .ifPresent(fluidHandler -> update((FluidNetwork) network, fluidHandler));
        }
    }

    private void update(ItemNetwork network, BlockPos sourcePos, IItemHandler source) {
        if (stackSize == 0) {
            return;
        }

        int firstSlot = getFirstSlot(source);
        if (firstSlot == -1) {
            return;
        }

        ItemStack extracted = source.extractItem(firstSlot, stackSize, true);
        if (extracted.isEmpty()) {
            return;
        }

        Destination destination = findDestination(network, sourcePos, extracted);
        if (destination == null) {
            LOGGER.warn("No destination found from " + pipe.getPos());
            return;
        }

        Path<BlockPos> path = network
            .getDestinationPathCache()
            .getPath(pipe.getPos(), destination);
        if (path == null) {
            LOGGER.error("No path found from " + pipe.getPos() + " to " + destination);
            return;
        }

        ItemStack extractedActual = source.extractItem(firstSlot, stackSize, false);
        if (extractedActual.isEmpty()) {
            return;
        }

        BlockPos fromPos = pipe.getPos().offset(getDirection());

        ((ItemPipe) pipe).addTransport(new ItemTransport(
            extractedActual.copy(),
            fromPos,
            destination.getReceiver(),
            path.toQueue(),
            new ItemInsertTransportCallback(destination.getReceiver(), destination.getIncomingDirection(), extractedActual),
            new ItemBounceBackTransportCallback(destination.getReceiver(), sourcePos, extractedActual),
            new ItemPipeGoneTransportCallback(extractedActual)
        ));
    }

    private Destination findDestination(ItemNetwork network, BlockPos sourcePos, ItemStack extracted) {
        switch (routingMode) {
            case NEAREST:
                return network.getDestinationPathCache()
                    .findNearestDestination(pipe.getPos(), d -> isDestinationApplicable(sourcePos, extracted, d));
            case FURTHEST:
                return network.getDestinationPathCache()
                    .findFurthestDestination(pipe.getPos(), d -> isDestinationApplicable(sourcePos, extracted, d));
            case RANDOM: {
                List<Destination> destinations = new ArrayList<>(network.getDestinations(DestinationType.ITEM_HANDLER));

                while (!destinations.isEmpty()) {
                    int randomIndex = pipe.getWorld().getRandom().nextInt(destinations.size());
                    Destination randomDestination = destinations.get(randomIndex);

                    if (isDestinationApplicable(sourcePos, extracted, randomDestination)) {
                        return randomDestination;
                    }

                    destinations.remove(randomIndex);
                }

                return null;
            }
            case ROUND_ROBIN: {
                List<Destination> destinations = network.getDestinations(DestinationType.ITEM_HANDLER);
                if (roundRobinIndex >= destinations.size()) {
                    roundRobinIndex = 0;
                }

                while (true) {
                    Destination dest = destinations.get(roundRobinIndex);

                    if (isDestinationApplicable(sourcePos, extracted, dest)) {
                        roundRobinIndex++;
                        return dest;
                    } else {
                        roundRobinIndex++;
                        if (roundRobinIndex >= destinations.size()) {
                            break;
                        }
                    }
                }

                return null;
            }
            default:
                throw new RuntimeException("?");
        }
    }

    public ExtractorAttachmentType getType() {
        return type;
    }

    public ItemStackHandler getItemFilter() {
        return itemFilter;
    }

    public RedstoneMode getRedstoneMode() {
        return redstoneMode;
    }

    public void setRedstoneMode(RedstoneMode redstoneMode) {
        if (!type.getCanSetRedstoneMode()) {
            return;
        }

        this.redstoneMode = redstoneMode;
    }

    public void setBlacklistWhitelist(BlacklistWhitelist blacklistWhitelist) {
        if (!type.getCanSetWhitelistBlacklist()) {
            return;
        }

        this.blacklistWhitelist = blacklistWhitelist;
    }

    public BlacklistWhitelist getBlacklistWhitelist() {
        return blacklistWhitelist;
    }

    public void setRoutingMode(RoutingMode routingMode) {
        if (!type.getCanSetRoutingMode()) {
            return;
        }

        this.routingMode = routingMode;
    }

    public RoutingMode getRoutingMode() {
        return routingMode;
    }

    public void setStackSize(int stackSize) {
        if (stackSize < 0) {
            stackSize = 0;
        }

        if (stackSize > type.getItemsToExtract()) {
            stackSize = type.getItemsToExtract();
        }

        this.stackSize = stackSize;
    }

    public int getStackSize() {
        return stackSize;
    }

    public void setRoundRobinIndex(int roundRobinIndex) {
        this.roundRobinIndex = roundRobinIndex;
    }

    public void setExactMode(boolean exactMode) {
        if (!type.getCanSetExactMode()) {
            return;
        }

        this.exactMode = exactMode;
    }

    public boolean isExactMode() {
        return exactMode;
    }

    private void update(FluidNetwork network, IFluidHandler source) {
        FluidStack drained = source.drain(type.getFluidsToExtract(), IFluidHandler.FluidAction.SIMULATE);
        if (drained.isEmpty()) {
            return;
        }

        int filled = network.getFluidTank().fill(drained, IFluidHandler.FluidAction.SIMULATE);
        if (filled <= 0) {
            return;
        }

        int toDrain = Math.min(type.getFluidsToExtract(), filled);

        drained = source.drain(toDrain, IFluidHandler.FluidAction.EXECUTE);

        network.getFluidTank().fill(drained, IFluidHandler.FluidAction.EXECUTE);

        NetworkManager.get(pipe.getWorld()).markDirty();
    }

    private boolean isDestinationApplicable(BlockPos sourcePos, ItemStack extracted, Destination destination) {
        TileEntity tile = destination.getConnectedPipe().getWorld().getTileEntity(destination.getReceiver());
        if (tile == null) {
            return false;
        }

        IItemHandler handler = tile.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, destination.getIncomingDirection().getOpposite()).orElse(null);
        if (handler == null) {
            return false;
        }

        // Avoid extractions that lead back to the source pos through the same pipe.
        // Only if the incoming direction is different, then we'll allow it.
        if (destination.getReceiver().equals(sourcePos) && destination.getIncomingDirection() == getDirection()) {
            return false;
        }

        return ItemHandlerHelper.insertItem(handler, extracted, true).isEmpty();
    }

    private int getFirstSlot(IItemHandler handler) {
        for (int i = 0; i < handler.getSlots(); ++i) {
            ItemStack stack = handler.getStackInSlot(i);

            if (!stack.isEmpty() && acceptsItem(stack)) {
                return i;
            }
        }

        return -1;
    }

    private boolean acceptsItem(ItemStack stack) {
        if (blacklistWhitelist == BlacklistWhitelist.WHITELIST) {
            for (int i = 0; i < itemFilter.getSlots(); ++i) {
                ItemStack filtered = itemFilter.getStackInSlot(i);

                boolean equals = filtered.isItemEqual(stack);
                if (exactMode) {
                    equals = equals && ItemStack.areItemStackTagsEqual(filtered, stack);
                }

                if (equals) {
                    return true;
                }
            }

            return false;
        } else if (blacklistWhitelist == BlacklistWhitelist.BLACKLIST) {
            for (int i = 0; i < itemFilter.getSlots(); ++i) {
                ItemStack filtered = itemFilter.getStackInSlot(i);

                boolean equals = filtered.isItemEqual(stack);
                if (exactMode) {
                    equals = equals && ItemStack.areItemStackTagsEqual(filtered, stack);
                }

                if (equals) {
                    return false;
                }
            }

            return true;
        }

        return false;
    }

    @Override
    public void openContainer(ServerPlayerEntity player) {
        super.openContainer(player);

        ExtractorAttachmentContainerProvider.open(pipe, this, player);
    }

    @Override
    public ResourceLocation getId() {
        return type.getId();
    }

    @Override
    public ItemStack getDrop() {
        return new ItemStack(type.getItem());
    }

    @Override
    public CompoundNBT writeToNbt(CompoundNBT tag) {
        tag.putByte("rm", (byte) redstoneMode.ordinal());
        tag.put("itemfilter", itemFilter.serializeNBT());
        tag.putByte("bw", (byte) blacklistWhitelist.ordinal());
        tag.putInt("rr", roundRobinIndex);
        tag.putByte("routingm", (byte) routingMode.ordinal());
        tag.putInt("stacksi", stackSize);
        tag.putBoolean("exa", exactMode);

        return super.writeToNbt(tag);
    }

    public static ItemStackHandler createItemFilterInventory(@Nullable ExtractorAttachment attachment) {
        return new ItemStackHandler(MAX_FILTER_SLOTS) {
            @Override
            protected void onContentsChanged(int slot) {
                super.onContentsChanged(slot);

                if (attachment != null) {
                    NetworkManager.get(attachment.pipe.getWorld()).markDirty();
                }
            }
        };
    }
}