package com.blib.api.common.dismemberment.v1;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.blib.api.common.data_sync.v1.DataAccessor;
import com.blib.api.common.data_sync.v1.model.DataUser;
import com.blib.api.common.nbt.v1.model.NBTSerializable;
import com.blib.mod.common.registry.init.BLibDataSyncKeys;

/**
 * Per-entity component tracking which limbs have been detached.
 * <p>
 * The authoritative state lives on the server. The same set is mirrored to watching clients through
 * {@code BLibDataSyncKeys.ENTITY_DETACHED_LIMBS} so the renderer's bone visibility filter can hide the corresponding
 * bones.
 * <p>
 * NBT persistence is handled directly here (rather than through {@code DataContainer}) because the underlying container
 * only persists primitive values today.
 */
public final class DismembermentManager implements NBTSerializable {

    private static final String NBT_KEY = "DetachedLimbs";

    private static final String LIMB_DAMAGE_NBT_KEY = "LimbDamage";

    private static final String LIMB_DAMAGE_ID_NBT_KEY = "Id";

    private static final String LIMB_DAMAGE_VALUE_NBT_KEY = "Damage";

    private final LivingEntity entity;

    private final DataAccessor<List<ResourceLocation>> detachedLimbsAccessor;

    private final DataAccessor<List<LimbDamageState>> limbDamageAccessor;

    private Set<ResourceLocation> detachedLimbs;

    private transient Set<ResourceLocation> detachedLimbsView;

    private transient Set<ResourceLocation> viewSource;

    private final Map<ResourceLocation, Float> limbDamage = new HashMap<>();

    public DismembermentManager(LivingEntity entity) {
        if (!(entity instanceof DataUser)) {
            throw new IllegalArgumentException(
                "Entity " + entity.getType() + " must implement DataUser to use a DismembermentManager"
            );
        }

        this.entity = entity;
        this.detachedLimbsAccessor = new DataAccessor<>((DataUser) entity, BLibDataSyncKeys.ENTITY_DETACHED_LIMBS.get());
        this.limbDamageAccessor = new DataAccessor<>((DataUser) entity, BLibDataSyncKeys.ENTITY_LIMB_DAMAGE.get());
        this.detachedLimbs = new HashSet<>();
        this.detachedLimbsAccessor.onChange(this::onDetachedLimbsChanged);
        this.detachedLimbsAccessor.onLoad(this::onDetachedLimbsChanged);
        this.limbDamageAccessor.onChange(this::onLimbDamageChanged);
        this.limbDamageAccessor.onLoad(this::onLimbDamageChanged);
    }

    public LivingEntity getEntity() {
        return entity;
    }

    /** Read-only view of the detached limbs. The view is cached; it isn't a new object per call. */
    public Set<ResourceLocation> getDetachedLimbIds() {
        var view = detachedLimbsView;

        // detachedLimbs can be replaced (e.g. on load/sync), so rebuild the view if it points at an old set.
        if (view == null || viewSource != detachedLimbs) {
            viewSource = detachedLimbs;
            view = detachedLimbsView = Collections.unmodifiableSet(detachedLimbs);
        }

        return view;
    }

    public boolean isDetached(ResourceLocation limbId) {
        return detachedLimbs.contains(limbId);
    }

    public boolean isDetached(LimbDefinition limbDefinition) {
        return detachedLimbs.contains(limbDefinition.id());
    }

    public boolean hasAnyDetached() {
        return !detachedLimbs.isEmpty();
    }

    /** Returns the accumulated firearm damage for one limb pool. */
    public float getLimbDamage(ResourceLocation limbId) {
        return limbDamage.getOrDefault(limbId, 0.0F);
    }

    /**
     * Server-side: add damage to one limb pool and return the new accumulated amount. Detached limbs cannot be damaged
     * further.
     */
    public float addLimbDamage(ResourceLocation limbId, float damage) {
        if (entity.level().isClientSide || damage <= 0.0F || isDetached(limbId)) {
            return getLimbDamage(limbId);
        }

        var updated = getLimbDamage(limbId) + damage;
        limbDamage.put(limbId, updated);
        publishLimbDamage();
        return updated;
    }

    /**
     * Server-side: lower still-attached limb pools by restored health. Detached limbs remain detached.
     */
    public void healLimbDamage(float healedHealth) {
        if (entity.level().isClientSide || healedHealth <= 0.0F || limbDamage.isEmpty()) {
            return;
        }

        limbDamage.replaceAll((limbId, damage) -> Math.max(0.0F, damage - healedHealth));
        limbDamage.entrySet().removeIf(entry -> entry.getValue() <= 0.0F);
        publishLimbDamage();
    }

    /**
     * Server-side: marks a limb as detached. Returns true if state changed.
     */
    public boolean markDetached(ResourceLocation limbId) {
        if (entity.level().isClientSide) {
            return false;
        }

        if (!detachedLimbs.add(limbId)) {
            return false;
        }

        limbDamage.remove(limbId);
        publishToAccessor();
        return true;
    }

    /**
     * Server-side: marks a limb as reattached/restored. Returns true if state changed.
     */
    public boolean markReattached(ResourceLocation limbId) {
        if (entity.level().isClientSide) {
            return false;
        }

        if (!detachedLimbs.remove(limbId)) {
            return false;
        }

        publishToAccessor();
        return true;
    }

    public void clear() {
        if (entity.level().isClientSide || (detachedLimbs.isEmpty() && limbDamage.isEmpty())) {
            return;
        }

        detachedLimbs.clear();
        limbDamage.clear();
        publishToAccessor();
    }

    private void publishToAccessor() {
        detachedLimbsAccessor.set(List.copyOf(detachedLimbs));
        publishLimbDamage();
    }

    private void publishLimbDamage() {
        var states = limbDamage.entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new LimbDamageState(entry.getKey(), entry.getValue()))
            .toList();
        limbDamageAccessor.set(states);
    }

    private void onDetachedLimbsChanged(List<ResourceLocation> incoming) {
        detachedLimbs = new HashSet<>(incoming);
    }

    private void onLimbDamageChanged(List<LimbDamageState> incoming) {
        limbDamage.clear();
        for (var state : incoming) {
            if (state.damage() > 0.0F && !detachedLimbs.contains(state.limbId())) {
                limbDamage.put(state.limbId(), state.damage());
            }
        }
    }

    @Override
    public void load(CompoundTag compoundTag) {
        var loaded = new HashSet<ResourceLocation>();
        if (compoundTag.contains(NBT_KEY, Tag.TAG_LIST)) {
            var listTag = compoundTag.getList(NBT_KEY, Tag.TAG_STRING);

            for (var i = 0; i < listTag.size(); i++) {
                var raw = listTag.getString(i);
                var parsed = ResourceLocation.tryParse(raw);

                if (parsed != null) {
                    loaded.add(parsed);
                }
            }
        }

        detachedLimbs = loaded;
        limbDamage.clear();

        if (compoundTag.contains(LIMB_DAMAGE_NBT_KEY, Tag.TAG_LIST)) {
            var damageList = compoundTag.getList(LIMB_DAMAGE_NBT_KEY, Tag.TAG_COMPOUND);
            for (var i = 0; i < damageList.size(); i++) {
                var entry = damageList.getCompound(i);
                var id = ResourceLocation.tryParse(entry.getString(LIMB_DAMAGE_ID_NBT_KEY));
                var damage = entry.getFloat(LIMB_DAMAGE_VALUE_NBT_KEY);
                if (id != null && damage > 0.0F && !detachedLimbs.contains(id)) {
                    limbDamage.put(id, damage);
                }
            }
        }

        if (!entity.level().isClientSide) {
            publishToAccessor();
        }
    }

    @Override
    public void save(CompoundTag compoundTag) {
        if (detachedLimbs.isEmpty() && limbDamage.isEmpty()) {
            return;
        }

        var listTag = new ListTag();

        for (var id : detachedLimbs) {
            listTag.add(StringTag.valueOf(id.toString()));
        }

        compoundTag.put(NBT_KEY, listTag);

        if (!limbDamage.isEmpty()) {
            var damageList = new ListTag();
            for (var entry : limbDamage.entrySet()) {
                var damageTag = new CompoundTag();
                damageTag.putString(LIMB_DAMAGE_ID_NBT_KEY, entry.getKey().toString());
                damageTag.putFloat(LIMB_DAMAGE_VALUE_NBT_KEY, entry.getValue());
                damageList.add(damageTag);
            }
            compoundTag.put(LIMB_DAMAGE_NBT_KEY, damageList);
        }
    }

    public static boolean isDetached(Entity entity, ResourceLocation limbId) {
        return entity instanceof Dismemberable dismemberable
            && dismemberable.getDismembermentManager().isDetached(limbId);
    }
}
