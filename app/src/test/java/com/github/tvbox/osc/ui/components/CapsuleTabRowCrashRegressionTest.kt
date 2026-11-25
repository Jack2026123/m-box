package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.avbox.osc.ComposeTestActivity
import com.github.tvbox.osc.R
import com.github.tvbox.osc.testing.TestApplication
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regression test for the ConfigManage tab-row crash (logtest39, fixed logtest40).
 * Composes the exact 4-option tab row (real icons, real paddings,
 * titleSmall) at phone-portrait width plus two forced-overflow variants.
 * Any measure-contract violation throws during setContent/waitForIdle.
 * Kept as a regression test: the logtest39 loose-probe + re-measure policy threw IllegalStateException on first layout.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = TestApplication::class, qualifiers = "w411dp-h891dp-xxhdpi")
class CapsuleTabRowCrashRegressionTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComposeTestActivity>()

    @Composable
    private fun TabRow(labelSize: TextUnit? = null) {
        CapsuleSegmentedButton(
            options = listOf(
                SegmentOption("点播", "vod", iconPainter = painterResource(R.drawable.ic_config_vod), hugContent = true),
                SegmentOption("TMDB", "tmdb", iconPainter = painterResource(R.drawable.ic_config_tmdb), hugContent = true),
                SegmentOption("直播", "live", iconPainter = painterResource(R.drawable.ic_config_live), hugContent = true),
                SegmentOption("阅读", "reading", iconPainter = painterResource(R.drawable.ic_config_reading), hugContent = true),
            ),
            selectedValue = "vod",
            onOptionSelected = {},
            distributeLeftover = true,
            containerColor = MaterialTheme.colorScheme.surfaceBright,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            iconSize = 20.dp,
            iconSpacing = 4.dp,
            labelStyle = if (labelSize != null) {
                MaterialTheme.typography.titleSmall.copy(fontSize = labelSize)
            } else {
                MaterialTheme.typography.titleSmall
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                .testTag("tabRow"),
        )
    }

    private fun assertRowWithinRoot() {
        rule.waitForIdle()
        val root = rule.onNodeWithTag("root").fetchSemanticsNode().boundsInRoot
        val row = rule.onNodeWithTag("tabRow").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "tab row width ${row.width} must not exceed root width ${root.width}",
            row.width <= root.width + 1f,
        )
    }

    @Test
    fun tabRow_phoneWidth_noThrow() {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("root")) {
                    TabRow()
                }
            }
        }
        assertRowWithinRoot()
    }

    @Test
    fun tabRow_narrowWidth_shrinkBranch_noThrow() {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("root")) {
                    Box(Modifier.width(200.dp)) {
                        TabRow()
                    }
                }
            }
        }
        assertRowWithinRoot()
    }

    @Test
    fun tabRow_hugeFont_shrinkBranch_noThrow() {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("root")) {
                    TabRow(labelSize = 30.sp)
                }
            }
        }
        assertRowWithinRoot()
    }
}
