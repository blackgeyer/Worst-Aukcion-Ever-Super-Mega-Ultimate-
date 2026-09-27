import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import net.milkbowl.vault.economy.Economy;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WorstAukcionSuperMegaUltimate extends JavaPlugin implements CommandExecutor, Listener, TabCompleter {

    private static class AuctionLot {
        private final UUID id;
        private final ItemStack item;
        private final double price;
        private final String sellerName;
        private final UUID sellerUUID;
        private final long expiresAt;

        public AuctionLot(ItemStack item, double price, Player seller, long expiresAt) {
            this.id = UUID.randomUUID();
            this.item = item.clone();
            this.price = price;
            this.sellerName = seller.getName();
            this.sellerUUID = seller.getUniqueId();
            this.expiresAt = expiresAt;
        }

        public AuctionLot(UUID id, ItemStack item, double price, String sellerName, UUID sellerUUID, long expiresAt) {
            this.id = id;
            this.item = item;
            this.price = price;
            this.sellerName = sellerName;
            this.sellerUUID = sellerUUID;
            this.expiresAt = expiresAt;
        }

        public long getExpiresAt() { return expiresAt; }
        public boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
        public UUID getId() { return id; }
        public ItemStack getItem() { return item; }
        public double getPrice() { return price; }
        public String getSellerName() { return sellerName; }
        public UUID getSellerUUID() { return sellerUUID; }
    }

    private final Map<UUID, List<AuctionLot>> expiredLots = new HashMap<>();
    private final Map<UUID, AuctionLot> auctionLots = new HashMap<>();
    private final Map<UUID, Integer> playerCurrentPage = new HashMap<>();
    private final Map<UUID, Map<Integer, UUID>> playerSlotToLotMap = new HashMap<>();

    private static Economy econ = null;
    
    private final String guiTitlePrefix  = "§8Auction | Page ";
    private final String guiTitlePrefix1 = "§8Search | ";
    private final String guiTitlePrefix2 = "§8Player | ";
    private final String guiTitlePrefix3 = "§8Expired лоты | Page ";
    private final String guiTitlePrefix4 = "§8My лоты | Page ";

    private double maxPrice;
    private Connection connection;

    private final Map<String, String> translations = new HashMap<>();

    private String formatTimeLeft(AuctionLot lot) {
        long timeLeft = lot.getExpiresAt() - System.currentTimeMillis();
        if (timeLeft <= 0) return "§cExpired / Истёк";
        long seconds = timeLeft / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return "§c" + hours + "h. " + minutes + "m.";
    }

    @Override
    public void onEnable() {
        printAsciiArt();

        try {
            if (!setupEconomy()) {
                getLogger().severe("Извините, но Vault or economy plugin not found! Выключаюсь...");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            saveDefaultConfig();
            
            double loadedMaxPrice = getConfig().getDouble("max-price", 1000000.0);
            maxPrice = loadedMaxPrice;
            
            File translationFile = new File(getDataFolder(), "translations.yml");
            if (!translationFile.exists()) {
                saveResource("translations.yml", false);
            }

            initDatabase();
            loadAuctionLots();
            loadTranslations();

            this.getCommand("ah").setExecutor(this);
            this.getCommand("ah").setTabCompleter(this);
            getServer().getPluginManager().registerEvents(this, this);

            Bukkit.getScheduler().runTaskTimer(this, () -> {
                try {
                    List<AuctionLot> toExpire = new ArrayList<>();
                    for (AuctionLot lot : auctionLots.values()) {
                        if (lot.isExpired()) toExpire.add(lot);
                    }
                    for (AuctionLot lot : toExpire) {
                        auctionLots.remove(lot.getId());
                        expiredLots.computeIfAbsent(lot.getSellerUUID(), k -> new ArrayList<>()).add(lot);
                        moveLotToExpiredInDb(lot);
                        Player seller = Bukkit.getPlayer(lot.getSellerUUID());
                        if (seller != null) {
                            seller.sendMessage("§eТвой лот истёк! Забери предмет через §a/ah expired");
                        }
                    }
                } catch (Throwable t) {
                    getLogger().warning("Извините, но something went wrong in lot expiration task: " + t.getMessage());
                }
            }, 1200L, 1200L);
        } catch (Throwable t) {
            getLogger().warning("Извините, но something went wrong during onEnable: " + t.getMessage());
        }
    }

    private void printAsciiArt() {
        String ascii = "\n" +
                "██╗  ██╗██████╗ ██████╗ ███████╗████████╗    ██████╗ ██╗   ██╗██╗  ██╗██████╗ ███████╗██████╗ \n" +
                "██║  ██║██╔══██╗██╔══██╗██╔════╝╚══██╔══╝    ██╔══██╗██║   ██║██║ ██╔╝██╔══██╗██╔════╝██╔══██╗\n" +
                "██║  ██║██████╔╝██████╔╝███████╗   ██║       ███████║██║   ██║█████═╝ ██║  ██║█████╗  ██████╔╝\n" +
                "██║  ██║██╔══██╗██╔══██╗╚════██║   ██║       ██╔══██║██║   ██║██╔═██╗ ██║  ██║██╔══╝  ██╔══██╗\n" +
                "╚█████╔╝██║  ██║██║  ██║███████║   ██║       ██║  ██║╚██████╔╝██║  ██╗██████╔╝███████╗██║  ██║\n" +
                " ╚════╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚══════╝   ╚═╝       ╚═╝  ╚═╝ ╚═════╝ ╚═╝  ╚═╝╚═════╝ ╚══════╝╚═╝  ╚═╝\n";
        getLogger().info(ascii);
    }

    @Override
    public void onDisable() {
        closeDatabase();
    }

    private void initDatabase() {
        try {
            File dbFile = new File(getDataFolder(), "database.db");
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }

            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS auction_lots (" +
                        "id VARCHAR(36) PRIMARY KEY, " +
                        "item TEXT NOT NULL, " +
                        "price DOUBLE NOT NULL, " +
                        "seller_name VARCHAR(16) NOT NULL, " +
                        "seller_uuid VARCHAR(36) NOT NULL, " +
                        "expires_at BIGINT NOT NULL)");

                statement.execute("CREATE TABLE IF NOT EXISTS expired_lots (" +
                        "id VARCHAR(36) PRIMARY KEY, " +
                        "item TEXT NOT NULL, " +
                        "price DOUBLE NOT NULL, " +
                        "seller_name VARCHAR(16) NOT NULL, " +
                        "seller_uuid VARCHAR(36) NOT NULL, " +
                        "expires_at BIGINT NOT NULL)");
            }
        } catch (Throwable t) {
            getLogger().warning("Извините, но database initialization failed! Something went wrong: " + t.getMessage());
        }
    }

    private void closeDatabase() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (Throwable t) {
            getLogger().warning("Извините, но database close failed! Something went wrong: " + t.getMessage());
        }
    }

    private String itemStackToString(ItemStack item) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
            dataOutput.writeObject(item);
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Throwable t) {
            getLogger().warning("Извините, но item serialization failed! Something went wrong: " + t.getMessage());
            return "";
        }
    }

    private ItemStack itemStackFromString(String data) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
            return (ItemStack) dataInput.readObject();
        } catch (Throwable t) {
            getLogger().warning("Извините, но item deserialization failed! Something went wrong: " + t.getMessage());
            return null;
        }
    }

    private void saveLotToDb(AuctionLot lot, boolean isExpired) {
        String table = isExpired ? "expired_lots" : "auction_lots";
        String query = "INSERT OR REPLACE INTO " + table + " (id, item, price, seller_name, seller_uuid, expires_at) VALUES (?, ?, ?, ?, ?, ?)";
        
        try (PreparedStatement ps = connection.prepareStatement(query)) {
            ps.setString(1, lot.getId().toString());
            ps.setString(2, itemStackToString(lot.getItem()));
            ps.setDouble(3, lot.getPrice());
            ps.setString(4, lot.getSellerName());
            ps.setString(5, lot.getSellerUUID().toString());
            ps.setLong(6, lot.getExpiresAt());
            ps.executeUpdate();
        } catch (Throwable t) {
            getLogger().warning("Извините, но saving lot to DB failed! Something went wrong: " + t.getMessage());
        }
    }

    private void removeLotFromDb(UUID id, boolean isExpired) {
        String table = isExpired ? "expired_lots" : "auction_lots";
        String query = "DELETE FROM " + table + " WHERE id = ?";
        
        try (PreparedStatement ps = connection.prepareStatement(query)) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        } catch (Throwable t) {
            getLogger().warning("Извините, но removing lot from DB failed! Something went wrong: " + t.getMessage());
        }
    }

    private void moveLotToExpiredInDb(AuctionLot lot) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement del = connection.prepareStatement("DELETE FROM auction_lots WHERE id = ?");
                 PreparedStatement ins = connection.prepareStatement("INSERT OR REPLACE INTO expired_lots (id, item, price, seller_name, seller_uuid, expires_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                
                del.setString(1, lot.getId().toString());
                del.executeUpdate();

                ins.setString(1, lot.getId().toString());
                ins.setString(2, itemStackToString(lot.getItem()));
                ins.setDouble(3, lot.getPrice());
                ins.setString(4, lot.getSellerName());
                ins.setString(5, lot.getSellerUUID().toString());
                ins.setLong(6, lot.getExpiresAt());
                ins.executeUpdate();

                connection.commit();
            } catch (Throwable t) {
                connection.rollback();
                getLogger().warning("Извините, но moving lot to expired failed! Something went wrong: " + t.getMessage());
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Throwable t) {
            getLogger().warning("Извините, но transaction error! Something went wrong: " + t.getMessage());
        }
    }

    private int getMaxLots(Player player) {
        List<Map<?, ?>> limitsList = getConfig().getMapList("slot-limits");
        for (Map<?, ?> entry : limitsList) {
            Object permissionObj = entry.get("permission");
            Object slotsObj = entry.get("slots");
            String permission = permissionObj != null ? permissionObj.toString() : null;
            int slots = slotsObj instanceof Number ? ((Number) slotsObj).intValue() : 10;
            if (permission == null || permission.isEmpty()) return slots;
            if (player.hasPermission(permission)) return slots;
        }
        return 10;
    }

    private void loadTranslations() {
        File file = new File(getDataFolder(), "translations.yml");
        if (!file.exists()) return;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                int colonIndex = line.indexOf(':');
                if (colonIndex < 0) continue;

                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();

                if (value.isEmpty()) continue;

                String materialKey = key
                    .replace("block.minecraft.", "")
                    .replace("item.minecraft.", "")
                    .toUpperCase();
                translations.put(materialKey, value.toLowerCase());
            }
        } catch (Throwable t) {
            getLogger().warning("Извините, но loading translations failed! Something went wrong: " + t.getMessage());
        }
    }

    private void loadAuctionLots() {
        auctionLots.clear();
        expiredLots.clear();

        try (Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("SELECT * FROM auction_lots")) {
                while (rs.next()) {
                    try {
                        UUID id = UUID.fromString(rs.getString("id"));
                        ItemStack item = itemStackFromString(rs.getString("item"));
                        double price = rs.getDouble("price");
                        String sellerName = rs.getString("seller_name");
                        UUID sellerUUID = UUID.fromString(rs.getString("seller_uuid"));
                        long expiresAt = rs.getLong("expires_at");
                        if (item != null && sellerName != null) {
                            auctionLots.put(id, new AuctionLot(id, item, price, sellerName, sellerUUID, expiresAt));
                        }
                    } catch (Throwable t) {
                        getLogger().warning("Извините, но reading auction lot row failed! Something went wrong: " + t.getMessage());
                    }
                }
            }

            try (ResultSet rs = statement.executeQuery("SELECT * FROM expired_lots")) {
                while (rs.next()) {
                    try {
                        UUID id = UUID.fromString(rs.getString("id"));
                        ItemStack item = itemStackFromString(rs.getString("item"));
                        double price = rs.getDouble("price");
                        String sellerName = rs.getString("seller_name");
                        UUID sellerUUID = UUID.fromString(rs.getString("seller_uuid"));
                        long expiresAt = rs.getLong("expires_at");
                        if (item != null && sellerName != null) {
                            AuctionLot lot = new AuctionLot(id, item, price, sellerName, sellerUUID, expiresAt);
                            expiredLots.computeIfAbsent(sellerUUID, k -> new ArrayList<>()).add(lot);
                        }
                    } catch (Throwable t) {
                        getLogger().warning("Извините, но reading expired lot row failed! Something went wrong: " + t.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            getLogger().warning("Извините, но loading auction lots failed! Something went wrong: " + t.getMessage());
        }
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return econ != null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        try {
            if (args.length == 0) {
                openAuctionMenu(player, 0);
                return true;
            }

            if (args.length >= 1 && args[0].equalsIgnoreCase("sell")) {
                if (args.length < 2) {
                    player.sendMessage("§cТы не указал цену! Используй: /ah sell <цена>");
                    return true;
                }

                double price;
                try {
                    price = Double.parseDouble(args[1]);
                } catch (NumberFormatException e) {
                    player.sendMessage("§cЦена должна быть числом! Введи корректное число.");
                    return true;
                }

                if (Double.isNaN(price) || Double.isInfinite(price) || price <= 0) {
                    player.sendMessage("§cНекорректная цена! Укажи число больше нуля.");
                    return true;
                }

                if (price > maxPrice) {
                    player.sendMessage("§cТы превысил максимальную цену лота: §e" + maxPrice + "$");
                    return true;
                }

                ItemStack itemInHand = player.getInventory().getItemInMainHand();
                if (itemInHand.getType() == Material.AIR) {
                    player.sendMessage("§cТы не можешь продавать воздух! Возьми предмет в руку.");
                    return true;
                }

                long playerLotCount = auctionLots.values().stream()
                        .filter(lot -> lot.getSellerUUID().equals(player.getUniqueId()))
                        .count();

                int maxLots = getMaxLots(player);
                if (playerLotCount >= maxLots) {
                    player.sendMessage("§cТы достиг лимита! Тебе нельзя выставить больше §e" + maxLots + " §cлотов.");
                    return true;
                }

                long durationConfigHours = getConfig().getLong("lot-duration-hours", 48);
                long durationMillis = durationConfigHours * 3600 * 1000L;
                long expiresAt = System.currentTimeMillis() + durationMillis;

                AuctionLot newLot = new AuctionLot(itemInHand, price, player, expiresAt);
                auctionLots.put(newLot.getId(), newLot);
                player.getInventory().setItemInMainHand(null);
                player.sendMessage("§aТы успешно выставил предмет на аукцион за §e" + price + "$");
                saveLotToDb(newLot, false);

                int lastPage = auctionLots.size() / 45;
                openAuctionMenu(player, lastPage);
                return true;

            } else if (args.length >= 1 && args[0].equalsIgnoreCase("help")) {
                player.sendMessage("§aИспользование команд:");
                player.sendMessage("§a/ah §7- Открыть меню аукциона");
                player.sendMessage("§a/ah sell §e<цена> §7- Выставить предмет из руки");
                player.sendMessage("§a/ah search §d<название> §7- Найти предмет по имени");
                player.sendMessage("§a/ah player §3<ник> §7- Посмотреть товары игрока");
                player.sendMessage("§a/ah my §7- Твои активные лоты");
                player.sendMessage("§a/ah expired §7- Твои истёкшие лоты");
                return true;

            } else if (args.length >= 2 && args[0].equalsIgnoreCase("search")) {
                openSearchMenu(player, args[1].toLowerCase(), 0);
                return true;

            } else if (args.length >= 2 && args[0].equalsIgnoreCase("player")) {
                openPlayerMenu(player, args[1], 0);
                return true;

            } else if (args.length >= 1 && args[0].equalsIgnoreCase("expired")) {
                openExpiredMenu(player, 0);
                return true;

            } else if (args.length >= 1 && args[0].equalsIgnoreCase("my")) {
                openOwnMenu(player, 0);
                return true;
            }

            player.sendMessage("§cНеизвестная команда! Введи /ah help для помощи.");
        } catch (Throwable t) {
            getLogger().warning("Извините, но command execution error! Something went wrong: " + t.getMessage());
        }
        return true;
    }

    private void openAuctionMenu(Player player, int page) {
        try {
            UUID playerUUID = player.getUniqueId();
            Inventory inv = Bukkit.createInventory(null, 54, guiTitlePrefix + (page + 1));
            Map<Integer, UUID> slotMap = new HashMap<>();
            List<AuctionLot> allLots = new ArrayList<>(auctionLots.values());

            int startIndex = page * 45;
            int endIndex = Math.min(startIndex + 45, allLots.size());

            int currentSlot = 0;
            for (int i = startIndex; i < endIndex; i++) {
                AuctionLot lot = allLots.get(i);
                ItemStack displayItem = lot.getItem().clone();
                ItemMeta meta = displayItem.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
                    lore.add("");
                    lore.add("§7Price / Цена: §e" + lot.getPrice() + "$");
                    lore.add("§7Seller / Продавец: §a" + lot.getSellerName());
                    lore.add("§7Expires in / Истёк через: " + formatTimeLeft(lot));
                    lore.add("");
                    lore.add(lot.getSellerUUID().equals(playerUUID) ? "§c▶ Click to take back / Забрать" : "§e▶ Click to buy / Купить");
                    meta.setLore(lore);
                    displayItem.setItemMeta(meta);
                }
                inv.setItem(currentSlot, displayItem);
                slotMap.put(currentSlot, lot.getId());
                currentSlot++;
            }

            inv.setItem(45, createNavigationItem(Material.SPECTRAL_ARROW, "§a◀ Previous page"));
            inv.setItem(53, createNavigationItem(Material.SPECTRAL_ARROW, "§aNext page ▶"));
            inv.setItem(47, createNavigationItem(Material.ENDER_CHEST, "§aExpired лоты"));
            inv.setItem(51, createNavigationItem(Material.CHEST, "§aMy лоты"));

            player.openInventory(inv);
            playerCurrentPage.put(playerUUID, page);
            playerSlotToLotMap.put(playerUUID, slotMap);
        } catch (Throwable t) {
            getLogger().warning("Извините, но failed to open auction menu! Something went wrong: " + t.getMessage());
        }
    }

    private void openSearchMenu(Player player, String query, int page) {
        try {
            UUID playerUUID = player.getUniqueId();
            List<AuctionLot> filtered = new ArrayList<>();

            for (AuctionLot lot : auctionLots.values()) {
                String itemName = lot.getItem().getType().name().toLowerCase();
                String displayName = lot.getItem().hasItemMeta() && lot.getItem().getItemMeta().hasDisplayName()
                        ? lot.getItem().getItemMeta().getDisplayName().toLowerCase() : "";
                String russianName = translations.getOrDefault(lot.getItem().getType().name(), "").toLowerCase();
                if (itemName.contains(query) || displayName.contains(query) || russianName.contains(query)) {
                    filtered.add(lot);
                }
            }

            if (filtered.isEmpty()) {
                player.sendMessage("§cПо твоему запросу ничего не найдено: §e" + query);
                return;
            }

            int startIndex = page * 45;
            int endIndex = Math.min(startIndex + 45, filtered.size());
            Inventory inv = Bukkit.createInventory(null, 54, guiTitlePrefix1 + query + " | Page " + (page + 1));
            Map<Integer, UUID> slotMap = new HashMap<>();

            int currentSlot = 0;
            for (int i = startIndex; i < endIndex; i++) {
                AuctionLot lot = filtered.get(i);
                ItemStack displayItem = lot.getItem().clone();
                ItemMeta meta = displayItem.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
                    lore.add("");
                    lore.add("§7Price / Цена: §e" + lot.getPrice() + "$");
                    lore.add("§7Seller / Продавец: §a" + lot.getSellerName());
                    lore.add("§7Expires in / Истёк через: " + formatTimeLeft(lot));
                    lore.add("");
                    lore.add(lot.getSellerUUID().equals(playerUUID) ? "§c▶ Click to take back / Забрать" : "§e▶ Click to buy / Купить");
                    meta.setLore(lore);
                    displayItem.setItemMeta(meta);
                }
                inv.setItem(currentSlot, displayItem);
                slotMap.put(currentSlot, lot.getId());
                currentSlot++;
            }

            inv.setItem(45, createNavigationItem(Material.SPECTRAL_ARROW, "§a◀ Previous page"));
            inv.setItem(53, createNavigationItem(Material.SPECTRAL_ARROW, "§aNext page ▶"));
            inv.setItem(47, createNavigationItem(Material.ENDER_CHEST, "§aExpired лоты"));
            inv.setItem(51, createNavigationItem(Material.CHEST, "§aMy лоты"));

            player.openInventory(inv);
            playerCurrentPage.put(playerUUID, page);
            playerSlotToLotMap.put(playerUUID, slotMap);
        } catch (Throwable t) {
            getLogger().warning("Извините, но failed to open search menu! Something went wrong: " + t.getMessage());
        }
    }

    private void openPlayerMenu(Player player, String targetName, int page) {
        try {
            UUID playerUUID = player.getUniqueId();
            if (!Bukkit.getOfflinePlayer(targetName).hasPlayedBefore()) {
                player.sendMessage("§cИгрок §e" + targetName + " §cникогда здесь не играл!");
                return;
            }

            List<AuctionLot> filtered = new ArrayList<>();
            for (AuctionLot lot : auctionLots.values()) {
                if (lot.getSellerName().equalsIgnoreCase(targetName)) {
                    filtered.add(lot);
                }
            }

            if (filtered.isEmpty()) {
                player.sendMessage("§cУ игрока §e" + targetName + " §cнет активных лотов.");
                return;
            }

            int startIndex = page * 45;
            int endIndex = Math.min(startIndex + 45, filtered.size());
            Inventory inv = Bukkit.createInventory(null, 54, guiTitlePrefix2 + targetName + " | Page " + (page + 1));
            Map<Integer, UUID> slotMap = new HashMap<>();

            int currentSlot = 0;
            for (int i = startIndex; i < endIndex; i++) {
                AuctionLot lot = filtered.get(i);
                ItemStack displayItem = lot.getItem().clone();
                ItemMeta meta = displayItem.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
                    lore.add("");
                    lore.add("§7Price / Цена: §e" + lot.getPrice() + "$");
                    lore.add("§7Seller / Продавец: §a" + lot.getSellerName());
                    lore.add("§7Expires in / Истёк через: " + formatTimeLeft(lot));
                    lore.add("");
                    lore.add(lot.getSellerUUID().equals(playerUUID) ? "§c▶ Click to take back / Забрать" : "§e▶ Click to buy / Купить");
                    meta.setLore(lore);
                    displayItem.setItemMeta(meta);
                }
                inv.setItem(currentSlot, displayItem);
                slotMap.put(currentSlot, lot.getId());
                currentSlot++;
            }

            inv.setItem(45, createNavigationItem(Material.SPECTRAL_ARROW, "§a◀ Previous page"));
            inv.setItem(53, createNavigationItem(Material.SPECTRAL_ARROW, "§aNext page ▶"));
            inv.setItem(47, createNavigationItem(Material.ENDER_CHEST, "§aExpired лоты"));
            inv.setItem(51, createNavigationItem(Material.CHEST, "§aMy лоты"));

            player.openInventory(inv);
            playerCurrentPage.put(playerUUID, page);
            playerSlotToLotMap.put(playerUUID, slotMap);
        } catch (Throwable t) {
            getLogger().warning("Извините, но failed to open player menu! Something went wrong: " + t.getMessage());
        }
    }

    private void openExpiredMenu(Player player, int page) {
        try {
            UUID playerUUID = player.getUniqueId();
            List<AuctionLot> expired = expiredLots.getOrDefault(playerUUID, new ArrayList<>());

            if (expired.isEmpty()) {
                player.sendMessage("§aУ тебя нет истёкших лотов.");
                return;
            }

            int startIndex = page * 45;
            int endIndex = Math.min(startIndex + 45, expired.size());
            Inventory inv = Bukkit.createInventory(null, 54, guiTitlePrefix3 + (page + 1));
            Map<Integer, UUID> slotMap = new HashMap<>();

            int currentSlot = 0;
            for (int i = startIndex; i < endIndex; i++) {
                AuctionLot lot = expired.get(i);
                ItemStack displayItem = lot.getItem().clone();
                ItemMeta meta = displayItem.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
                    lore.add("");
                    lore.add("§7Old price / Старая цена: §e" + lot.getPrice() + "$");
                    lore.add("§cLot expired / Истёк");
                    lore.add("");
                    lore.add("§e▶ Click to claim / Забрать");
                    meta.setLore(lore);
                    displayItem.setItemMeta(meta);
                }
                inv.setItem(currentSlot, displayItem);
                slotMap.put(currentSlot, lot.getId());
                currentSlot++;
            }

            inv.setItem(45, createNavigationItem(Material.SPECTRAL_ARROW, "§a◀ Previous page"));
            inv.setItem(53, createNavigationItem(Material.SPECTRAL_ARROW, "§aNext page ▶"));

            player.openInventory(inv);
            playerCurrentPage.put(playerUUID, page);
            playerSlotToLotMap.put(playerUUID, slotMap);
        } catch (Throwable t) {
            getLogger().warning("Извините, но failed to open expired menu! Something went wrong: " + t.getMessage());
        }
    }

    private void openOwnMenu(Player player, int page) {
        try {
            UUID playerUUID = player.getUniqueId();
            List<AuctionLot> filtered = new ArrayList<>();
            for (AuctionLot lot : auctionLots.values()) {
                if (lot.getSellerUUID().equals(playerUUID)) {
                    filtered.add(lot);
                }
            }

            if (filtered.isEmpty()) {
                player.sendMessage("§cУ тебя нет активных лотов.");
                return;
            }

            int startIndex = page * 45;
            int endIndex = Math.min(startIndex + 45, filtered.size());
            Inventory inv = Bukkit.createInventory(null, 54, guiTitlePrefix4 + (page + 1));
            Map<Integer, UUID> slotMap = new HashMap<>();

            int currentSlot = 0;
            for (int i = startIndex; i < endIndex; i++) {
                AuctionLot lot = filtered.get(i);
                ItemStack displayItem = lot.getItem().clone();
                ItemMeta meta = displayItem.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
                    lore.add("");
                    lore.add("§7Price / Цена: §e" + lot.getPrice() + "$");
                    lore.add("§7Expires in / Истёк через: " + formatTimeLeft(lot));
                    lore.add("");
                    lore.add("§c▶ Click to cancel / Забрать");
                    meta.setLore(lore);
                    displayItem.setItemMeta(meta);
                }
                inv.setItem(currentSlot, displayItem);
                slotMap.put(currentSlot, lot.getId());
                currentSlot++;
            }

            inv.setItem(45, createNavigationItem(Material.SPECTRAL_ARROW, "§a◀ Previous page"));
            inv.setItem(53, createNavigationItem(Material.SPECTRAL_ARROW, "§aNext page ▶"));

            player.openInventory(inv);
            playerCurrentPage.put(playerUUID, page);
            playerSlotToLotMap.put(playerUUID, slotMap);
        } catch (Throwable t) {
            getLogger().warning("Извините, но failed to open own menu! Something went wrong: " + t.getMessage());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        try {
            if (args.length == 1) {
                completions.add("sell");
                completions.add("search");
                completions.add("player");
                completions.add("my");
                completions.add("expired");
                completions.add("help");

            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("sell")) {
                    completions.add("<price>");

                } else if (args[0].equalsIgnoreCase("search")) {
                    String input = args[1].toLowerCase();
                    for (Map.Entry<String, String> entry : translations.entrySet()) {
                        if (entry.getValue().startsWith(input)) {
                            completions.add(entry.getValue());
                        }
                    }
                    if (completions.isEmpty()) {
                        for (Material mat : Material.values()) {
                            String name = mat.name().toLowerCase();
                            if (name.startsWith(input)) completions.add(name);
                        }
                    }

                } else if (args[0].equalsIgnoreCase("player")) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        completions.add(p.getName());
                    }
                }
            }
        } catch (Throwable t) {
            getLogger().warning("Извините, но tab completion error! Something went wrong: " + t.getMessage());
        }
        return completions;
    }

    private ItemStack createNavigationItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        try {
            String title = event.getView().getTitle();
            if (!title.startsWith(guiTitlePrefix) &&
                !title.startsWith(guiTitlePrefix1) &&
                !title.startsWith(guiTitlePrefix2) &&
                !title.startsWith(guiTitlePrefix3) &&
                !title.startsWith(guiTitlePrefix4)) return;

            // Блокируем ЛЮБЫЕ клики и перемещения (включая Shift-click из инвентаря игрока)
            event.setCancelled(true);

            if (!(event.getWhoClicked() instanceof Player)) return;
            Player buyer = (Player) event.getWhoClicked();

            // Клик засчитывается только если кликнули по верхнему инвентарю
            if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;

            UUID buyerUUID = buyer.getUniqueId();
            int slot = event.getRawSlot();

            if (slot < 0 || slot >= 54) return;

            int currentPage = playerCurrentPage.getOrDefault(buyerUUID, 0);
            Map<Integer, UUID> slotMap = playerSlotToLotMap.get(buyerUUID);

            if (slotMap != null && slotMap.containsKey(slot)) {
                if (title.startsWith(guiTitlePrefix3)) {
                    handleExpiredLotClick(buyer, buyerUUID, slot, currentPage, slotMap);
                } else {
                    handleLotClick(buyer, buyerUUID, slot, currentPage, slotMap, title);
                }
                return;
            }

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null) return;
            Material type = clicked.getType();

            if (type == Material.SPECTRAL_ARROW) {
                if (slot == 45 && currentPage > 0) {
                    navigateBack(buyer, title, currentPage);
                } else if (slot == 53) {
                    navigateForward(buyer, title, currentPage);
                }
            } else if (type == Material.ENDER_CHEST && slot == 47) {
                openExpiredMenu(buyer, 0);
            } else if (type == Material.CHEST && slot == 51) {
                openOwnMenu(buyer, 0);
            }
        } catch (Throwable t) {
            getLogger().warning("Извините, но inventory click error! Something went wrong: " + t.getMessage());
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        try {
            String title = event.getView().getTitle();
            if (title.startsWith(guiTitlePrefix) ||
                title.startsWith(guiTitlePrefix1) ||
                title.startsWith(guiTitlePrefix2) ||
                title.startsWith(guiTitlePrefix3) ||
                title.startsWith(guiTitlePrefix4)) {
                // Блокируем перетаскивание предметов зажатой кнопкой мыши
                event.setCancelled(true);
            }
        } catch (Throwable t) {
            getLogger().warning("Извините, но inventory drag error! Something went wrong: " + t.getMessage());
        }
    }

    private void navigateBack(Player player, String title, int currentPage) {
        if (title.startsWith(guiTitlePrefix1)) {
            String query = title.replace(guiTitlePrefix1, "").split(" \\| ")[0];
            openSearchMenu(player, query, currentPage - 1);
        } else if (title.startsWith(guiTitlePrefix2)) {
            String targetName = title.replace(guiTitlePrefix2, "").split(" \\| ")[0];
            openPlayerMenu(player, targetName, currentPage - 1);
        } else if (title.startsWith(guiTitlePrefix3)) {
            openExpiredMenu(player, currentPage - 1);
        } else if (title.startsWith(guiTitlePrefix4)) {
            openOwnMenu(player, currentPage - 1);
        } else {
            openAuctionMenu(player, currentPage - 1);
        }
    }

    private void navigateForward(Player player, String title, int currentPage) {
        if (title.startsWith(guiTitlePrefix1)) {
            String query = title.replace(guiTitlePrefix1, "").split(" \\| ")[0];
            openSearchMenu(player, query, currentPage + 1);
        } else if (title.startsWith(guiTitlePrefix2)) {
            String targetName = title.replace(guiTitlePrefix2, "").split(" \\| ")[0];
            openPlayerMenu(player, targetName, currentPage + 1);
        } else if (title.startsWith(guiTitlePrefix3)) {
            openExpiredMenu(player, currentPage + 1);
        } else if (title.startsWith(guiTitlePrefix4)) {
            openOwnMenu(player, currentPage + 1);
        } else {
            openAuctionMenu(player, currentPage + 1);
        }
    }

    private void handleLotClick(Player buyer, UUID buyerUUID, int slot, int currentPage, Map<Integer, UUID> slotMap, String title) {
        AuctionLot lot = null;
        try {
            UUID lotId = slotMap.get(slot);
            lot = auctionLots.remove(lotId);

            if (lot == null) {
                buyer.sendMessage("§cЭтот предмет уже кто-то купил или его сняли с продажи!");
                openAuctionMenu(buyer, currentPage);
                return;
            }

            if (buyerUUID.equals(lot.getSellerUUID())) {
                giveItemSafely(buyer, lot.getItem());
                removeLotFromDb(lot.getId(), false);
                buyer.sendMessage("§aТы успешно забрал свой лот обратно.");
                if (title.startsWith(guiTitlePrefix4)) {
                    openOwnMenu(buyer, currentPage);
                } else {
                    openAuctionMenu(buyer, currentPage);
                }
                return;
            }

            if (!hasInventorySpace(buyer, lot.getItem())) {
                auctionLots.put(lot.getId(), lot);
                buyer.sendMessage("§cТвой инвентарь полон! Освободи место перед покупкой.");
                return;
            }

            double lotPrice = lot.getPrice();

            if (!econ.has(buyer, lotPrice)) {
                auctionLots.put(lot.getId(), lot);
                buyer.sendMessage("§cУ тебя недостаточно денег! Тебе нужно: §e" + lotPrice + "$");
                openAuctionMenu(buyer, currentPage);
                return;
            }

            econ.withdrawPlayer(buyer, lotPrice);
            econ.depositPlayer(Bukkit.getOfflinePlayer(lot.getSellerUUID()), lotPrice);

            giveItemSafely(buyer, lot.getItem());
            removeLotFromDb(lot.getId(), false);
            buyer.sendMessage("§aТы успешно купил предмет за §e" + lotPrice + "$");

            OfflinePlayer seller = Bukkit.getOfflinePlayer(lot.getSellerUUID());
            if (seller.isOnline() && seller.getPlayer() != null) {
                seller.getPlayer().sendMessage("§aТвой предмет §e" + lot.getItem().getType().name()
                        + " §aбыл куплен игроком §b" + buyer.getName() + " §aза §e" + lotPrice + "$");
            }

            openAuctionMenu(buyer, currentPage);

        } catch (Throwable t) {
            getLogger().warning("Извините, но handle lot click error! Something went wrong: " + t.getMessage());
            if (lot != null) {
                auctionLots.put(lot.getId(), lot);
            }
        }
    }

    private void handleExpiredLotClick(Player buyer, UUID buyerUUID, int slot, int currentPage, Map<Integer, UUID> slotMap) {
        try {
            UUID lotId = slotMap.get(slot);
            List<AuctionLot> expired = expiredLots.get(buyerUUID);
            if (expired == null) return;

            AuctionLot foundLot = null;
            for (AuctionLot lot : expired) {
                if (lot.getId().equals(lotId)) {
                    foundLot = lot;
                    break;
                }
            }

            if (foundLot == null) {
                buyer.sendMessage("§cТы уже забрал этот истёкший предмет!");
                openExpiredMenu(buyer, currentPage);
                return;
            }

            if (!hasInventorySpace(buyer, foundLot.getItem())) {
                buyer.sendMessage("§cТвой инвентарь полон! Освободи место.");
                return;
            }

            expired.remove(foundLot);
            if (expired.isEmpty()) expiredLots.remove(buyerUUID);

            giveItemSafely(buyer, foundLot.getItem());
            removeLotFromDb(foundLot.getId(), true);
            buyer.sendMessage("§aТы успешно забрал свой истёкший предмет.");
            openExpiredMenu(buyer, currentPage);
        } catch (Throwable t) {
            getLogger().warning("Извините, но handle expired lot click error! Something went wrong: " + t.getMessage());
        }
    }

    private boolean hasInventorySpace(Player player, ItemStack item) {
        try {
            Inventory tempInv = Bukkit.createInventory(null, 36);
            for (int i = 0; i < 36; i++) {
                ItemStack slot = player.getInventory().getItem(i);
                if (slot != null) tempInv.setItem(i, slot.clone());
            }
            return tempInv.addItem(item.clone()).isEmpty();
        } catch (Throwable t) {
            getLogger().warning("Извините, но inventory space check failed! Something went wrong: " + t.getMessage());
            return false;
        }
    }

    private void giveItemSafely(Player player, ItemStack item) {
        try {
            Map<Integer, ItemStack> leftOver = player.getInventory().addItem(item.clone());
            for (ItemStack drop : leftOver.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        } catch (Throwable t) {
            getLogger().warning("Извините, но give item failed! Something went wrong: " + t.getMessage());
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        try {
            UUID playerUUID = event.getPlayer().getUniqueId();
            playerCurrentPage.remove(playerUUID);
            playerSlotToLotMap.remove(playerUUID);
        } catch (Throwable t) {
            getLogger().warning("Извините, но inventory close error! Something went wrong: " + t.getMessage());
        }
    }
}
