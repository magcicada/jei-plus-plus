package com.lingmu0.JeiPlusPlusMod;

import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Optional Forge play channel; older client-only JEI++ functionality stays independent. */
public final class Ae2PatternNetwork {
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(JeiPlusPlus.MODID, "ae2_patterns"), () -> "1",
            NetworkRegistry.acceptMissingOr("1"), NetworkRegistry.acceptMissingOr("1"));
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final int CHUNK_SIZE = 8;

    private Ae2PatternNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(Request.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Request::write).decoder(Request::read)
                .consumerMainThread((request, supplier) -> {
                    ServerPlayer player = supplier.get().getSender();
                    if (player != null) receive(player, request);
                    supplier.get().setPacketHandled(true);
                }).add();
    }

    public static boolean available(Connection connection) {
        return connection != null && CHANNEL.isRemotePresent(connection);
    }

    public static void send(List<Ae2PatternPlan> plans, boolean force, int menuId) {
        int batch = ThreadLocalRandom.current().nextInt();
        int count = (plans.size() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        for (int i = 0; i < count; i++) {
            CHANNEL.sendToServer(new Request(menuId, batch, i, count, force,
                    List.copyOf(plans.subList(i * CHUNK_SIZE, Math.min(plans.size(), (i + 1) * CHUNK_SIZE)))));
        }
    }

    private static void receive(ServerPlayer player, Request request) {
        if (request.total < 1 || request.total > 48 || request.index < 0 || request.index >= request.total
                || request.plans.isEmpty() || request.plans.size() > CHUNK_SIZE
                || player.containerMenu.containerId != request.menuId) return;
        UUID id = player.getUUID();
        Pending pending = PENDING.get(id);
        if (pending == null || pending.batch != request.batch || pending.menuId != request.menuId
                || System.nanoTime() - pending.created > 10_000_000_000L) {
            if (request.index != 0) return;
            pending = new Pending(request.batch, request.menuId, request.total, request.force);
            PENDING.put(id, pending);
        }
        if (request.index != pending.next || request.total != pending.total || request.force != pending.force) {
            PENDING.remove(id);
            return;
        }
        pending.parts.addAll(request.plans);
        pending.next++;
        if (request.index + 1 == request.total) {
            PENDING.remove(id);
            Ae2PatternServer.create(player, pending.parts, pending.force);
        }
    }

    private static final class Pending {
        final int batch, menuId, total;
        final boolean force;
        final long created = System.nanoTime();
        final List<Ae2PatternPlan> parts = new ArrayList<>();
        int next;
        Pending(int batch, int menuId, int total, boolean force) {
            this.batch = batch; this.menuId = menuId; this.total = total; this.force = force;
        }
    }

    public record Request(int menuId, int batch, int index, int total, boolean force,
                          List<Ae2PatternPlan> plans) {
        private void write(FriendlyByteBuf buffer) {
            buffer.writeVarInt(menuId);
            buffer.writeInt(batch);
            buffer.writeVarInt(index);
            buffer.writeVarInt(total);
            buffer.writeBoolean(force);
            buffer.writeVarInt(plans.size());
            for (Ae2PatternPlan plan : plans) {
                buffer.writeBoolean(plan.recipeId() != null);
                if (plan.recipeId() != null) buffer.writeResourceLocation(plan.recipeId());
                buffer.writeVarInt(plan.inputs().size());
                for (ItemStack stack : plan.inputs()) buffer.writeItem(stack);
                buffer.writeVarInt(plan.outputs().size());
                for (ItemStack stack : plan.outputs()) buffer.writeItem(stack);
                buffer.writeBoolean(plan.substitute());
            }
        }

        private static Request read(FriendlyByteBuf buffer) {
            int menuId = buffer.readVarInt();
            int batch = buffer.readInt();
            int index = buffer.readVarInt();
            int total = buffer.readVarInt();
            boolean force = buffer.readBoolean();
            int size = buffer.readVarInt();
            if (size < 0 || size > CHUNK_SIZE) throw new IllegalArgumentException("Invalid pattern chunk");
            List<Ae2PatternPlan> plans = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                ResourceLocation recipeId = buffer.readBoolean() ? buffer.readResourceLocation() : null;
                int inputCount = buffer.readVarInt();
                if (inputCount < 0 || inputCount > 9) throw new IllegalArgumentException("Invalid pattern inputs");
                List<ItemStack> inputs = new ArrayList<>(inputCount);
                for (int j = 0; j < inputCount; j++) inputs.add(buffer.readItem());
                int outputCount = buffer.readVarInt();
                if (outputCount < 0 || outputCount > 3) throw new IllegalArgumentException("Invalid pattern outputs");
                List<ItemStack> outputs = new ArrayList<>(outputCount);
                for (int j = 0; j < outputCount; j++) outputs.add(buffer.readItem());
                plans.add(new Ae2PatternPlan(recipeId, inputs, outputs, buffer.readBoolean()));
            }
            return new Request(menuId, batch, index, total, force, plans);
        }
    }
}
