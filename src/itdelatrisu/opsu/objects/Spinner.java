/*
 * opsu! - an open-source osu! client
 * Copyright (C) 2014, 2015 Jeffrey Han
 *
 * opsu! is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * opsu! is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with opsu!.  If not, see <http://www.gnu.org/licenses/>.
 */

package itdelatrisu.opsu.objects;

import itdelatrisu.opsu.GameData;
import itdelatrisu.opsu.GameData.HitObjectType;
import itdelatrisu.opsu.GameImage;
import itdelatrisu.opsu.GameMod;
import itdelatrisu.opsu.Options;
import itdelatrisu.opsu.Utils;
import itdelatrisu.opsu.audio.SoundController;
import itdelatrisu.opsu.audio.SoundEffect;
import itdelatrisu.opsu.beatmap.HitObject;
import itdelatrisu.opsu.states.Game;

import org.newdawn.slick.Color;
import org.newdawn.slick.GameContainer;
import org.newdawn.slick.Graphics;
import org.newdawn.slick.Image;

/**
 * Data type representing a spinner object.
 */
public class Spinner implements GameObject {
	/** Container dimensions. */
	private static int width, height;

	/** The map's overall difficulty value. */
	private static float overallDifficulty = 5f;

	/** The number of rotation velocities to store. */
	// note: currently takes about 200ms to spin up (4 * 50)
	private static final int MAX_ROTATION_VELOCITIES = 50;

	/** The amount of time, in milliseconds, before another velocity is stored. */
	private static final int DELTA_UPDATE_TIME = 4;

	/** The amount of time, in milliseconds, to fade in the spinner. */
	private static final int FADE_IN_TIME = 500;

	/** Angle mod multipliers: "auto" (477rpm), "spun out" (287rpm) */
	private static final float
		AUTO_MULTIPLIER = 1 / 20f,         // angle = 477/60f * delta/1000f * TWO_PI;
		SPUN_OUT_MULTIPLIER = 1 / 33.25f;  // angle = 287/60f * delta/1000f * TWO_PI;

	/** PI constants. */
	private static final float
		TWO_PI  = (float) (Math.PI * 2),
		HALF_PI = (float) (Math.PI / 2);

	/** The associated HitObject. */
	private HitObject hitObject;

	/** The associated GameData object. */
	private GameData data;

	/** The last rotation angle. */
	private float lastAngle = 0f;

	/** The current number of rotations. */
	private float rotations = 0f;

	/** The current rotation to draw. */
	private float drawRotation = 0f;

	/** The total number of rotations needed to clear the spinner. */
	private float rotationsNeeded;

	/** The remaining amount of time that was not used. */
	private int deltaOverflow;

	/** The sum of all the velocities in storedVelocities. */
	private float sumVelocity = 0f;

	/** Array holding the most recent rotation velocities. */
	private float[] storedVelocities = new float[MAX_ROTATION_VELOCITIES];

	/** True if the mouse cursor is pressed. */
	private boolean isSpinning;

	/** Current index of the stored velocities in rotations/second. */
	private int velocityIndex = 0;

	/**
	 * Initializes the Spinner data type with images and dimensions.
	 * @param container the game container
	 * @param difficulty the map's overall difficulty value
	 */
	public static void init(GameContainer container, float difficulty) {
		width  = container.getWidth();
		height = container.getHeight();
		overallDifficulty = difficulty;
	}

	/**
	 * Constructor.
	 * @param hitObject the associated HitObject
	 * @param game the associated Game object
	 * @param data the associated GameData object
	 */
	public Spinner(HitObject hitObject, Game game, GameData data) {
		this.hitObject = hitObject;
		this.data = data;

		// calculate rotations needed
		float spinsPerMinute = 100 + (overallDifficulty * 15);
		rotationsNeeded = spinsPerMinute * (hitObject.getEndTime() - hitObject.getTime()) / 60000f;
	}

	@Override
	public void draw(Graphics g, int trackPosition) {
		// only draw spinners shortly before start time
		int timeDiff = hitObject.getTime() - trackPosition;
		if (timeDiff - FADE_IN_TIME > 0)
			return;

		boolean spinnerComplete = (rotations >= rotationsNeeded);
		float alpha = Utils.clamp(1 - (float) timeDiff / FADE_IN_TIME, 0f, 1f);

		// darken screen
		if (Options.getSkin().isSpinnerFadePlayfield()) {
			float oldAlpha = Utils.COLOR_BLACK_ALPHA.a;
			if (timeDiff > 0)
				Utils.COLOR_BLACK_ALPHA.a *= alpha;
			g.setColor(Utils.COLOR_BLACK_ALPHA);
			g.fillRect(0, 0, width, height);
			Utils.COLOR_BLACK_ALPHA.a = oldAlpha;
		}

		// rpm
		int rpm = Math.abs(Math.round(sumVelocity / storedVelocities.length * 60));
		Image rpmImg = GameImage.SPINNER_RPM.getImage();
		rpmImg.setAlpha(alpha);
		rpmImg.drawCentered(width / 2f, height - rpmImg.getHeight() / 2f);
		if (timeDiff < 0)
			data.drawSymbolString(Integer.toString(rpm), (width + rpmImg.getWidth() * 0.95f) / 2f,
					height - data.getScoreSymbolImage('0').getHeight() * 1.025f, 1f, 1f, true);

		// spinner meter (subimage)
		Image spinnerMetre = GameImage.SPINNER_METRE.getImage();
		int spinnerMetreY = (spinnerComplete) ? 0 : (int) (spinnerMetre.getHeight() * (1 - (rotations / rotationsNeeded)));
		Image spinnerMetreSub = spinnerMetre.getSubImage(
				0, spinnerMetreY,
				spinnerMetre.getWidth(), spinnerMetre.getHeight() - spinnerMetreY
		);
		spinnerMetreSub.setAlpha(alpha);
		spinnerMetreSub.draw(0, height - spinnerMetreSub.getHeight());

		// main spinner elements
		float approachScale = 1 - Utils.clamp(((float) timeDiff / (hitObject.getTime() - hitObject.getEndTime())), 0f, 1f);
		GameImage.SPINNER_CIRCLE.getImage().setAlpha(alpha);
		GameImage.SPINNER_CIRCLE.getImage().setRotation(drawRotation * 360f);
		GameImage.SPINNER_CIRCLE.getImage().drawCentered(width / 2, height / 2);
		Image approachCircleScaled = GameImage.SPINNER_APPROACHCIRCLE.getImage().getScaledCopy(approachScale);
		approachCircleScaled.setAlpha(alpha);
		approachCircleScaled.drawCentered(width / 2, height / 2);
		GameImage.SPINNER_SPIN.getImage().setAlpha(alpha);
		GameImage.SPINNER_SPIN.getImage().drawCentered(width / 2, height * 3 / 4);

		if (spinnerComplete) {
			GameImage.SPINNER_CLEAR.getImage().drawCentered(width / 2, height / 4);
			int extraRotations = (int) (rotations - rotationsNeeded);
			if (extraRotations > 0)
				data.drawSymbolNumber(extraRotations * 1000, width / 2, height * 2 / 3, 1f, 1f);
		}
	}

	/**
	 * Calculates and sends the spinner hit result.
	 * @return the hit result (GameData.HIT_* constants)
	 */
	private int hitResult() {
		// TODO: verify ratios
		int result;
		float ratio = rotations / rotationsNeeded;
		if (ratio >= 1.0f || GameMod.AUTO.isActive() || GameMod.AUTOPILOT.isActive() || GameMod.SPUN_OUT.isActive()) {
			result = GameData.HIT_300;
			SoundController.playSound(SoundEffect.SPINNEROSU);
		} else if (ratio >= 0.9f)
			result = GameData.HIT_100;
		else if (ratio >= 0.75f)
			result = GameData.HIT_50;
		else
			result = GameData.HIT_MISS;

		data.hitResult(hitObject.getEndTime(), result, width / 2, height / 2,
				Color.transparent, true, hitObject, 0, HitObjectType.SPINNER, null, true);
		return result;
	}

	@Override
	public boolean mousePressed(int x, int y, int trackPosition) { return false; }  // not used

	@Override
	public boolean update(boolean overlap, int delta, int mouseX, int mouseY, boolean keyPressed, int trackPosition) {

		// end of spinner
		if (overlap || trackPosition > hitObject.getEndTime()) {
			hitResult();
			return true;
		}

		// game button is released
		if (isSpinning && !(keyPressed || GameMod.RELAX.isActive()))
			isSpinning = false;

		// spin automatically
		// http://osu.ppy.sh/wiki/FAQ#Spinners
		float angle;
		if (GameMod.AUTO.isActive()) {
			lastAngle = 0;
			angle = delta * AUTO_MULTIPLIER;
			isSpinning = true;
		} else if (GameMod.SPUN_OUT.isActive() || GameMod.AUTOPILOT.isActive()) {
			lastAngle = 0;
			angle = delta * SPUN_OUT_MULTIPLIER;
			isSpinning = true;
		} else {
			angle = (float) Math.atan2(mouseY - (height / 2), mouseX - (width / 2));

			// set initial angle to current mouse position to skip first click
			if (!isSpinning && (keyPressed || GameMod.RELAX.isActive())) {
				lastAngle = angle;
				isSpinning = true;
				return false;
			}
		}

		// make angleDiff the smallest angle change possible
		// (i.e. 1/4 rotation instead of 3/4 rotation)
		float angleDiff = angle - lastAngle;
		if (angleDiff < -Math.PI)
			angleDiff += TWO_PI;
		else if (angleDiff > Math.PI)
			angleDiff -= TWO_PI;

		// spin caused by the cursor
		float cursorVelocity = 0;
		if (isSpinning)
			cursorVelocity = Utils.clamp(angleDiff / TWO_PI / delta * 1000, -8f, 8f);

		deltaOverflow += delta;
		while (deltaOverflow >= DELTA_UPDATE_TIME) {
			sumVelocity -= storedVelocities[velocityIndex];
			sumVelocity += cursorVelocity;
			storedVelocities[velocityIndex++] = cursorVelocity;
			velocityIndex %= storedVelocities.length;
			deltaOverflow -= DELTA_UPDATE_TIME;
		}
		float rotationAngle = sumVelocity / storedVelocities.length * TWO_PI * delta / 1000;
		rotate(rotationAngle);
		if (Math.abs(rotationAngle) > 0.00001f)
			data.changeHealth(delta * GameData.HP_DRAIN_MULTIPLIER);

		lastAngle = angle;
		return false;
	}

	@Override
	public void updatePosition() {}

	@Override
	public float[] getPointAt(int trackPosition) {
		// get spinner time
		int timeDiff;
		float x = hitObject.getScaledX(), y = hitObject.getScaledY();
		if (trackPosition <= hitObject.getTime())
			timeDiff = 0;
		else if (trackPosition >= hitObject.getEndTime())
			timeDiff = hitObject.getEndTime() - hitObject.getTime();
		else
			timeDiff = trackPosition - hitObject.getTime();

		// calculate point
		float multiplier = (GameMod.AUTO.isActive()) ? AUTO_MULTIPLIER : SPUN_OUT_MULTIPLIER;
		float angle = (timeDiff * multiplier) - HALF_PI;
		final float r = height / 10f;
		return new float[] {
			(float) (x + r * Math.cos(angle)),
			(float) (y + r * Math.sin(angle))
		};
	}

	@Override
	public int getEndTime() { return hitObject.getEndTime(); }

	/**
	 * Rotates the spinner by an angle.
	 * @param angle the angle to rotate (in radians)
	 */
	private void rotate(float angle) {
		drawRotation += angle / TWO_PI;
		angle = Math.abs(angle);
		float newRotations = rotations + (angle / TWO_PI);

		// added one whole rotation...
		if (Math.floor(newRotations) > rotations) {
			if (newRotations > rotationsNeeded) {  // extra rotations
				data.changeScore(1000);
				SoundController.playSound(SoundEffect.SPINNERBONUS);
			} else {
				data.changeScore(100);
				SoundController.playSound(SoundEffect.SPINNERSPIN);
			}
		}

		rotations = newRotations;
	}
}
