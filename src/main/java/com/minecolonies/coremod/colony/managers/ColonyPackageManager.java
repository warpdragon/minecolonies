package com.minecolonies.coremod.colony.managers;

import com.minecolonies.api.colony.permissions.Rank;
import com.minecolonies.coremod.MineColonies;
import com.minecolonies.coremod.colony.Colony;
import com.minecolonies.coremod.colony.Structures;
import com.minecolonies.coremod.colony.WorkManager;
import com.minecolonies.coremod.colony.permissions.Permissions;
import com.minecolonies.coremod.colony.workorders.AbstractWorkOrder;
import com.minecolonies.coremod.network.messages.ColonyStylesMessage;
import com.minecolonies.coremod.network.messages.ColonyViewMessage;
import com.minecolonies.coremod.network.messages.ColonyViewWorkOrderMessage;
import com.minecolonies.coremod.network.messages.PermissionsMessage;
import com.minecolonies.coremod.util.ColonyUtils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

import static com.minecolonies.api.util.constant.ColonyConstants.MAX_SQ_DIST_OLD_SUBSCRIBER_UPDATE;
import static com.minecolonies.api.util.constant.ColonyConstants.MAX_SQ_DIST_SUBSCRIBER_UPDATE;
import static com.minecolonies.api.util.constant.Constants.TICKS_HOUR;

public class ColonyPackageManager implements IColonyPackageManager
{
    /**
     * List of players subscribing to the colony.
     */
    @NotNull
    private Set<EntityPlayerMP> subscribers = new HashSet<>();

    /**
     * Variables taking care of updating the views.
     */
    private boolean isDirty = false;

    /**
     * Amount of ticks passed.
     */
    private int ticksPassed = 0;

    /**
     * The last contact in hours.
     */
    private int lastContactInHours = 0;

    /**
     * The colony of the manager.
     */
    private final Colony colony;

    /**
     * Creates the ColonyPackageManager for a colony.
     * @param colony the colony.
     */
    public ColonyPackageManager(final Colony colony)
    {
        this.colony = colony;
    }

    @Override
    public int getLastContactInHours()
    {
        return lastContactInHours;
    }

    @Override
    public void setLastContactInHours(final int lastContactInHours)
    {
        this.lastContactInHours = lastContactInHours;
    }

    @Override
    public Set<EntityPlayerMP> getSubscribers()
    {
        return new HashSet<>(subscribers);
    }

    @Override
    public void updateSubscribers()
    {
        final World world = colony.getWorld();
        // If the world or server is null, don't try to update the subscribers this tick.
        if (world == null || world.getMinecraftServer() == null)
        {
            return;
        }

        //  Recompute subscribers every frame (for now)
        //  Subscribers = Owners + Players within (double working town hall range)
        @NotNull final Set<EntityPlayerMP> oldSubscribers = subscribers;
        subscribers = new HashSet<>();

        // Add owners
        world.getMinecraftServer().getPlayerList().getPlayers()
                .stream()
                .filter(colony.getPermissions()::isSubscriber)
                .forEach(subscribers::add);

        if (subscribers.isEmpty())
        {
            if (ticksPassed >= TICKS_HOUR)
            {
                ticksPassed = 0;
                lastContactInHours++;
                colony.markDirty();
            }
            ticksPassed++;
        }
        else if (lastContactInHours != 0)
        {
            lastContactInHours = 0;
            ticksPassed = 0;
            colony.markDirty();
        }

        //  Add nearby players
        for (final EntityPlayer o : world.playerEntities)
        {
            if (o instanceof EntityPlayerMP)
            {
                @NotNull final EntityPlayerMP player = (EntityPlayerMP) o;

                final double distance = player.getDistanceSq(colony.getCenter());
                if (distance < MAX_SQ_DIST_SUBSCRIBER_UPDATE
                        || (oldSubscribers.contains(player) && distance < MAX_SQ_DIST_OLD_SUBSCRIBER_UPDATE))
                {
                    // Players become subscribers if they come within 16 blocks of the edge of the colony
                    // Players remain subscribers while they remain within double the colony's radius
                    subscribers.add(player);
                }
            }
        }

        if (!subscribers.isEmpty())
        {
            //  Determine if any new subscribers were added this pass
            final boolean hasNewSubscribers = ColonyUtils.hasNewSubscribers(oldSubscribers, subscribers);

            //  Send each type of update packet as appropriate:
            //      - To Subscribers if the data changes
            //      - To New Subscribers even if it hasn't changed

            //ColonyView
            sendColonyViewPackets(oldSubscribers, hasNewSubscribers);

            //Permissions
            sendPermissionsPackets(oldSubscribers, hasNewSubscribers);

            //WorkOrders
            sendWorkOrderPackets(oldSubscribers, hasNewSubscribers);

            colony.getCitizenManager().sendPackets(oldSubscribers, hasNewSubscribers, subscribers);

            colony.getBuildingManager().sendPackets(oldSubscribers, hasNewSubscribers, subscribers);

            //schematics
            if (Structures.isDirty())
            {
                sendSchematicsPackets(hasNewSubscribers);
                Structures.clearDirty();
            }
        }

        isDirty = false;
        colony.getPermissions().clearDirty();

        colony.getBuildingManager().clearDirty();
        colony.getCitizenManager().clearDirty();
    }

    @Override
    public void sendColonyViewPackets(@NotNull final Set<EntityPlayerMP> oldSubscribers, final boolean hasNewSubscribers)
    {
        if (isDirty || hasNewSubscribers)
        {
            for (final EntityPlayerMP player : subscribers)
            {
                final boolean isNewSubscriber = !oldSubscribers.contains(player);
                if (isDirty || isNewSubscriber)
                {
                    MineColonies.getNetwork().sendTo(new ColonyViewMessage(colony, isNewSubscriber), player);
                }
            }
        }
    }

    @Override
    public void sendPermissionsPackets(@NotNull final Set<EntityPlayerMP> oldSubscribers, final boolean hasNewSubscribers)
    {
        final Permissions permissions = colony.getPermissions();
        if (permissions.isDirty() || hasNewSubscribers)
        {
            subscribers
                    .stream()
                    .filter(player -> permissions.isDirty() || !oldSubscribers.contains(player)).forEach(player ->
            {
                final Rank rank = permissions.getRank(player);
                MineColonies.getNetwork().sendTo(new PermissionsMessage.View(colony, rank), player);
            });
        }
    }

    @Override
    public void sendWorkOrderPackets(@NotNull final Set<EntityPlayerMP> oldSubscribers, final boolean hasNewSubscribers)
    {
        final WorkManager workManager = colony.getWorkManager();
        if (workManager.isDirty() || hasNewSubscribers)
        {
            for (final AbstractWorkOrder workOrder : workManager.getWorkOrders().values())
            {
                subscribers.stream().filter(player -> workManager.isDirty() || !oldSubscribers.contains(player))
                        .forEach(player -> MineColonies.getNetwork().sendTo(new ColonyViewWorkOrderMessage(colony, workOrder), player));
            }

            workManager.setDirty(false);
        }
    }

    @Override
    public void sendSchematicsPackets(final boolean hasNewSubscribers)
    {
        if (Structures.isDirty() || hasNewSubscribers)
        {
            subscribers.stream()
                    .forEach(player -> MineColonies.getNetwork().sendTo(new ColonyStylesMessage(), player));
        }
    }

    @Override
    public void setDirty()
    {
        this.isDirty = true;
    }
}
