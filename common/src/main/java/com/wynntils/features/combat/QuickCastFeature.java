/*
 * Copyright © Wynntils 2022-2026.
 * This file is released under LGPLv3. See LICENSE for full license details.
 */
package com.wynntils.features.combat;

import com.wynntils.core.components.Managers;
import com.wynntils.core.components.Models;
import com.wynntils.core.consumers.features.Feature;
import com.wynntils.core.consumers.features.ProfileDefault;
import com.wynntils.core.consumers.features.properties.RegisterKeyBind;
import com.wynntils.core.keybinds.KeyBind;
import com.wynntils.core.keybinds.KeyBindDefinition;
import com.wynntils.core.persisted.Persisted;
import com.wynntils.core.persisted.config.Category;
import com.wynntils.core.persisted.config.Config;
import com.wynntils.core.persisted.config.ConfigCategory;
import com.wynntils.mc.event.ArmSwingEvent;
import com.wynntils.mc.event.ChangeCarriedItemEvent;
import com.wynntils.mc.event.TickEvent;
import com.wynntils.mc.event.UseItemEvent;
import com.wynntils.models.character.type.ClassType;
import com.wynntils.models.items.properties.ClassableItemProperty;
import com.wynntils.models.items.properties.RequirementItemProperty;
import com.wynntils.models.spells.event.SpellEvent;
import com.wynntils.models.spells.type.SpellDirection;
import com.wynntils.models.worlds.event.WorldStateEvent;
import com.wynntils.utils.mc.McUtils;
import com.wynntils.utils.wynn.ItemUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;

@ConfigCategory(Category.COMBAT)
public class QuickCastFeature extends Feature {
    @RegisterKeyBind
    private final KeyBind castFirstSpell = KeyBindDefinition.CAST_FIRST_SPELL.create(this::castFirstSpell);

    @RegisterKeyBind
    private final KeyBind castSecondSpell = KeyBindDefinition.CAST_SECOND_SPELL.create(this::castSecondSpell);

    @RegisterKeyBind
    private final KeyBind castThirdSpell = KeyBindDefinition.CAST_THIRD_SPELL.create(this::castThirdSpell);

    @RegisterKeyBind
    private final KeyBind castFourthSpell = KeyBindDefinition.CAST_FOURTH_SPELL.create(this::castFourthSpell);

    @Persisted
    private final Config<Integer> leftClickTickDelay = new Config<>(3);

    @Persisted
    private final Config<Integer> rightClickTickDelay = new Config<>(3);

    @Persisted
    private final Config<Boolean> blockAttacks = new Config<>(true);

    @Persisted
    private final Config<Boolean> checkValidWeapon = new Config<>(true);

    @Persisted
    private final Config<SafeCastType> safeCasting = new Config<>(SafeCastType.NONE);

    @Persisted
    private final Config<Integer> spellCooldown = new Config<>(0);

    private int lastSpellTick = 0;
    private int packetCountdown = 0;
    private boolean awaitingConfirmation = false;
    private boolean spellMismatchDetected = false;
    private List<SpellDirection> lastQueuedSpell = List.of();
    private List<SpellDirection> expectedCompletedSpell = List.of();

    public QuickCastFeature() {
        super(ProfileDefault.ENABLED);
    }

    @SubscribeEvent
    public void onSwing(ArmSwingEvent event) {
        lastSpellTick = McUtils.player().tickCount;

        if (!blockAttacks.get()) return;
        if (event.getActionContext() != ArmSwingEvent.ArmSwingContext.ATTACK_OR_START_BREAKING_BLOCK) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (Models.Spell.isSpellQueueEmpty()) return;

        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onUseItem(UseItemEvent event) {
        if (Models.WorldState.inCharacterWardrobe()) return;

        lastSpellTick = McUtils.player().tickCount;

        if (!blockAttacks.get()) return;
        if (Models.Spell.isSpellQueueEmpty()) return;

        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onHeldItemChange(ChangeCarriedItemEvent event) {
        // Don't reset if we're mid-spellcast; the spell should continue through weapon swaps
        if (Models.Spell.isSpellQueueEmpty()) {
            resetState();
        }
    }

    @SubscribeEvent
    public void onWorldChange(WorldStateEvent e) {
        resetState();
    }

    @SubscribeEvent
    public void onSpellCompleted(SpellEvent.Completed e) {
        if (awaitingConfirmation && !expectedCompletedSpell.isEmpty()) {
            SpellDirection[] completed = e.getSpellDirectionArray();
            boolean matches = completed.length == expectedCompletedSpell.size();
            for (int i = 0; matches && i < completed.length; i++) {
                matches = completed[i] == expectedCompletedSpell.get(i);
            }
            if (!matches) {
                spellMismatchDetected = true;
            }
        }
        awaitingConfirmation = false;
    }

    @SubscribeEvent
    public void onSpellExpired(SpellEvent.Expired e) {
        awaitingConfirmation = false;
    }

    @SubscribeEvent
    public void onSpellFailed(SpellEvent.Failed e) {
        awaitingConfirmation = false;
    }

    private void castFirstSpell() {
        tryCastSpell(SpellUnit.PRIMARY, SpellUnit.SECONDARY, SpellUnit.PRIMARY);
    }

    private void castSecondSpell() {
        tryCastSpell(SpellUnit.PRIMARY, SpellUnit.PRIMARY, SpellUnit.PRIMARY);
    }

    private void castThirdSpell() {
        tryCastSpell(SpellUnit.PRIMARY, SpellUnit.SECONDARY, SpellUnit.SECONDARY);
    }

    private void castFourthSpell() {
        tryCastSpell(SpellUnit.PRIMARY, SpellUnit.PRIMARY, SpellUnit.SECONDARY);
    }

    private void tryCastSpell(SpellUnit a, SpellUnit b, SpellUnit c) {
        if (!Models.Spell.isSpellQueueEmpty()) return;

        SpellDirection[] spellInProgress = Models.Spell.getLastSpell();
        // SpellModel keeps the last spell for other uses but here we just want to know
        // the inputs so if a full spell
        // is the last spell then we just reset it to empty
        if (spellInProgress.length == 3) {
            spellInProgress = SpellDirection.NO_SPELL;
        }

        if (safeCasting.get() == SafeCastType.BLOCK_ALL && spellInProgress.length != 0) {
            sendCancelReason(Component.translatable("feature.wynntils.quickCast.spellInProgress"));
            return;
        }
        if (safeCasting.get() == SafeCastType.FINISH_COMPATIBLE && spellInProgress.length != 0 && lastSpellTick == 0) {
            sendCancelReason(Component.translatable("feature.wynntils.quickCast.spellInProgress"));
            return;
        }

        boolean isArcher = Models.Character.getClassType() == ClassType.ARCHER;

        if (checkValidWeapon.get()) {
            ItemStack heldItem = McUtils.player().getItemInHand(InteractionHand.MAIN_HAND);

            if (!ItemUtils.isWeapon(heldItem)) {
                sendCancelReason(Component.translatable("feature.wynntils.quickCast.notAWeapon"));
                return;
            }

            // First check if the character is an archer or not in case CharacterModel
            // failed to parse correctly
            Optional<ClassableItemProperty> classItemPropOpt =
                    Models.Item.asWynnItemProperty(heldItem, ClassableItemProperty.class);

            if (classItemPropOpt.isEmpty()) {
                sendCancelReason(Component.translatable("feature.wynntils.quickCast.notAWeapon"));
                return;
            } else {
                isArcher = classItemPropOpt.get().getRequiredClass() == ClassType.ARCHER;
            }

            // Now check for met requirements
            Optional<RequirementItemProperty> reqItemPropOpt =
                    Models.Item.asWynnItemProperty(heldItem, RequirementItemProperty.class);

            if (reqItemPropOpt.isPresent() && !reqItemPropOpt.get().meetsActualRequirements()) {
                sendCancelReason(Component.translatable("feature.wynntils.quickCast.notMetRequirements"));
                return;
            }
        }

        boolean isSpellInverted = isArcher;
        List<SpellDirection> unconfirmedSpell = Stream.of(a, b, c)
                .map(x -> (x == SpellUnit.PRIMARY) == isSpellInverted ? SpellDirection.LEFT : SpellDirection.RIGHT)
                .toList();

        List<SpellDirection> confirmedSpell = new ArrayList<>(unconfirmedSpell);

        if (safeCasting.get() == SafeCastType.FINISH_COMPATIBLE && spellInProgress.length != 0) {
            for (int i = 0; i < spellInProgress.length; i++) {
                if (spellInProgress[i] == unconfirmedSpell.get(i)) {
                    confirmedSpell.removeFirst();
                } else {
                    sendCancelReason(Component.translatable("feature.wynntils.quickCast.incompatibleInProgress"));
                    return;
                }
            }
        }

        if (safeCasting.get() == SafeCastType.PREVENT_MISCAST_CASCADE && spellInProgress.length != 0) {
            if (awaitingConfirmation && isServerProcessingPreviousSend(spellInProgress)) {
                // We recently sent a complete spell and the server hasn't confirmed it yet.
                // The in-progress state is a prefix of our expected spell, so the server is
                // still processing our previous send.
                // Queue the full new spell -- the server will finish the old one on its own.
            } else {
                // Either we're not awaiting confirmation, or the in-progress state doesn't
                // match what we expected (indicating packet loss changed the server state).
                // Apply compatibility logic to finish the in-progress spell if possible.
                awaitingConfirmation = false;
                for (int i = 0; i < spellInProgress.length; i++) {
                    if (spellInProgress[i] == unconfirmedSpell.get(i)) {
                        confirmedSpell.removeFirst();
                    } else {
                        sendCancelReason(Component.translatable("feature.wynntils.quickCast.incompatibleInProgress"));
                        return;
                    }
                }
            }
        }

        if (safeCasting.get() == SafeCastType.PREVENT_MISCAST_CASCADE) {
            lastQueuedSpell = List.copyOf(unconfirmedSpell);
        }
        Models.Spell.addSpellToQueue(confirmedSpell);
    }

    @SubscribeEvent
    public void onTick(TickEvent e) {
        if (!Models.WorldState.onWorld()) return;

        if (packetCountdown > 0) {
            packetCountdown--;
        }

        if (packetCountdown > 0) return;

        if (Models.Spell.isSpellQueueEmpty()) return;

        SpellDirection nextDirection = Models.Spell.checkNextSpellDirection();

        if (nextDirection == null) return;

        int comparisonTime =
                nextDirection == SpellDirection.LEFT ? leftClickTickDelay.get() : rightClickTickDelay.get();
        if (McUtils.player().tickCount - lastSpellTick < comparisonTime) return;

        Models.Spell.sendNextSpell();
        lastSpellTick = McUtils.player().tickCount;

        if (Models.Spell.isSpellQueueEmpty()) {
            lastSpellTick = 0;
            if (safeCasting.get() == SafeCastType.PREVENT_MISCAST_CASCADE) {
                if (spellMismatchDetected) {
                    // A different spell than intended was confirmed -- input was likely dropped.
                    // Force compatibility mode for the next spell to break any cascade.
                    awaitingConfirmation = false;
                    spellMismatchDetected = false;
                } else {
                    awaitingConfirmation = true;
                    expectedCompletedSpell = lastQueuedSpell;
                }
            }
            packetCountdown = Math.max(packetCountdown, spellCooldown.get());
        }
    }

    private void resetState() {
        lastSpellTick = 0;
        packetCountdown = 0;
        awaitingConfirmation = false;
        spellMismatchDetected = false;
        lastQueuedSpell = List.of();
        expectedCompletedSpell = List.of();
    }

    private boolean isServerProcessingPreviousSend(SpellDirection[] spellInProgress) {
        // Check if the in-progress state is a prefix of the spell we last sent.
        // If so, the server is still processing our previous send (normal latency).
        // If not, packet loss has altered the server state and we can't trust awaitingConfirmation.
        if (expectedCompletedSpell.isEmpty()) return false;
        if (spellInProgress.length > expectedCompletedSpell.size()) return false;

        for (int i = 0; i < spellInProgress.length; i++) {
            if (spellInProgress[i] != expectedCompletedSpell.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static void sendCancelReason(MutableComponent reason) {
        Managers.Notification.queueMessage(reason.withStyle(ChatFormatting.RED));
    }

    public enum SpellUnit {
        PRIMARY,
        SECONDARY
    }

    public enum SafeCastType {
        NONE,
        BLOCK_ALL,
        FINISH_COMPATIBLE,
        PREVENT_MISCAST_CASCADE
    }
}
