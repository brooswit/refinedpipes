package com.raoulvdberge.refinedpipes.setup;

import com.raoulvdberge.refinedpipes.RefinedPipes;
import com.raoulvdberge.refinedpipes.RefinedPipesBlocks;
import com.raoulvdberge.refinedpipes.block.ItemPipeBlock;
import com.raoulvdberge.refinedpipes.item.AttachmentItem;
import com.raoulvdberge.refinedpipes.item.BaseBlockItem;
import com.raoulvdberge.refinedpipes.network.NetworkManager;
import com.raoulvdberge.refinedpipes.network.pipe.ItemPipeType;
import com.raoulvdberge.refinedpipes.network.pipe.attachment.AttachmentRegistry;
import com.raoulvdberge.refinedpipes.network.pipe.attachment.AttachmentType;
import com.raoulvdberge.refinedpipes.network.pipe.attachment.ExtractorAttachmentType;
import com.raoulvdberge.refinedpipes.network.pipe.transport.callback.ItemBounceBackTransportCallback;
import com.raoulvdberge.refinedpipes.network.pipe.transport.callback.ItemInsertTransportCallback;
import com.raoulvdberge.refinedpipes.network.pipe.transport.callback.ItemPipeGoneTransportCallback;
import com.raoulvdberge.refinedpipes.network.pipe.transport.callback.TransportCallbackFactoryRegistry;
import com.raoulvdberge.refinedpipes.tile.ItemPipeTileEntity;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.tileentity.TileEntityType;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class CommonSetup {
    public CommonSetup() {
        AttachmentRegistry.INSTANCE.addType(new ExtractorAttachmentType());

        TransportCallbackFactoryRegistry.INSTANCE.addFactory(ItemInsertTransportCallback.ID, ItemInsertTransportCallback::of);
        TransportCallbackFactoryRegistry.INSTANCE.addFactory(ItemBounceBackTransportCallback.ID, ItemBounceBackTransportCallback::of);
        TransportCallbackFactoryRegistry.INSTANCE.addFactory(ItemPipeGoneTransportCallback.ID, ItemPipeGoneTransportCallback::of);

        RefinedPipes.NETWORK.register();
    }

    @SubscribeEvent
    public void onRegisterBlocks(RegistryEvent.Register<Block> e) {
        e.getRegistry().register(new ItemPipeBlock(ItemPipeType.SIMPLE));
        e.getRegistry().register(new ItemPipeBlock(ItemPipeType.BASIC));
        e.getRegistry().register(new ItemPipeBlock(ItemPipeType.IMPROVED));
        e.getRegistry().register(new ItemPipeBlock(ItemPipeType.ADVANCED));
    }

    @SubscribeEvent
    public void onRegisterItems(RegistryEvent.Register<Item> e) {
        e.getRegistry().register(new BaseBlockItem(RefinedPipesBlocks.SIMPLE_ITEM_PIPE));
        e.getRegistry().register(new BaseBlockItem(RefinedPipesBlocks.BASIC_ITEM_PIPE));
        e.getRegistry().register(new BaseBlockItem(RefinedPipesBlocks.IMPROVED_ITEM_PIPE));
        e.getRegistry().register(new BaseBlockItem(RefinedPipesBlocks.ADVANCED_ITEM_PIPE));

        for (AttachmentType type : AttachmentRegistry.INSTANCE.getTypes()) {
            e.getRegistry().register(new AttachmentItem(type));
        }
    }

    @SubscribeEvent
    public void onRegisterTileEntities(RegistryEvent.Register<TileEntityType<?>> e) {
        e.getRegistry().register(TileEntityType.Builder.create(() -> new ItemPipeTileEntity(ItemPipeType.SIMPLE), RefinedPipesBlocks.SIMPLE_ITEM_PIPE).build(null).setRegistryName(ItemPipeType.SIMPLE.getId()));
        e.getRegistry().register(TileEntityType.Builder.create(() -> new ItemPipeTileEntity(ItemPipeType.BASIC), RefinedPipesBlocks.BASIC_ITEM_PIPE).build(null).setRegistryName(ItemPipeType.BASIC.getId()));
        e.getRegistry().register(TileEntityType.Builder.create(() -> new ItemPipeTileEntity(ItemPipeType.IMPROVED), RefinedPipesBlocks.IMPROVED_ITEM_PIPE).build(null).setRegistryName(ItemPipeType.IMPROVED.getId()));
        e.getRegistry().register(TileEntityType.Builder.create(() -> new ItemPipeTileEntity(ItemPipeType.ADVANCED), RefinedPipesBlocks.ADVANCED_ITEM_PIPE).build(null).setRegistryName(ItemPipeType.ADVANCED.getId()));
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent e) {
        if (!e.world.isRemote && e.phase == TickEvent.Phase.END) {
            NetworkManager.get(e.world).getNetworks().forEach(n -> n.update(e.world));
        }
    }
}
