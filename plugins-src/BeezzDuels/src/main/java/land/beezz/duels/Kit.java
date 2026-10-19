package land.beezz.duels;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.Arrays;
import java.util.List;

/** Duel kits: everyone gets exactly the same things, nothing from the survival inventory goes in or out. */
enum Kit {

    DIAMOND("diamond", "алмазный", "Алмазный", List.of(
            "Алмазная броня и алмазный меч",
            "5 золотых яблок",
            "2 зелья скорости",
            "2 зелья силы",
            "8 зелий исцеления")),
    IRON("iron", "железный", "Железный", List.of(
            "Железная броня и железный меч",
            "Удочка",
            "Лук и 5 стрел",
            "2 золотых яблока")),
    ARCHER("archer", "лучник", "Лучник", List.of(
            "Лук",
            "64 стрелы",
            "Без брони — кто точнее, тот и победил"));

    private final String id, ru, title;
    private final List<String> items;

    Kit(String id, String ru, String title, List<String> items) {
        this.id = id;
        this.ru = ru;
        this.title = title;
        this.items = items;
    }

    String id() {
        return id;
    }

    String title() {
        return title;
    }

    /** What is inside, one line per item group: shown on hover in chat and on the site. */
    List<String> items() {
        return items;
    }

    String description() {
        return String.join(", ", items).toLowerCase();
    }

    static Kit byId(String s) {
        return Arrays.stream(values()).filter(k -> k.id.equalsIgnoreCase(s) || k.ru.equalsIgnoreCase(s)).findFirst().orElse(null);
    }

    void give(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        switch (this) {
            case DIAMOND -> {
                armor(inv, Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS);
                inv.setItem(0, new ItemStack(Material.DIAMOND_SWORD));
                inv.setItem(1, new ItemStack(Material.GOLDEN_APPLE, 5));
                inv.setItem(2, potion(PotionType.SWIFTNESS));
                inv.setItem(3, potion(PotionType.SWIFTNESS));
                inv.setItem(4, potion(PotionType.STRENGTH));
                inv.setItem(5, potion(PotionType.STRENGTH));
                for (int i = 0; i < 8; i++) inv.addItem(potion(PotionType.STRONG_HEALING)); // slots 6-8, then the inventory
            }
            case IRON -> {
                armor(inv, Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS);
                inv.setItem(0, new ItemStack(Material.IRON_SWORD));
                inv.setItem(1, new ItemStack(Material.FISHING_ROD));
                inv.setItem(2, new ItemStack(Material.BOW));
                inv.setItem(3, new ItemStack(Material.GOLDEN_APPLE, 2));
                inv.setItem(9, new ItemStack(Material.ARROW, 5));
            }
            case ARCHER -> {
                inv.setItem(0, new ItemStack(Material.BOW));
                inv.setItem(9, new ItemStack(Material.ARROW, 64));
            }
        }
    }

    private static void armor(PlayerInventory inv, Material h, Material c, Material l, Material b) {
        inv.setHelmet(new ItemStack(h));
        inv.setChestplate(new ItemStack(c));
        inv.setLeggings(new ItemStack(l));
        inv.setBoots(new ItemStack(b));
    }

    private static ItemStack potion(PotionType type) {
        ItemStack i = new ItemStack(Material.POTION);
        PotionMeta m = (PotionMeta) i.getItemMeta();
        m.setBasePotionType(type);
        i.setItemMeta(m);
        return i;
    }
}
