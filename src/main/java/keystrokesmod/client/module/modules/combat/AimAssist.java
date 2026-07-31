package keystrokesmod.client.module.modules.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.lwjgl.input.Mouse;

import com.google.common.eventbus.Subscribe;

import keystrokesmod.client.event.EventTiming;
import keystrokesmod.client.event.impl.GameLoopEvent;
import keystrokesmod.client.event.impl.UpdateEvent;
import keystrokesmod.client.module.Module;
import keystrokesmod.client.module.modules.client.Targets;
import keystrokesmod.client.module.modules.world.AntiBot;
import keystrokesmod.client.module.setting.impl.ComboSetting;
import keystrokesmod.client.module.setting.impl.DescriptionSetting;
import keystrokesmod.client.module.setting.impl.DoubleSliderSetting;
import keystrokesmod.client.module.setting.impl.SliderSetting;
import keystrokesmod.client.module.setting.impl.TickSetting;
import keystrokesmod.client.utils.CombatUtils;
import keystrokesmod.client.utils.Utils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemSpade;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;

/**
 * AimAssist — ported from Myau (Forge/Architectury) to Raven's module system.
 *
 * Mode differences vs. the Myau version:
 *  - Regular / Linear / Lock-on move the ACTUAL camera (mc.thePlayer.rotationYaw/Pitch)
 *    once per game tick via GameLoopEvent, same as KillAura's aura loop.
 *  - Silent injects rotation into the outgoing movement packet only (UpdateEvent),
 *    leaving the render/camera rotation untouched, and attacks with a randomized
 *    CPS window — same trick Hitflick/KillAura use in this codebase.
 */
public class AimAssist extends Module {

    // ── Mode ──────────────────────────────────────────────────────────────────
    public static ComboSetting mode;

    // ── Aim speed (Regular / Linear / Lock-on) ──────────────────────────────────
    public static SliderSetting hSpeed, vSpeed, smoothing;

    // ── Range / FOV ───────────────────────────────────────────────────────────
    public static SliderSetting range, fov;

    // ── Silent mode: autoclicker ────────────────────────────────────────────────
    public static DoubleSliderSetting cps;
    public static SliderSetting extraSwing, maxAngle;

    // ── Target settings ───────────────────────────────────────────────────────
    public static ComboSetting targetMode;
    public static TickSetting weaponOnly, allowTools, botCheck, teamCheck, friendCheck,
            requireMouse, breakPause, disableOnDeath;

    // ── State ─────────────────────────────────────────────────────────────────
    private EntityPlayer currentTarget;
    private EntityPlayer attackingTarget;
    private float silentYaw;
    private float silentPitch;
    private long nextAttackMs;
    private long breakPauseUntil;

    // Read by AutoBlock/etc. to suppress digging packets on attack ticks
    public static boolean attackingThisTick = false;

    public AimAssist() {
        super("AimAssist", ModuleCategory.combat);

        this.registerSetting(new DescriptionSetting("Aims at and attacks nearby targets."));

        this.registerSetting(mode = new ComboSetting("Mode", Mode.Regular));

        this.registerSetting(hSpeed    = new SliderSetting("H-Speed",   3.0D, 0.0D, 10.0D, 0.1D));
        this.registerSetting(vSpeed    = new SliderSetting("V-Speed",   1.0D, 0.0D, 10.0D, 0.1D));
        this.registerSetting(smoothing = new SliderSetting("Smoothing", 50D,  0D,   100D,  1D));

        this.registerSetting(range = new SliderSetting("Range", 4.5D, 1.0D, 8.0D, 0.1D));
        this.registerSetting(fov   = new SliderSetting("FOV",   180D, 10D,  360D, 1D));

        this.registerSetting(cps        = new DoubleSliderSetting("Silent CPS", 8D, 12D, 1D, 20D, 1D));
        this.registerSetting(extraSwing = new SliderSetting("Extra Swing", 0.5D, 0.0D, 2.0D, 0.1D));
        this.registerSetting(maxAngle   = new SliderSetting("Max Angle", 180D, 1D, 180D, 1D));

        this.registerSetting(targetMode = new ComboSetting("Target Mode", TargetMode.Distance));

        this.registerSetting(weaponOnly    = new TickSetting("Weapons Only", true));
        this.registerSetting(allowTools    = new TickSetting("Allow Tools", false));
        this.registerSetting(botCheck      = new TickSetting("Bot Check", true));
        this.registerSetting(teamCheck     = new TickSetting("Teams", true));
        this.registerSetting(friendCheck   = new TickSetting("Friends", true));
        this.registerSetting(requireMouse  = new TickSetting("Require Mouse Down", false));
        this.registerSetting(breakPause    = new TickSetting("Break Blocks Pause", true));
        this.registerSetting(disableOnDeath = new TickSetting("Disable on Death", false));
    }

    @Override
    public void onEnable() {
        if (mc.thePlayer != null) {
            silentYaw   = mc.thePlayer.rotationYaw;
            silentPitch = mc.thePlayer.rotationPitch;
        }
    }

    @Override
    public void onDisable() {
        currentTarget     = null;
        attackingTarget   = null;
        attackingThisTick = false;
        breakPauseUntil   = 0;
    }

    public EntityPlayer getTarget()          { return currentTarget; }
    public EntityPlayer getAttackingTarget() { return attackingTarget; }

    // ── Regular / Linear / Lock-on: moves the real camera, once per tick ───────
    @Subscribe
    public void onGameLoop(GameLoopEvent event) {
        if (!Utils.Player.isPlayerInGame() || mc.currentScreen != null) return;
        if (mode.getMode() == Mode.Silent) return; // handled in onUpdate instead

        if (disableOnDeath.isToggled() && mc.thePlayer.getHealth() <= 0) { this.disable(); return; }
        if (requireMouse.isToggled() && !Mouse.isButtonDown(0)) return;
        if (!isWeaponConditionMet()) return;
        if (isBreakPaused()) return;

        EntityPlayer target = findTarget(false);
        if (target == null) return;

        float[] dest = Utils.Player.getTargetRotations(target, 0);
        if (dest == null) return;

        float yawStep, pitchStep;
        Mode m = (Mode) mode.getMode();

        if (m == Mode.LockOn) {
            yawStep   = dest[0] - mc.thePlayer.rotationYaw;
            pitchStep = dest[1] - mc.thePlayer.rotationPitch;
        } else if (m == Mode.Linear) {
            float h  = (float) hSpeed.getInput() * 0.5f;
            float v  = (float) vSpeed.getInput() * 0.5f;
            float dy = MathHelper.wrapAngleTo180_float(dest[0] - mc.thePlayer.rotationYaw);
            float dp = dest[1] - mc.thePlayer.rotationPitch;
            yawStep   = Math.abs(dy) < h ? dy : Math.signum(dy) * h;
            pitchStep = Math.abs(dp) < v ? dp : Math.signum(dp) * v;
        } else { // Regular — proportional, further = faster
            yawStep   = (dest[0] - mc.thePlayer.rotationYaw)   * 0.1f * (float) hSpeed.getInput();
            pitchStep = (dest[1] - mc.thePlayer.rotationPitch) * 0.1f * (float) vSpeed.getInput();
        }

        mc.thePlayer.rotationYaw   += yawStep;
        mc.thePlayer.rotationPitch += pitchStep;
    }

    // ── Silent mode: rotation via UpdateEvent, attack via playerController ─────
    @Subscribe
    public void onUpdate(UpdateEvent event) {
        if (mode.getMode() != Mode.Silent) return;
        if (!Utils.Player.isPlayerInGame()) return;

        if (event.getTiming() == EventTiming.PRE) {
            attackingTarget   = null;
            attackingThisTick = false;

            if (disableOnDeath.isToggled() && mc.thePlayer.getHealth() <= 0) { this.disable(); return; }
            if (requireMouse.isToggled() && !Mouse.isButtonDown(0)) { currentTarget = null; return; }
            if (isBreakPaused()) { currentTarget = null; return; }

            currentTarget = findTarget(true);
            if (currentTarget == null) {
                silentYaw   = mc.thePlayer.rotationYaw;
                silentPitch = mc.thePlayer.rotationPitch;
                return;
            }

            float[] rot = calcSilentRotation(currentTarget);
            silentYaw   = rot[0];
            silentPitch = rot[1];

            if (getAngleDiff(silentYaw, silentPitch) > maxAngle.getInput()) {
                currentTarget = null;
                silentYaw   = mc.thePlayer.rotationYaw;
                silentPitch = mc.thePlayer.rotationPitch;
                return;
            }

            event.setYaw(silentYaw);
            event.setPitch(silentPitch);

        } else if (event.getTiming() == EventTiming.POST) {
            if (currentTarget == null || currentTarget.isDead) return;
            if (mc.thePlayer.getDistanceToEntity(currentTarget) > range.getInput() + extraSwing.getInput()) return;
            if (System.currentTimeMillis() < nextAttackMs) return;

            attackingThisTick = true;
            mc.thePlayer.swingItem();
            mc.playerController.attackEntity(mc.thePlayer, currentTarget);
            attackingTarget = currentTarget;
            scheduleNextAttack();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private EntityPlayer findTarget(boolean silent) {
        List<EntityPlayer> targets = new ArrayList<>();
        for (EntityPlayer ep : mc.theWorld.playerEntities) {
            if (isValidTarget(ep, silent)) targets.add(ep);
        }
        if (targets.isEmpty()) return null;

        TargetMode tm = (TargetMode) targetMode.getMode();
        switch (tm) {
            case Yaw:
                targets.sort(Comparator.comparingDouble(p -> Math.abs(Utils.Player.fovFromEntity(p))));
                break;
            case Health:
                targets.sort(Comparator.comparingDouble(EntityPlayer::getHealth));
                break;
            default: // Distance
                targets.sort(Comparator.comparingDouble(p -> mc.thePlayer.getDistanceToEntity(p)));
                break;
        }
        return targets.get(0);
    }

    private boolean isValidTarget(EntityPlayer p, boolean silent) {
        if (p == mc.thePlayer) return false;
        if (p.deathTime > 0 || p.isDead) return false;

        double maxRange = silent ? range.getInput() + extraSwing.getInput() : range.getInput();
        if (mc.thePlayer.getDistanceToEntity(p) > maxRange) return false;
        if (!silent && !Utils.Player.fov(p, (float) fov.getInput())) return false;

        if (friendCheck.isToggled() && Targets.isAFriend(p)) return false;
        if (botCheck.isToggled() && AntiBot.bot(p)) return false;
        if (teamCheck.isToggled() && CombatUtils.isTeam(mc.thePlayer, p)) return false;

        return true;
    }

    private float[] calcSilentRotation(EntityPlayer target) {
        float[] rot = Utils.Player.getTargetRotations(target, 0);
        if (rot == null) return new float[]{ silentYaw, silentPitch };

        // smoothing: 0 = snap instantly, 100 = crawl toward the target
        float step = 1.0f - (float) smoothing.getInput() / 100.0f;
        if (step < 0.02f) step = 0.02f;

        float yawDiff   = MathHelper.wrapAngleTo180_float(rot[0] - silentYaw);
        float pitchDiff = rot[1] - silentPitch;

        return new float[]{ silentYaw + yawDiff * step, silentPitch + pitchDiff * step };
    }

    private float getAngleDiff(float yaw, float pitch) {
        float dy = Math.abs(MathHelper.wrapAngleTo180_float(yaw   - mc.thePlayer.rotationYaw));
        float dp = Math.abs(MathHelper.wrapAngleTo180_float(pitch - mc.thePlayer.rotationPitch));
        return Math.max(dy, dp);
    }

    private boolean isWeaponConditionMet() {
        if (!weaponOnly.isToggled()) return true;
        if (allowTools.isToggled() && isHoldingTool()) return true;
        return Utils.Player.isPlayerHoldingWeapon();
    }

    private boolean isHoldingTool() {
        ItemStack stack = mc.thePlayer.getCurrentEquippedItem();
        if (stack == null) return false;
        return stack.getItem() instanceof ItemPickaxe
                || stack.getItem() instanceof ItemSpade
                || stack.getItem() instanceof ItemHoe;
    }

    private boolean isBreakPaused() {
        if (!breakPause.isToggled()) return false;
        if (mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK
                && mc.thePlayer.isUsingItem()) {
            breakPauseUntil = System.currentTimeMillis() + 200;
        }
        return System.currentTimeMillis() < breakPauseUntil;
    }

    private void scheduleNextAttack() {
        double minCps = Math.max(cps.getInputMin(), 0.1D);
        double maxCps = Math.max(cps.getInputMax(), 0.1D);
        double minMs  = 1000.0D / Math.max(minCps, maxCps);
        double maxMs  = 1000.0D / Math.min(minCps, maxCps);
        nextAttackMs  = System.currentTimeMillis()
                + (long) (minMs + ThreadLocalRandom.current().nextDouble() * (maxMs - minMs));
    }

    public enum Mode { Regular, Linear, LockOn, Silent }

    public enum TargetMode { Distance, Yaw, Health }
}
