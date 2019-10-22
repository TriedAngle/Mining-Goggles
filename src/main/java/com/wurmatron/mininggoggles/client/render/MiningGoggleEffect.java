package com.wurmatron.mininggoggles.client.render;

import static com.wurmatron.mininggoggles.common.items.ItemGogglesMining.armorDetection;

import baubles.api.BaublesApi;
import com.wurmatron.mininggoggles.MiningGoggles;
import com.wurmatron.mininggoggles.common.ConfigHandler;
import com.wurmatron.mininggoggles.common.items.ItemGogglesMining;
import com.wurmatron.mininggoggles.common.reference.Global;
import java.util.Arrays;
import java.util.Iterator;
import java.util.stream.Collectors;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.oredict.OreDictionary;
import org.cliffc.high_scale_lib.NonBlockingHashMap;
import org.cliffc.high_scale_lib.NonBlockingHashSet;

public class MiningGoggleEffect {

  private static NonBlockingHashSet<RenderOre> oreTargets = new NonBlockingHashSet<>();
  private static NonBlockingHashSet<RenderOre> renderingOre = new NonBlockingHashSet<>();
  private static NonBlockingHashMap<String, Integer> filterCache = new NonBlockingHashMap<>();

  @SubscribeEvent
  public void renderInWorld(RenderWorldLastEvent e) {
    if (oreTargets.size() > 0) {
      RenderOre[] ore = oreTargets.toArray(new RenderOre[0]);
      for (int index = 0; index < ConfigHandler.renderOverTime; index++) {
        if (index < ore.length) {
          renderingOre.add(ore[index]);
          oreTargets.remove(ore[index]);
        }
      }
    }
    for (RenderOre target : renderingOre) {
      target.render(e.getPartialTicks());
    }
  }

  @SubscribeEvent
  public void onClientTick(ClientTickEvent e) {
    if (e.side == Side.CLIENT && armorDetection) {
      // 1st Half
      MiningGoggles.EXECUTORS.submit(() -> {
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player != null && player.world != null
            &&
            player.world.getWorldTime() % (calcRangeModifier(
                ItemGogglesMining.getRange(player.
                    inventory.armorInventory.get(3)))) == 0) {
          if (!checkArmor(player)) {
            armorDetection = false;
            return;
          }
          int range = ItemGogglesMining.getRange(getGoggles(player));
          removeOutdatedEntries(player.world, player.getPosition(), range);
          Iterable<BlockPos> blocksToTest = BlockPos
              .getAllInBox((int) player.posX - range, (int) player.posY - range,
                  (int) player.posZ - range, (int) player.posX + range, (int) player.posY + range,
                  (int) player.posZ + range);
          for (BlockPos pos : blocksToTest) {
            validatePos(player.world, pos, player);
          }
        }
      });
      // 2nd Half
      MiningGoggles.EXECUTORS.submit(() -> {
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player != null && player.world != null
            &&
            player.world.getWorldTime() % (calcRangeModifier(
                ItemGogglesMining.getRange(getGoggles(player)))) == 0) {
          if (!checkArmor(player)) {
            armorDetection = false;
            return;
          }
          int range = ItemGogglesMining.getRange(getGoggles(player));
          Iterable<BlockPos> blocksToTest = BlockPos
              .getAllInBox((int) player.posX + range, (int) player.posY + range,
                  (int) player.posZ + range, (int) player.posX + range * 2,
                  (int) player.posY + range * 2,
                  (int) player.posZ + range * 2);
          for (BlockPos pos : blocksToTest) {
            validatePos(player.world, pos, player);
          }
        }
      });
    } else if (!MiningGoggleEffect.renderingOre.isEmpty()) {
      MiningGoggleEffect.renderingOre.clear();
    }
  }

  public ItemStack getGoggles(EntityPlayer player) {
    if (Loader.isModLoaded("baubles")) {
      return BaublesApi.getBaublesHandler(player).getStackInSlot(4);
    }
    return player.inventory.armorInventory.get(3);
  }

  private void removeOutdatedEntries(World world, BlockPos playerPos, int updateRange) {
    MiningGoggles.EXECUTORS.submit(() -> {
      Iterator<RenderOre> iterator = renderingOre.iterator();
      while (iterator.hasNext()) {
        RenderOre ore = iterator.next();
        boolean withinX = ore.pos.getX() + updateRange > playerPos.getX()
            || ore.pos.getX() - updateRange > playerPos.getX();
        boolean withinY = ore.pos.getY() + updateRange > playerPos.getY()
            || ore.pos.getY() - updateRange > playerPos.getY();
        boolean withinZ = ore.pos.getZ() + updateRange > playerPos.getZ()
            || ore.pos.getZ() - updateRange > playerPos.getZ();
        boolean exist = world.getBlockState(ore.pos).getBlock() != Blocks.AIR;
        if (!withinX || !withinY || !withinZ || !exist) {
          renderingOre.remove(ore);
        }
      }
    });
  }

  private double calcRangeModifier(int range) {
    if (range > 4) {
      if (range <= 64) {
        return ConfigHandler.gogglesUpdateFrequency + range / 4;
      } else if (range <= 256) {
        return ConfigHandler.gogglesUpdateFrequency + (range);
      } else {
        return ConfigHandler.gogglesUpdateFrequency + (2 * range);
      }
    }
    return ConfigHandler.gogglesUpdateFrequency;
  }

  private boolean checkArmor(EntityPlayer player) {
    return getGoggles(player) != ItemStack.EMPTY && getGoggles(player).getItem() instanceof ItemGogglesMining;
  }

  private void validatePos(World world, BlockPos pos, EntityPlayer player) {
    if (world.getBlockState(pos).getBlock() instanceof Block
        && world.getBlockState(pos).getBlock() != Blocks.AIR) {
      IBlockState state = world.getBlockState(pos);
      int oreColor = getColorForOre(
          getGoggles(player).getTagCompound()
              .getCompoundTag(Global.NBT_FILTERS),
          world.getBlockState(pos), world.getTileEntity(pos));
      if (oreColor != -1) {
        MiningGoggleEffect.oreTargets
            .add(new RenderOre(world, player, pos, world.getTileEntity(pos),
                oreColor));
      }
    }
  }

  private static final int[] ORE_COLOR = new int[]{0xffffff, 0xffa500, 0xff00ff, 0xadd8e6, 0xffff00,
      0xbfff00, 0xff69b4, 0x181818, 0x606060, 0x00ffff, 0xEE82EE, 0x0000ff, 0xCD853F, 0x008000,
      0xff0000, 0x000000};

  private static int getColorForOre(NBTTagCompound filterData, IBlockState block,
      TileEntity entity) {
    int oreColor = getOreData(filterData, block);
    if (oreColor != -1) {
      return ORE_COLOR[oreColor];
    }
    return -1;
  }

  private static int getOreData(NBTTagCompound filterData, IBlockState block) {
    if (filterData == null || filterData.hasNoTags() || block.getBlock() == Blocks.AIR) {
      return -1;
    }
    filterCache.clear();
    for (int index = 0; index < 16; index++) {
      if (!filterData.getString("color" + index).contains("9")) {
        String[] filter = filterData.getString("color" + index).replaceAll("9", "").split(";");
        for (String f : filter) {
          filterCache.put(f, index);
        }
      }
    }
    for (String name : getBlockNames(block)) {
      if (filterCache.containsKey(name)) {
        return filterCache.get(name);
      }
    }
    return -1;
  }

  private static String[] getBlockNames(IBlockState block) {
    ItemStack itemBlock = new ItemStack(Item.getItemFromBlock(block.getBlock()), 1, block.getBlock().getMetaFromState(block));
    if (itemBlock != ItemStack.EMPTY && !itemBlock.isEmpty()) {
      return Arrays.stream(OreDictionary.getOreIDs(itemBlock))
          .mapToObj(OreDictionary::getOreName).collect(Collectors.toList()).toArray(new String[0]);
    }
    return new String[0];
  }
}
