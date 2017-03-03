/*------------------------------------------------------------------------------
 Copyright (c) CovertJaguar, 2011-2016
 http://railcraft.info

 This code is the property of CovertJaguar
 and may only be used with explicit written
 permission unless otherwise specified on the
 license page at http://railcraft.info/wiki/info:license.
 -----------------------------------------------------------------------------*/
package mods.railcraft.common.plugins.color;

import mods.railcraft.api.core.IRailcraftRecipeIngredient;
import mods.railcraft.api.core.IVariantEnum;
import mods.railcraft.common.plugins.forge.LocalizationPlugin;
import mods.railcraft.common.util.inventory.InvTools;
import mods.railcraft.common.util.misc.MiscTools;
import net.minecraft.block.material.MapColor;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.oredict.OreDictionary;
import org.apache.commons.lang3.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author CovertJaguar <http://www.railcraft.info>
 */
public enum EnumColor implements IVariantEnum {

    WHITE(0xFFFFFF, "dyeWhite", "white"),
    ORANGE(0xFF6A00, "dyeOrange", "orange"),
    MAGENTA(0xFF64FF, "dyeMagenta", "magenta"),
    LIGHT_BLUE(0x7F9AD1, "dyeLightBlue", "light_blue", "lightBlue"),
    YELLOW(0xFFC700, "dyeYellow", "yellow"),
    LIME(0x3FAA36, "dyeLime", "lime"),
    PINK(0xE585A0, "dyePink", "pink"),
    GRAY(0x444444, "dyeGray", "gray"),
    SILVER(0x888888, "dyeLightGray", "silver", "light_gray", "lightGray"),
    CYAN(0x36809E, "dyeCyan", "cyan"),
    PURPLE(0x843FBF, "dyePurple", "purple"),
    BLUE(0x3441A2, "dyeBlue", "blue"),
    BROWN(0x5C3A24, "dyeBrown", "brown"),
    GREEN(0x394C1E, "dyeGreen", "green"),
    RED(0xA33835, "dyeRed", "red"),
    BLACK(0x2D2D2D, "dyeBlack", "black"),;
    public static final EnumColor[] VALUES = values();
    public static final EnumColor[] VALUES_INVERTED = values();
    public static final String DEFAULT_COLOR_TAG = "color";
    public static final Map<String, EnumColor> nameMap = new HashMap<>();

    static {
        ArrayUtils.reverse(VALUES_INVERTED);
        for (EnumColor color : VALUES) {
            for (String name : color.names) {
                nameMap.put(name, color);
            }
        }
    }

    private final int hexColor;
    private final String oreTagDyeName;
    private final String[] names;
    private List<ItemStack> dyes;

    EnumColor(int hexColor, String oreTagDyeName, String... names) {
        this.hexColor = hexColor;
        this.oreTagDyeName = oreTagDyeName;
        this.names = names;

    }

    public static EnumColor fromDye(EnumDyeColor dyeColor) {
        return fromOrdinal(dyeColor.getMetadata());
    }

    public static EnumColor fromOrdinal(int id) {
        if (id < 0 || id >= VALUES.length)
            return WHITE;
        return VALUES[id];
    }

    public static EnumColor fromDyeOreDictTag(String dyeTag) {
        for (EnumColor color : VALUES) {
            if (color.getDyeOreDictTag().equalsIgnoreCase(dyeTag))
                return color;
        }
        return null;
    }

    public static EnumColor fromName(String name) {
        EnumColor color = nameMap.get(name);
        if (color == null)
            return WHITE;
        return color;
    }

    public static EnumColor getRand() {
        return VALUES[MiscTools.RANDOM.nextInt(VALUES.length)];
    }

    public static EnumColor readFromNBT(@Nullable NBTTagCompound nbt, String tag) {
        if (nbt != null) {
            if (nbt.hasKey(tag, 8))
                return EnumColor.fromName(nbt.getString(tag));
            if (nbt.hasKey(tag, 1))
                return EnumColor.fromOrdinal(nbt.getByte(tag));
        }
        return EnumColor.WHITE;
    }

    public static boolean isColored(ItemStack stack) {
        if (stack == null)
            return false;
        if (InvTools.isStackEqualToBlock(stack, Blocks.WOOL))
            return true;
        if (stack.getItem() == Items.DYE)
            return true;
        NBTTagCompound nbt = stack.getTagCompound();
        return nbt.hasKey(DEFAULT_COLOR_TAG);
    }

    @Nonnull
    public static EnumColor fromItemStack(ItemStack stack) {
        if (stack == null)
            return EnumColor.WHITE;
        if (InvTools.isStackEqualToBlock(stack, Blocks.WOOL))
            return EnumColor.fromOrdinal(stack.getItemDamage());
        if (stack.getItem() == Items.DYE)
            return EnumColor.fromOrdinal(15 - stack.getItemDamage());
        NBTTagCompound nbt = stack.getTagCompound();
        return EnumColor.readFromNBT(nbt, DEFAULT_COLOR_TAG);
    }

    public EnumDyeColor getDye() {
        return EnumDyeColor.byMetadata(ordinal());
    }

    public MapColor getMapColor() {
        return getDye().getMapColor();
    }

    public int getHexColor() {
        return hexColor;
    }

    public EnumColor next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    public EnumColor previous() {
        return VALUES[(ordinal() + VALUES.length - 1) % VALUES.length];
    }

    public EnumColor inverse() {
        return EnumColor.VALUES[15 - ordinal()];
    }

    public String getTag() {
        return "color." + getBaseTag();
    }

    public String getBaseTag() {
        return getName();
    }

    public String getTranslatedName() {
        return LocalizationPlugin.translate("railcraft." + getTag());
    }

    public String getDyeOreDictTag() {
        return oreTagDyeName;
    }

    public void writeToNBT(NBTTagCompound nbt, String tag) {
        nbt.setString(tag, getName());
    }

    @Override
    public String toString() {
        return getName();
    }

    @Override
    public String getName() {
        return names[0];
    }

    public ItemStack setItemColor(ItemStack stack) {
        return setItemColor(stack, DEFAULT_COLOR_TAG);
    }

    public ItemStack setItemColor(ItemStack stack, String tag) {
        if (stack == null)
            return null;
        NBTTagCompound nbt = InvTools.getItemData(stack);
        writeToNBT(nbt, tag);
        return stack;
    }

    public boolean isEqual(EnumDyeColor dye) {
        return dye != null && getDye() == dye;
    }

    @Nullable
    @Override
    public Object getAlternate(IRailcraftRecipeIngredient container) {
        return null;
    }

    public List<ItemStack> getDyesStacks() {
        if (dyes == null) {
            dyes = new ArrayList<>();
            dyes.addAll(OreDictionary.getOres(getDyeOreDictTag()));
        }
        return dyes;
    }
}
