package net.evmodder.PeacefulPetHeads;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Random;
import org.bukkit.attribute.Attributable;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityEnterLoveModeEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import net.evmodder.DropHeads.DropHeads;
import net.evmodder.DropHeads.JunkUtils;
import net.evmodder.DropHeads.events.EntityBeheadEvent;
import net.evmodder.DropHeads.events.HeadRollEvent;
import net.evmodder.EvLib.extras.TextUtils;
import net.evmodder.EvLib.EvPlugin;
import net.evmodder.EvLib.FileIO;
import net.evmodder.EvLib.extras.TellrawUtils.ListComponent;

public final class PeacefulPetHeads extends EvPlugin implements Listener{
	private HashSet<EntityType> onFeedToHeal, onFeedToBreed, onFeedToTame, onTame, disableMurderBehead;
	private String MSG_HEAD_FROM_FEEDING;
	private int JSON_LIMIT;
	private DropHeads dropheadsPlugin = null;
	private Random rand;

	private DropHeads getDropHeadsPlugin(){
		if(dropheadsPlugin == null) dropheadsPlugin = (DropHeads)getServer().getPluginManager().getPlugin("DropHeads");
		return dropheadsPlugin;
	}

	@Override public void reloadConfig(){ // Same as EvPlugin, except we use "getDropHeadsPlugin()" instead of "this"
		InputStream defaultConfig = getClass().getResourceAsStream("/config.yml");
		if(defaultConfig != null){
			FileIO.verifyDir(getDropHeadsPlugin());
			config = FileIO.loadConfig(this, "config-"+getName()+".yml", defaultConfig, /*notifyIfNew=*/true);
		}
	}

	private HashSet<EntityType> getEntityTypesFromStringList(String configKey){
		HashSet<EntityType> entities = new HashSet<>();
		for(String entityTypeName : config.getStringList(configKey)){
			try{entities.add(EntityType.valueOf(entityTypeName.toUpperCase()));}
			catch(IllegalArgumentException ex){getLogger().severe("Unknown entity in '"+configKey+"': "+entityTypeName);}
		}
		return entities;
	}

	@Override public void onEvEnable(){
		reloadConfig();
		onFeedToHeal = getEntityTypesFromStringList("feeding-to-heal-triggers-head-drop-chance");
		onFeedToBreed = getEntityTypesFromStringList("feeding-to-breed-triggers-head-drop-chance");
		onFeedToTame = getEntityTypesFromStringList("feeding-to-tame-triggers-head-drop-chance");
		onTame = getEntityTypesFromStringList("taming-triggers-head-drop-chance");
		disableMurderBehead = getEntityTypesFromStringList("disable-head-drop-from-murder");

		MSG_HEAD_FROM_FEEDING = TextUtils.translateAlternateColorCodes('&', config.getString("message-for-awarding-head",
				"&6${PLAYER}&r was awarded the head of &6${ENTITY}&r by feeding them"));

		JSON_LIMIT = getDropHeadsPlugin().getConfig().getInt("message-json-limit", 15000);
		rand = new Random();
		getServer().getPluginManager().registerEvents(this, this);

		if(!disableMurderBehead.isEmpty()){
			getServer().getPluginManager().registerEvents(new Listener(){
				@EventHandler
				public void onEntityBehead(EntityBeheadEvent evt){
					if(evt.getSourceEvent() instanceof EntityDeathEvent && disableMurderBehead.contains(evt.getEntityType())){
						evt.setCancelled(true);
					}
				}
			}, this);
		}
		onTame.removeAll(onFeedToTame); // We probably shouldn't roll twice for tame attempt AND tame success?
		if(!onTame.isEmpty()){
			getServer().getPluginManager().registerEvents(new Listener(){
				@EventHandler
				public void onTame(EntityTameEvent evt){
					if(onTame.contains(evt.getEntity().getType()) && evt.getOwner() instanceof Player){
						Player player = (Player)evt.getOwner();
						doHeadDropRoll(player, evt.getEntity(), evt, player.getInventory().getItemInMainHand());
					}
				}
			}, this);
		}
	}

	void doHeadDropRoll(Entity feeder, Entity pet, Event triggerEvt, ItemStack foodItem){
		final double rawDropChance = getDropHeadsPlugin().getDropChanceAPI().getRawDropChance(pet);
		final double permMod = getDropHeadsPlugin().getDropChanceAPI().getPermissionsMult(pet, feeder);
		final double dropChance = rawDropChance*permMod;
		final double dropRoll = rand.nextDouble();
		final HeadRollEvent rollEvent = new HeadRollEvent(feeder, pet, dropChance, dropRoll, dropRoll < dropChance);
		getServer().getPluginManager().callEvent(rollEvent);
		getLogger().fine("entity: "+pet.getType()+", feeder: "+feeder.getName()+", evt: "+triggerEvt.getEventName()+", food: "+foodItem.getType());
		if(!rollEvent.getDropSuccess()) return;

		getDropHeadsPlugin().getDropChanceAPI().triggerHeadDropEvent(pet, feeder, triggerEvt, foodItem, ()->{
			final ListComponent message = new ListComponent();
			message.addComponent(MSG_HEAD_FROM_FEEDING
					// Some aliases
					.replace("${PET}", "${ENTITY}").replace("${FEEDER}", "${PLAYER}").replace("${FOOD}", "${ITEM}"));
			message.replaceRawTextWithComponent("${ENTITY}", JunkUtils.getDisplayNameSelectorComponent(pet, /*fakeSelector=*/false));
			message.replaceRawTextWithComponent("${PLAYER}", JunkUtils.getDisplayNameSelectorComponent(feeder, /*fakeSelector=*/false));
			message.replaceRawTextWithComponent("${ITEM}", JunkUtils.getMurderItemComponent(foodItem, JSON_LIMIT));
			return message;
		});
	}
	
	class RegainHealthListener implements Listener{
		Player feeder;
		ItemStack mainHand, offHand;
		final int amtMainHand, amtOffHand;
		RegainHealthListener(Player feeder, ItemStack mainHand, ItemStack offHand){
			this.feeder = feeder;
			this.mainHand = mainHand;
			this.offHand = offHand;
			amtMainHand = mainHand == null ? 0 : mainHand.getAmount();
			amtOffHand = offHand == null ? 0 : offHand.getAmount();
		}
		@EventHandler
		public void onEntityHeal(EntityRegainHealthEvent evt){
			if(evt.getRegainReason() == RegainReason.EATING && onFeedToHeal.contains(evt.getEntity().getType())){
				new BukkitRunnable(){@Override public void run(){
					ItemStack newMainHand = feeder.getInventory().getItemInMainHand();
					ItemStack newOffHand = feeder.getInventory().getItemInOffHand();
					if(amtMainHand == (newMainHand == null ? 1 : newMainHand.getAmount()+1)){
						doHeadDropRoll(feeder, evt.getEntity(), evt, mainHand);
					}
					else if(amtOffHand == (newOffHand == null ? 1 : newOffHand.getAmount()+1)){
						doHeadDropRoll(feeder, evt.getEntity(), evt, offHand);
					}
				}}.runTaskLater(PeacefulPetHeads.this, 1);
			}
			HandlerList.unregisterAll(this);
		}
	}

	class LoveModeListener implements Listener{
		ItemStack food;
		LoveModeListener(ItemStack food){this.food = food;}
		@EventHandler
		public void onEntityLove(EntityEnterLoveModeEvent evt){
			if(onFeedToBreed.contains(evt.getEntity().getType())){
				doHeadDropRoll(evt.getHumanEntity(), evt.getEntity(), evt, food);
			}
			HandlerList.unregisterAll(this);
		}
	}

	private boolean canFeedToHealForHeadDrop(Entity entity){
		return onFeedToHeal.contains(entity.getType()) && entity instanceof Damageable && entity instanceof Attributable
				&& ((Damageable)entity).getHealth() < ((Attributable)entity).getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
	}
	private boolean canFeedToBreedForHeadDrop(Entity entity){
		return onFeedToBreed.contains(entity.getType()) && entity instanceof Animals && !((Animals)entity).isLoveMode();
	}
	private boolean canFeedToTame(Entity entity){
		return onFeedToTame.contains(entity.getType()) && entity instanceof Tameable && !((Tameable)entity).isTamed();
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onClick(PlayerInteractEntityEvent evt){
		if(evt.isCancelled()) return;
		final ItemStack mainHand = evt.getPlayer().getInventory().getItemInMainHand();
		final ItemStack offHand = evt.getPlayer().getInventory().getItemInOffHand();

		if(canFeedToTame(evt.getRightClicked())){
			final int amtMainHand = mainHand == null ? 0 : mainHand.getAmount(), amtOffHand = offHand == null ? 0 : offHand.getAmount();
			new BukkitRunnable(){@Override public void run(){
				ItemStack newMainHand = evt.getPlayer().getInventory().getItemInMainHand();
				ItemStack newOffHand = evt.getPlayer().getInventory().getItemInOffHand();
				if(amtMainHand == (newMainHand == null ? 1 : newMainHand.getAmount()+1)){
					doHeadDropRoll(evt.getPlayer(), evt.getRightClicked(), evt, mainHand);
				}
				else if(amtOffHand == (newOffHand == null ? 1 : newOffHand.getAmount()+1)){
					doHeadDropRoll(evt.getPlayer(), evt.getRightClicked(), evt, offHand);
				}
			}}.runTaskLater(this, 1);
		}
		else if(canFeedToHealForHeadDrop(evt.getRightClicked())){
			RegainHealthListener listener = new RegainHealthListener(evt.getPlayer(), mainHand, offHand);
			getServer().getPluginManager().registerEvents(listener, this);
			new BukkitRunnable(){@Override public void run(){HandlerList.unregisterAll(listener);}}.runTaskLater(this, 1);
		}
		else if(canFeedToBreedForHeadDrop(evt.getRightClicked())){
			Animals animal = (Animals)evt.getRightClicked();
			ItemStack breedFood = (mainHand != null && (offHand == null || animal.isBreedItem(mainHand) || !animal.isBreedItem(offHand))) ? mainHand : offHand;
			LoveModeListener listener = new LoveModeListener(breedFood);
			getServer().getPluginManager().registerEvents(listener, this);
			new BukkitRunnable(){@Override public void run(){HandlerList.unregisterAll(listener);}}.runTaskLater(this, 1);
		}
	}
}