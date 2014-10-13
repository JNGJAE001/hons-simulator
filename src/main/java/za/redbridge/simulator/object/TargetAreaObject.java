package za.redbridge.simulator.object;

import org.jbox2d.callbacks.QueryCallback;
import org.jbox2d.collision.AABB;
import org.jbox2d.common.Vec2;
import org.jbox2d.dynamics.Body;
import org.jbox2d.dynamics.BodyType;
import org.jbox2d.dynamics.Fixture;
import org.jbox2d.dynamics.World;
import org.jbox2d.dynamics.contacts.Contact;

import java.awt.Color;
import java.awt.Paint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sim.engine.SimState;
import za.redbridge.simulator.FitnessStats;
import za.redbridge.simulator.phenotype.Phenotype;
import za.redbridge.simulator.physics.BodyBuilder;
import za.redbridge.simulator.physics.Collideable;
import za.redbridge.simulator.physics.FilterConstants;
import za.redbridge.simulator.portrayal.Portrayal;
import za.redbridge.simulator.portrayal.RectanglePortrayal;

import static za.redbridge.simulator.Utils.resizeAABB;

/**
 * Created by shsu on 2014/08/13.
 */
public class TargetAreaObject extends PhysicalObject implements Collideable {

    private static final boolean ALLOW_REMOVAL = true;

    private static final float BLAME_BOX_EXPANSION_RATE = 1.05f;
    private static final int BLAME_BOX_TRIES = 5;

    private int width, height;
    private final AABB aabb;

    //total resource value in this target area
    private Map<Phenotype,FitnessStats> fitnesses = new HashMap<>();

    //hash set so that object values only get added to forage area once
    private final Set<ResourceObject> containedObjects = new HashSet<>();
    private final List<Fixture> watchedFixtures = new ArrayList<>();

    //keeps track of what has been pushed into this place
    public TargetAreaObject(World world, Vec2 position, int width, int height) {
        super(createPortrayal(width, height), createBody(world, position, width, height));

        this.width = width;
        this.height = height;

        aabb = getBody().getFixtureList().getAABB(0);
    }

    protected static Portrayal createPortrayal(int width, int height) {
        Paint areaColour = new Color(31, 110, 11, 100);
        return new RectanglePortrayal(width, height, areaColour, true);
    }

    protected static Body createBody(World world, Vec2 position, int width, int height) {
        BodyBuilder bb = new BodyBuilder();
        return bb.setBodyType(BodyType.STATIC)
                .setPosition(position)
                .setRectangular(width, height)
                .setSensor(true)
                .setFilterCategoryBits(FilterConstants.CategoryBits.TARGET_AREA)
                .setFilterMaskBits(FilterConstants.CategoryBits.RESOURCE
                        | FilterConstants.CategoryBits.TARGET_AREA_SENSOR)
                .build(world);
    }

    @Override
    public void step(SimState simState) {
        super.step(simState);

        // Check if any objects have passed into the target area completely or have left
        for (Fixture fixture : watchedFixtures) {
            ResourceObject resource = (ResourceObject) fixture.getBody().getUserData();
            if (aabb.contains(fixture.getAABB(0))) {
                // Object moved completely into the target area
                addResource(resource);
            } else if (ALLOW_REMOVAL) {
                // Object moved out of completely being within the target area
                removeResource(resource);
            }
        }
    }

    private void addResource(ResourceObject resource) {
        if (containedObjects.add(resource)) {
            // Mark resource as collected
            resource.setCollected(true);
            resource.getPortrayal().setPaint(Color.CYAN);

            List<Phenotype> pushingPhenotypes = blamePhenotypes(resource);

            for(Phenotype p : pushingPhenotypes){
                FitnessStats newFitness = new FitnessStats();
                FitnessStats existingFitness = fitnesses.putIfAbsent(p, newFitness);

                FitnessStats stats = existingFitness != null ? existingFitness : newFitness;
                stats.addTaskFitness(resource.getValue() / pushingPhenotypes.size());
                stats.addRetrievedResource(resource, pushingPhenotypes.size());
            }
        }
    }

    private void removeResource(ResourceObject resource) {
        if (containedObjects.remove(resource)) {
            // Mark resource as no longer collected
            resource.setCollected(false);
            resource.getPortrayal().setPaint(Color.MAGENTA);

            List<Phenotype> pushingPhenotypes = blamePhenotypes(resource);

            // Update phenotype fitness values
            for(Phenotype p : pushingPhenotypes){
                FitnessStats newFitness = new FitnessStats();
                FitnessStats existingFitness = fitnesses.putIfAbsent(p, newFitness);

                FitnessStats stats = existingFitness != null ? existingFitness : newFitness;
                stats.addTaskFitness(-resource.getValue() / pushingPhenotypes.size());
            }
        }
    }

    /*
     * Finds robots very close to the ResourceObject that can be blamed for pushing the resource
     * in/out of target area.
     */
    private List<Phenotype> blamePhenotypes(ResourceObject resource) {
        // Check which robots pushed the resource out based on a bounding box
        Fixture resourceFixture = resource.getBody().getFixtureList();
        AABB resourceBox = resourceFixture.getAABB(0);

        // Try query robots within the AABB of the resource
        RobotObjectQueryCallback callback = new RobotObjectQueryCallback();
        getBody().getWorld().queryAABB(callback, resourceBox);

        List<Phenotype> phenotypes = callback.getNearbyPhenotypes();
        if (!phenotypes.isEmpty()) {
            return phenotypes;
        }

        // If no robots found, iteratively expand the dimensions of the query box
        AABB blameBox = new AABB(resourceBox);
        for (int i = 0; i < BLAME_BOX_TRIES; i++) {
            float width = (blameBox.upperBound.x - blameBox.lowerBound.x)
                    * BLAME_BOX_EXPANSION_RATE;
            float height = (blameBox.upperBound.y - blameBox.upperBound.y)
                    * BLAME_BOX_EXPANSION_RATE;
            resizeAABB(blameBox, width, height);
            getBody().getWorld().queryAABB(callback, blameBox);

            if (!phenotypes.isEmpty()) {
                break;
            }
        }

        return phenotypes;
    }

    public int getNumberOfContainedResources() {
        return containedObjects.size();
    }

    public AABB getAabb() {
        return aabb;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Map<Phenotype, FitnessStats> getFitnesses() {
        return fitnesses;
    }

    @Override
    public void handleBeginContact(Contact contact, Fixture otherFixture) {
        if (!(otherFixture.getBody().getUserData() instanceof ResourceObject)) {
            return;
        }

        if (!watchedFixtures.contains(otherFixture)) {
            watchedFixtures.add(otherFixture);
        }
    }

    @Override
    public void handleEndContact(Contact contact, Fixture otherFixture) {
        if (!(otherFixture.getBody().getUserData() instanceof ResourceObject)) {
            return;
        }

        // Remove from watch list
        watchedFixtures.remove(otherFixture);

        // Remove from the score
        if (ALLOW_REMOVAL) {
            ResourceObject resource = (ResourceObject) otherFixture.getBody().getUserData();
            removeResource(resource);
        }
    }

    private static class RobotObjectQueryCallback implements QueryCallback {

        private List<Phenotype> nearbyPhenotypes = new ArrayList<>();

        public boolean reportFixture(Fixture fixture) {

            if (fixture.getBody().getUserData() instanceof RobotObject) {

                nearbyPhenotypes.add(((RobotObject) fixture.getBody().getUserData()).getPhenotype());
            }

            return true;
        }

        public List<Phenotype> getNearbyPhenotypes() { return nearbyPhenotypes; }
    }

}
