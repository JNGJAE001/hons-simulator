package za.redbridge.simulator.object;

import org.jbox2d.common.Rot;
import org.jbox2d.common.Transform;
import org.jbox2d.common.Vec2;
import org.jbox2d.dynamics.Body;
import org.jbox2d.dynamics.BodyType;
import org.jbox2d.dynamics.World;
import org.jbox2d.dynamics.joints.Joint;
import org.jbox2d.dynamics.joints.JointDef;
import org.jbox2d.dynamics.joints.WeldJointDef;

import java.awt.Color;
import java.awt.Paint;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

import sim.engine.SimState;
import sim.util.Double2D;
import za.redbridge.simulator.physics.BodyBuilder;
import za.redbridge.simulator.portrayal.Portrayal;
import za.redbridge.simulator.portrayal.RectanglePortrayal;

/**
 * Object to represent the resources in the environment. Has a value and a weight.
 *
 * Created by jamie on 2014/07/23.
 */
public class ResourceObject extends PhysicalObject {

    private static final Paint DEFAULT_COLOUR = new Color(255, 235, 82);

    private enum Side {
        LEFT, RIGHT, TOP, BOTTOM
    }
    private Side stickySide;

    private final double value;

    private double width, height;
    private boolean isCollected = false;

    //keep track of all the bots attached to this ResourceObject
    private Map<RobotObject, Joint> jointList;

    //keep track of all the pending joints that need to be made and their corresponding bots
    private Stack<JointDef> pendingJoints;
    private Stack<RobotObject> pendingRobots;

    public ResourceObject(World world, Double2D position, double width, double height, double mass,
                          double value) {
        super(createPortrayal(width, height),
                createBody(world, position, width, height, mass));
        this.value = value;
        this.width = width;
        this.height = height;

        jointList = new HashMap<>();
        pendingJoints = new Stack<>();
        pendingRobots = new Stack<>();
    }

    protected static Portrayal createPortrayal(double width, double height) {
        return new RectanglePortrayal(width, height, DEFAULT_COLOUR, true);
    }

    protected static Body createBody(World world, Double2D position, double width, double height,
                                     double mass) {
        BodyBuilder bb = new BodyBuilder();
        return bb.setBodyType(BodyType.DYNAMIC)
                .setPosition(position)
                .setRectangular(width, height, mass)
                .setFriction(0.9f)
                .setRestitution(1.0f)
                .build(world);
    }

    public double getValue() {
        return value;
    }

    @Override
    public void step(SimState simState) {
        super.step(simState);
        while (!pendingJoints.empty() && !pendingRobots.empty()) {
                Joint joint = getBody().getWorld().createJoint(pendingJoints.pop());
                jointList.put(pendingRobots.pop(), joint);
        }
    }

    public void tryPickup(RobotObject robot) {
        if (isCollected) {
            return;
        }

        if (jointList.get(robot) != null && pendingRobots.contains(robot)) {
            return;
        }

        Body resourceBody = getBody();
        Body robotBody = robot.getBody();

        Vec2 anchorA;
        float referenceAngle;
        switch (getSideClosestToPoint(robotBody.getPosition())) {
            case LEFT:
                anchorA = new Vec2((float) width / 2, 0);
                referenceAngle = (float) Math.PI;
                break;
            case RIGHT:
                anchorA = new Vec2((float) -width / 2, 0);
                referenceAngle = 0f;
                break;
            case TOP:
                anchorA = new Vec2(0, (float) height / 2);
                referenceAngle = (float) -Math.PI / 2;
                break;
            case BOTTOM:
                anchorA = new Vec2(0, (float) -height / 2);
                referenceAngle = (float) Math.PI / 2;
                break;
            default:
                throw new RuntimeException("Unable to match robot position to side");
        }

        WeldJointDef wjd = new WeldJointDef();
        wjd.bodyA = resourceBody;
        wjd.bodyB = robotBody;
        wjd.referenceAngle = referenceAngle;
        wjd.localAnchorA.set(anchorA);
        wjd.localAnchorB.set(robot.getRadius() + 0.01f, 0f); // Attach to front of robot
        wjd.collideConnected = true;

        pendingJoints.push(wjd);
        pendingRobots.push(robot);

        // Mark the robot as bound
        robot.setBoundToResource(true);
    }

    private Side getSideClosestToPoint(Vec2 point) {
        Vec2 relativePoint = Transform.mulTrans(getBody().getTransform(), point);
        final Side side;
        if (Math.abs(relativePoint.x) > Math.abs(relativePoint.y)) {
            if (relativePoint.x > 0) {
                side = Side.RIGHT;
            } else {
                side = Side.LEFT;
            }
        } else {
            if (relativePoint.y > 0) {
                side = Side.TOP;
            } else {
                side = Side.BOTTOM;
            }
        }
        return side;
    }

    //works out if an attachment is happening on a 'valid' side of the resource
    public boolean isValidAttachment (Body resourceBody, Vec2 anchor) {

        Vec2 anchorOnResource = new Vec2();
        resourceBody.getLocalPointToOut(anchor, anchorOnResource);

        //get normalised angle
        float orientation = getBody().getAngle();

        Rot rot = new Rot(orientation);
        Transform boxTransform = new Transform(getBody().getLocalCenter(), rot);

        Vec2 rotatedAnchor = Transform.mul(boxTransform, anchorOnResource);

        return rotatedAnchor.y <= 0 && rotatedAnchor.y >= -height/2;
    }

    /**
     * Check whether this object has been collected
     * @return true if the object is in the target area
     */
    public boolean isCollected() {
        return isCollected;
    }

    /**
     * Mark this object as collected. i.e. mark it as being in the target area
     */
    public void markCollected() {
        this.isCollected = true;
        breakRobotWeldJoint();
    }

    private void breakRobotWeldJoint() {

        for (RobotObject r: jointList.keySet()) {
            Joint robotJoint = jointList.get(r);
            RobotObject robot = (RobotObject) robotJoint.getBodyB().getUserData();
            robot.setBoundToResource(false);
            getBody().getWorld().destroyJoint(robotJoint);
        }

    }
}
