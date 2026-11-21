package com.grapsee.grapmail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.ui.components.BundledAnimation
import com.grapsee.grapmail.ui.components.GradientButton
import com.grapsee.grapmail.ui.components.GrapMailLogo
import com.grapsee.grapmail.ui.theme.LocalPalette
import com.grapsee.grapmail.ui.theme.ScreenBackground

data class OnboardingSceneData(
    val index: Int,
    val eyebrow: String,
    val title: String,
    val description: String,
    val ctaText: String,
    val assetName: String,
)

/**
 * The founder's five scenes, in order. Scene 1 is the required welcome; 2..5 are
 * the discovery scenes, and the screen draws its Skip from that same fact.
 */
val ONBOARDING_SCENES = listOf(
    OnboardingSceneData(
        index = 0,
        eyebrow = "WELCOME",
        title = "Welcome to Your Sanctuary",
        description = "Your new address is ready; welcome to a cleaner way to communicate.",
        ctaText = "Get Started",
        assetName = "onboarding/onboarding_scene_1.html",
    ),
    OnboardingSceneData(
        index = 1,
        eyebrow = "SCREENER GATE",
        title = "You Decide Who Reaches You",
        description = "Unknown senders wait in the Screener until you decide.",
        ctaText = "Next",
        assetName = "onboarding/onboarding_scene_2.html",
    ),
    OnboardingSceneData(
        index = 2,
        eyebrow = "SUBSCRIPTIONS",
        title = "Tame the Clutter",
        description = "Unsubscribe, snooze, and return to what matters.",
        ctaText = "Next",
        assetName = "onboarding/onboarding_scene_3.html",
    ),
    OnboardingSceneData(
        index = 3,
        eyebrow = "QUIET BY DEFAULT",
        title = "Choose Your Rhythm",
        description = "Set your theme and decide when GrapMail can interrupt you.",
        ctaText = "Next",
        assetName = "onboarding/onboarding_scene_4.html",
    ),
    OnboardingSceneData(
        index = 4,
        eyebrow = "YOUR SANCTUARY",
        title = "Your Inbox Is Yours Again",
        description = "Welcome home; your new mailbox is ready.",
        ctaText = "Open My Mailbox",
        assetName = "onboarding/onboarding_scene_5.html",
    ),
)

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    /** Test-only: the snapshot host opens directly on one scene. */
    initialScene: Int = 0,
) {
    val palette = LocalPalette.current
    val heroFraction = if (LocalConfiguration.current.fontScale > 1.15f) 0.38f else 0.48f
    ScreenBackground(palette.bg) {
        var sceneIndex by remember {
            mutableIntStateOf(initialScene.coerceIn(0, ONBOARDING_SCENES.lastIndex))
        }
        val scene = ONBOARDING_SCENES[sceneIndex]

        BoxWithConstraints(
            Modifier.fillMaxSize().background(palette.bg).testTag("onboarding"),
        ) {
            // The artwork is authored in a 440 x 560 box. Keep that exact
            // ratio — taking the full width but capping only the height used
            // to squash the box, which clipped the bottom of scene 2's
            // screener card. Cap the width instead, centre the result, and
            // the whole scene fits with room for the copy and the CTA.
            val heroWidth = minOf(maxWidth, maxHeight * heroFraction * (440f / 560f))
            val heroHeight = heroWidth * (560f / 440f)

            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                OnboardingTopBar(
                    canSkip = sceneIndex > 0,
                    onSkip = { onFinish() },
                )

                // Reload the bundled scene for artwork or theme changes.
                key(scene.index, palette.primarySolid, palette.bg) {
                    BundledAnimation(
                        asset = scene.assetName,
                        accent = palette.primarySolid,
                        backdrop = palette.bg,
                        description = "Animated scene: ${scene.title}",
                        interactive = true,
                        onOpenMailbox = onFinish.takeIf { sceneIndex == ONBOARDING_SCENES.lastIndex },
                        modifier = Modifier
                            .width(heroWidth)
                            .height(heroHeight)
                            .testTag("onboarding-hero"),
                    )
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        scene.eyebrow,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.8.sp,
                        color = palette.primarySolid,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        scene.title,
                        fontSize = 27.sp,
                        lineHeight = 33.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.4).sp,
                        color = palette.textPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        scene.description,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                PageDots(
                    count = ONBOARDING_SCENES.size,
                    index = sceneIndex,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                )

                GradientButton(
                    text = scene.ctaText,
                    onClick = {
                        if (sceneIndex < ONBOARDING_SCENES.lastIndex) sceneIndex++
                        else onFinish()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .testTag("onboarding-cta"),
                    trailing = {
                        Spacer(Modifier.size(8.dp))
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )

                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun OnboardingTopBar(canSkip: Boolean, onSkip: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GrapMailLogo(size = 28.dp, wordmark = true)
        Spacer(Modifier.width(1.dp))
        Box(Modifier.weight(1f))
        if (canSkip) {
            Text(
                "Skip",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onSkip)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("onboarding-skip"),
            )
        } else {
            // Scene 1 cannot be skipped; the empty box keeps the wordmark in
            // the same place when the control appears on scene 2.
            Spacer(Modifier.size(width = 56.dp, height = 1.dp))
        }
    }
}

@Composable
private fun PageDots(count: Int, index: Int, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val active = i == index
            Box(
                Modifier
                    .size(width = if (active) 22.dp else 6.dp, height = 5.dp)
                    .clip(if (active) RoundedCornerShape(3.dp) else CircleShape)
                    .background(if (active) palette.primarySolid else palette.border)
                    .border(
                        0.5.dp,
                        if (active) Color.Transparent else palette.border,
                        if (active) RoundedCornerShape(3.dp) else CircleShape,
                    )
            )
        }
    }
}

/**
 * "Shown once, on this device."
 *
 * The walkthrough is tied to the device, not to an account: somebody who
 * creates a second mailbox has already met the product, and replaying five
 * scenes at them is an interruption. Stored in plain preferences, cleared by
 * uninstalling the app — the same lifetime the welcome tour has.
 */
object OnboardingGate {
    private const val PREFS = "grapmail.onboarding"
    private const val KEY_DONE = "completed"

    fun isDone(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)

    fun markDone(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()
    }
}
