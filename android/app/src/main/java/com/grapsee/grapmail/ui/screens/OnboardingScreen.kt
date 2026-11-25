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
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.grapsee.grapmail.ui.components.GMLayout
import com.grapsee.grapmail.ui.components.gmIsShortWindow
import com.grapsee.grapmail.ui.components.gmMaxWidth
import com.grapsee.grapmail.ui.theme.LocalPalette
import com.grapsee.grapmail.ui.theme.ScreenBackground
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import androidx.annotation.StringRes

data class OnboardingSceneData(
    val index: Int,
    @StringRes val eyebrow: Int,
    @StringRes val title: Int,
    @StringRes val description: Int,
    @StringRes val ctaText: Int,
    val assetName: String,
)

/**
 * The founder's five scenes, in order. Scene 1 is the required welcome; 2..5 are
 * the discovery scenes, and the screen draws its Skip from that same fact.
 */
val ONBOARDING_SCENES = listOf(
    OnboardingSceneData(
        index = 0,
        eyebrow = R.string.acct_welcome,
        title = R.string.acct_welcome_to_your_sanctuary,
        description = R.string.acct_your_new_address_is_ready_welcome_to,
        ctaText = R.string.acct_get_started,
        assetName = "onboarding/onboarding_scene_1.html",
    ),
    OnboardingSceneData(
        index = 1,
        eyebrow = R.string.acct_screener_gate,
        title = R.string.acct_you_decide_who_reaches_you,
        description = R.string.acct_unknown_senders_wait_in_the_screener,
        ctaText = R.string.acct_next,
        assetName = "onboarding/onboarding_scene_2.html",
    ),
    OnboardingSceneData(
        index = 2,
        eyebrow = R.string.acct_subscriptions,
        title = R.string.acct_tame_the_clutter,
        description = R.string.acct_unsubscribe_snooze_and_return_to,
        ctaText = R.string.acct_next,
        assetName = "onboarding/onboarding_scene_3.html",
    ),
    OnboardingSceneData(
        index = 3,
        eyebrow = R.string.acct_quiet_by_default,
        title = R.string.acct_choose_your_rhythm,
        description = R.string.acct_set_your_theme_and_decide_when,
        ctaText = R.string.acct_next,
        assetName = "onboarding/onboarding_scene_4.html",
    ),
    OnboardingSceneData(
        index = 4,
        eyebrow = R.string.acct_your_sanctuary,
        title = R.string.acct_your_inbox_is_yours_again,
        description = R.string.acct_welcome_home_your_new_mailbox_is,
        ctaText = R.string.acct_open_my_mailbox,
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
    val short = gmIsShortWindow()
    val heroFraction = if (LocalConfiguration.current.fontScale > 1.15f || short) 0.38f else 0.48f
    ScreenBackground(palette.bg) {
        var sceneIndex by remember {
            mutableIntStateOf(initialScene.coerceIn(0, ONBOARDING_SCENES.lastIndex))
        }
        val scene = ONBOARDING_SCENES[sceneIndex]

        BoxWithConstraints(
            Modifier.fillMaxSize().background(palette.bg).testTag("onboarding"),
        ) {
            // Wide landscape windows (tablets, unfolded foldables sideways,
            // Chromebook / DeX / desktop windows, phones in landscape) put the
            // scene beside the copy instead of shrinking it above the copy.
            val split = gmUseSplitLayout(maxWidth, maxHeight)

            @Composable
            fun Hero(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
                // Reload the bundled scene for artwork or theme changes.
                key(scene.index, palette.primarySolid, palette.bg) {
                    BundledAnimation(
                        asset = scene.assetName,
                        accent = palette.primarySolid,
                        backdrop = palette.bg,
                        description = stringResource(R.string.acct_onboarding_animated_scene, stringResource(scene.title)),
                        interactive = true,
                        onOpenMailbox = onFinish.takeIf { sceneIndex == ONBOARDING_SCENES.lastIndex },
                        modifier = Modifier
                            .width(width)
                            .height(height)
                            .testTag("onboarding-hero"),
                    )
                }
            }

            @Composable
            fun Copy(modifier: Modifier) {
                Column(
                    modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(scene.eyebrow),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.8.sp,
                        color = palette.primarySolid,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(scene.title),
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
                        stringResource(scene.description),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            @Composable
            fun Cta(modifier: Modifier) {
                GradientButton(
                    text = stringResource(scene.ctaText),
                    onClick = {
                        if (sceneIndex < ONBOARDING_SCENES.lastIndex) sceneIndex++
                        else onFinish()
                    },
                    modifier = modifier.testTag("onboarding-cta"),
                    trailing = {
                        Spacer(Modifier.size(8.dp))
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }

            if (split) {
                // The 440 x 560 scene, fitted (never cropped) into the left
                // pane's height; the copy, dots and CTA in the right pane.
                val paneHeight = maxHeight - 64.dp // top bar + breathing room
                val paneWidth = (maxWidth - 32.dp * 2 - 40.dp) / 2
                val heroHeight = minOf(paneHeight, paneWidth * (560f / 440f)).coerceAtLeast(0.dp)
                val heroWidth = heroHeight * (440f / 560f)
                Column(Modifier.fillMaxSize()) {
                    OnboardingTopBar(
                        canSkip = sceneIndex > 0,
                        onSkip = { onFinish() },
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) { Hero(heroWidth, heroHeight) }
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Copy(
                                Modifier
                                    .gmMaxWidth(GMLayout.Form)
                                    .weight(1f),
                            )
                            PageDots(
                                count = ONBOARDING_SCENES.size,
                                index = sceneIndex,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp, bottom = if (short) 12.dp else 20.dp),
                            )
                            Cta(Modifier.gmMaxWidth(GMLayout.Form))
                            Spacer(Modifier.height(if (short) 12.dp else 28.dp))
                        }
                    }
                }
            } else {
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

                Hero(heroWidth, heroHeight)

                // Copy and CTA keep to one form column on wide portrait windows
                // (tablets, unfolded foldables); on phones the cap is wider
                // than the screen, so nothing moves.
                Copy(
                    Modifier
                        .gmMaxWidth(GMLayout.Form)
                        .weight(1f)
                        .padding(horizontal = 24.dp),
                )

                PageDots(
                    count = ONBOARDING_SCENES.size,
                    index = sceneIndex,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                )

                Cta(
                    Modifier
                        .gmMaxWidth(GMLayout.Form)
                        .padding(horizontal = 24.dp),
                )

                Spacer(Modifier.height(28.dp))
            }
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
                stringResource(R.string.acct_skip),
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
