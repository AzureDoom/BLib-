package com.blib.api.client.model.v1;

import org.jetbrains.annotations.Nullable;

import java.util.*;

public class AzBakedModel {

    private static AzBakedModel defaultModel = new AzBakedModel(List.of());

    private final Map<String, AzBone> bonesByName;

    private final List<AzBone> topLevelBones;

    /** Every bone, in breadth-first order. Backed by an array list so per-frame loops can index it. */
    private final List<AzBone> allBones;

    private final UUID modelUUID;

    public AzBakedModel(List<AzBone> topLevelBones) {
        this(topLevelBones, UUID.randomUUID());
    }

    /**
     * @param modelUUID identifies the baked source model. Deep copies share their template's id, so a per-instance copy
     *                  can tell when the shared model it was copied from has been replaced (e.g. by a resource reload).
     */
    public AzBakedModel(List<AzBone> topLevelBones, UUID modelUUID) {
        this.topLevelBones = Collections.unmodifiableList(topLevelBones);
        this.bonesByName = Collections.unmodifiableMap(mapBonesByName(topLevelBones));
        this.allBones = Collections.unmodifiableList(collectBones(topLevelBones));
        this.modelUUID = modelUUID;
    }

    private Map<String, AzBone> mapBonesByName(List<AzBone> bones) {
        var bonesByName = new HashMap<String, AzBone>();
        var nodesToMap = new ArrayDeque<>(bones);

        while (!nodesToMap.isEmpty()) {
            var currentBone = nodesToMap.poll();
            nodesToMap.addAll(currentBone.getChildBones());
            currentBone.saveInitialSnapshot();
            bonesByName.put(currentBone.getName(), currentBone);
        }

        return bonesByName;
    }

    private static List<AzBone> collectBones(List<AzBone> bones) {
        var all = new ArrayList<AzBone>();
        var nodes = new ArrayDeque<>(bones);

        while (!nodes.isEmpty()) {
            var bone = nodes.poll();
            nodes.addAll(bone.getChildBones());
            all.add(bone);
        }

        return all;
    }

    /**
     * Every bone in the model, in breadth-first order. Random access: loop over it by index in per-frame code, which
     * avoids the iterator {@code getBonesByName().values()} allocates.
     */
    public List<AzBone> getAllBones() {
        return allBones;
    }

    public AzBakedModel deepCopy() {
        List<AzBone> copied = new ArrayList<>(this.topLevelBones.size());
        for (AzBone bone : this.topLevelBones) {
            copied.add(bone.deepCopy()); // each child deepCopy() calls saveInitialSnapshot()
        }
        return new AzBakedModel(copied, this.modelUUID); // this will rebuild bonesByName internally
    }

    public @Nullable AzBone getBoneOrNull(String name) {
        return bonesByName.get(name);
    }

    public Optional<AzBone> getBone(String name) {
        return Optional.ofNullable(getBoneOrNull(name));
    }

    public Map<String, AzBone> getBonesByName() {
        return bonesByName;
    }

    public List<AzBone> getTopLevelBones() {
        return topLevelBones;
    }

    /** Identifies the baked source model; shared by all deep copies of it. */
    public UUID getModelUUID() {
        return modelUUID;
    }

    public static AzBakedModel getDefault() {
        return defaultModel;
    }

    public static void setDefault(AzBakedModel model) {
        defaultModel = model != null ? model : new AzBakedModel(List.of());
    }
}
