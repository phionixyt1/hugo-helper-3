package dev.hugohelper.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.sounds.SoundEvents;

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

public final class HugoOrderHelperClient implements ClientModInitializer {
    private static final Pattern ORDER_PRICE = Pattern.compile("Price per item:\\s*\\$\\s*([0-9][0-9,.]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELIVERED = Pattern.compile("([0-9][0-9,.]*)\\s*/\\s*([0-9][0-9,.]*)\\s*Delivered", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUCTION_PRICE = Pattern.compile("Price:\\s*\\$\\s*([0-9][0-9,.]*)", Pattern.CASE_INSENSITIVE);

    private static final Map<String, OrderQuote> ORDERS = new HashMap<>();
    private static final Set<String> ALERTED = new HashSet<>();
    private static final NumberFormat INTEGER = NumberFormat.getIntegerInstance(Locale.US);

    // Safe defaults. Lower or raise these to taste.
    private static final long MIN_PROFIT = 1_000L;
    private static final double MIN_MARGIN_PERCENT = 5.0;

    @Override
    public void onInitializeClient() {
        ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof AbstractContainerScreen<?>) {
                ScreenEvents.afterRender(screen).register(HugoOrderHelperClient::afterRender);
            }
        });
    }

    private static void afterRender(Screen rawScreen, GuiGraphics graphics, int mouseX, int mouseY, float tickDelta) {
        if (!(rawScreen instanceof AbstractContainerScreen<?> screen)) return;

        String title = normalize(screen.getTitle().getString());
        AbstractContainerMenu menu = screen.getMenu();
        List<Slot> slots = menu.slots;
        int playerSlots = Math.min(36, slots.size());
        int containerSlots = Math.max(0, slots.size() - playerSlots);

        if (title.equals("orders")) {
            scanOrders(slots, containerSlots);
            drawPanel(graphics, List.of(
                "Hugo Helper",
                ORDERS.size() + " order price" + (ORDERS.size() == 1 ? "" : "s") + " cached"
            ), 0x55FFFF);
            return;
        }

        if (title.equals("auktionen")) {
            scanAndRenderAuctions(graphics, slots, containerSlots);
            return;
        }

        if (title.equals("bestatigen")) {
            renderPurchaseConfirmation(graphics, slots, containerSlots);
            return;
        }

        if (title.equals("order beliefern")) {
            renderDeliveryConfirmation(graphics, slots, containerSlots);
        }
    }

    private static void scanOrders(List<Slot> slots, int containerSlots) {
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;

            List<String> lines = tooltipLines(stack);
            Long price = findMoney(lines, ORDER_PRICE);
            long remaining = findRemaining(lines);
            if (price == null || price <= 0 || remaining <= 0) continue;

            String item = orderItemName(stack, lines);
            if (!item.isBlank()) {
                ORDERS.merge(key(item), new OrderQuote(item, price, remaining), OrderQuote::betterOf);
            }
        }
    }

    private static void scanAndRenderAuctions(GuiGraphics graphics, List<Slot> slots, int containerSlots) {
        int matches = 0;
        Profit best = null;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;

            Profit profit = calculateAuctionProfit(stack);
            if (profit == null || !profit.qualifies()) continue;
            matches++;
            if (best == null || profit.profit > best.profit) best = profit;
        }

        List<String> panel = new ArrayList<>();
        panel.add("Hugo Helper");
        if (ORDERS.isEmpty()) {
            panel.add("Open /order first to cache prices");
        } else {
            panel.add(matches + " profitable listing" + (matches == 1 ? "" : "s"));
            if (best != null) {
                panel.add("Best: " + best.item + " +$" + INTEGER.format(best.profit));
            }
        }
        drawPanel(graphics, panel, ORDERS.isEmpty() ? 0xFFAA00 : 0x55FF55);

        if (best != null) {
            String fingerprint = best.item + ":" + best.auctionPrice + ":" + best.quantity;
            if (ALERTED.add(fingerprint)) {
                Minecraft client = Minecraft.getInstance();
                if (client.player != null) {
                    client.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.6f);
                }
            }
        }
    }

    private static void renderPurchaseConfirmation(GuiGraphics graphics, List<Slot> slots, int containerSlots) {
        Profit best = null;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            Profit profit = calculateAuctionProfit(stack);
            if (profit != null && (best == null || profit.profit > best.profit)) best = profit;
        }
        if (best == null) return;

        int color = best.qualifies() ? 0x55FF55 : 0xFF5555;
        drawPanel(graphics, List.of(
            best.item + " x" + best.quantity,
            "Order gross: $" + INTEGER.format(best.gross),
            "Auction cost: $" + INTEGER.format(best.auctionPrice),
            "Est. profit: $" + INTEGER.format(best.profit),
            "(before delivery tax)"
        ), color);
    }

    private static void renderDeliveryConfirmation(GuiGraphics graphics, List<Slot> slots, int containerSlots) {
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            List<String> lines = tooltipLines(stack);
            String receive = findLine(lines, "you receive:");
            String taxes = findLine(lines, "taxes:");
            String quantity = findLine(lines, "you deliver:");
            if (receive != null) {
                List<String> panel = new ArrayList<>();
                panel.add(quantity == null ? "Delivery" : quantity);
                if (taxes != null) panel.add(taxes);
                panel.add(receive);
                drawPanel(graphics, panel, 0x55FFFF);
                return;
            }
        }
    }

    private static Profit calculateAuctionProfit(ItemStack stack) {
        List<String> lines = tooltipLines(stack);
        Long auctionPrice = findMoney(lines, AUCTION_PRICE);
        if (auctionPrice == null || auctionPrice <= 0) return null;

        String item = auctionItemName(stack, lines);
        OrderQuote order = ORDERS.get(key(item));
        if (order == null) return null;

        int stackCount = Math.max(1, stack.getCount());
        long quantity = Math.min(stackCount, order.remaining);
        long gross = quantity * order.pricePerItem;
        long profit = gross - auctionPrice;
        double margin = auctionPrice == 0 ? 0 : (profit * 100.0 / auctionPrice);
        return new Profit(item, quantity, auctionPrice, gross, profit, margin);
    }

    private static List<String> tooltipLines(ItemStack stack) {
        Minecraft client = Minecraft.getInstance();
        List<Component> tooltip = Screen.getTooltipFromItem(client, stack);
        List<String> lines = new ArrayList<>(tooltip.size());
        for (Component line : tooltip) lines.add(ChatFormatting.stripFormatting(line.getString()));
        return lines;
    }

    private static String orderItemName(ItemStack stack, List<String> lines) {
        for (String line : lines) {
            String lower = normalize(line);
            if (lower.endsWith("'s order") || lower.contains("price per item") || lower.contains("delivered")
                || lower.contains("paid out") || lower.startsWith("click to deliver")) continue;
            if (!line.isBlank()) return line.trim();
        }
        return stack.getHoverName().getString();
    }

    private static String auctionItemName(ItemStack stack, List<String> lines) {
        for (String line : lines) {
            String lower = normalize(line);
            if (lower.startsWith("price:") || lower.startsWith("seller:") || lower.startsWith("expires in:")) continue;
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

    private static String findLine(List<String> lines, String prefix) {
        for (String line : lines) {
            if (normalize(line).startsWith(prefix)) return line.trim();
        }
        return null;
    }

    private static long parseNumber(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? 0 : Long.parseLong(digits);
    }

    private static String normalize(String text) {
        return ChatFormatting.stripFormatting(text).trim().toLowerCase(Locale.ROOT);
    }

    private static String key(String item) {
        return normalize(item).replaceAll("\\s+", " ");
    }

    private static void drawPanel(GuiGraphics graphics, List<String> lines, int accent) {
        Minecraft client = Minecraft.getInstance();
        int width = 0;
        for (String line : lines) width = Math.max(width, client.font.width(line));
        int x = 6;
        int y = 6;
        int height = 6 + lines.size() * 11;
        graphics.fill(x - 3, y - 3, x + width + 5, y + height, 0xD0101010);
        graphics.fill(x - 3, y - 3, x - 1, y + height, 0xFF000000 | accent);
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(client.font, lines.get(i), x, y + i * 11, i == 0 ? accent : 0xFFFFFF);
        }
    }

    private record OrderQuote(String item, long pricePerItem, long remaining) {
        private static OrderQuote betterOf(OrderQuote a, OrderQuote b) {
            return b.pricePerItem > a.pricePerItem ? b : a;
        }
    }

    private record Profit(String item, long quantity, long auctionPrice, long gross, long profit, double margin) {
        private boolean qualifies() {
            return profit >= MIN_PROFIT && margin >= MIN_MARGIN_PERCENT;
        }
    }
}
