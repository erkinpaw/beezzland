package land.beezz.duels;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Arrays;

/** Duel kits: everyone gets exactly the same things, nothing from the survival inventory goes in or out. */
enum Kit {

    DIAMOND("алмазный", "Алмазный", "алмазная броня, меч, лук, щит, 2 золотых яблока"),
    NETHERITE("незеритовый", "Незеритовый", "незерит с зачарованиями, тотем, 6 золотых яблок"),
    ARCHER("лучник", "Лучник", "кожаная броня, лук и арбалет, 64 стрелы");

    private final String id, title, description;

    Kit(String id, String title, String description) {
        this.id = id;
        this.title = title;
        this.description = description;
    }

    String id() {
        return id;
    }

    String title() {
        return title;
    }

    String description() {
        return description;
    }

    static Kit byId(String s) {
        return Arrays.stream(values()).filter(k -> k.id.equalsIgnoreCase(s) || k.name().equalsIgnoreCase(s)).findFirst().orElse(null);
    }

    void give(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        switch (this) {
            case DIAMOND -> {
                armor(inv, Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS, 0);
                inv.setItem(0, new ItemStack(Material.DIAMOND_SWORD));
                inv.setItem(1, new ItemStack(Material.BOW));
                inv.setItem(2, new ItemStack(Material.GOLDEN_APPLE, 2));
                inv.setItem(3, new ItemStack(Material.COOKED_BEEF, 16));
                inv.setItem(9, new ItemStack(Material.ARROW, 16));
                inv.setItemInOffHand(new ItemStack(Material.SHIELD));
            }
            case NETHERITE -> {
                armor(inv, Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS, 4);
                inv.setItem(0, enchanted(Material.NETHERITE_SWORD, Enchantment.SHARPNESS, 5));
                inv.setItem(1, enchanted(Material.NETHERITE_AXE, Enchantment.SHARPNESS, 3));
                inv.setItem(2, new ItemStack(Material.GOLDEN_APPLE, 6));
                inv.setItem(3, new ItemStack(Material.COOKED_BEEF, 16));
                inv.setItem(8, new ItemStack(Material.TOTEM_OF_UNDYING));
                inv.setItemInOffHand(new ItemStack(Material.SHIELD));
            }
            case ARCHER -> {
                armor(inv, Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS, 2);
                inv.setItem(0, enchanted(Material.BOW, Enchantment.POWER, 3));
                inv.setItem(1, new ItemStack(Material.CROSSBOW));
                inv.setItem(2, new ItemStack(Material.STONE_SWORD));
                inv.setItem(3, new ItemStack(Material.GOLDEN_APPLE, 2));
                inv.setItem(4, new ItemStack(Material.COOKED_BEEF, 16));
                inv.setItem(9, new ItemStack(Material.ARROW, 64));
            }
        }
    }

    private static void armor(PlayerInventory inv, Material h, Material c, Material l, Material b, int protection) {
        inv.setHelmet(protection > 0 ? enchanted(h, Enchantment.PROTECTION, protection) : new ItemStack(h));
        inv.setChestplate(protection > 0 ? enchanted(c, Enchantment.PROTECTION, protection) : new ItemStack(c));
        inv.setLeggings(protection > 0 ? enchanted(l, Enchantment.PROTECTION, protection) : new ItemStack(l));
        inv.setBoots(protection > 0 ? enchanted(b, Enchantment.PROTECTION, protection) : new ItemStack(b));
    }

    private static ItemStack enchanted(Material m, Enchantment e, int level) {
        ItemStack i = new ItemStack(m);
        i.addUnsafeEnchantment(e, level);
        return i;
    }
}
