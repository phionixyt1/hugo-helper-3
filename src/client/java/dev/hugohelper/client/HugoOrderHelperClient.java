package dev.hugohelper.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manual auction/order assistant. Reads what is already on screen and reports it
 * to chat. Never clicks, buys, confirms, or delivers anything.
 */
public final class HugoOrderHelperClient implements ClientModInitializer {
    private static final Pattern ORDER_PRICE = Pattern.compile("Price per item:\\s*\\$\\s*([0-9][0-9,.]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELIVERED = Pattern.compile("([0-9][0-9,.]*)\\s*/\\s*([0-9][0-9,.]*)\\s*Delivered", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUCTION_PRICE = Pattern.compile("Price:\\s*\\$\\s*([0-9][0-9,.]*)", Pattern.CASE_INSENSITIVE);

    private static final Map<String, OrderQuote> ORDERS = new HashMap<>();
    private static final Set<String> ALERTED = new HashSet<>();
    private static final NumberFormat INTEGER = NumberFormat.getIntegerInstance(Locale.US);

    private static final long MIN_PROFIT = 1_000L;
    private static final double MIN_MARGIN_PERCENT = 5.0;

    private static Screen lastScreen = null;
    private static int bootTicks = 0;
    private static boolean announced = false;

    @Override
    public void onInitializeClient() {
        System.out.println("[Hugo Helper] Initializer running.");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Announce once, a couple of seconds in, so we know the tick hook works.
            bootTicks++;
            if (!announced && bootTicks > 40 && client.player != null) {
                announced = true;
                report(client, "loaded - open /order then /ah", false);
            }

            Screen screen = client.screen;
            if (screen == null) {
                lastScreen = null;
                return;
            }
            if (screen == lastScreen) return;
            lastScreen = screen;

            if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return;

            AbstractContainerMenu menu = containerScreen.getMenu();
            List<Slot> slots = menu.slots;
            int containerSlots = Math.max(0, slots.size() - 36);
            String title = ChatFormatting.stripFormatting(screen.getTitle().getString()).toLowerCase(Locale.ROOT);

            boolean orders = false;
            boolean auction = false;
            String firstTip = null;

            for (int i = 0; i < containerSlots && i < slots.size(); i++) {
                ItemStack stack = slots.get(i).getItem();
                if (stack.isEmpty()) continue;
                List<String> lines = tooltipLines(stack);
                if (firstTip == null && !lines.isEmpty()) firstTip = lines.get(0);
                for (String line : lines) {
                    String l = line.toLowerCase(Locale.ROOT);
                    if (l.startsWith("price per item")) orders = true;
                    if (l.startsWith("seller:") || l.startsWith("expires in:")) auction = true;
                }
            }

            if (orders || title.contains("order")) {
                scanOrders(slots, containerSlots);
                report(client, "Orders: " + ORDERS.size() + " cached (slots=" + containerSlots + ")", false);
                return;
            }

            if (auction || title.contains("auktion")) {
                scanAndReportAuctions(client, slots, containerSlots);
                return;
            }

            report(client, "container title='" + title + "' slots=" + containerSlots
                + (firstTip == null ? "" : " tip='" + firstTip + "'"), false);
        });
    }

    private static void scanOrders(List<Slot> slots, int containerSlots) {
        for (int i = 0; i < containerSlots && i < slots.size(); i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;

            List<String> lines = tooltipLines(stack);
            Long price = findMoney(lines, ORDER_PRICE);
            long remaining = findRemaining(lines);
            if (price == null || price <= 0 || remaining <= 0) continue;

            String key = itemKey(stack);
            if (key.isEmpty()) continue;
            ORDERS.merge(key, new OrderQuote(displayName(lines, stack), key, price, remaining, hasEnchantLines(lines)),
                OrderQuote::betterOf);
        }
    }

    private static void scanAndReportAuctions(Minecraft client, List<Slot> slots, int containerSlots) {
        int matches = 0;
        int blocked = 0;
        Profit best = null;

        for (int i = 0; i < containerSlots && i < slots.size(); i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;

            List<String> lines = tooltipLines(stack);
            Long auctionPrice = findMoney(lines, AUCTION_PRICE);
            if (auctionPrice == null || auctionPrice <= 0) continue;

            String key = itemKey(stack);
            OrderQuote order = ORDERS.get(key);
            if (order == null) continue;

            if (order.enchanted != hasEnchantLines(lines)) {
                blocked++;
                continue;
            }

            long quantity = Math.min(Math.max(1, stack.getCount()), order.remaining);
            long gross = quantity * order.pricePerItem;
            long profit = gross - auctionPrice;
            double margin = auctionPrice == 0 ? 0 : (profit * 100.0 / auctionPrice);
            if (profit < MIN_PROFIT || margin < MIN_MARGIN_PERCENT) continue;

            matches++;
            Profit candidate = new Profit(displayName(lines, stack), key, quantity, auctionPrice, gross, profit, margin);
            if (best == null || candidate.profit > best.profit) best = candidate;
        }

        StringBuilder sb = new StringBuilder("Auctions: ").append(matches).append(" profitable");
        if (best != null) sb.append("  best ").append(best.label).append(" +$").append(INTEGER.format(best.profit));
        if (blocked > 0) sb.append("  (").append(blocked).append(" enchant mismatch)");
        if (ORDERS.isEmpty()) sb.append("  - open /order first");
        sb.append("  slots=").append(containerSlots);
        report(client, sb.toString(), best != null);

        if (best != null) {
            String fingerprint = best.key + ":" + best.auctionPrice + ":" + best.quantity;
            if (ALERTED.add(fingerprint) && client.player != null) {
                client.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.6f);
            }
        }
    }

    private static void report(Minecraft client, String message, boolean alert) {
        if (client.player == null) return;
        client.player.displayClientMessage(
            Component.literal("[Hugo Helper] " + message)
                .withStyle(alert ? ChatFormatting.GREEN : ChatFormatting.AQUA),
            false
        );
        System.out.println("[Hugo Helper] " + message);
    }

    private static String itemKey(ItemStack stack) {
        if (stack.isEmpty()) return "";
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    private static List<String> tooltipLines(ItemStack stack) {
        Minecraft client = Minecraft.getInstance();
        List<Component> tooltip = Screen.getTooltipFromItem(client, stack);
        List<String> lines = new ArrayList<>(tooltip.size());
        for (Component line : tooltip) lines.add(ChatFormatting.stripFormatting(line.getString()).trim());
        return lines;
    }

    private static boolean hasEnchantLines(List<String> lines) {
        for (String line : lines) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.startsWith("price") || l.startsWith("seller") || l.startsWith("expires")) continue;
            if (l.contains("delivered") || l.contains("paid out")) continue;
            if (l.startsWith("click to deliver") || l.endsWith("'s order")) continue;
            if (looksLikeEnchant(line)) return true;
        }
        return false;
    }

    private static boolean looksLikeEnchant(String line) {
        return line.matches("^[A-Za-z' ]+\\s+(I|II|III|IV|V|VI|VII|VIII|IX|X)$")
            || line.equalsIgnoreCase("Mending")
            || line.equalsIgnoreCase("Silk Touch")
            || line.equalsIgnoreCase("Vanishing Curse")
            || line.equalsIgnoreCase("Binding Curse");
    }

    private static String displayName(List<String> lines, ItemStack stack) {
        for (String line : lines) {
            String l = line.toLowerCase(Locale.ROOT);
            if (l.startsWith("price") || l.startsWith("seller") || l.startsWith("expires")) continue;
            if (l.contains("delivered") || l.contains("paid out")) continue;
            if (l.startsWith("click to deliver") || l.endsWith("'s order")) continue;
            if (looksLikeEnchant(line)) continue;
            if (!line.isBlank()) return line.trim();
        }
        return stack.getHoverName().getString();
    }

    private static Long findMoney(List<String> lines, Pattern pattern) {
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) return parseNumber(matcher.group(1));
        }
        return null;
    }

    private static long findRemaining(List<String> lines) {
        for (String line : lines) {
            Matcher matcher = DELIVERED.matcher(line);
            if (matcher.find()) {
                long delivered = parseNumber(matcher.group(1));
                long total = parseNumber(matcher.group(2));
                return Math.max(0, total - delivered);
            }
        }
        return Long.MAX_VALUE;
    }

    private static long parseNumber(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? 0 : Long.parseLong(digits);
    }

    private record OrderQuote(String label, String key, long pricePerItem, long remaining, boolean enchanted) {
        private static OrderQuote betterOf(OrderQuote a, OrderQuote b) {
            return b.pricePerItem > a.pricePerItem ? b : a;
        }
    }

    private record Profit(String label, String key, long quantity, long auctionPrice, long gross,
                          long profit, double margin) {
    }
}
