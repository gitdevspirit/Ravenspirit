package keystrokesmod.client.esp;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import java.awt.Color;
import java.util.concurrent.ConcurrentHashMap;

public class ESPManager {
    // Singleton instance
    private static ESPManager instance;
    
    // Current render module
    private IRenderModule currentModule;
    
    // Store all player targets
    private final ConcurrentHashMap<String, RenderTarget> targets = new ConcurrentHashMap<>();
    
    // ESP state
    private boolean enabled = true;
    private int renderMode = 1; // 0=Forge, 1=Safe, 2=Overlay
    
    // Private constructor (singleton pattern)
    private ESPManager() {
        // Start with Safe mode by default
        setModule(new SafeESPModule());
        System.out.println("[RavenESP] ESP Manager initialized");
    }
    
    // Get singleton instance
    public static ESPManager getInstance() {
        if (instance == null) {
            instance = new ESPManager();
        }
        return instance;
    }
    
    // Switch render module
    public void setModule(IRenderModule module) {
        // Shutdown current module if exists
        if (currentModule != null) {
            currentModule.shutdown();
        }
        
        // Set and initialize new module
        currentModule = module;
        currentModule.initialize();
        System.out.println("[RavenESP] Switched to: " + module.getName());
    }
    
    // Set render mode by number
    public void setRenderMode(int mode) {
        this.renderMode = mode;
        
        switch (mode) {
            case 0:
                setModule(new ForgeESPModule());
                break;
            case 1:
                setModule(new SafeESPModule());
                break;
            case 2:
                // Check if Windows (overlay only works on Windows)
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    setModule(new OverlayESPModule());
                } else {
                    System.out.println("[RavenESP] Overlay mode only available on Windows, falling back to Safe mode");
                    setModule(new SafeESPModule());
                    this.renderMode = 1;
                }
                break;
            default:
                setModule(new SafeESPModule());
                this.renderMode = 1;
        }
    }
    
    // Main render event handler (called by Forge)
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        // Check if ESP is enabled and module is active
        if (!enabled || currentModule == null || !currentModule.isActive()) {
            return;
        }
        
        // Check if in-game
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        
        // Update player targets
        updateTargets();
        
        // Render each target
        for (RenderTarget target : targets.values()) {
            currentModule.render(target);
        }
    }
    
    // Update the list of players to track
    private void updateTargets() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        
        // Clear old targets
        targets.clear();
        
        // Loop through all players in the world
        for (Object obj : mc.theWorld.playerEntities) {
            if (!(obj instanceof EntityPlayer)) continue;
            
            EntityPlayer player = (EntityPlayer) obj;
            
            // Skip yourself
            if (player == mc.thePlayer) continue;
            
            // Skip dead players
            if (player.isDead) continue;
            
            // Calculate distance
            double distance = mc.thePlayer.getDistanceToEntity(player);
            
            // Only track players within 50 blocks
            if (distance > 50) continue;
            
            // Get color based on distance
            Color color = getColorForDistance(distance);
            
            // Get player position
            Vec3 position = new Vec3(player.posX, player.posY, player.posZ);
            
            // Create render target
            RenderTarget target = new RenderTarget(
                player,
                position,
                distance,
                color,
                player.getDisplayName().getUnformattedText(),
                player.getHealth()
            );
            
            // Store target
            targets.put(player.getName(), target);
        }
    }
    
    // Get color based on distance (red = close, green = far)
    private Color getColorForDistance(double distance) {
        if (distance < 10) {
            return Color.RED;        // Very close - red
        } else if (distance < 25) {
            return Color.ORANGE;      // Close - orange
        } else if (distance < 40) {
            return Color.YELLOW;      // Medium - yellow
        } else {
            return Color.GREEN;       // Far - green
        }
    }
    
    // Toggle ESP on/off
    public void toggle() {
        enabled = !enabled;
        System.out.println("[RavenESP] ESP " + (enabled ? "enabled" : "disabled"));
    }
    
    // Getters
    public boolean isEnabled() {
        return enabled;
    }
    
    public int getRenderMode() {
        return renderMode;
    }
    
    public IRenderModule getCurrentModule() {
        return currentModule;
    }
    
    // Cleanup on mod shutdown
    public void shutdown() {
        if (currentModule != null) {
            currentModule.shutdown();
        }
        StreamingDetector.shutdown();
        System.out.println("[RavenESP] ESP Manager shutdown complete");
    }
}
