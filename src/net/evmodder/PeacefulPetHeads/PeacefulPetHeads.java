package net.evmodder.PeacefulPetHeads;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Random;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEnterLoveModeEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import net.evmodder.DropHeads.DropHeads;
import net.evmodder.DropHeads.JunkUtils;
import net.evmodder.DropHeads.events.EntityBeheadEvent;
import net.evmodder.DropHeads.events.HeadRollEvent;
import net.evmodder.EvLib.extras.TextUtils;
import net.evmodder.EvLib.extras.TellrawUtils.ListComponent;
import net.evmodder.EvLib.extras.TellrawUtils.SelectorComponent;

public final class PeacefulPetHeads extends JavaPlugin implements Listener{
	private FileConfiguration config;
	private HashSet<EntityType> petsToHeal, petsToBreed, petsToTame;
	private String MSG_HEAD_FROM_FEEDING;
	private final boolean USE_PLAYER_DISPLAYNAMES = false;//TODO: move to config, when possible
	private int JSON_LIMIT;
	private DropHeads dropheadsPlugin = null;
	private Random rand;

	private DropHeads getDropHeadsPlugin(){
		if(dropheadsPlugin == null) dropheadsPlugin = (DropHeads)getServer().getPluginManager().getPlugin("DropHeads");
		return dropheadsPlugin;
	}

	@Override public FileConfiguration getConfig(){return config;}
	@Override public void saveConfig(){
		if(config != null && !FileIO.saveConfig("config-"+getName()+".yml", config)){
			getLogger().severe("Error while saving plugin configuration file!");
		}
	}
	@Override public void reloadConfig(){
		InputStream defaultConfig = getClass().getResourceAsStream("/config.yml");
		if(defaultConfig != null){
			FileIO.verifyDir(getDropHeadsPlugin());
			config = FileIO.loadConfig(this, "config-"+getName()+".yml", defaultConfig, /*notifyIfNew=*/true);
		}
	}

	@Override public void onEnable(){
		reloadConfig();
		petsToHeal = new HashSet<>();
		for(String entityTypeName : config.getStringList("feeding-to-heal-triggers-head-drop-chance")){
			try{petsToHeal.add(EntityType.valueOf(entityTypeName.toUpperCase()));}
			catch(IllegalArgumentException ex){getLogger().severe("Unknown entity in 'feeding-to-heal' setting: "+entityTypeName);}
		}
		petsToBreed = new HashSet<>();
		for(String entityTypeName : config.getStringList("feeding-to-breed-triggers-head-drop-chance")){
			try{petsToBreed.add(EntityType.valueOf(entityTypeName.toUpperCase()));}
			catch(IllegalArgumentException ex){getLogger().severe("Unknown entity in 'feeding-to-breed' setting: "+entityTypeName);}
		}
		petsToTame = new HashSet<>();
		for(String entityTypeName : config.getStringList("taming-triggers-head-drop-chance")){
			try{petsToTame.add(EntityType.valueOf(entityTypeName.toUpperCase()));}
			catch(IllegalArgumentException ex){getLogger().severe("Unknown entity in 'taming' setting: "+entityTypeName);}
		}
		MSG_HEAD_FROM_FEEDING = TextUtils.translateAlternateColorCodes('&', config.getString("message-for-awarding-head",
				"&6${PLAYER}&r was awarded the head of &6${ENTITY}&r by feeding them"));

		JSON_LIMIT = getDropHeadsPlugin().getConfig().getInt("message-json-limit", 15000);
		rand = new Random();
		getServer().getPluginManager().registerEvents(this, this);

		if(config.getBoolean("entities-listed-here-do-not-drop-heads-when-killed", false)){
			getServer().getPluginManager().registerEvents(new Listener(){
				@EventHandler
				public void onEntityBehead(EntityBeheadEvent evt){
					if((petsToHeal.contains(evt.getEntityType()) ||
						petsToBreed.contains(evt.getEntityType()) ||
						petsToTame.contains(evt.getEntityType())) &&
							!(evt.getSourceEvent() instanceof EntityRegainHealthEvent) &&
							!(evt.getSourceEvent() instanceof EntityEnterLoveModeEvent) &&
							!(evt.getSourceEvent() instanceof EntityTameEvent))
					{
						evt.setCancelled(true);
					}
				}
			}, this);
		}
	}

	void doHeadDropRoll(Entity feeder, Entity pet, Event triggerEvt, ItemStack foodItem){
		final double rawDropChance = getDropHeadsPlugin().getDropChanceAPI().getRawDropChance(pet);
		final double permMod = getDropHeadsPlugin().getDropChanceAPI().getPermsBasedDropRateModifier(feeder);
		final double dropChance = rawDropChance*permMod;
		final double dropRoll = rand.nextDouble();
		final HeadRollEvent rollEvent = new HeadRollEvent(feeder, pet, dropChance, dropRoll, dropRoll < dropChance);
		getServer().getPluginManager().callEvent(rollEvent);
		getLogger().info("entity: "+pet.getType()+", feeder: "+feeder.getName()+", evt: "+triggerEvt.getEventName());
		if(!rollEvent.getDropSuccess()) return;

		feeder.sendMessage(MSG_HEAD_FROM_FEEDING);
		ListComponent message = new ListComponent();
		message.addComponent(MSG_HEAD_FROM_FEEDING
				// Some aliases
				.replace("${PET}", "${ENTITY}").replace("${FEEDER}", "${PLAYER}").replace("${FOOD}", "${ITEM}"));
		message.replaceRawDisplayTextWithComponent("${ENTITY}", new SelectorComponent(pet.getUniqueId(), USE_PLAYER_DISPLAYNAMES));
		message.replaceRawDisplayTextWithComponent("${PLAYER}", new SelectorComponent(feeder.getUniqueId(), USE_PLAYER_DISPLAYNAMES));
		message.replaceRawDisplayTextWithComponent("${ITEM}", JunkUtils.getMurderItemComponent(foodItem, JSON_LIMIT));
		getDropHeadsPlugin().getDropChanceAPI().triggerHeadDropEvent(pet, feeder, triggerEvt, foodItem, message);
	}
	
	class RegainHealthListener implements Listener{
		Entity feeder;
		ItemStack food;
		RegainHealthListener(Entity feeder, ItemStack food){this.feeder = feeder; this.food = food;}
		@EventHandler
		public void onEntityHeal(EntityRegainHealthEvent evt){
			if(evt.getRegainReason() == RegainReason.EATING && petsToHeal.contains(evt.getEntity().getType())){
				doHeadDropRoll(feeder, evt.getEntity(), evt, food);
			}
			HandlerList.unregisterAll(this);
		}
	}

	class LoveModeListener implements Listener{
		ItemStack food;
		LoveModeListener(ItemStack food){this.food = food;}
		@EventHandler
		public void onEntityLove(EntityEnterLoveModeEvent evt){
			if(petsToBreed.contains(evt.getEntity().getType())){
				doHeadDropRoll(evt.getHumanEntity(), evt.getEntity(), evt, food);
			}
			HandlerList.unregisterAll(this);
		}
	}

	@EventHandler
	public void onClick(PlayerInteractEntityEvent evt){
		// TODO: Sometimes the food item consumed comes from the offhand, not main hand!!
		if(petsToHeal.contains(evt.getRightClicked().getType())){
			RegainHealthListener listener = new RegainHealthListener(evt.getPlayer(), evt.getPlayer().getInventory().getItemInMainHand());
			getServer().getPluginManager().registerEvents(listener, this);
			new BukkitRunnable(){@Override public void run(){HandlerList.unregisterAll(listener);}}.runTaskLater(this, 1);
		}
		if(petsToBreed.contains(evt.getRightClicked().getType())){
			LoveModeListener listener = new LoveModeListener(evt.getPlayer().getInventory().getItemInMainHand());
			getServer().getPluginManager().registerEvents(listener, this);
			new BukkitRunnable(){@Override public void run(){HandlerList.unregisterAll(listener);}}.runTaskLater(this, 1);
		}
	}

	@EventHandler
	public void onTame(EntityTameEvent evt){
		if(petsToTame.contains(evt.getEntity().getType()) && evt.getOwner() instanceof Player){
			Player player = (Player)evt.getOwner();
			doHeadDropRoll(player, evt.getEntity(), evt, player.getInventory().getItemInMainHand());
		}
	}
}